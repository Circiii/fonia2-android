package tech.fonia.domain

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource

// Port of FoniaDomainTests/ProgressionTests.swift: 18 test functions.

@DisplayName("Progression")
class ProgressionTests {
    private fun confirmed(rung: Rung): ProgressionState = ProgressionState(rung = rung, adultConfirmed = true)

    @Test
    fun fiveOfSixMovesUp() {
        val (state, step) = Progression.afterBlock(correct = 5, state = confirmed(Rung.WordsInitial))
        assertEquals(ProgressionStep.Advance(to = Rung.WordsMedial), step)
        assertEquals(Rung.WordsMedial, state.rung)
    }

    @Test
    fun sixOfSixMovesUpToo() {
        assertEquals(ProgressionStep.Advance(to = Rung.WordsInitial), Progression.afterBlock(correct = 6, state = confirmed(Rung.Syllables)).step)
    }

    @ParameterizedTest
    @ValueSource(ints = [3, 4])
    fun middleScoresStay(correct: Int) {
        val (state, step) = Progression.afterBlock(correct = correct, state = confirmed(Rung.WordsMedial))
        assertEquals(ProgressionStep.Stay, step)
        assertEquals(Rung.WordsMedial, state.rung)
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2])
    fun underThreeDropsOneRung(correct: Int) {
        val (state, step) = Progression.afterBlock(correct = correct, state = confirmed(Rung.Clusters))
        assertEquals(ProgressionStep.Drop(to = Rung.WordsFinal), step)
        assertEquals(Rung.WordsFinal, state.rung)
    }

    @Test
    fun noDropBelowTheIsolatedSound() {
        val (state, step) = Progression.afterBlock(correct = 0, state = ProgressionState())
        assertEquals(ProgressionStep.Stay, step)
        assertEquals(Rung.Isolated, state.rung)
    }

    @Test
    fun aBlockCutShortDecidesNothing() {
        val start = confirmed(Rung.WordsFinal)
        val (state, step) = Progression.afterBlock(correct = 1, attempted = 4, state = start)
        assertEquals(ProgressionStep.Stay, step)
        assertEquals(start, state)
    }

    @Test
    fun everyRungAboveIsolatedIsLockedByDefault() {
        val state = ProgressionState()
        assertTrue(state.lockEnabled)
        assertFalse(Progression.isLocked(Rung.Isolated, state))
        for (rung in Rung.entries.filter { it > Rung.Isolated }) {
            assertTrue(Progression.isLocked(rung, state), "$rung")
        }
    }

    @Test
    fun aPerfectBlockCannotPassTheLock() {
        val (state, step) = Progression.afterBlock(correct = 6, state = ProgressionState())
        assertEquals(ProgressionStep.Stay, step)
        assertEquals(Rung.Isolated, state.rung)
        assertEquals(6, state.establishCorrect)
    }

    @Test
    fun twelveCorrectAsksForAnAdult() {
        var state = ProgressionState()
        state = Progression.afterBlock(correct = 6, state = state).state
        val (next, step) = Progression.afterBlock(correct = 6, state = state)
        assertEquals(ProgressionStep.NeedsAdult(next = Rung.Syllables), step)
        assertEquals(Rung.Isolated, next.rung)
    }

    @Test
    fun adultConfirmationOpensTheLockAndMovesUp() {
        val state = Progression.confirmedByAdult(ProgressionState(establishCorrect = 12))
        assertTrue(state.adultConfirmed)
        assertEquals(Rung.Syllables, state.rung)
        assertFalse(Progression.isLocked(Rung.Everyday, state))
    }

    @Test
    fun droppingBackToIsolatedDoesNotAskAgain() {
        val (state, _) = Progression.afterBlock(correct = 0, state = confirmed(Rung.Syllables))
        assertEquals(Rung.Isolated, state.rung)
        assertEquals(ProgressionStep.Advance(to = Rung.Syllables), Progression.afterBlock(correct = 5, state = state).step)
    }

    @Test
    fun parentCanOpenEverything() {
        val state = Progression.openingEverything(ProgressionState())
        for (rung in Rung.entries) {
            assertFalse(Progression.isLocked(rung, state), "$rung")
        }
        assertEquals(ProgressionStep.Advance(to = Rung.Syllables), Progression.afterBlock(correct = 5, state = state).step)
    }

    @Test
    fun turningTheLockOffAdvancesAtFiveOfSix() {
        val state = ProgressionState(lockEnabled = false)
        assertFalse(Progression.isLocked(Rung.Syllables, state))
        assertEquals(ProgressionStep.Advance(to = Rung.Syllables), Progression.afterBlock(correct = 5, state = state).step)
    }

    @ParameterizedTest
    @MethodSource("quietRungs")
    fun anAdultDecidesAfterTheQuietRungs(rung: Rung, next: Rung) {
        val (state, step) = Progression.afterBlock(correct = 6, state = confirmed(rung))
        assertEquals(ProgressionStep.NeedsAdult(next = next), step)
        assertEquals(rung, state.rung)
        assertEquals(next, Progression.confirmedByAdult(state).rung)
    }

    @Test
    fun theParentCanSkipThatAdultStepToo() {
        val state = ProgressionState(rung = Rung.Sentences, openedByParent = true)
        assertEquals(ProgressionStep.Advance(to = Rung.Retell), Progression.afterBlock(correct = 6, state = state).step)
    }

    @Test
    fun theLastRungStays() {
        val (state, step) = Progression.afterBlock(correct = 6, state = confirmed(Rung.Everyday))
        assertEquals(ProgressionStep.Stay, step)
        assertEquals(Rung.Everyday, state.rung)
    }

    @Test
    fun clustersLeadIntoPairsAndPairsIntoSentences() {
        assertEquals(ProgressionStep.Advance(to = Rung.Pairs), Progression.afterBlock(correct = 5, state = confirmed(Rung.Clusters)).step)
        assertEquals(ProgressionStep.Advance(to = Rung.Sentences), Progression.afterBlock(correct = 5, state = confirmed(Rung.Pairs)).step)
    }

    @Test
    fun repeatedWeakBlocksSuggestALogoped() {
        var state = ProgressionState()
        repeat(Progression.weakBlocksBeforeLogoped - 1) {
            state = Progression.afterBlock(correct = 1, state = state).state
        }
        assertFalse(Progression.suggestsLogoped(state))
        state = Progression.afterBlock(correct = 1, state = state).state
        assertTrue(Progression.suggestsLogoped(state))
    }

    companion object {
        @JvmStatic
        fun quietRungs(): List<Arguments> = listOf(
            Arguments.of(Rung.Sentences, Rung.Retell),
            Arguments.of(Rung.Retell, Rung.Everyday),
        )
    }
}
