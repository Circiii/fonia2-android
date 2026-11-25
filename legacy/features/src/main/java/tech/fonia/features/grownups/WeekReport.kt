package tech.fonia.features.grownups

import tech.fonia.domain.Rung
import tech.fonia.features.app.at
import tech.fonia.store.Attempt
import tech.fonia.store.Recording
import tech.fonia.store.SessionRecord
import tech.fonia.store.SoundProgress
import tech.fonia.store.localDay
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.math.ceil

// One week of a child's practice for the adult and the logoped (iOS `WeekReport`): per sound, the days and minutes,
// where the lessons stand and what moved, and a few kept takes worth hearing. In the trial this page is modelled on
// (McKechnie 2020), the app said only "right / not yet" at home and a speech therapist listened to the home takes once
// a week; the fine advice came from the person. Nothing here leaves the phone.

/**
 * The adult's two answers to a take heard later (iOS `AdultEar`). They are written as the adult's verdict on the
 * attempt, the same field the adult fills during a lesson.
 */
enum class AdultEar {
    /** „Sună bine”. */
    SoundsGood,

    /** „Mai lucrăm”. */
    KeepWorking,
    ;

    /** The verdict to store. "Keep working" leaves an "almost" the adult gave during the lesson as it was. */
    fun outcome(replacing: Attempt.Outcome?): Attempt.Outcome = when (this) {
        SoundsGood -> Attempt.Outcome.Good
        KeepWorking -> if (of(replacing) == KeepWorking && replacing != null) replacing else Attempt.Outcome.NotYet
    }

    companion object {
        /** What an existing verdict reads as: "almost" and "not yet" both mean there is still work on it. */
        fun of(outcome: Attempt.Outcome?): AdultEar? = when (outcome) {
            Attempt.Outcome.Good -> SoundsGood
            Attempt.Outcome.Almost, Attempt.Outcome.NotYet -> KeepWorking
            Attempt.Outcome.Unclear, Attempt.Outcome.NotJudged, null -> null
        }
    }
}

data class WeekReport(
    val monday: LocalDate,
    /** Days of the week with any practice, on any track. */
    val days: Int,
    val minutes: Int,
    val sounds: List<SoundWeek>,
) {
    /** A kept take next to the attempt it belongs to. */
    data class Take(val attempt: Attempt, val recording: Recording, val why: Why = Why.Other) {
        /** Why the take is on the short list. */
        enum class Why {
            /** The week's first take on a word that was also said later in the week. */
            FirstOnWord,

            /** The week's latest take on that word. */
            LatestOnWord,

            /** The phone said "almost" or did not judge it, and no adult has said anything yet. */
            PhoneUnsure,

            /** No adult has said anything about it yet. */
            NotHeard,

            /** Fills the list when there are few takes. */
            Other,
        }

        val id: UUID get() = attempt.id
        val word: String get() = attempt.target ?: recording.label
        val ear: AdultEar? get() = AdultEar.of(attempt.adultOutcome)
    }

    /** One sound worked on in the week. */
    data class SoundWeek(
        val sound: String,
        val days: Int,
        val minutes: Int,
        val attempts: Int,
        /** The rung the week started on and the one it ended on. */
        val rungBefore: Rung,
        val rungAfter: Rung,
        /** Every take of the week that is still on the phone. */
        val kept: List<Take>,
        /** The short list to hear, oldest first. */
        val picked: List<Take>,
    ) {
        /** Kept takes no adult has answered yet. */
        val waiting: Int get() = kept.count { it.attempt.adultOutcome == null }

        /** Takes on the short list no adult has answered yet. */
        val listWaiting: Int get() = picked.count { it.attempt.adultOutcome == null }
        val moved: Boolean get() = rungAfter != rungBefore
        val changedStage: Boolean get() = StageNames.name(rungAfter.stage) != StageNames.name(rungBefore.stage)
    }

    val sunday: LocalDate get() = monday.plusDays(6)
    val isEmpty: Boolean get() = days == 0 && sounds.isEmpty()
    val waiting: Int get() = sounds.sumOf { it.waiting }

    /** Takes on the week's short lists, every sound, that no adult has answered yet. */
    val listWaiting: Int get() = sounds.sumOf { it.listWaiting }

    /**
     * The same report with one attempt's adult verdict changed. The short lists stay as they were, so a row doesn't
     * jump away under the finger.
     */
    fun marking(attemptID: UUID, outcome: Attempt.Outcome?): WeekReport {
        fun mark(take: Take): Take = if (take.id == attemptID) take.copy(attempt = take.attempt.copy(adultOutcome = outcome)) else take
        return copy(sounds = sounds.map { sound -> sound.copy(kept = sound.kept.map(::mark), picked = sound.picked.map(::mark)) })
    }

    companion object {
        /** How many takes a sound shows at most. A logoped hears five in a minute or two. */
        const val TAKES_PER_SOUND = 5

        /**
         * [sessions], [attempts] and [recordings] are the person's, at least those of the week. [order] lists sound ids
         * in the order the page shows them; sounds not in it come after, by name. [timeZone] is where block end times
         * are read as days, as the store does for sessions and attempts.
         */
        fun build(
            monday: LocalDate,
            sessions: List<SessionRecord>,
            attempts: List<Attempt>,
            recordings: List<Recording>,
            progress: List<SoundProgress>,
            order: List<String>,
            timeZone: ZoneId = ZoneId.systemDefault(),
        ): WeekReport {
            val first = monday.toString()
            val last = monday.plusDays(6).toString()
            fun inWeek(day: String) = day >= first && day <= last
            val weekSessions = sessions.filter { inWeek(it.localDay) && (it.endedAt != null || it.attempts > 0) }
            val weekAttempts = attempts.filter { inWeek(it.localDay) }.sortedBy { it.ts }
            val byID = recordings.associateBy { it.id }

            val rank = order.withIndex().associate { it.value to it.index }
            val ordered = (weekAttempts.mapNotNull { it.sound } + weekSessions.mapNotNull { it.sound })
                .toSet()
                .sortedWith(compareBy({ rank[it] ?: Int.MAX_VALUE }, { it }))

            val sounds = ordered.map { sound ->
                val tries = weekAttempts.filter { it.sound == sound }
                val ownSessions = weekSessions.filter { it.sound == sound }
                val kept = tries.mapNotNull { attempt ->
                    val recording = attempt.recordingId?.let(byID::get) ?: return@mapNotNull null
                    Take(attempt, recording)
                }
                val (before, after) = rungs(progress.firstOrNull { it.sound == sound }, first, last, timeZone)
                SoundWeek(
                    sound = sound,
                    days = (tries.map { it.localDay } + ownSessions.map { it.localDay }).toSet().size,
                    minutes = minutes(ownSessions),
                    attempts = tries.size,
                    rungBefore = before,
                    rungAfter = after,
                    kept = kept,
                    picked = pick(kept),
                )
            }

            return WeekReport(
                monday = monday,
                days = (weekSessions.map { it.localDay } + weekAttempts.map { it.localDay }).toSet().size,
                minutes = minutes(weekSessions),
                sounds = sounds,
            )
        }

        private val oldestFirst = compareBy<Take>({ it.attempt.ts }, { it.id.toString().uppercase() })

        /**
         * The short list, oldest first. In order of what an ear learns most from:
         * 1. the week's first and latest take on one word, the pair furthest apart in time;
         * 2. takes the phone called "almost" or did not judge, that no adult has answered, newest first;
         * 3. any other take no adult has answered, newest first;
         * 4. the rest, newest first, so a short week still shows what it kept.
         */
        fun pick(takes: List<Take>, limit: Int = TAKES_PER_SOUND): List<Take> {
            if (limit <= 0) return emptyList()
            val sorted = takes.sortedWith(oldestFirst)
            val newestFirst = sorted.reversed()
            val picked = mutableListOf<Take>()
            fun add(take: Take, why: Take.Why) {
                if (picked.size >= limit || picked.any { it.id == take.id }) return
                picked += take.copy(why = why)
            }

            if (limit >= 2) {
                widestPair(sorted)?.let { (first, latest) ->
                    add(first, Take.Why.FirstOnWord)
                    add(latest, Take.Why.LatestOnWord)
                }
            }
            val unsure = setOf(Attempt.Outcome.Almost, Attempt.Outcome.NotJudged)
            for (take in newestFirst) if (take.attempt.adultOutcome == null && take.attempt.machineOutcome in unsure) add(take, Take.Why.PhoneUnsure)
            for (take in newestFirst) if (take.attempt.adultOutcome == null) add(take, Take.Why.NotHeard)
            for (take in newestFirst) add(take, Take.Why.Other)
            return picked.sortedWith(oldestFirst)
        }

        /** Among words said more than once, the first and latest take of the one whose two lie furthest apart. A tie goes to the pair that ends later. */
        private fun widestPair(oldestFirst: List<Take>): Pair<Take, Take>? =
            oldestFirst.groupBy { it.word.lowercase() }.values
                .filter { it.size > 1 }
                .map { it.first() to it.last() }
                .maxWithOrNull(
                    compareBy<Pair<Take, Take>>({ Duration.between(it.first.attempt.ts, it.second.attempt.ts) }, { it.second.attempt.ts }),
                )

        /**
         * Where the lessons stood when the week began and when it ended. A block is kept with the rung it practised,
         * so the week's first block tells where it began, and the first block after the week (or the saved rung, for
         * the latest week) tells where it ended. A week without blocks shows no move.
         */
        fun rungs(progress: SoundProgress?, first: String, last: String, timeZone: ZoneId): Pair<Rung, Rung> {
            val blocks = (progress?.blockHistory ?: emptyList()).sortedBy { it.endedAt }
            fun day(block: SoundProgress.Block) = localDay(block.endedAt, timeZone)
            val after = blocks.firstOrNull { day(it) > last }?.let { Rung.at(it.rung) } ?: Rung.at(progress?.rung ?: 0)
            val before = blocks.firstOrNull { day(it) >= first && day(it) <= last }?.let { Rung.at(it.rung) } ?: after
            return before to after
        }

        /** Minutes of the finished sessions, rounded up as the PDF report does. */
        private fun minutes(sessions: List<SessionRecord>): Int {
            val seconds = sessions.sumOf { session ->
                val end = session.endedAt ?: return@sumOf 0.0
                maxOf(0.0, Duration.between(session.startedAt, end).toMillis() / 1_000.0)
            }
            return ceil(seconds / 60).toInt()
        }

        /** The Monday of the earliest week with a session, if any. */
        fun firstMonday(sessions: List<SessionRecord>): LocalDate? =
            sessions.mapNotNull { runCatching { LocalDate.parse(it.localDay) }.getOrNull() }.minOrNull()?.let(PracticeReport::monday)
    }
}
