package tech.fonia.features.pilot

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * The first five days of a sound (03 §1.2 P6: "intensiv = 5 zile la rând imediat după deblocarea sunetului"). A sound
 * starts on its first day with a counted production; its five days are that day and the four after it.
 */
data class IntensiveWeek(
    val start: LocalDate,
    /** Days among the five with at least one counted production, up to today. */
    val daysWithPractice: Int,
    /** True while the five days are still running. */
    val isOpen: Boolean,
) {
    val end: LocalDate get() = start.plusDays((DAYS - 1).toLong())

    /** All five days had practice: the intensive start P6 asks for. */
    val isComplete: Boolean get() = daysWithPractice == DAYS

    companion object {
        const val DAYS = 5
    }
}

/** Counted productions of one sound in one week (Monday first, as the week strip). */
data class WeekDose(val monday: LocalDate, val attempts: Int, val days: Int)

/** The dose of one sound (03 §1.2 P6, §2.5 "Doza se numără și se arată"). */
data class SoundDose(
    /** `SoundID` raw value. */
    val sound: String,
    /** Counted productions per day, for the days that have some. */
    val byDay: Map<LocalDate, Int>,
    val intensiveWeek: IntensiveWeek?,
) {
    fun on(day: LocalDate): Int = byDay[day] ?: 0

    val total: Int get() = byDay.values.sum()

    fun week(monday: LocalDate): WeekDose {
        val days = (0L until 7L).map { monday.plusDays(it) }
        return WeekDose(monday, days.sumOf { on(it) }, days.count { on(it) > 0 })
    }
}

/** What the grown-ups' dose counter shows for one profile. */
data class DoseSummary(val today: LocalDate, val sounds: List<SoundDose>) {
    val thisMonday: LocalDate get() = PilotMath.monday(today)

    /** The current week, Monday to Sunday. */
    val week: List<LocalDate> get() = (0L until 7L).map { thisMonday.plusDays(it) }

    companion object {
        val empty = DoseSummary(LocalDate.MIN, emptyList())
    }
}

/** The retention markers of 03 §4.1: the last active day, and whether the profile was active at day 7, 14, 30, 90. */
data class RetentionMarkers(
    val lastActive: LocalDate?,
    /** Null until the day is reached. */
    val activeAt: Map<Int, Boolean?>,
) {
    companion object {
        val DAYS = listOf(7, 14, 30, 90)

        /** How "active at day N" is read, written into the export so the pilot can fix it before it starts (§4.1). */
        const val DEFINITION = "active at day N: practice (a session or an attempt) on at least one of days N-6..N after setup (day 0)"
    }
}

/** Arithmetic on the counters, pure, so the JVM tests hold it. */
object PilotMath {
    fun monday(day: LocalDate): LocalDate = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    fun day(text: String): LocalDate? = runCatching { LocalDate.parse(text) }.getOrNull()

    /** The dose of every sound with at least one counted production, in the order of [order] then by code. */
    fun doses(counters: List<AttemptCounter>, today: LocalDate, order: List<String> = emptyList()): DoseSummary {
        val bySound = counters
            .filter { it.mode == AttemptMode.Production.code && it.sound.isNotEmpty() && it.dose > 0 }
            .groupBy { it.sound }
        val sounds = bySound.map { (sound, rows) ->
            val byDay = rows.mapNotNull { row -> day(row.day)?.let { it to row.dose } }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, doses) -> doses.sum() }
            SoundDose(sound, byDay, intensiveWeek(byDay, today))
        }
        val rank = { sound: String -> order.indexOf(sound).let { if (it < 0) Int.MAX_VALUE else it } }
        return DoseSummary(today, sounds.sortedWith(compareBy({ rank(it.sound) }, { it.sound })))
    }

    /** P6's five days, from the first day with practice. Null when the sound has none. */
    fun intensiveWeek(byDay: Map<LocalDate, Int>, today: LocalDate): IntensiveWeek? {
        val start = byDay.filterValues { it > 0 }.keys.minOrNull() ?: return null
        val window = (0L until IntensiveWeek.DAYS.toLong()).map { start.plusDays(it) }
        val practised = window.count { !it.isAfter(today) && (byDay[it] ?: 0) > 0 }
        return IntensiveWeek(start, practised, isOpen = !today.isAfter(window.last()))
    }

    /**
     * Retention against the setup day (day 0): active at day N when there was practice on one of days N−6..N. A day
     * not reached yet stays null. [RetentionMarkers.DEFINITION] says the same in the export.
     */
    fun retention(setup: LocalDate, activeDays: Collection<LocalDate>, today: LocalDate): RetentionMarkers {
        val markers = RetentionMarkers.DAYS.associateWith { n ->
            val target = setup.plusDays(n.toLong())
            if (today.isBefore(target)) {
                null
            } else {
                activeDays.any { day -> ChronoUnit.DAYS.between(day, target) in 0L..6L }
            }
        }
        return RetentionMarkers(activeDays.maxOrNull(), markers)
    }

    /** Days with any practice: a session, or an attempt of any kind. */
    fun activeDays(counters: List<AttemptCounter>, sessions: List<SessionDay>): Set<LocalDate> =
        (counters.filter { it.attempts > 0 }.map { it.day } + sessions.filter { it.sessions > 0 }.map { it.day }).mapNotNull(::day).toSet()

    /**
     * The three-state reading of a verdict the child got, to compare it with ZIPA's: good is correct, almost and not
     * yet are "try again", and unclear or not judged are an abstention (null).
     */
    fun shownAsVerdict(shown: String): String? = when (shown) {
        "good" -> "correct"
        "almost", "notYet" -> "tryAgain"
        else -> null
    }

    /** ZIPA's verdict as a decision, or null for an abstention. */
    fun zipaAsVerdict(verdict: String): String? = when (verdict) {
        "correct", "tryAgain" -> verdict
        else -> null
    }
}
