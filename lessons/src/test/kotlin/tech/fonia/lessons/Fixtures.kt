package tech.fonia.lessons

import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import tech.fonia.model.Rung
import tech.fonia.model.SoundId

object Fixtures {
    val contentDir: File = File(System.getProperty("fonia.contentV2") ?: "../spec/content/v2")
    val vectorsDir: File = File(System.getProperty("fonia.vectors") ?: "../spec/content/vectors")
    val voiceDir: File = File(System.getProperty("fonia.voice") ?: "../spec/content/voice")

    val content: LessonContent by lazy { LessonContent.fromDirectory(contentDir) }
    val builder: LessonBuilder by lazy { LessonBuilder(content) }
}

/** The golden vectors: request and plan as plain JSON, so the Swift test can read the same file. */
object Vectors {
    val seeds = listOf(1uL, 42uL, 20261121uL)

    /** 9 sounds x 14 rungs x 3 seeds, plus "încă una", the long pause and the three adaptation states per sound. */
    fun requests(content: LessonContent): List<Pair<String, LessonRequest>> {
        val out = mutableListOf<Pair<String, LessonRequest>>()
        for (sound in SoundId.entries) {
            for (rung in Rung.entries) {
                seeds.forEachIndexed { index, seed ->
                    out += "${sound.raw}/${rung.raw}/$seed" to request(content, sound, rung, seed, index)
                }
            }
            val base = request(content, sound, Rung.WordsInitial, 7uL, 1)
            out += "${sound.raw}/oneMore" to base.copy(oneMore = true)
            out += "${sound.raw}/afterPause" to request(content, sound, Rung.WordsMedial, 7uL, 1).copy(daysSinceLast = 9)
            for (level in AdaptLevel.entries) {
                out += "${sound.raw}/isolated/${level.raw}" to request(content, sound, Rung.Isolated, 9uL, 1).copy(adaptation = level)
                out += "${sound.raw}/syllables/${level.raw}" to request(content, sound, Rung.Syllables, 9uL, 1).copy(adaptation = level)
            }
        }
        return out
    }

    /**
     * Variant 0: a first session. Variant 1: every second item of the rung (and of the passed word rungs) known,
     * every fifth one missed last session. Variant 2: an older child who has worked on every sound, with every
     * therapist-check item approved.
     */
    fun request(content: LessonContent, sound: SoundId, rung: Rung, seed: ULong, variant: Int): LessonRequest {
        val sessions = variant * 3
        if (variant == 0) return LessonRequest(sound, rung, seed)
        val pool = (LessonBuilder.WORD_RUNGS.filter { it.ordinal <= rung.ordinal } + rung).distinct()
            .flatMap { content.items(sound, it) }
        val stats = linkedMapOf<String, ItemStat>()
        pool.forEachIndexed { i, item ->
            when {
                i % 5 == 0 -> stats[item.id] = ItemStat(seen = 2, successes = 1, lastSeenSession = sessions - 1, lastSuccess = false, box = 0)
                i % 2 == 0 -> stats[item.id] = ItemStat(seen = 3, successes = 2, lastSeenSession = sessions - 2, lastSuccess = true, box = 2)
            }
        }
        val history = LessonHistory(
            items = stats,
            lastSessionItems = pool.filterIndexed { i, _ -> i % 3 == 0 }.map { it.id }.toSet(),
            recent = List(10) { it % 4 != 0 },
            recentBlocks = listOf(BlockResult(9, 7), BlockResult(9, 8)),
        )
        return if (variant == 1) {
            LessonRequest(sound, rung, seed, sessionsCompleted = sessions, history = history)
        } else {
            val approved = content.sounds.values.flatMap { d -> d.items.filter { it.needsTherapistCheck }.map { it.id } } +
                content.twisters.filter { it.needsTherapistCheck }.map { it.id }
            LessonRequest(sound, rung, seed, sessionsCompleted = sessions, history = history, childAge = 7,
                approvedItems = approved.toSet(), workedSounds = SoundId.entries.toSet())
        }
    }

    fun requestJson(r: LessonRequest): JsonObject = buildJsonObject {
        put("sound", r.sound.raw)
        put("rung", r.rung.raw)
        put("seed", r.seed.toString())
        put("sessionsCompleted", r.sessionsCompleted)
        put("childAge", r.childAge)
        put("daysSinceLast", r.daysSinceLast)
        put("oneMore", r.oneMore)
        r.adaptation?.let { put("adaptation", it.raw) }
        putJsonArray("approvedItems") { r.approvedItems.sorted().forEach { add(JsonPrimitive(it)) } }
        putJsonArray("workedSounds") { r.workedSounds.sortedBy { it.ordinal }.forEach { add(JsonPrimitive(it.raw)) } }
        putJsonObject("history") {
            putJsonObject("items") {
                r.history.items.toSortedMap().forEach { (id, s) ->
                    putJsonObject(id) {
                        put("seen", s.seen); put("successes", s.successes); put("lastSeenSession", s.lastSeenSession)
                        put("lastSuccess", s.lastSuccess); put("box", s.box)
                    }
                }
            }
            putJsonArray("lastSessionItems") { r.history.lastSessionItems.sorted().forEach { add(JsonPrimitive(it)) } }
            putJsonArray("recent") { r.history.recent.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("recentBlocks") {
                r.history.recentBlocks.forEach { b -> add(buildJsonArray { add(JsonPrimitive(b.attempts)); add(JsonPrimitive(b.successes)) }) }
            }
        }
    }

    fun requestFrom(o: JsonObject): LessonRequest {
        val h = o.getValue("history").jsonObject
        return LessonRequest(
            sound = SoundId.of(o.str("sound"))!!,
            rung = Rung.of(o.str("rung"))!!,
            seed = o.str("seed").toULong(),
            sessionsCompleted = o.int("sessionsCompleted"),
            history = LessonHistory(
                items = h.getValue("items").jsonObject.mapValues { (_, v) ->
                    val s = v.jsonObject
                    ItemStat(s.int("seen"), s.int("successes"), s.int("lastSeenSession"),
                        s.getValue("lastSuccess").jsonPrimitive.boolean, s.int("box"))
                },
                lastSessionItems = h.getValue("lastSessionItems").jsonArray.map { it.jsonPrimitive.content }.toSet(),
                recent = h.getValue("recent").jsonArray.map { it.jsonPrimitive.boolean },
                recentBlocks = h.getValue("recentBlocks").jsonArray.map {
                    BlockResult(it.jsonArray[0].jsonPrimitive.int, it.jsonArray[1].jsonPrimitive.int)
                },
            ),
            childAge = o.int("childAge"),
            approvedItems = o.getValue("approvedItems").jsonArray.map { it.jsonPrimitive.content }.toSet(),
            workedSounds = o.getValue("workedSounds").jsonArray.map { SoundId.of(it.jsonPrimitive.content)!! }.toSet(),
            daysSinceLast = o.int("daysSinceLast"),
            oneMore = o.getValue("oneMore").jsonPrimitive.boolean,
            adaptation = o["adaptation"]?.jsonPrimitive?.content?.let { raw -> AdaptLevel.entries.first { it.raw == raw } },
        )
    }

    fun planJson(p: LessonPlan): JsonObject = buildJsonObject {
        put("seed", p.seed.toString())
        put("kind", p.kind.name)
        put("adaptation", p.adaptation.raw)
        put("firstOnRung", p.firstOnRung)
        putJsonArray("steps") {
            p.steps.forEach { s ->
                add(buildJsonObject {
                    put("kind", s.kind.name)
                    put("block", s.block)
                    s.rung?.let { put("rung", it.raw) }
                    put("start", s.startSecond)
                    put("seconds", s.seconds)
                    putJsonArray("lines") { s.lineKeys.forEach { add(JsonPrimitive(it)) } }
                    s.holdGoalSeconds?.let { put("holdGoalSeconds", it) }
                    s.holdMode?.let { put("holdMode", it) }
                    s.cutAt?.let { put("cutAt", it) }
                    putJsonArray("items") {
                        s.items.forEach { i ->
                            add(buildJsonObject {
                                put("id", i.id)
                                if (i.takes != 1) put("takes", i.takes)
                                if (i.model) put("model", true)
                                if (i.surprise) put("surprise", true)
                                if (!i.countsForProgress) put("counts", false)
                                if (i.review) put("review", true)
                                i.variant?.let { put("variant", it) }
                            })
                        }
                    }
                })
            }
        }
    }

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.int(key: String) = getValue(key).jsonPrimitive.int
}
