package tech.fonia.domain

/** Where one learner stands on one sound. Swift's `var` fields become `val`s; `Progression` returns updated copies. */
data class ProgressionState(
    val rung: Rung = Rung.Isolated,
    /** An adult or logoped heard the isolated sound and said it is good. */
    val adultConfirmed: Boolean = false,
    /** The lock after the isolated sound. On by default. */
    val lockEnabled: Boolean = true,
    /** The parent opened everything, which overrides every lock. */
    val openedByParent: Boolean = false,
    /** Correct attempts so far on the isolated rung. */
    val establishCorrect: Int = 0,
    /** Blocks under 3 of 6 on the isolated rung. */
    val weakEstablishBlocks: Int = 0,
)

sealed interface ProgressionStep {
    data object Stay : ProgressionStep

    data class Advance(val to: Rung) : ProgressionStep

    /** Move down without showing anything to the learner. */
    data class Drop(val to: Rung) : ProgressionStep

    /** Ready to move on, once an adult or logoped says it is good. */
    data class NeedsAdult(val next: Rung) : ProgressionStep
}

/** Rules for moving along the ladder. Starting values, to be checked with a logoped. */
object Progression {
    val blockSize = 6
    val advanceAt = 5
    val dropBelow = 3

    /** Correct attempts on the isolated rung before an adult is asked to listen. */
    val establishTarget = 12

    /** Weak blocks on the isolated rung, about three sessions' worth, before suggesting a logoped. */
    val weakBlocksBeforeLogoped = 9

    /** Swift's `(state:, step:)` tuple. Destructures as `val (state, step) = …`. */
    data class BlockOutcome(val state: ProgressionState, val step: ProgressionStep)

    fun isLocked(rung: Rung, state: ProgressionState): Boolean =
        rung > Rung.Isolated && state.lockEnabled && !state.openedByParent && !state.adultConfirmed

    /** Applies one finished block of the current rung. A block cut short decides nothing. */
    fun afterBlock(correct: Int, attempted: Int = blockSize, state: ProgressionState): BlockOutcome {
        if (attempted != blockSize) return BlockOutcome(state, ProgressionStep.Stay)
        var updated = state
        if (state.rung == Rung.Isolated) {
            updated = updated.copy(establishCorrect = updated.establishCorrect + correct)
            if (correct < dropBelow) updated = updated.copy(weakEstablishBlocks = updated.weakEstablishBlocks + 1)
        }

        if (correct < dropBelow) {
            val lower = state.rung.previous ?: return BlockOutcome(updated, ProgressionStep.Stay)
            updated = updated.copy(rung = lower)
            return BlockOutcome(updated, ProgressionStep.Drop(to = lower))
        }

        val next = state.rung.next ?: return BlockOutcome(updated, ProgressionStep.Stay)

        if (state.rung == Rung.Isolated && isLocked(next, state)) {
            return BlockOutcome(updated, if (updated.establishCorrect >= establishTarget) ProgressionStep.NeedsAdult(next = next) else ProgressionStep.Stay)
        }
        if (correct < advanceAt) return BlockOutcome(updated, ProgressionStep.Stay)
        if (adultDecides(leaving = state.rung) && !state.openedByParent) {
            return BlockOutcome(updated, ProgressionStep.NeedsAdult(next = next))
        }
        updated = updated.copy(rung = next)
        return BlockOutcome(updated, ProgressionStep.Advance(to = next))
    }

    /** The adult or logoped said it is good: opens the lock and moves up one rung. */
    fun confirmedByAdult(state: ProgressionState): ProgressionState {
        var updated = state.copy(adultConfirmed = true)
        state.rung.next?.let { updated = updated.copy(rung = it) }
        return updated
    }

    fun openingEverything(state: ProgressionState): ProgressionState = state.copy(openedByParent = true)

    /** True when the isolated sound keeps not coming: more drills won't help, so suggest a logoped. */
    fun suggestsLogoped(state: ProgressionState): Boolean =
        state.rung == Rung.Isolated && state.weakEstablishBlocks >= weakBlocksBeforeLogoped

    /** Past sentences the phone stays quiet, so only a person can say it's time to move on. */
    private fun adultDecides(leaving: Rung): Boolean = leaving == Rung.Sentences || leaving == Rung.Retell
}
