package tech.fonia.features.session

import tech.fonia.domain.Rung
import tech.fonia.features.app.at
import tech.fonia.features.app.index
import tech.fonia.store.Attempt
import tech.fonia.store.FoniaRepository
import tech.fonia.store.SessionRecord
import tech.fonia.store.SoundProgress
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * What an adult said about single takes of a sound, read back for its progression: „Sună bine” is a take whose
 * [Attempt.adultOutcome] is good, „Mai lucrăm” one whose adult outcome is „not yet”. The weekly page stores them
 * there, and so does an adult who rates a take during the lesson.
 *
 * A take keeps no rung of its own. It gets the rung of the target blocks its session saved for the sound: a session
 * runs from its start to the next session's start, and every target block in it practised the session's rung (in the
 * adaptive pilot, where the rung can change between blocks, the block that ended at or after the take). A take whose
 * session saved no block (a review of an earlier rung, a session left before its first block) gets no rung and is
 * left out.
 */
object AdultMarks {
    data class Mark(val rung: Rung, val day: LocalDate, val outcome: Attempt.Outcome)

    /** The adult's „Sună bine” and „Mai lucrăm” on takes of [progress]'s sound, each with the rung it was said on. */
    fun of(attempts: List<Attempt>, sessions: List<SessionRecord>, progress: SoundProgress?): List<Mark> {
        if (progress == null || progress.blockHistory.isEmpty()) return emptyList()
        val blocks = progress.blockHistory.sortedBy { it.endedAt }
        val ordered = sessions.sortedBy { it.startedAt }
        val windows = ordered.mapIndexed { i, session -> session.id to (session.startedAt to ordered.getOrNull(i + 1)?.startedAt) }.toMap()
        return attempts.mapNotNull { take ->
            val outcome = take.adultOutcome
            if (take.sound != progress.sound || (outcome != Attempt.Outcome.Good && outcome != Attempt.Outcome.NotYet)) return@mapNotNull null
            val (start, end) = windows[take.sessionId] ?: return@mapNotNull null
            val inSession = blocks.filter { it.endedAt >= start && (end == null || it.endedAt < end) }
            val block = inSession.firstOrNull { it.endedAt >= take.ts } ?: inSession.lastOrNull() ?: return@mapNotNull null
            val day = runCatching { LocalDate.parse(take.localDay) }.getOrNull() ?: return@mapNotNull null
            Mark(Rung.at(block.rung), day, outcome)
        }
    }

    /**
     * The latest day an adult said „Mai lucrăm” on a take at [rung]: good blocks there that ended on or before it no
     * longer count towards moving on. Never a step down.
     */
    fun notBefore(marks: List<Mark>, rung: Rung): LocalDate? =
        marks.filter { it.rung == rung && it.outcome == Attempt.Outcome.NotYet }.maxOfOrNull { it.day }

    /** The marks on takes at [rung], read from the store. Only the days the sound was practised there are read. */
    suspend fun load(repository: FoniaRepository, profileId: UUID, progress: SoundProgress?, rung: Rung): List<Mark> {
        val onRung = progress?.blockHistory?.filter { it.rung == rung.index } ?: return emptyList()
        if (onRung.isEmpty()) return emptyList()
        // A day either side: a take's day is where the phone was when it was said.
        val zone = ZoneId.systemDefault()
        val from = onRung.minOf { it.endedAt }.atZone(zone).toLocalDate().minusDays(1)
        val through = onRung.maxOf { it.endedAt }.atZone(zone).toLocalDate().plusDays(1)
        val attempts = repository.attempts(profileId, from.toString(), through.toString())
        if (attempts.none { it.adultOutcome == Attempt.Outcome.Good || it.adultOutcome == Attempt.Outcome.NotYet }) return emptyList()
        return of(attempts, repository.sessions(profileId), progress).filter { it.rung == rung }
    }
}
