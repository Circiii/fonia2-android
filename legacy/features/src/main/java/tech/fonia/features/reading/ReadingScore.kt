package tech.fonia.features.reading

import tech.fonia.domain.Plural
import tech.fonia.features.session.romanianDecimal
import kotlin.math.roundToInt

// The numbers of a one-minute probe (A §7.2 in the research hub): WCPM is the words read correctly in the window,
// scaled to a minute when the child finishes sooner; accuracy is correct over read; syllables per second count only
// the syllables of the words read correctly. The window starts at the first word and closes at 60 s.

/** The window of one probe. */
const val PROBE_SECONDS = 60.0

/** Under this, a probe was stopped by mistake: there is too little to scale to a minute. */
const val PROBE_MINIMUM_SECONDS = 5.0

/** What one probe measured. [errors] are the words the adult marked among the [wordsRead]. */
data class ProbeScore(
    val wordsRead: Int,
    val errors: Int,
    val seconds: Double,
    val syllablesRead: Int,
    val syllablesCorrect: Int,
) {
    val correct: Int get() = wordsRead - errors

    /** Words correct per minute: the ones in the window, over the seconds read, times 60. */
    val wcpm: Double get() = if (seconds <= 0.0) 0.0 else correct * 60.0 / seconds

    /** Correct over read, 0…1. */
    val accuracy: Double get() = if (wordsRead == 0) 0.0 else correct.toDouble() / wordsRead

    val syllablesPerSecond: Double get() = if (seconds <= 0.0) 0.0 else syllablesCorrect / seconds

    /** WCPM as the page shows it, and as it is compared with the published band: a whole number, half up. */
    val wcpmShown: Int get() = wholeWords(wcpm)

    val accuracyPercent: Int get() = (accuracy * 100).roundToInt()

    /** Enough time and at least one word read. */
    val isUsable: Boolean get() = wordsRead > 0 && seconds >= PROBE_MINIMUM_SECONDS

    companion object {
        /**
         * The score of [probe] when the child got as far as word [lastRead] (0-based, included) in [seconds]. Marks
         * after the last word read don't count: the child never reached those words.
         */
        fun of(probe: ReadingProbe, lastRead: Int, errorIndices: Collection<Int>, seconds: Double): ProbeScore {
            val read = (lastRead + 1).coerceIn(0, probe.words.size)
            val wrong = errorIndices.filter { it in 0 until read }.toSortedSet()
            val syllablesRead = probe.words.take(read).sumOf { it.syllableCount }
            val syllablesWrong = wrong.sumOf { probe.words[it].syllableCount }
            return ProbeScore(
                wordsRead = read,
                errors = wrong.size,
                seconds = seconds.coerceIn(0.0, PROBE_SECONDS),
                syllablesRead = syllablesRead,
                syllablesCorrect = syllablesRead - syllablesWrong,
            )
        }

        /** Half up, as people round a count. */
        fun wholeWords(value: Double): Int = if (value <= 0.0) 0 else (value + 0.5).toInt()
    }
}

/** Where a result stands against the published band. */
enum class BandPosition(val word: String) {
    Below("sub"),
    Within("în"),
    Above("peste"),
}

/**
 * The PEAFC norms for the end of grade I (Bodea Hațegan et al. 2017, N = 1.977, one minute per list), as research
 * report A §2.5 transcribes them. The band is the middle column of the article's table 5; its bounds are quartiles,
 * and the source isn't clear about ≤ or <, so a value on a bound counts as inside the band. Fonia's lists are not
 * PEAFC's, so this is only ever a band, shown with its source, after the child's own curve, and the article's
 * column names (which speak of risk) never reach a screen (fonia-android-plan/03 §2.1).
 */
data class NormBand(val kind: ProbeKind, val low: Int, val high: Int, val mean: Double, val sd: Double)

object PeafcNorms {
    val bands: Map<ProbeKind, NormBand> = listOf(
        NormBand(ProbeKind.Mono, low = 26, high = 40, mean = 41.21, sd = 19.66),
        NormBand(ProbeKind.Bi, low = 25, high = 37, mean = 37.66, sd = 18.22),
        NormBand(ProbeKind.Tri, low = 16, high = 25, mean = 25.73, sd = 13.01),
        NormBand(ProbeKind.Text, low = 23, high = 36, mean = 38.74, sd = 21.52),
    ).associateBy { it.kind }

    /** Who measured the band, for the line under it. */
    const val SOURCE_SHORT = "Bodea Hațegan, Talaș, Costea-Bărluțiu și Trifu (2017), 1.977 de elevi la final de clasa I"

    const val SOURCE = "Bodea Hațegan C., Talaș D., Costea-Bărluțiu C., Trifu R. (2017). Reading fluency: normative data " +
        "for the Romanian-speaking population. Social Welfare: Interdisciplinary Approach 7(2)."

    fun band(kind: ProbeKind): NormBand = bands.getValue(kind)

    /** Compares the number the page shows, so the words never disagree with the figure next to them. */
    fun position(kind: ProbeKind, wcpm: Double): BandPosition {
        val band = band(kind)
        val shown = ProbeScore.wholeWords(wcpm)
        return when {
            shown < band.low -> BandPosition.Below
            shown > band.high -> BandPosition.Above
            else -> BandPosition.Within
        }
    }

    /** "în banda raportată pentru final de clasa I". */
    fun phrase(position: BandPosition): String = "${position.word} banda raportată pentru final de clasa I"

    /** "26–40 de cuvinte corecte pe minut, la cuvintele de o silabă". */
    fun range(kind: ProbeKind): String {
        val band = band(kind)
        return "${band.low}–${band.high} ${Plural.form(band.high, Plural.Noun.Cuvant)} corecte pe minut, la ${kind.inSentence}"
    }
}

/** "38 de cuvinte corecte pe minut", "1 cuvânt corect pe minut". */
fun wordsPerMinute(count: Int): String =
    if (count == 1) "1 cuvânt corect pe minut" else "${Plural.format(count, Plural.Noun.Cuvant)} corecte pe minut"

/** "1,4": one decimal with a comma, as the rest of the app writes rates. */
fun oneDecimal(value: Double): String = romanianDecimal(value)

/** "1:00", "0:42". */
fun clockText(seconds: Double): String {
    val whole = seconds.coerceAtLeast(0.0).toInt()
    return "${whole / 60}:${(whole % 60).toString().padStart(2, '0')}"
}
