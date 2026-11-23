package tech.fonia.lessons

import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import tech.fonia.model.Rung
import tech.fonia.model.SoundId

class LessonBuilderTest {
    private val content = Fixtures.content
    private val builder = Fixtures.builder

    private fun plan(sound: SoundId, rung: Rung, seed: ULong = 1uL, variant: Int = 0) =
        builder.build(Vectors.request(content, sound, rung, seed, variant))

    private val voiceKeys: Set<String> by lazy {
        val keys = mutableSetOf("human-needed")
        content.lines.forEach { keys += it.key }
        content.twisters.forEach { keys += it.voice }
        content.sounds.values.forEach { d ->
            d.items.forEach { i -> listOfNotNull(i.voice, i.voiceA, i.voiceB, i.promptVoice, i.foilVoice).forEach { keys += it }; keys += i.lineVoices }
        }
        keys
    }

    private fun lineExists(key: String): Boolean =
        content.line(key) != null || content.sounds.values.any { d -> d.hero.chapters.values.any { it.key == key } || d.items.any { it.promptVoice == key } }

    @Test
    fun `same request gives the same plan, another seed another plan`() {
        val request = Vectors.request(content, SoundId.S, Rung.WordsInitial, 42uL, 1)
        assertEquals(builder.build(request), builder.build(request))
        val other = builder.build(request.copy(seed = 43uL))
        assertNotEquals(builder.build(request).productions.map { it.id }, other.productions.map { it.id })
        assertEquals(LessonBuilder.seedFor(request), builder.build(request).seed)
    }

    @Test
    fun `every sound, rung and seed gives a sound 4-minute plan`() {
        for (sound in SoundId.entries) for (rung in Rung.entries) for ((v, seed) in Vectors.seeds.withIndex()) {
            val p = plan(sound, rung, seed, v)
            val where = "${sound.raw}/${rung.raw}/$seed"
            assertEquals(PlanKind.Normal, p.kind)
            assertEquals(StepKind.Opening, p.steps.first().kind, where)
            assertEquals(StepKind.Final, p.steps.last().kind, where)
            assertTrue(p.totalSeconds <= 240, "$where lasts ${p.totalSeconds}")
            assertEquals(p.steps.sortedBy { it.startSecond }, p.steps, "$where steps out of order")
            assertTrue(p.steps.any { it.kind == StepKind.Move }, where)
            val blocks = p.steps.filter { it.block in 2..3 }
            assertEquals(2, blocks.size, where)
            blocks.forEach { assertTrue(it.items.isNotEmpty(), "$where block ${it.block} empty") }
            for (step in p.steps) {
                step.lineKeys.forEach { assertTrue(lineExists(it), "$where: line $it") }
                for (item in step.items) {
                    item.voices.forEach { assertTrue(it in voiceKeys, "$where: voice $it") }
                    val known = item.id.startsWith("train:") || item.id.startsWith("warm.") || item.id.startsWith("t.") ||
                        content.item(item.id) != null
                    assertTrue(known, "$where: unknown item ${item.id}")
                }
                val ids = step.items.map { it.id }
                ids.zipWithNext().forEach { (a, b) -> assertTrue(a != b || ids.toSet().size == 1, "$where: $a twice in a row") }
            }
        }
    }

    @Test
    fun `block 2 of a word rung holds only that rung, block 3 mixes and has one surprise at slot 5 to 7`() {
        for (sound in SoundId.entries) for (rung in LessonBuilder.WORD_RUNGS) {
            val p = plan(sound, rung, 42uL, 1)
            val block2 = p.steps.first { it.block == 2 }
            block2.items.forEach { assertEquals(rung.raw, content.item(it.id)!!.rung, "${sound.raw} ${it.id}") }
            assertEquals(9, block2.items.size, "${sound.raw}/${rung.raw} block 2: ${p.steps.map { it.kind.name + it.block + ":" + it.items.size }}")
            val block3 = p.steps.first { it.block == 3 }
            val surprises = block3.items.withIndex().filter { it.value.surprise }
            assertEquals(1, surprises.size, "${sound.raw}/${rung.raw}")
            assertTrue(surprises.single().index in 4..6)
            assertTrue(!surprises.single().value.countsForProgress)
            assertEquals(block3.items.size / 2, block3.cutAt)
            if (rung != Rung.WordsInitial) {
                assertTrue(block3.items.any { !it.surprise && content.item(it.id)!!.rung != rung.raw }, "${sound.raw}/${rung.raw} not mixed")
            }
        }
    }

    @Test
    fun `items waiting for the therapist and items for older children stay out`() {
        for (sound in SoundId.entries) for (rung in Rung.entries) {
            val p = plan(sound, rung, 1uL, 0)
            for (item in p.productions) {
                content.item(item.id)?.let {
                    assertTrue(!it.needsTherapistCheck, "${item.id} needs a therapist")
                    assertEquals("4+", it.ageBand, "${item.id} is ${it.ageBand}")
                }
                content.twisters.firstOrNull { it.id == item.id }?.let { assertEquals("4+", it.ageBand) }
            }
        }
        val approved = content.items(SoundId.Z, Rung.WordsFinal).filter { it.needsTherapistCheck }.map { it.id }.toSet()
        val withApproval = builder.build(LessonRequest(SoundId.Z, Rung.WordsFinal, 5uL, approvedItems = approved, childAge = 8))
        assertTrue(withApproval.productions.any { it.id in approved })
    }

    @Test
    fun `words with unworked hard sounds come in the second half`() {
        val p = builder.build(LessonRequest(SoundId.S, Rung.WordsInitial, 3uL))
        val block2 = p.steps.first { it.block == 2 }.items.map { content.item(it.id)!! }
        val firstDeferred = block2.indexOfFirst { it.otherSounds.isNotEmpty() }
        if (firstDeferred >= 0) assertTrue(block2.drop(firstDeferred).all { it.otherSounds.isNotEmpty() })
    }

    @Test
    fun `the adaptation moves the hold goal, the train length and the model`() {
        val spec = content.lesson.adaptation
        assertEquals(AdaptLevel.Harder, Adaptation.assess(LessonHistory(recentBlocks = listOf(BlockResult(10, 10), BlockResult(10, 10))), spec))
        assertEquals(AdaptLevel.Easier, Adaptation.assess(LessonHistory(recent = List(10) { it < 5 }), spec))
        assertEquals(AdaptLevel.Steady, Adaptation.assess(LessonHistory(recent = List(10) { it < 8 }), spec))
        assertEquals(AdaptLevel.Steady, Adaptation.assess(LessonHistory(recent = listOf(false, false)), spec))

        val base = Vectors.request(content, SoundId.S, Rung.Isolated, 9uL, 1)
        val easy = builder.build(base.copy(adaptation = AdaptLevel.Easier)).steps.first { it.block == 2 }
        val hard = builder.build(base.copy(adaptation = AdaptLevel.Harder)).steps.first { it.block == 2 }
        assertEquals(1.0, easy.holdGoalSeconds)
        assertEquals(3.0, hard.holdGoalSeconds)
        assertNull(builder.build(base.copy(sound = SoundId.Ts)).steps.first { it.block == 2 }.holdGoalSeconds, "bursts have no seconds goal")

        val syl = Vectors.request(content, SoundId.R, Rung.Syllables, 9uL, 1)
        val short = builder.build(syl.copy(adaptation = AdaptLevel.Easier)).steps.first { it.block == 2 }.items
        val long = builder.build(syl.copy(adaptation = AdaptLevel.Harder)).steps.first { it.block == 2 }.items
        assertTrue(short.all { it.syllables.size == 2 && it.model })
        assertTrue(long.all { it.syllables.size == 5 })

        val tw = Vectors.request(content, SoundId.L, Rung.Twister, 9uL, 1)
        assertEquals("slow", builder.build(tw.copy(adaptation = AdaptLevel.Easier)).steps.first { it.block == 3 }.items.single().variant)
        assertEquals("step", builder.build(tw.copy(adaptation = AdaptLevel.Steady)).steps.first { it.block == 3 }.items.single().variant)
    }

    @Test
    fun `a missed item comes back when its spaced-repetition interval is up`() {
        val word = content.items(SoundId.Sh, Rung.WordsInitial)[3]
        val missed = mapOf(word.id to ItemStat(seen = 2, successes = 1, lastSeenSession = 4, lastSuccess = false, box = 0))
        val due = builder.build(LessonRequest(SoundId.Sh, Rung.WordsInitial, 11uL, sessionsCompleted = 5,
            history = LessonHistory(items = missed)))
        val back = due.productions.filter { it.id == word.id }
        assertTrue(back.isNotEmpty() && back.first().review, "the missed word should be reviewed")
        val notYet = builder.build(LessonRequest(SoundId.Sh, Rung.WordsInitial, 11uL, sessionsCompleted = 5,
            history = LessonHistory(items = mapOf(word.id to missed.getValue(word.id).copy(box = 3)))))
        assertTrue(notYet.productions.none { it.review })
    }

    @Test
    fun `known and new are mixed at about 70 to 30, half and half on a first session`() {
        val pool = content.items(SoundId.R, Rung.WordsInitial)
        val known = pool.take(10).associate { it.id to ItemStat(seen = 3, successes = 2, lastSeenSession = 0, lastSuccess = true, box = 2) }
        val p = builder.build(LessonRequest(SoundId.R, Rung.WordsInitial, 2uL, sessionsCompleted = 1,
            history = LessonHistory(items = known), workedSounds = SoundId.entries.toSet()))
        val block2 = p.steps.first { it.block == 2 }.items
        assertEquals(3, block2.count { it.id !in known })
        val first = builder.build(LessonRequest(SoundId.R, Rung.WordsInitial, 2uL))
        assertTrue(first.firstOnRung)
        assertTrue(first.steps.first { it.block == 2 }.items.all { it.model })
    }

    @Test
    fun `one more is one block and the final, about 90 seconds`() {
        for (rung in Rung.entries) {
            val p = builder.build(LessonRequest(SoundId.Ce, rung, 4uL, oneMore = true))
            assertEquals(PlanKind.OneMore, p.kind)
            assertEquals(listOf(2, 0), p.steps.map { it.block })
            assertTrue(p.totalSeconds <= content.lesson.template.oneMore.seconds)
            assertTrue(p.steps.first().items.isNotEmpty(), rung.raw)
        }
    }

    @Test
    fun `after a long pause only two blocks of known items`() {
        val r = Vectors.request(content, SoundId.Z, Rung.WordsMedial, 7uL, 1).copy(daysSinceLast = 8)
        val p = builder.build(r)
        assertEquals(PlanKind.AfterPause, p.kind)
        assertTrue(p.steps.none { it.kind == StepKind.Move || it.block == 3 })
        val block2 = p.steps.first { it.block == 2 }.items
        assertTrue(block2.all { r.history.items[it.id]?.known == true })
    }

    @Test
    fun `listen alternates the right word and the mix-up, pairs keep both words`() {
        val listen = plan(SoundId.S, Rung.Listen, 1uL).steps.filter { it.kind == StepKind.Listen }.flatMap { it.items }
        assertTrue(listen.map { it.variant }.toSet() == setOf("target", "foil"))
        listen.filter { it.variant == "foil" }.forEach { assertEquals(listOf("human-needed"), it.voices) }
        val pairs = plan(SoundId.R, Rung.Pairs, 1uL).steps.filter { it.kind == StepKind.Pairs }.flatMap { it.items }
        pairs.forEach { assertEquals(2, it.voices.size) }
    }

    @Test
    fun `record folds results into the history`() {
        val p = plan(SoundId.J, Rung.WordsInitial, 1uL)
        val counted = p.steps.filter { it.block in 2..3 }.flatMap { it.items }.filter { it.countsForProgress }
        val results = counted.mapIndexed { i, item -> ItemResult(item.id, i % 4 != 0) }
        val h = LessonHistory().record(p, results, sessionIndex = 0)
        val missed = results.groupBy { it.itemId }.entries.first { (_, r) -> !r.last().success }.key
        assertEquals(0, h.items.getValue(missed).box)
        assertTrue(h.items.values.any { it.box == 1 })
        assertEquals(results.size, h.recent.size)
        assertEquals(2, h.recentBlocks.size)
        val next = builder.build(LessonRequest(SoundId.J, Rung.WordsInitial, 1uL, sessionsCompleted = 1, history = h))
        assertTrue(next.productions.any { it.id == missed && it.review }, "a missed word returns the next session")
    }
}
