package tech.fonia.features.session

import tech.fonia.judge.PersonalReference
import tech.fonia.judge.TakeVisual
import tech.fonia.model.Verdict
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * The few numbers of one take's [TakeVisual] (no audio, no text): where his hiss sat on the S↔Ș axis and the target
 * zone, whether the voice was on, the R flutter, the closure of Ț/CE/GE, the held time, and how it was judged. Enough
 * to draw "today vs. the first time".
 */
data class TakeDot(
    val atMillis: Long,
    /** `SoundId.raw` ("s", "sh", …). */
    val sound: String,
    /** "correct", "almost", "named", "unsure". */
    val outcome: String,
    val place: Float = Float.NaN,
    val zoneLow: Float = Float.NaN,
    val zoneHigh: Float = Float.NaN,
    /** 1 voice on, 0 off, NaN not measured. */
    val voiced: Float = Float.NaN,
    val trill: Float = Float.NaN,
    /** 1 closure then release, 0 not, NaN n/a. */
    val closure: Float = Float.NaN,
    val durationMs: Float = Float.NaN,
) {
    val hasPlace: Boolean get() = !place.isNaN() && !zoneLow.isNaN()
    val inZone: Boolean get() = hasPlace && place in zoneLow..zoneHigh
    val isCorrect: Boolean get() = outcome == "correct"

    fun line(): String = listOf(
        atMillis.toString(), sound, outcome, f(place), f(zoneLow), f(zoneHigh), f(voiced), f(trill), f(closure), f(durationMs),
    ).joinToString(",")

    companion object {
        private fun f(v: Float) = if (v.isNaN()) "" else String.format(Locale.ROOT, "%.3f", v)
        private fun g(s: String?) = s?.toFloatOrNull() ?: Float.NaN

        fun parse(line: String): TakeDot? {
            val p = line.split(',')
            if (p.size < 10) return null
            val at = p[0].toLongOrNull() ?: return null
            return TakeDot(at, p[1], p[2], g(p[3]), g(p[4]), g(p[5]), g(p[6]), g(p[7]), g(p[8]), g(p[9]))
        }

        fun of(visual: TakeVisual?, sound: String, verdict: Verdict, atMillis: Long): TakeDot {
            val outcome = when (verdict) {
                Verdict.Correct -> "correct"
                Verdict.Almost -> "almost"
                is Verdict.NamedError -> "named"
                else -> "unsure"
            }
            val axis = visual?.placeAxis
            val voice = visual?.voicing?.present
            val closure = visual?.closure?.let { c -> if (c.closure == null) null else (c.closure == true && c.release != false) }
            return TakeDot(
                atMillis, sound, outcome,
                place = axis?.value ?: Float.NaN, zoneLow = axis?.zoneLow ?: Float.NaN, zoneHigh = axis?.zoneHigh ?: Float.NaN,
                voiced = when (voice) { true -> 1f; false -> 0f; null -> Float.NaN },
                trill = visual?.trill?.value ?: Float.NaN,
                closure = when (closure) { true -> 1f; false -> 0f; null -> Float.NaN },
                durationMs = visual?.duration?.value ?: Float.NaN,
            )
        }
    }
}

/** "Today vs. the first time" for one sound: the dots of the first day he practised it and of the latest day. */
data class SoundProgress(
    val first: List<TakeDot>,
    val latest: List<TakeDot>,
    /** Latest day: takes in the zone (or right, for sounds with no axis) and all judged takes. */
    val latestGood: Int,
    val latestTotal: Int,
    val firstGood: Int,
    val firstTotal: Int,
) {
    val sameDay: Boolean get() = first === latest || first == latest

    companion object {
        private const val DAY = 86_400_000L

        /** Takes compared at each end ("8 din 10"). */
        const val WINDOW = 10

        private fun good(d: TakeDot) = if (d.hasPlace) d.inZone else d.isCorrect

        /** Null when there is nothing judged yet. [zoneOffsetMillis] puts the day boundary at local midnight. */
        fun of(dots: List<TakeDot>, zoneOffsetMillis: Long = 0L): SoundProgress? {
            val judged = dots.filter { it.outcome != "unsure" }.sortedBy { it.atMillis }
            if (judged.isEmpty()) return null
            val byDay = judged.groupBy { (it.atMillis + zoneOffsetMillis).floorDiv(DAY) }
            var first = byDay.getValue(byDay.keys.min())
            var latest = byDay.getValue(byDay.keys.max())
            // One day only, but enough takes: his first ten against his last ten, so the move shows the same day.
            if (byDay.size == 1 && judged.size >= 2 * WINDOW) {
                first = judged.take(WINDOW)
                latest = judged.takeLast(WINDOW)
            } else {
                latest = latest.takeLast(WINDOW)
                first = first.take(WINDOW)
            }
            return SoundProgress(first, latest, latest.count(::good), latest.size, first.count(::good), first.size)
        }
    }
}

/**
 * Per profile, beside the database in no-backup storage: his [PersonalReference] (`personal-<id>.json`) and the dots
 * of his takes (`dots-<id>.csv`, newest [MAX_DOTS] kept). Everything of a profile goes with [keepOnly] (a deleted
 * profile, "Șterge tot"). Thread-safe; [dir] null keeps everything in memory (JVM tests).
 */
class VoiceStore(private val dir: File?) {
    private val lock = Any()
    private val personal = HashMap<UUID, PersonalReference>()
    private val dots = HashMap<UUID, MutableList<TakeDot>>()

    private fun personalFile(id: UUID) = dir?.let { File(it, "personal-$id.json") }
    private fun dotsFile(id: UUID) = dir?.let { File(it, "dots-$id.csv") }

    /** His reference, loaded once per process. */
    fun personal(id: UUID): PersonalReference = synchronized(lock) {
        personal.getOrPut(id) {
            val text = runCatching { personalFile(id)?.takeIf { it.isFile }?.readText() }.getOrNull()
            text?.let { runCatching { PersonalReference.fromJson(it) }.getOrNull() } ?: PersonalReference()
        }
    }

    fun savePersonal(id: UUID) {
        val ref = synchronized(lock) { personal[id] } ?: return
        val text = synchronized(ref) { ref.toJson() }
        write(personalFile(id), text)
    }

    /** True once his own S/Ș boundary is in use ("Calibrat pe vocea ta"). */
    fun calibrated(id: UUID): Boolean = personal(id).let { synchronized(it) { it.active } }

    fun addDot(id: UUID, dot: TakeDot) {
        val all = synchronized(lock) {
            val list = loadDots(id)
            list += dot
            while (list.size > MAX_DOTS) list.removeAt(0)
            list.toList()
        }
        write(dotsFile(id), all.joinToString("\n") { it.line() })
    }

    fun dots(id: UUID, sound: String? = null): List<TakeDot> = synchronized(lock) {
        loadDots(id).filter { sound == null || it.sound == sound }
    }

    /** Forgets every profile not in [ids]. */
    fun keepOnly(ids: Set<UUID>) {
        synchronized(lock) {
            personal.keys.retainAll(ids)
            dots.keys.retainAll(ids)
        }
        val files = dir?.listFiles() ?: return
        for (f in files) {
            val id = f.name.removePrefix("personal-").removePrefix("dots-").substringBeforeLast('.')
            val uuid = runCatching { UUID.fromString(id) }.getOrNull() ?: continue
            if (uuid !in ids) f.delete()
        }
    }

    private fun loadDots(id: UUID): MutableList<TakeDot> = dots.getOrPut(id) {
        val text = runCatching { dotsFile(id)?.takeIf { it.isFile }?.readText() }.getOrNull() ?: ""
        text.lineSequence().mapNotNull { TakeDot.parse(it) }.toMutableList()
    }

    private fun write(file: File?, text: String) {
        file ?: return
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    companion object {
        const val MAX_DOTS = 3000
    }
}
