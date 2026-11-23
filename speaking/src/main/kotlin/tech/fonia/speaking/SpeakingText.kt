package tech.fonia.speaking

import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Romanian lines for the speaking screens: numbers phrased neutrally (no grade, no "good/bad", no emotion), plurals
 * by the rule of plan 01 §11 ("de" when the last two digits are 00 or ≥ 20, never at zero), comma-below Ș/Ț.
 * (`Plural` lives in `:core:domain`, which this engine may not depend on; the rule is repeated here.)
 */
object SpeakingText {
    fun plural(n: Int, one: String, many: String): String = when {
        n == 1 -> "1 $one"
        n != 0 && (n % 100 == 0 || n % 100 >= 20) -> "$n de $many"
        else -> "$n $many"
    }

    /** "6,2" (one decimal, comma). */
    fun decimal(x: Double): String {
        val tenths = (x * 10).roundToLong()
        return "${tenths / 10},${kotlin.math.abs(tenths % 10)}"
    }

    /** "1 secundă", "58 de secunde", "6,2 secunde". */
    fun seconds(x: Double): String {
        val tenths = (x * 10).roundToLong()
        return if (tenths % 10 == 0L) plural((tenths / 10).toInt(), "secundă", "secunde") else "${decimal(x)} secunde"
    }

    fun fillers(n: Int) = plural(n, "umplutură", "umpluturi")
    fun falseStarts(n: Int) = plural(n, "pornire falsă", "porniri false")
    fun repetitions(n: Int) = plural(n, "repetare", "repetări")
    fun pauses(n: Int) = plural(n, "pauză", "pauze")

    /** "3 umpluturi · 1 pornire falsă · 1 pauză de 2,1 s" for the spans in [cuts]. */
    fun headline(a: SpeakingAnalysis, cuts: Set<Int>): String {
        val chosen = a.spans.filter { it.id in cuts }
        val parts = ArrayList<String>()
        val f = chosen.count { it.isFiller || it.kind == SpanKind.LENGTHENING }
        if (f > 0) parts.add(fillers(f))
        val fs = chosen.count { it.kind == SpanKind.FALSE_START }
        if (fs > 0) parts.add(falseStarts(fs))
        val rp = chosen.count { it.kind == SpanKind.REPETITION }
        if (rp > 0) parts.add(repetitions(rp))
        val long = a.pauses.filter { it.long && it.start > 0.05 && it.end < a.durationSeconds - 0.05 }
        when (long.size) {
            0 -> {}
            1 -> parts.add("1 pauză de ${decimal(long[0].duration)} s")
            else -> parts.add(plural(long.size, "pauză lungă", "pauze lungi"))
        }
        return if (parts.isEmpty()) "Nicio umplutură" else parts.joinToString(" · ")
    }

    /** „Ai scos 4 umpluturi. 6,2 secunde mai scurt.” */
    fun cleaned(stats: CleanStats): String {
        val removed = stats.fillersRemoved + stats.falseStartsRemoved + stats.repetitionsRemoved
        val shorter = "${seconds(stats.secondsRemoved)} mai scurt."
        return when {
            removed > 0 && stats.secondsRemoved >= 0.05 -> "Ai scos ${fillers(removed)}. $shorter"
            stats.secondsRemoved >= 0.05 -> "Pauzele lungi s-au strâns. $shorter"
            else -> "Nimic de scos: așa a sunat."
        }
    }

    /** NO-UM step 6: „Umpluturi: 3 → 0”; unchanged: „3 → 3. Încearcă o pauză în locul lui «ăă».” */
    fun comparison(before: Int, after: Int): String =
        if (after < before || before == 0) "Umpluturi: $before → $after"
        else "Umpluturi: $before → $after. Încearcă o pauză în locul lui «ăă»."

    /** For the screen reader: „58 de secunde, 6 pauze”. */
    fun spoken(s: ProsodySummary): String = "${seconds(s.durationSeconds.roundToInt().toDouble())}, ${pauses(s.pauseCount)}"

    /** The measured numbers, one per line, no judgement. */
    fun lines(s: ProsodySummary): List<String> {
        val out = ArrayList<String>()
        out.add(spoken(s))
        val wpm = s.wordsPerMinute
        if (wpm != null) out.add(plural(wpm.roundToInt(), "cuvânt", "cuvinte") + " pe minut")
        else if (s.syllablesPerSecond > 0) out.add("${decimal(s.syllablesPerSecond)} silabe pe secundă")
        if (s.pauseCount > 0) out.add("Pauze: ${seconds(s.pauseSeconds)} din ${s.durationSeconds.roundToInt()}")
        if (s.pitchRangeSt > 0.5) out.add("Tonul s-a mișcat pe " + plural(s.pitchRangeSt.roundToInt(), "semiton", "semitonuri"))
        out.add(if (s.fillerCount == 0) "Nicio umplutură" else "${fillers(s.fillerCount)}, ${decimal(s.fillersPerMinute)} pe minut")
        return out
    }

    /** MINUTE: one measured thing that went well (a fact, not praise). */
    fun worked(s: ProsodySummary): String = when {
        s.pauseCount > 0 && s.longPauseCount == 0 -> "Ai făcut ${pauses(s.pauseCount)}, niciuna prea lungă."
        s.fillerCount <= 1 && s.durationSeconds >= 10 ->
            (if (s.fillerCount == 0) "Nicio umplutură" else "O singură umplutură") + " în ${seconds(s.durationSeconds.roundToInt().toDouble())}."
        s.pitchRangeSt >= 6 -> "Tonul a urcat și a coborât pe ${plural(s.pitchRangeSt.roundToInt(), "semiton", "semitonuri")}."
        else -> "Ai vorbit ${seconds(s.durationSeconds.roundToInt().toDouble())}."
    }

    /** MINUTE: one thing to try next time. */
    fun toTry(s: ProsodySummary): String = when {
        s.fillerCount >= 2 -> "Încearcă o pauză în locul lui «ăă»."
        s.longPauseCount > 0 -> "Încearcă pauze de cel mult o secundă."
        s.durationSeconds > 15 && s.pauseCount < s.durationSeconds / 15 -> "Încearcă o pauză după fiecare idee."
        s.pitchRangeSt in 0.5..4.0 -> "Încearcă să urci tonul pe cuvântul important."
        else -> "Încă o dată, mai scurt."
    }
}
