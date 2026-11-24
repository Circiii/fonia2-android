package tech.fonia.features.speaking

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import tech.fonia.features.app.FoniaPreferences
import java.util.UUID
import kotlin.math.max
import kotlin.math.sqrt

/**
 * What "Vorbit în public" keeps for one person (iOS `SpeakingMemory`): where they are on the topic ladder, how many
 * talks they have finished, and how many hesitations each of the latest talks had. The topics are ordered from the
 * most private to the most public, and exposure works best a step at a time, so today's topic is the one after the
 * last finished. The person can always step back.
 */
data class SpeakingMemory(
    /** The ladder step of the last topic finished, from any place it was started. */
    val lastFinished: Int? = null,
    /** A step the person went back to. A finished topic clears it. */
    val chosen: Int? = null,
    /** Every finished talk, ladder or not. */
    val talksFinished: Int = 0,
    /** The hesitations of the latest finished talks, oldest first: what a new talk is compared with. */
    val fillerTalks: List<FillerSample> = emptyList(),
) {
    /** Today's step: the one the person went back to, else the one after the last finished, else the first. */
    fun todaysStep(count: Int): Int {
        if (count <= 0) return 0
        val step = chosen ?: lastFinished?.let { it + 1 } ?: 0
        return step.coerceIn(0, count - 1)
    }

    /**
     * A talk that was heard all the way to the report. [step] is its place on the ladder, null for a talk that isn't
     * on it (a drill, the person's own presentation). [fillers] are its hesitations, for later talks to be compared with.
     */
    fun finished(step: Int?, fillers: FillerSample? = null): SpeakingMemory {
        val kept = if (fillers == null) fillerTalks else (fillerTalks + fillers).takeLast(FILLER_TALKS_KEPT)
        return if (step == null) {
            copy(talksFinished = talksFinished + 1, fillerTalks = kept)
        } else {
            copy(talksFinished = talksFinished + 1, fillerTalks = kept, lastFinished = step, chosen = null)
        }
    }

    fun stepBack(count: Int): SpeakingMemory = copy(chosen = max(todaysStep(count) - 1, 0))

    /** Saying "Abia aștept!" to yourself helps some people and not others: in full for the first talks, then a tap away. */
    val showsNervesTip: Boolean get() = talksFinished < TALKS_WITH_NERVES_TIP

    fun encode(): String = buildJsonObject {
        lastFinished?.let { put("lastFinished", it) }
        chosen?.let { put("chosen", it) }
        put("talksFinished", talksFinished)
        put(
            "fillerTalks",
            buildJsonArray {
                for (sample in fillerTalks) add(buildJsonObject { put("count", sample.count); put("seconds", sample.seconds) })
            },
        )
    }.toString()

    fun save(profile: UUID, preferences: FoniaPreferences?) {
        preferences?.setString(key(profile), encode())
    }

    companion object {
        const val TALKS_WITH_NERVES_TIP = 2

        /** The comparison looks at the last five talks long enough, so twenty is plenty. */
        const val FILLER_TALKS_KEPT = 20

        /** Under `fonia.`, with the other preferences that "Șterge tot" clears. */
        fun key(profile: UUID): String = "fonia.speaking.$profile"

        /**
         * Everything may be missing: what was saved before the hesitations were kept has no `fillerTalks`, and a missing
         * key gives an empty list, not a lost place on the ladder. A file that can't be read starts over.
         */
        fun decode(stored: String): SpeakingMemory {
            if (stored.isBlank()) return SpeakingMemory()
            val root = runCatching { Json.parseToJsonElement(stored) as? JsonObject }.getOrNull() ?: return SpeakingMemory()
            fun int(key: String): Int? = (root[key] as? JsonPrimitive)?.intOrNull
            val talks = (root["fillerTalks"] as? JsonArray).orEmpty().mapNotNull { element ->
                val sample = element as? JsonObject ?: return@mapNotNull null
                val count = (sample["count"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
                val seconds = (sample["seconds"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                FillerSample(count, seconds)
            }
            return SpeakingMemory(int("lastFinished"), int("chosen"), int("talksFinished") ?: 0, talks)
        }

        fun load(profile: UUID, preferences: FoniaPreferences?): SpeakingMemory =
            preferences?.let { decode(it.string(key(profile))) } ?: SpeakingMemory()

        /** When the profile is deleted. */
        fun forget(profile: UUID, preferences: FoniaPreferences?) {
            preferences?.remove(key(profile))
        }
    }
}

/** The hesitations of one finished talk and the seconds of speech they were counted in. */
data class FillerSample(val count: Int, val seconds: Double) {
    /** Per minute of speech, or null when the talk was too short for a rate to mean anything. */
    val perMinute: Double? get() = if (seconds >= FillerTrend.MINIMUM_SECONDS) count * 60.0 / seconds else null
}

/**
 * How a talk's hesitations compare with the same person's earlier talks, and with nothing else (iOS `FillerTrend`).
 * There is no "normal" amount of "ăă" that holds for everyone, so no number here is a limit: a talk is compared with
 * the median of that person's last five talks that had enough speech, and counts within the noise of a count are
 * "about the same".
 */
sealed interface FillerTrend {
    /** Under 30 s of speech: the count is shown and nothing more. */
    data object TooShort : FillerTrend

    /** Too few earlier talks with enough speech to say what is usual for this person. */
    data object FirstTalks : FillerTrend

    /** Fewer, about the same or more than usual, with what is usual in hesitations per minute. */
    data class Fewer(val usual: Double) : FillerTrend
    data class Same(val usual: Double) : FillerTrend
    data class More(val usual: Double) : FillerTrend

    val isFewer: Boolean get() = this is Fewer

    enum class Direction { Lower, Alike, Higher }

    companion object {
        /** The shortest speech a per-minute rate is read from (iOS `SpeechReport.minimumSecondsForRate`). */
        const val MINIMUM_SECONDS = 30.0

        /** How many of the latest earlier talks make up "usual". */
        const val WINDOW = 5

        /** A median of one or two talks is one talk's luck, so the comparison waits for three. */
        const val MINIMUM_EARLIER = 3

        /**
         * A count of hesitations is a count of events, so its usual spread is its square root. Within this many of them,
         * and never fewer than [FLOOR] hesitations, a talk is about the same as usual.
         */
        const val SPREAD = 1.5
        const val FLOOR = 2.0

        /** The median rate of the last [WINDOW] earlier talks long enough to have one; null with fewer than [MINIMUM_EARLIER]. */
        fun usualRate(earlier: List<FillerSample>): Double? {
            val rates = earlier.mapNotNull { it.perMinute }.takeLast(WINDOW).sorted()
            if (rates.size < MINIMUM_EARLIER) return null
            val middle = rates.size / 2
            return if (rates.size % 2 == 0) (rates[middle - 1] + rates[middle]) / 2 else rates[middle]
        }

        /** [count] hesitations in [seconds] of speech against [earlier], the person's earlier talks oldest first. */
        fun compare(count: Int, seconds: Double, earlier: List<FillerSample>): FillerTrend {
            if (seconds < MINIMUM_SECONDS) return TooShort
            val usual = usualRate(earlier) ?: return FirstTalks
            return when (direction(count, seconds, usual)) {
                Direction.Lower -> Fewer(usual)
                Direction.Alike -> Same(usual)
                Direction.Higher -> More(usual)
            }
        }

        /**
         * Whether [count] in [seconds] is beyond what the rate [usual] would give in that time, by more than the noise of
         * a count. Longer talks get a narrower allowance in rate terms, since the same noise is spread over more time.
         */
        fun direction(count: Int, seconds: Double, usual: Double): Direction {
            val expected = usual * seconds / 60
            val allowance = max(FLOOR, SPREAD * sqrt(expected))
            val value = count.toDouble()
            return when {
                value > expected + allowance -> Direction.Higher
                value < expected - allowance -> Direction.Lower
                else -> Direction.Alike
            }
        }

        /** The previous take against this one: the earlier one's rate is "usual". Null when either is too short. */
        fun change(earlier: FillerSample, current: FillerSample): Direction? {
            val usual = earlier.perMinute ?: return null
            if (current.perMinute == null) return null
            return direction(current.count, current.seconds, usual)
        }
    }
}
