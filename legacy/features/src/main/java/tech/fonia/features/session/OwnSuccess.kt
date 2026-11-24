package tech.fonia.features.session

import tech.fonia.domain.Progression
import tech.fonia.domain.ProgressionState
import tech.fonia.domain.ProgressionStep
import tech.fonia.domain.Rung

/**
 * No adult in the loop (v2-flow, owner 2026-11-24): what used to wait for an adult opens by the learner's own success.
 *
 * - The lessons past the plain sound open once the plain sound was heard right [TARGET] times (good takes on the
 *   isolated rung, counted from the stored blocks), shown as [TARGET] dots.
 * - Past sentences, where the phone used to wait for an adult, a good block moves on by itself.
 *
 * Everything else is [Progression] unchanged: 5 of 6 to move up, under 3 of 6 to step down.
 */
object OwnSuccess {
    const val TARGET = 5

    fun opened(establishCorrect: Int): Boolean = establishCorrect >= TARGET

    /** The dots: how many of [TARGET] are done. */
    fun dots(establishCorrect: Int): Int = establishCorrect.coerceIn(0, TARGET)

    /** [Progression.afterBlock] where the learner's own good takes stand in for the adult's word. */
    fun afterBlock(correct: Int, attempted: Int, state: ProgressionState): Progression.BlockOutcome {
        var before = state
        if (before.rung == Rung.Isolated && opened(before.establishCorrect + correct)) before = before.copy(adultConfirmed = true)
        val outcome = Progression.afterBlock(correct = correct, attempted = attempted, state = before)
        val step = outcome.step
        if (step is ProgressionStep.NeedsAdult) {
            return Progression.BlockOutcome(outcome.state.copy(rung = step.next), ProgressionStep.Advance(step.next))
        }
        return outcome
    }

    /** A step from elsewhere (the adaptive pilot) that would wait for an adult moves on instead. */
    fun withoutAdult(step: ProgressionStep): ProgressionStep =
        if (step is ProgressionStep.NeedsAdult) ProgressionStep.Advance(step.next) else step
}
