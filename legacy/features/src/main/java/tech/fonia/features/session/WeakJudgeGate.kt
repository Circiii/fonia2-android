package tech.fonia.features.session

import tech.fonia.domain.Progression
import tech.fonia.domain.Rung
import tech.fonia.domain.SoundID
import tech.fonia.features.app.at
import tech.fonia.features.app.index
import tech.fonia.features.app.isChild
import tech.fonia.features.app.progressionState
import tech.fonia.store.Attempt
import tech.fonia.store.FoniaRepository
import tech.fonia.store.Profile
import tech.fonia.store.SoundProgress

/**
 * The adult's ear standing in for the phone's, on Android only, for the sounds the phone still hears poorly.
 *
 * On these sounds a child can say it right and still not be heard right, so good blocks may never come. For a child
 * below sentences, two „Sună bine” on takes at the current rung, on two different days, with no „Mai lucrăm” at that
 * rung on or after the earlier of the two, count as the good blocks would: the child moves up one rung. The lock after
 * the plain sound still waits for its adult, and sentences and retelling still wait for an adult to open them.
 */
object WeakJudgeGate {
    /**
     * Sounds under 80 % acceptance: the share of good takes the phone accepted at v29 (docs/PENTRU_STEFAN.md §4) was
     * CE 52 %, GE 64 %, J 72 %, Ț 78 %. Update when EVAL_v2 changes.
     */
    val sounds: Set<SoundID> = setOf(SoundID.Ce, SoundID.Ge, SoundID.J, SoundID.Ts)

    /** Two „Sună bine” on two days at [rung], with no „Mai lucrăm” there on or after the earlier one. */
    fun passes(marks: List<AdultMarks.Mark>, rung: Rung): Boolean {
        val onRung = marks.filter { it.rung == rung }
        val keepWorking = onRung.filter { it.outcome == Attempt.Outcome.NotYet }.maxOfOrNull { it.day }
        val goodDays = onRung
            .filter { it.outcome == Attempt.Outcome.Good && (keepWorking == null || it.day.isAfter(keepWorking)) }
            .map { it.day }
            .toSet()
        return goodDays.size >= 2
    }

    /** Where the adult's marks move a child on [sound], or null when they don't. */
    fun nextRung(profile: Profile, sound: SoundID, progress: SoundProgress?, marks: List<AdultMarks.Mark>): Rung? {
        if (!profile.isChild || sound !in sounds) return null
        val state = profile.progressionState(sound, progress)
        if (state.rung >= Rung.Sentences) return null
        val next = state.rung.next ?: return null
        if (Progression.isLocked(next, state)) return null
        return if (passes(marks, state.rung)) next else null
    }

    /** Moves each weak sound the adult's marks have passed up one rung. Returns the sounds that moved. */
    suspend fun apply(repository: FoniaRepository, profile: Profile): List<SoundID> {
        if (!profile.isChild) return emptyList()
        val moved = mutableListOf<SoundID>()
        for (progress in repository.soundProgress(profile.id)) {
            val sound = SoundID.fromRawValue(progress.sound) ?: continue
            if (sound !in sounds) continue
            val marks = AdultMarks.load(repository, profile.id, progress, Rung.at(progress.rung))
            val next = nextRung(profile, sound, progress, marks) ?: continue
            repository.saveSoundProgress(progress.copy(rung = next.index))
            moved += sound
        }
        return moved
    }
}
