package tech.fonia.speaking.coach

import tech.fonia.speaking.SpeakingText
import kotlin.math.roundToInt

/** What one finished talk leaves for his habit: its pace and its pitch range. NaN = not measured. */
data class TalkLandmarks(
    /** Syllables per second of speech over the whole talk. */
    val syllablesPerSecond: Double,
    /** p10 to p90 of the talk's melody, in semitones. */
    val pitchRangeSt: Double,
    /** A 30-second drill bends one thing on purpose: it is compared with his habit but does not make it. */
    val drill: Boolean = false,
)

/**
 * His usual pace and pitch range: the median of each over his last [CoachSpec.Baseline.talks] whole talks that
 * measured it. NaN where fewer than [CoachSpec.Baseline.minTalks] did. Pure.
 */
data class SpeakerBaseline(val pace: Double, val pitchRangeSt: Double, val paceTalks: Int, val pitchRangeTalks: Int) {
    companion object {
        /** From his talks, oldest first; null when neither measure has enough talks yet. */
        fun of(talks: List<TalkLandmarks>, spec: CoachSpec = CoachSpec.v1): SpeakerBaseline? {
            val whole = talks.filter { !it.drill }
            val paces = whole.map { it.syllablesPerSecond }.filter { it.isFinite() && it > 0 }.takeLast(spec.baseline.talks)
            val ranges = whole.map { it.pitchRangeSt }.filter { it.isFinite() && it > 0 }.takeLast(spec.baseline.talks)
            val pace = if (paces.size >= spec.baseline.minTalks) median(paces) else Double.NaN
            val range = if (ranges.size >= spec.baseline.minTalks) median(ranges) else Double.NaN
            if (pace.isNaN() && range.isNaN()) return null
            return SpeakerBaseline(pace, range, paces.size, ranges.size)
        }

        private fun median(values: List<Double>): Double {
            val sorted = values.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
        }
    }
}

/** Where one measure of this talk sits against his habit. */
enum class Versus { Below, Usual, Above }

/**
 * This talk against his own habit, besides the bands fitted on other people. A null side is not said: his habit is
 * not known yet, or this talk did not measure it. Pure.
 */
data class AgainstUsual(
    val pace: Versus?,
    val pitchRange: Versus?,
    /** This talk's and his usual values, for the detail line (NaN when unknown). */
    val nowPace: Double,
    val usualPace: Double,
    val nowPitchRangeSt: Double,
    val usualPitchRangeSt: Double,
) {
    companion object {
        /** Null when nothing can be said (fewer than [CoachSpec.Baseline.minTalks] talks, or no measure now). */
        fun of(rate: Double, pitchRangeSt: Double, baseline: SpeakerBaseline?, spec: CoachSpec = CoachSpec.v1): AgainstUsual? {
            if (baseline == null) return null
            val b = spec.baseline
            val pace = if (rate.isFinite() && rate > 0 && baseline.pace.isFinite()) {
                when {
                    rate > baseline.pace * (1 + b.paceShare) -> Versus.Above
                    rate < baseline.pace * (1 - b.paceShare) -> Versus.Below
                    else -> Versus.Usual
                }
            } else {
                null
            }
            val range = if (pitchRangeSt.isFinite() && pitchRangeSt > 0 && baseline.pitchRangeSt.isFinite()) {
                when {
                    pitchRangeSt > baseline.pitchRangeSt + b.pitchRangeSt -> Versus.Above
                    pitchRangeSt < baseline.pitchRangeSt - b.pitchRangeSt -> Versus.Below
                    else -> Versus.Usual
                }
            } else {
                null
            }
            if (pace == null && range == null) return null
            return AgainstUsual(pace, range, rate, baseline.pace, pitchRangeSt, baseline.pitchRangeSt)
        }
    }
}

/** The coach's sentences about his habit and his pauses. Plain, no verdict, no feeling named. */
object UsualText {
    fun pace(v: Versus): String = when (v) {
        Versus.Above -> "Ai vorbit mai repede decât de obicei."
        Versus.Below -> "Ai vorbit mai rar decât de obicei."
        Versus.Usual -> "Ai vorbit în ritmul tău obișnuit."
    }

    fun pitchRange(v: Versus): String = when (v) {
        Versus.Above -> "Tonul a urcat și a coborât mai mult decât de obicei."
        Versus.Below -> "Tonul a urcat și a coborât mai puțin decât de obicei."
        Versus.Usual -> "Tonul a urcat și a coborât ca de obicei."
    }

    /** „5,8 silabe pe secundă; de obicei 5,0. Reperul general: 3,3–6,6.” */
    fun paceDetail(now: Double, usual: Double, spec: CoachSpec = CoachSpec.v1): String =
        "${SpeakingText.decimal(now)} silabe pe secundă; de obicei ${SpeakingText.decimal(usual)}. " +
            "Reperul general: ${SpeakingText.decimal(spec.pace.bandLow)}–${SpeakingText.decimal(spec.pace.bandHigh)}."

    /** „Pe 6 semitonuri; de obicei pe 8.” */
    fun pitchRangeDetail(now: Double, usual: Double): String =
        "Pe ${SpeakingText.plural(now.roundToInt(), "semiton", "semitonuri")}; de obicei pe ${usual.roundToInt()}."

    /** One line on the pauses between ideas against the long ones; null without audio. */
    fun pauses(p: PauseCounts?): String? {
        if (p == null) return null
        return when {
            p.long == 0 && p.medium >= 3 -> "Pauzele dintre idei au fost la locul lor."
            p.long == 0 -> "Ai făcut puține pauze. Respiră după fiecare idee."
            p.medium >= 2 * p.long -> "Multe pauze între idei, puține lungi."
            else -> "Multe pauze lungi. Încearcă pauze de o clipă, după idei."
        }
    }

    /** The three kinds of pause with their limits, for the counts under the strip: („scurte”, „sub 0,2 s”)… */
    fun pauseKinds(spec: CoachSpec = CoachSpec.v1): List<Pair<String, String>> {
        val medium = number(spec.pauses.mediumFromSeconds)
        val long = number(spec.pauses.longFromSeconds)
        return listOf("scurte" to "sub $medium s", "medii" to "$medium–$long s", "lungi" to "peste $long s")
    }

    private fun number(x: Double): String =
        if (kotlin.math.abs(x - x.roundToInt()) < 1e-9) "${x.roundToInt()}" else SpeakingText.decimal(x)

    /** „o dată”, „de 3 ori”, „de 20 de ori”. */
    fun times(n: Int): String = when {
        n == 1 -> "o dată"
        n % 100 == 0 || n % 100 >= 20 -> "de $n de ori"
        else -> "de $n ori"
    }

    /** „0:12, 0:40 și 1:05”. */
    fun at(times: List<Double>): String {
        val labels = times.map { clock(it) }
        return if (labels.size <= 1) labels.joinToString() else labels.dropLast(1).joinToString(", ") + " și " + labels.last()
    }

    fun clock(seconds: Double): String {
        val s = seconds.toInt().coerceAtLeast(0)
        return "${s / 60}:${"%02d".format(s % 60)}"
    }
}

/**
 * The strip of the report: the talk's length, with where the fillers were (the „ăăă” the sound detector heard, and
 * the filler words a recogniser wrote down) and where the long pauses were. Pure.
 */
object FluencyStrip {
    /** The fillers and the long pauses of the report, in time order. */
    fun marks(report: CoachReport): List<Moment> = report.moments.filter { it.kind in KINDS }

    val KINDS = setOf(MomentKind.Filler, MomentKind.LongPause)

    /** What a mark is called in the legend and the spoken summary: „ăăă”, „deci”, pauză lungă. */
    fun label(m: Moment): String = when {
        m.kind == MomentKind.Filler && m.word != null -> "„${m.word}”"
        else -> m.kind.label
    }

    /** The marks grouped by what they are, in the order the legend shows them: sounds, words, pauses. */
    fun groups(report: CoachReport): List<Pair<String, List<Moment>>> {
        val marks = marks(report)
        val sounds = marks.filter { it.kind == MomentKind.Filler && it.word == null }
        val words = marks.filter { it.kind == MomentKind.Filler && it.word != null }.groupBy { it.word!!.lowercase() }
            .entries.sortedByDescending { it.value.size }
        val pauses = marks.filter { it.kind == MomentKind.LongPause }
        val out = ArrayList<Pair<String, List<Moment>>>()
        if (sounds.isNotEmpty()) out += MomentKind.Filler.label to sounds
        for ((word, list) in words) out += "„$word”" to list
        if (pauses.isNotEmpty()) out += MomentKind.LongPause.label to pauses
        return out
    }

    /** For the screen reader: the strip in words. „«ăăă» de 2 ori: la 0:12 și 0:40. 1 pauză lungă: la 0:20.” */
    fun describe(report: CoachReport): String {
        val groups = groups(report)
        if (groups.isEmpty()) return "Fără „ăăă” și fără pauze lungi."
        return groups.joinToString(" ") { (label, list) ->
            val count = if (list.first().kind == MomentKind.LongPause) {
                SpeakingText.plural(list.size, "pauză lungă", "pauze lungi")
            } else {
                "$label ${UsualText.times(list.size)}"
            }
            "$count: la ${UsualText.at(list.map { it.start })}."
        }
    }
}
