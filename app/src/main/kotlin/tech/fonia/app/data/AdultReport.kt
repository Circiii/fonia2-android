package tech.fonia.app.data

import tech.fonia.model.ErrorKind
import tech.fonia.model.SoundId
import java.time.LocalDate

/** A kind the phone suspected "în probă", with what it heard and how often. */
data class TrialObservation(val kind: ErrorKind, val heardAs: SoundId?, val times: Int)

/** ADULT-HOME „Cum merge”, per sound. Numbers only, no percentages (chapter 01 §9, §11). */
data class SoundReport(
    val sound: SoundId,
    val today: Int,
    val week: Int,
    /** Judged takes this week (retakes left out). */
    val judgedWeek: Int,
    /** The phone said Correct. */
    val correctWeek: Int,
    /** The phone abstained (Unsure) and asked the adult. */
    val abstainedWeek: Int,
    /** The adult answered with the thumb. */
    val adultAnsweredWeek: Int,
    val trial: List<TrialObservation>,
    val confirmedGood: Int,
    val calibrated: Boolean,
)

data class AdultReport(
    val daysThisWeek: Int,
    val attemptsThisWeek: Int,
    val sounds: List<SoundReport>,
) {
    companion object {
        /** The week = the last 7 local days, today included. */
        fun build(attempts: List<AttemptView>, book: CalibrationBook, today: LocalDate): AdultReport {
            val todayKey = today.toString()
            val weekStart = today.minusDays(6).toString()
            val week = attempts.filter { it.localDay >= weekStart }
            val sounds = SoundId.entries.mapNotNull { s ->
                val all = attempts.filter { it.sound == s }
                if (all.isEmpty()) return@mapNotNull null
                val w = week.filter { it.sound == s }
                val judged = w.filter { it.judged }
                val trial = judged.mapNotNull { a -> a.trialKind?.let { it to a.trialHeardAs } }
                    .groupingBy { it }.eachCount()
                    .map { (k, n) -> TrialObservation(k.first, k.second, n) }
                    .sortedByDescending { it.times }
                SoundReport(
                    sound = s,
                    today = all.count { it.localDay == todayKey },
                    week = w.size,
                    judgedWeek = judged.size,
                    correctWeek = judged.count { it.verdict == "correct" },
                    abstainedWeek = judged.count { it.verdict == "unsure" },
                    adultAnsweredWeek = judged.count { it.adultGood != null },
                    trial = trial,
                    confirmedGood = book.forSound(s).confirmedGood,
                    calibrated = book.calibrated(s),
                )
            }
            return AdultReport(week.map { it.localDay }.distinct().size, week.size, sounds)
        }
    }
}
