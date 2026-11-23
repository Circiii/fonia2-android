package tech.fonia.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import tech.fonia.app.house.Exercise
import tech.fonia.lessons.BlockResult
import tech.fonia.lessons.ItemResult
import tech.fonia.lessons.ItemStat
import tech.fonia.lessons.LessonHistory
import tech.fonia.lessons.LessonPlan
import tech.fonia.lessons.record
import tech.fonia.model.Rung
import tech.fonia.model.SoundId
import java.io.File
import java.time.LocalDate

/**
 * Where one sound stands: the rung on the ladder, the adult's lock after `isolated`, „Deblochează tot”, the guided
 * sessions, the LessonBuilder history and how often each exercise card was played.
 */
data class SoundPractice(
    val rung: Rung = Rung.Isolated,
    /** Good `isolated` takes collected towards the lock (12, chapter 06 §1). */
    val isolatedWins: Int = 0,
    /** An adult confirmed the sound alone sounds right: the rungs after `isolated` may open. */
    val adultConfirmed: Boolean = false,
    /** „Deblochează tot pentru acest sunet” (adult area). */
    val unlockAll: Boolean = false,
    val sessions: Int = 0,
    val history: LessonHistory = LessonHistory(),
    val plays: Map<Exercise, Int> = emptyMap(),
)

/** Today's mission card, waiting for the adult's tick. */
data class PendingMission(val sound: SoundId, val itemId: String, val text: String, val day: String, val done: Boolean = false)

/** Everything the practice book keeps (one JSON file in no-backup storage). */
data class PracticeState(
    val sounds: Map<SoundId, SoundPractice> = emptyMap(),
    val lastSound: SoundId? = null,
    /** Days with at least one counted round (ISO dates). The total only ever grows (Q7). */
    val days: Set<String> = emptySet(),
    val mission: PendingMission? = null,
) {
    fun of(sound: SoundId): SoundPractice = sounds[sound] ?: SoundPractice()

    fun with(sound: SoundId, f: (SoundPractice) -> SoundPractice): PracticeState = copy(sounds = sounds + (sound to f(of(sound))))

    val totalDays: Int get() = days.size

    /** Days in a row ending today (or yesterday, if today has nothing yet). 0 when the row is broken: then it is not shown (Q7). */
    fun streak(today: LocalDate): Int {
        var day = if (today.toString() in days) today else today.minusDays(1)
        var n = 0
        while (day.toString() in days) { n++; day = day.minusDays(1) }
        return n
    }
}

/**
 * The ladder rules (pure; JVM-tested). A guided session moves the rung up after 5 of 6 good in its counted blocks;
 * `isolated` never moves by itself: it collects good takes and then waits for the adult (the lock). The phone never
 * moves a child down.
 */
object Ladder {
    const val ISOLATED_WINS = 12
    const val PASS_RATE = 5.0 / 6.0

    fun afterGuided(p: SoundPractice, plan: LessonPlan, results: List<ItemResult>): SoundPractice {
        val history = p.history.record(plan, results, sessionIndex = p.sessions)
        val counted = plan.steps.filter { it.block in 2..3 }.flatMap { s -> s.items.filter { it.countsForProgress }.map { it.id } }.toSet()
        val mine = results.filter { it.itemId in counted }
        val rate = if (mine.isEmpty()) 0.0 else mine.count { it.success }.toDouble() / mine.size
        var next = p.copy(history = history, sessions = p.sessions + 1)
        if (plan.rung == Rung.Isolated) {
            next = next.copy(isolatedWins = p.isolatedWins + results.count { it.success })
        } else if (mine.size >= 3 && rate >= PASS_RATE && plan.rung == p.rung) {
            next = next.copy(rung = up(p.rung))
        }
        return next
    }

    fun afterExercise(p: SoundPractice, exercise: Exercise, plan: LessonPlan?, results: List<ItemResult>): SoundPractice {
        val history = if (plan != null && results.isNotEmpty()) p.history.record(plan, results, sessionIndex = p.sessions) else p.history
        val wins = if (exercise == Exercise.Hold && p.rung == Rung.Isolated) results.count { it.success } else 0
        return p.copy(history = history, plays = p.plays + (exercise to (p.plays[exercise] ?: 0) + 1), isolatedWins = p.isolatedWins + wins)
    }

    /** The lock is waiting for an adult: enough good takes of the sound alone, not confirmed yet. */
    fun needsAdult(p: SoundPractice): Boolean = p.rung == Rung.Isolated && !p.adultConfirmed && p.isolatedWins >= ISOLATED_WINS

    /** „Da, sună bine”: the lock opens and the ladder moves to syllables. */
    fun confirmIsolated(p: SoundPractice): SoundPractice =
        p.copy(adultConfirmed = true, rung = if (p.rung == Rung.Isolated) Rung.Syllables else p.rung)

    fun up(r: Rung): Rung = when (r) {
        Rung.Listen, Rung.Warmup -> Rung.Isolated
        Rung.Everyday -> Rung.Everyday
        else -> Rung.entries[r.ordinal + 1]
    }
}

/** Where the practice book is kept: the JSON file on the phone, or memory (tests, the demo film). */
interface PracticeStore {
    fun read(): String?
    fun write(text: String)
    fun wipe()
}

class FilePracticeStore(private val file: File) : PracticeStore {
    override fun read(): String? = if (file.isFile) file.readText(Charsets.UTF_8) else null
    override fun write(text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }
    override fun wipe() { file.delete() }
}

class MemoryPracticeStore(private var text: String? = null) : PracticeStore {
    override fun read(): String? = text
    override fun write(text: String) { this.text = text }
    override fun wipe() { text = null }
}

/**
 * The practice book: [PracticeState] in one JSON file (`no_backup/practice/practice.json`; `:core:data` has no table
 * for the LessonHistory and the schema stays as it is). Loaded once on IO, every change written through at once.
 */
class PracticeBook(private val store: PracticeStore) {
    private val lock = Mutex()
    @Volatile private var cache: PracticeState? = null

    suspend fun state(): PracticeState = lock.withLock { loaded() }

    /** The last state read (composition may show it; null before the first load). */
    fun peek(): PracticeState? = cache

    suspend fun update(f: (PracticeState) -> PracticeState): PracticeState = lock.withLock {
        val next = f(loaded())
        cache = next
        withContext(Dispatchers.IO) { runCatching { store.write(PracticeCodec.encode(next)) } }
        next
    }

    suspend fun wipe() = lock.withLock {
        cache = PracticeState()
        withContext(Dispatchers.IO) { runCatching { store.wipe() } }
    }

    private suspend fun loaded(): PracticeState = cache ?: withContext(Dispatchers.IO) {
        store.read()?.let { runCatching { PracticeCodec.decode(it) }.getOrNull() } ?: PracticeState()
    }.also { cache = it }
}

/** JSON of the practice book (kotlinx.serialization's tree API; the app has no serialization plugin). Schema 1. */
object PracticeCodec {
    private val json = Json { prettyPrint = false }

    fun encode(s: PracticeState): String = json.encodeToString(JsonElement.serializer(), buildJsonObject {
        put("schema", 1)
        s.lastSound?.let { put("lastSound", it.raw) }
        put("days", JsonArray(s.days.sorted().map(::JsonPrimitive)))
        s.mission?.let { m ->
            put("mission", buildJsonObject {
                put("sound", m.sound.raw); put("itemId", m.itemId); put("text", m.text); put("day", m.day); put("done", m.done)
            })
        }
        put("sounds", buildJsonObject {
            for ((sound, p) in s.sounds) put(sound.raw, sound(p))
        })
    })

    private fun sound(p: SoundPractice): JsonObject = buildJsonObject {
        put("rung", p.rung.raw)
        put("isolatedWins", p.isolatedWins)
        put("adultConfirmed", p.adultConfirmed)
        put("unlockAll", p.unlockAll)
        put("sessions", p.sessions)
        put("plays", buildJsonObject { for ((e, n) in p.plays) put(e.raw, n) })
        put("history", buildJsonObject {
            put("items", buildJsonObject {
                for ((id, st) in p.history.items) put(id, buildJsonArray {
                    add(JsonPrimitive(st.seen)); add(JsonPrimitive(st.successes)); add(JsonPrimitive(st.lastSeenSession))
                    add(JsonPrimitive(st.lastSuccess)); add(JsonPrimitive(st.box))
                })
            })
            put("last", JsonArray(p.history.lastSessionItems.sorted().map(::JsonPrimitive)))
            put("recent", JsonArray(p.history.recent.map(::JsonPrimitive)))
            put("blocks", JsonArray(p.history.recentBlocks.map { JsonArray(listOf(JsonPrimitive(it.attempts), JsonPrimitive(it.successes))) }))
        })
    }

    fun decode(text: String): PracticeState {
        val o = json.parseToJsonElement(text).jsonObject
        val sounds = o["sounds"]?.jsonObject.orEmpty().mapNotNull { (raw, v) ->
            SoundId.of(raw)?.let { it to soundOf(v.jsonObject) }
        }.toMap()
        val mission = o["mission"]?.jsonObject?.let { m ->
            val sound = SoundId.of(m.str("sound")) ?: return@let null
            PendingMission(sound, m.str("itemId"), m.str("text"), m.str("day"), m["done"]?.jsonPrimitive?.booleanOrNull ?: false)
        }
        return PracticeState(
            sounds = sounds,
            lastSound = o["lastSound"]?.jsonPrimitive?.content?.let(SoundId::of),
            days = o["days"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty(),
            mission = mission,
        )
    }

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.content.orEmpty()

    private fun soundOf(o: JsonObject): SoundPractice {
        val h = o["history"]?.jsonObject
        val items = h?.get("items")?.jsonObject.orEmpty().mapValues { (_, v) ->
            val a = v.jsonArray
            ItemStat(a[0].jsonPrimitive.int, a[1].jsonPrimitive.int, a[2].jsonPrimitive.int, a[3].jsonPrimitive.boolean, a[4].jsonPrimitive.int)
        }
        val history = LessonHistory(
            items = items,
            lastSessionItems = h?.get("last")?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty(),
            recent = h?.get("recent")?.jsonArray?.map { it.jsonPrimitive.boolean }.orEmpty(),
            recentBlocks = h?.get("blocks")?.jsonArray?.map { b -> b.jsonArray.let { BlockResult(it[0].jsonPrimitive.int, it[1].jsonPrimitive.int) } }.orEmpty(),
        )
        return SoundPractice(
            rung = o["rung"]?.jsonPrimitive?.content?.let { Rung.of(it) } ?: Rung.Isolated,
            isolatedWins = o["isolatedWins"]?.jsonPrimitive?.intOrNull ?: 0,
            adultConfirmed = o["adultConfirmed"]?.jsonPrimitive?.booleanOrNull ?: false,
            unlockAll = o["unlockAll"]?.jsonPrimitive?.booleanOrNull ?: false,
            sessions = o["sessions"]?.jsonPrimitive?.intOrNull ?: 0,
            history = history,
            plays = o["plays"]?.jsonObject.orEmpty().mapNotNull { (k, v) -> Exercise.of(k)?.let { it to (v.jsonPrimitive.intOrNull ?: 0) } }.toMap(),
        )
    }
}
