package tech.fonia.features.session

import tech.fonia.domain.DaySpread
import tech.fonia.domain.Progression
import tech.fonia.domain.ProgressionState
import tech.fonia.domain.ProgressionStep
import tech.fonia.domain.Rung

/**
 * The learner's own success, with the lock of the plan (docs/v2/06, „Lacătul”) kept:
 *
 * - The plain sound heard right [TARGET] times (good takes on the isolated rung, counted from the stored blocks,
 *   shown as [TARGET] dots) is what makes the next step possible.
 * - The step after the isolated sound still waits for an adult who listened ("Am ascultat, e bine", in Setări),
 *   when the profile keeps the lock on (a child's default). Without it, a child could repeat a wrong sound for weeks.
 *   With the lock off, or once an adult confirmed, the dots alone move him on.
 * - Past sentences and retelling the phone stays quiet, so a good block waits for an adult (as on iOS), unless the
 *   whole path was opened.
 *
 * Everything else is [Progression] unchanged: 5 of 6 to move up, under 3 of 6 to step down.
 */
object OwnSuccess {
    const val TARGET = 5

    fun opened(establishCorrect: Int): Boolean = establishCorrect >= TARGET

    /** The dots: how many of [TARGET] are done. */
    fun dots(establishCorrect: Int): Int = establishCorrect.coerceIn(0, TARGET)

    /**
     * What TalkBack says for the dots: words, never "N din 5", since a child is shown no counts (the same words as
     * the lesson bar on iOS).
     */
    fun spokenDots(establishCorrect: Int): String = when (dots(establishCorrect)) {
        0 -> "Abia începem"
        TARGET -> "Gata"
        in 1 until (TARGET + 1) / 2 -> "Mai puțin de jumătate"
        else -> "Peste jumătate"
    }

    /** The lock is on and no adult has listened yet (nor opened the whole path). */
    fun waitsForAdult(state: ProgressionState): Boolean =
        state.lockEnabled && !state.openedByParent && !state.adultConfirmed

    /** Under a shut lesson past the plain sound: the dots still to fill, then the adult who listens. */
    fun lockLine(establishCorrect: Int): String =
        if (opened(establishCorrect)) "Un adult te ascultă, apoi se deschide." else "Se deschide când îți iese sunetul de $TARGET ori."

    /** [Progression.afterBlock] where the learner's own good takes stand in for the 12 of the plan. */
    fun afterBlock(
        correct: Int,
        attempted: Int,
        state: ProgressionState,
        daySpread: DaySpread = DaySpread.NotRequired,
    ): Progression.BlockOutcome {
        val onPlainSound = state.rung == Rung.Isolated
        val ownOpened = onPlainSound && opened(state.establishCorrect + correct)
        val locked = onPlainSound && waitsForAdult(state)
        var before = state
        if (ownOpened && !locked) before = before.copy(adultConfirmed = true)
        val outcome = Progression.afterBlock(correct = correct, attempted = attempted, state = before, daySpread = daySpread)
        val next = state.rung.next
        if (locked && ownOpened && next != null && outcome.step == ProgressionStep.Stay && attempted == Progression.blockSize) {
            return Progression.BlockOutcome(outcome.state, ProgressionStep.NeedsAdult(next))
        }
        return outcome
    }
}
