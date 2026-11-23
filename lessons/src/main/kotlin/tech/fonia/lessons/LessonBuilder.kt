package tech.fonia.lessons

import tech.fonia.model.Rung
import tech.fonia.model.SoundId

/**
 * F2: turns a [LessonRequest] into a 4-minute [LessonPlan] (chapter 06 §2). Pure and deterministic: no clock, no
 * I/O, every random choice goes through [SeededRandom] in a fixed call order, so iOS can replay it from the golden
 * vectors in spec/content/vectors.
 *
 * Template (lesson.json "template"): opening, warm-up, block 1 (the rung below, near-certain success), movement
 * break, block 2 (the current rung, its own position only), block 3 (current rung mixed with known words, one
 * surprise item at slot 5-7, may be cut at half), final (one sure success, "Ascultă-te", the adult's turn,
 * "Încă una?"). "Încă una" builds one block of 6 plus the final; after a pause of `longPauseDays` the session is
 * block 1 and block 2 of known items only.
 *
 * Item choice (§2.3): due spaced-repetition reviews first (missed items come back after 1, 2, 4… sessions), then
 * the known/new mix (30 % new, 50 % on the first session of a rung, moved by the adaptation), unused-last-session
 * items first, no item twice in a plan while others remain, items with unworked hard sounds in the second half,
 * age and therapist-approval filters. Pairs are whole, in random order.
 */
class LessonBuilder(private val content: LessonContent) {

    fun build(request: LessonRequest): LessonPlan {
        val spec = content.lesson
        val seed = seedFor(request)
        val random = SeededRandom(seed)
        val level = request.adaptation ?: Adaptation.assess(request.history, spec.adaptation)
        val params = spec.adaptation.levels.getValue(level.raw)
        val rungItems = content.items(request.sound, request.rung)
        val firstOnRung = if (rungItems.isEmpty()) request.history.recent.isEmpty()
        else rungItems.none { (request.history.items[it.id]?.seen ?: 0) > 0 }
        val kind = when {
            request.oneMore -> PlanKind.OneMore
            request.daysSinceLast >= spec.template.longPauseDays -> PlanKind.AfterPause
            else -> PlanKind.Normal
        }
        val session = Session(content, request, random, params, level, firstOnRung, knownOnly = kind == PlanKind.AfterPause)
        val steps = when (kind) {
            PlanKind.Normal -> session.normal()
            PlanKind.OneMore -> session.oneMore()
            PlanKind.AfterPause -> session.afterPause()
        }
        return LessonPlan(content.contentVersion, request.sound, request.rung, seed, kind, level, params, firstOnRung, steps)
    }

    companion object {
        private const val PHI = 0x9E37_79B9_7F4A_7C15uL

        /** `seed ^ sessionsCompleted·φ ^ hash("<sound>/<rung>")`, the formula of chapter 06 §2.5. */
        fun seedFor(request: LessonRequest): ULong =
            request.seed xor (request.sessionsCompleted.toLong().toULong() * PHI) xor
                SeededRandom.hash("${request.sound.raw}/${request.rung.raw}")

        internal val WORD_RUNGS = listOf(Rung.WordsInitial, Rung.WordsMedial, Rung.WordsFinal, Rung.Clusters)
    }
}

/** One build in progress: the request, the random stream and what the plan already holds. */
private class Session(
    val content: LessonContent,
    val request: LessonRequest,
    val random: SeededRandom,
    val params: AdaptParams,
    val level: AdaptLevel,
    val firstOnRung: Boolean,
    val knownOnly: Boolean,
) {
    val spec = content.lesson
    val sound: SoundId = request.sound
    val rung: Rung = request.rung
    val data = content.sound(sound)
    val slug = data.slug
    private val used = mutableListOf<String>()
    private var reviewsLeft = spec.review.maxPerSession
    private val history = request.history

    // ---------------------------------------------------------------- templates

    fun normal(): List<PlanStep> {
        val t = spec.template
        val steps = mutableListOf(opening(t.opening), warmup(t.warmup))
        block1(t.block1)?.let { steps += it }
        steps += PlanStep(StepKind.Move, 0, null, t.move[0], t.move[1] - t.move[0], listOf("move.${1 + random.below(4)}"))
        val blocks = spec.blocks.getValue(rung.raw)
        steps += current(block = 2, count = blocks.block2, window = t.block2)
        steps += current(block = 3, count = blocks.block3, window = t.block3)
        steps += final(t.final[0], t.final[1] - t.final[0], steps)
        return steps
    }

    fun oneMore(): List<PlanStep> {
        val t = spec.template
        val blocks = spec.blocks.getValue(rung.raw)
        // Six productions: six words, sentences, holds or listen choices; three pairs or trains; else one block 2.
        val count = when (rung) {
            Rung.Pairs, Rung.Syllables, Rung.Back -> t.oneMore.block / 2
            Rung.Listen, Rung.Isolated, Rung.Sentences, in LessonBuilder.WORD_RUNGS -> t.oneMore.block
            else -> blocks.block2
        }
        val finalSeconds = t.final[1] - t.final[0]
        val block = current(block = 2, count = count, window = listOf(0, t.oneMore.seconds - finalSeconds))
        return listOf(block, final(t.oneMore.seconds - finalSeconds, finalSeconds, listOf(block)))
    }

    fun afterPause(): List<PlanStep> {
        val t = spec.template
        val steps = mutableListOf(opening(t.opening), warmup(t.warmup))
        block1(t.block1)?.let { steps += it }
        val blocks = spec.blocks.getValue(rung.raw)
        val block2 = current(block = 2, count = blocks.block2, window = t.block2)
        steps += block2
        val start = t.block2[1] + 5
        steps += final(start, t.final[1] - t.final[0], steps)
        return steps
    }

    // ---------------------------------------------------------------- fixed steps

    private fun opening(window: List<Int>): PlanStep {
        val greet = if (request.sessionsCompleted == 0) "greet.1" else "greet.${2 + random.below(4)}"
        return PlanStep(StepKind.Opening, 0, rung, window[0], window[1] - window[0],
            listOf(greet, content.chapterKey(sound, rung)))
    }

    private fun openingWarmups(): List<String> {
        val list = data.warmups
        if (list.size < 2) return list
        val rotating = list[1 + request.sessionsCompleted % (list.size - 1)]
        return listOf(list[0], rotating).take(spec.template.warmupExercises)
    }

    private fun warmup(window: List<Int>): PlanStep = warmupStep(openingWarmups(), block = 0, window = window, rungOf = Rung.Warmup)

    private fun warmupStep(ids: List<String>, block: Int, window: List<Int>, rungOf: Rung?): PlanStep {
        val takes = spec.takes["warmup"] ?: 3
        val items = ids.map { PlanItem(id = "warm.$it", type = "warmup", text = it, takes = takes, voices = listOf("warm.$it")) }
        return PlanStep(StepKind.Warmup, block, rungOf, window[0], window[1] - window[0],
            listOf("step.warmup") + ids.map { "warm.$it" }, items, warmups = ids)
    }

    /** More warm-ups than the opening ones, rotating through the sound's list. */
    private fun extraWarmups(count: Int): List<String> {
        val taken = openingWarmups().toSet() + used.filter { it.startsWith("warm.") }.map { it.removePrefix("warm.") }
        val rest = data.warmups.filter { it !in taken }.ifEmpty { data.warmups }
        val start = request.sessionsCompleted % rest.size
        val ids = (0 until count).map { rest[(start + it) % rest.size] }
        used += ids.map { "warm.$it" }
        return ids
    }

    private fun final(start: Int, seconds: Int, steps: List<PlanStep>): PlanStep {
        val candidates = steps.filter { it.block in 1..3 }.flatMap { it.items }
            .filter { !it.surprise && it.type != "warmup" && it.type != "story" && it.type != "mission" }
        val best = candidates.maxByOrNull { item ->
            val stat = history.items[item.id]
            if (stat == null || stat.seen == 0) -1.0 else stat.successes.toDouble() / stat.seen
        }
        val sure = best?.let { candidates.firstOrNull { c -> c.id == it.id } } ?: candidates.firstOrNull()
        val items = listOfNotNull(sure?.copy(takes = 1, model = true, surprise = false, countsForProgress = false, review = false))
        return PlanStep(StepKind.Final, 0, null, start, seconds, listOf("step.final", "end.1", "end.6", "end.5", "end.2"), items)
    }

    // ---------------------------------------------------------------- blocks

    /** Block 1: the material of the rung below (lesson.json blocks[rung].block1), near-certain success. */
    private fun block1(window: List<Int>): PlanStep? {
        val material = spec.blocks.getValue(rung.raw).block1 ?: return null
        val counts = spec.block1Counts
        return when (material) {
            "warmup" -> warmupStep(extraWarmups(counts["warmup"] ?: 2), block = 1, window = window, rungOf = Rung.Warmup)
            "isolated" -> holdStep(block = 1, count = counts["isolated"] ?: 6, window = window, goal = holdGoal(easier = true))
            "syllables" -> syllableStep(block = 1, count = counts["syllables"] ?: 4, window = window,
                shapes = if (rung == Rung.Back) listOf("CV") else listOf("CV", "VC", "VCV"), rungOf = Rung.Syllables)
            "words" -> {
                val pool = passedWords(includeCurrent = true)
                wordStep(block = 1, items = pick(pool, counts["words"] ?: 6, preferKnown = true), window = window, rungOf = null)
            }
            "sentences" -> sentenceStep(block = 1, items = pick(content.items(sound, Rung.Sentences), counts["sentences"] ?: 3,
                preferKnown = true), window = window)
            else -> throw ContentException("lesson.json: unknown block1 material '$material'")
        }
    }

    /** Block 2 and 3: the current rung. */
    private fun current(block: Int, count: Int, window: List<Int>): PlanStep {
        val pool = content.items(sound, rung)
        return when (rung) {
            Rung.Listen -> {
                val items = choose(pool, count).map { (item, review) ->
                    val foil = random.below(2) == 1
                    planItem(item).copy(review = review, variant = if (foil) "foil" else "target",
                        voices = listOfNotNull(if (foil) item.foilVoice else item.voice))
                }
                PlanStep(StepKind.Listen, block, rung, window[0], window[1] - window[0],
                    listOf("step.listen", "step.listen.foil"), items)
            }
            Rung.Warmup -> warmupStep(extraWarmups(count), block, window, Rung.Warmup)
            Rung.Isolated -> holdStep(block, count, window, holdGoal(easier = false))
            Rung.Syllables -> syllableStep(block, count, window, listOf("CV"), Rung.Syllables)
            Rung.Back -> syllableStep(block, count, window, if (block == 2) listOf("VC") else listOf("VCV"), Rung.Back)
            Rung.WordsInitial, Rung.WordsMedial, Rung.WordsFinal, Rung.Clusters -> {
                val items = if (block == 3 && !knownOnly) mixedBlock3(pool, count) else pick(pool, count)
                wordStep(block, items, window, rung)
            }
            Rung.Pairs -> {
                val items = choose(pool, count).map { (item, review) ->
                    val swap = random.below(2) == 1
                    planItem(item).copy(review = review, variant = if (swap) "ba" else "ab",
                        voices = if (swap) listOfNotNull(item.voiceB, item.voiceA) else listOfNotNull(item.voiceA, item.voiceB))
                }
                PlanStep(StepKind.Pairs, block, rung, window[0], window[1] - window[0], listOf("step.pairs"), items)
            }
            Rung.Sentences -> sentenceStep(block, pick(pool, count), window)
            Rung.Twister -> twisterStep(block, window)
            Rung.Retell -> {
                val items = pick(pool, count)
                PlanStep(StepKind.Retell, block, rung, window[0], window[1] - window[0], listOf("step.retell"), items)
            }
            Rung.Everyday -> if (block == 2) {
                val items = pick(pool, count)
                PlanStep(StepKind.Mission, block, rung, window[0], window[1] - window[0],
                    listOf("step.mission", "adult.5", "adult.6"), items)
            } else {
                sentenceStep(block, pick(content.items(sound, Rung.Sentences), count, preferKnown = true), window)
            }
        }
    }

    // ---------------------------------------------------------------- step makers

    private fun holdGoal(easier: Boolean): Double? {
        if (data.holdMode != "sustain") return null
        return if (easier) spec.adaptation.levels.getValue(AdaptLevel.Easier.raw).holdSeconds else params.holdSeconds
    }

    private fun holdStep(block: Int, count: Int, window: List<Int>, goal: Double?): PlanStep {
        val variants = content.items(sound, Rung.Isolated)
        val start = request.sessionsCompleted % maxOf(1, variants.size)
        val items = (0 until count).mapNotNull { k ->
            variants.getOrNull((start + k) % variants.size)?.let { planItem(it).copy(variant = data.holdMode) }
        }
        val first = if (data.holdMode == "sustain") "step.hold.sustain" else "step.hold.burst"
        val prompts = items.mapNotNull { content.item(it.id)?.promptVoice }.distinct()
        return PlanStep(StepKind.Hold, block, Rung.Isolated, window[0], window[1] - window[0], listOf(first) + prompts, items,
            holdGoalSeconds = goal, holdMode = data.holdMode)
    }

    /** Trains of `trainLength` syllables: the first half repeats one syllable (sa-sa-sa), the rest vary the vowel. */
    private fun syllableStep(block: Int, count: Int, window: List<Int>, shapes: List<String>, rungOf: Rung): PlanStep {
        val length = params.trainLength
        val all = (content.items(sound, Rung.Syllables) + content.items(sound, Rung.Back)).filter { it.shape in shapes }
        var previous: List<ContentItem>? = null
        val trains = (0 until count).map { k ->
            val shape = shapes[k % shapes.size]
            val ofShape = all.filter { it.shape == shape }
            // Draw once; if the train equals the one before, move to the next syllable so no train repeats in a row.
            val draw = random.below(ofShape.size)
            val repeated = k < (count + 1) / 2 || ofShape.size < 2
            fun train(at: Int) = if (repeated) List(length) { ofShape[at % ofShape.size] }
            else List(length) { ofShape[(at + it) % ofShape.size] }
            val parts = train(draw).let { if (it == previous && ofShape.size > 1) train(draw + 1) else it }
            previous = parts
            PlanItem(
                id = "train:" + parts.joinToString("+") { it.id },
                type = "train",
                text = parts.joinToString("-") { it.text },
                model = level != AdaptLevel.Harder || firstOnRung,
                syllables = parts.map { it.text },
                voices = listOf("human-needed"),
            )
        }
        val lines = listOf("step.syllables") +
            if (firstOnRung && block >= 2 && (rung == Rung.Syllables || rung == Rung.Back)) listOf("step.syllables.split") else emptyList()
        return PlanStep(StepKind.Syllables, block, rungOf, window[0], window[1] - window[0], lines, trains)
    }

    private fun wordStep(block: Int, items: List<PlanItem>, window: List<Int>, rungOf: Rung?): PlanStep {
        val lines = mutableListOf("step.words")
        if (rungOf == Rung.WordsFinal) lines += "step.words.final"
        if (items.any { it.model }) lines += "model.repeat"
        if (items.any { it.surprise }) lines += "step.surprise"
        val cut = if (block == 3) items.size / 2 else null
        return PlanStep(StepKind.Words, block, rungOf, window[0], window[1] - window[0], lines, items, cutAt = cut)
    }

    private fun sentenceStep(block: Int, items: List<PlanItem>, window: List<Int>): PlanStep {
        val lines = listOf("step.sentence") + if (items.any { it.model }) listOf("model.slow") else emptyList()
        return PlanStep(StepKind.Sentence, block, Rung.Sentences, window[0], window[1] - window[0], lines, items,
            cutAt = if (block == 3) items.size / 2 else null)
    }

    /** One twister: block 2 at "slow" (melc), block 3 the same one at the adaptation's tempo (easier stays slow). */
    private fun twisterStep(block: Int, window: List<Int>): PlanStep {
        val takes = spec.takes["twister"] ?: 2
        val eligible = content.twisters(sound).filter { ageOk(it.ageBand) && (!it.needsTherapistCheck || it.id in request.approvedItems) }
        val chosen = used.firstNotNullOfOrNull { id -> eligible.firstOrNull { it.id == id } }
            ?: pickTwister(eligible)
        val tempo = if (block == 2) "slow" else params.tempo
        val items = listOfNotNull(chosen?.let {
            PlanItem(id = it.id, type = "twister", text = it.text, takes = takes, model = true, variant = tempo,
                voices = listOf(it.voice))
        })
        val line = if (tempo == "slow") "step.twister.slow" else "step.twister.step"
        return PlanStep(StepKind.Twister, block, Rung.Twister, window[0], window[1] - window[0], listOf(line), items)
    }

    private fun pickTwister(eligible: List<Twister>): Twister? {
        if (eligible.isEmpty()) return null
        val fresh = eligible.filter { it.id !in history.lastSessionItems }.ifEmpty { eligible }
        val chosen = fresh[random.below(fresh.size)]
        used += chosen.id
        return chosen
    }

    /** Block 3 of a word rung: two thirds the current rung, one third known words of passed positions, one surprise. */
    private fun mixedBlock3(pool: List<ContentItem>, count: Int): List<PlanItem> {
        val earlier = passedWords(includeCurrent = false)
        val fromEarlier = if (earlier.isEmpty()) 0 else count / 3
        val items = (pick(pool, count - fromEarlier) + pick(earlier, fromEarlier, preferKnown = true)).toMutableList()
        val mixed = noRepeatInARow(random.shuffled(items)) { it.id }.toMutableList()
        val surprise = surpriseItem()
        if (surprise != null && mixed.isNotEmpty()) {
            val slot = spec.template.surpriseSlot
            val position = minOf(mixed.size, slot[0] + random.below(slot[1] - slot[0] + 1))
            mixed.add(position, surprise)
        }
        return mixed
    }

    /** From the next word rung, or else a word never seen; it gets a special sticker and does not count. */
    private fun surpriseItem(): PlanItem? {
        val next = Rung.entries.getOrNull(rung.ordinal + 1)
        val fromNext = if (next != null && next in LessonBuilder.WORD_RUNGS) content.items(sound, next) else emptyList()
        val candidates = fromNext.filter { eligible(it) && it.id !in used }.ifEmpty {
            LessonBuilder.WORD_RUNGS.flatMap { content.items(sound, it) }
                .filter { eligible(it) && it.id !in used && (history.items[it.id]?.seen ?: 0) == 0 }
        }
        if (candidates.isEmpty()) return null
        val chosen = candidates[random.below(candidates.size)]
        used += chosen.id
        return planItem(chosen).copy(surprise = true, countsForProgress = false, model = true)
    }

    /** Words of the positions already passed (and the current one when asked). */
    private fun passedWords(includeCurrent: Boolean): List<ContentItem> {
        val limit = if (rung in LessonBuilder.WORD_RUNGS) rung.ordinal else Rung.Clusters.ordinal + 1
        return LessonBuilder.WORD_RUNGS.filter { it.ordinal < limit || (includeCurrent && it == rung) }
            .flatMap { content.items(sound, it) }
    }

    // ---------------------------------------------------------------- item choice

    private fun ageOk(band: String): Boolean = request.childAge >= (band.takeWhile { it.isDigit() }.toIntOrNull() ?: 4)

    private fun eligible(item: ContentItem): Boolean =
        ageOk(item.ageBand) && (!item.needsTherapistCheck || item.id in request.approvedItems)

    private fun deferred(item: ContentItem): Boolean {
        val worked = request.workedSounds.map { it.raw }.toSet() + sound.raw
        return item.otherSounds.any { it !in worked }
    }

    private fun due(item: ContentItem): Boolean {
        val stat = history.items[item.id] ?: return false
        if (stat.seen == 0 || stat.lastSuccess) return false
        val interval = spec.review.intervals.getOrElse(stat.box) { spec.review.intervals.last() }
        return request.sessionsCompleted - stat.lastSeenSession >= interval
    }

    /**
     * Chooses [count] items: due reviews, then the new share, then known ones (each group shuffled, then items not
     * yet in this plan and not in the last session first), refilled from the other group, and only then repeated.
     * Deferred items (unworked hard sounds) end up in the second half.
     */
    private fun pick(pool: List<ContentItem>, count: Int, preferKnown: Boolean = false): List<PlanItem> =
        choose(pool, count, preferKnown).map { (item, review) -> planItem(item).copy(review = review) }

    /** [pick] before the items become plan items: each chosen content item with its "is a review" flag. */
    private fun choose(pool: List<ContentItem>, count: Int, preferKnown: Boolean = false): List<Pair<ContentItem, Boolean>> {
        if (count <= 0) return emptyList()
        // A list with nothing for this age (J clusters at 4-5) falls back to the older children's words rather than
        // leave the block empty; therapist approval is never relaxed.
        var candidates = pool.filter { eligible(it) }.ifEmpty {
            pool.filter { !it.needsTherapistCheck || it.id in request.approvedItems }
        }
        if (knownOnly) candidates = candidates.filter { history.items[it.id]?.known == true }.ifEmpty { candidates.sortedBy { it.difficulty } }
        if (candidates.isEmpty()) return emptyList()
        fun order(group: List<ContentItem>): List<ContentItem> =
            random.shuffled(group).sortedWith(compareBy({ it.id in used }, { it.id in history.lastSessionItems }))

        val reviews = order(candidates.filter { due(it) }).take(minOf(reviewsLeft, count))
        reviewsLeft -= reviews.size
        val rest = candidates.filter { it !in reviews }
        val known = order(rest.filter { history.items[it.id]?.known == true })
        val fresh = order(rest.filter { history.items[it.id]?.known != true })
        val share = when {
            preferKnown -> 0.0
            firstOnRung -> spec.adaptation.firstSessionNewShare
            else -> params.newShare
        }
        val wantNew = Math.round((count - reviews.size) * share).toInt()
        val chosen = reviews.toMutableList()
        chosen += fresh.filter { it.id !in used }.take(wantNew)
        chosen += known.filter { it.id !in used && it !in chosen }.take(count - chosen.size)
        chosen += fresh.filter { it.id !in used && it !in chosen }.take(count - chosen.size)
        chosen += known.filter { it !in chosen }.take(count - chosen.size)
        chosen += fresh.filter { it !in chosen }.take(count - chosen.size)
        // N16: a short list repeats; never the same item twice in a row.
        val ordered = order(candidates)
        var k = 0
        while (chosen.size < count && ordered.isNotEmpty()) {
            val next = ordered[k % ordered.size]
            if (ordered.size == 1 || chosen.lastOrNull()?.id != next.id) chosen += next
            k++
        }
        val mixed = random.shuffled(chosen)
        // Deferred items go to the second half, unless that would put the same item twice in a row (short lists).
        val split = noRepeatInARow(mixed.filter { !deferred(it) }) + noRepeatInARow(mixed.filter { deferred(it) })
        val arranged = if (split.zipWithNext().any { (a, b) -> a.id == b.id }) noRepeatInARow(mixed) else split
        used += arranged.map { it.id }
        val reviewIds = reviews.map { it.id }.toSet()
        return arranged.map { it to (it.id in reviewIds) }
    }

    /**
     * Reorders a group so the same item never comes twice in a row when that is possible: each time, the item with
     * the most copies left that differs from the last one (ties: the earlier one in the group).
     */
    private fun noRepeatInARow(items: List<ContentItem>): List<ContentItem> = noRepeatInARow(items) { it.id }

    private fun <T> noRepeatInARow(items: List<T>, id: (T) -> String): List<T> {
        val remaining = items.toMutableList()
        val out = mutableListOf<T>()
        while (remaining.isNotEmpty()) {
            val counts = remaining.groupingBy(id).eachCount()
            val lastId = out.lastOrNull()?.let(id)
            val pickIndex = remaining.indices.filter { id(remaining[it]) != lastId }
                .maxWithOrNull(compareBy<Int> { counts.getValue(id(remaining[it])) }.thenByDescending { it }) ?: 0
            out += remaining.removeAt(pickIndex)
        }
        return out
    }

    private fun planItem(item: ContentItem): PlanItem {
        val known = history.items[item.id]?.known == true
        val model = when (level) {
            AdaptLevel.Easier -> true
            AdaptLevel.Harder -> firstOnRung
            AdaptLevel.Steady -> firstOnRung || !known
        }
        val voices = when (item.type) {
            "hold" -> listOfNotNull(item.promptVoice, item.voice)
            "pair" -> listOfNotNull(item.voiceA, item.voiceB)
            "story" -> item.lineVoices
            else -> listOfNotNull(item.voice)
        }
        val text = if (item.type == "story") item.lines.joinToString(" ") else item.text
        return PlanItem(id = item.id, type = item.type, text = text, takes = spec.takes[takesKey(item.type)] ?: 1,
            model = model, voices = voices)
    }

    private fun takesKey(type: String): String = when (type) {
        "word" -> "words"
        "pair" -> "pairs"
        "story" -> "retell"
        "syllable" -> "syllables"
        else -> type
    }
}
