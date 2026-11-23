package tech.fonia.domain.adaptive

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tech.fonia.domain.Rung

/** T2 (03 §1.6; G §6.4 item 3): 60–80 % per block; over 85 % in 2 blocks harder; under 50 % easier with KP. */
@DisplayName("Adaptive: Challenge Point")
class ChallengePointTests {
    private fun of(good: Int, total: Int = 6) = BlockTally(good, total)

    @Test
    fun theResearchNumbers() {
        assertEquals(0.60, ChallengePoint.TARGET_LOW)
        assertEquals(0.80, ChallengePoint.TARGET_HIGH)
        assertEquals(0.85, ChallengePoint.HARDER_ABOVE)
        assertEquals(2, ChallengePoint.HARDER_AFTER_BLOCKS)
        assertEquals(0.50, ChallengePoint.EASIER_BELOW)
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2])
    fun underHalfIsOneStepEasier(good: Int) {
        assertEquals(Move.Easier to ChallengePoint.Reason.BelowHalf, ChallengePoint.decide(emptyList(), of(good)))
    }

    @Test
    fun exactlyHalfIsNotUnderHalf() {
        assertEquals(Move.Stay to ChallengePoint.Reason.Held, ChallengePoint.decide(emptyList(), of(3)))
    }

    @Test
    fun overEightyFiveInTwoBlocksIsHarder() {
        // 6 + 5 of 12 = 91.7 %.
        assertEquals(Move.Harder to ChallengePoint.Reason.AboveTwice, ChallengePoint.decide(listOf(of(6)), of(5)))
        assertEquals(Move.Harder to ChallengePoint.Reason.AboveTwice, ChallengePoint.decide(listOf(of(5)), of(6)))
        // 5 + 5 of 12 = 83.3 %: in between, so it stays.
        assertEquals(Move.Stay to ChallengePoint.Reason.Held, ChallengePoint.decide(listOf(of(5)), of(5)))
        // One block alone is not two.
        assertEquals(Move.Stay to ChallengePoint.Reason.Held, ChallengePoint.decide(emptyList(), of(6)))
    }

    @Test
    fun onlyTheLastTwoBlocksCount() {
        assertEquals(Move.Stay to ChallengePoint.Reason.Held, ChallengePoint.decide(listOf(of(6), of(4)), of(6)))
        assertEquals(Move.Harder to ChallengePoint.Reason.AboveTwice, ChallengePoint.decide(listOf(of(4), of(6)), of(6)))
    }

    @Test
    fun aBlockWithFewVerdictsDecidesNothing() {
        assertEquals(Move.Stay to ChallengePoint.Reason.TooFewTakes, ChallengePoint.decide(emptyList(), of(0, total = 3)))
        val state = ChallengeState(Rung.WordsMedial)
        assertEquals(state, ChallengePoint.afterBlock(state, of(0, total = 3), fastAllowed = true).state)
        // Four verdicts are enough: 1 of 4 is under half.
        assertEquals(Move.Easier to ChallengePoint.Reason.BelowHalf, ChallengePoint.decide(emptyList(), of(1, total = 4)))
    }

    @Test
    fun theStepsAreTheRungsUpToTheCeilingThenItsPace() {
        assertEquals(
            listOf(Step(Rung.Isolated), Step(Rung.Syllables), Step(Rung.WordsInitial), Step(Rung.WordsInitial, fast = true)),
            ChallengePoint.steps(Rung.WordsInitial, fastAllowed = true),
        )
        assertEquals(listOf(Step(Rung.Isolated)), ChallengePoint.steps(Rung.Isolated, fastAllowed = false))
    }

    @Test
    fun easierGoesDownARungAndTurnsOnKnowledgeOfPerformance() {
        val outcome = ChallengePoint.afterBlock(ChallengeState(Rung.WordsMedial), of(2), fastAllowed = true)
        assertEquals(Move.Easier, outcome.move)
        assertEquals(Step(Rung.WordsInitial), outcome.state.step)
        assertTrue(outcome.state.kp)
        assertEquals(emptyList(), outcome.state.sinceMove)
    }

    @Test
    fun knowledgeOfPerformanceStaysUntilABlockIsBackInTheBand() {
        var state = ChallengePoint.afterBlock(ChallengeState(Rung.WordsMedial), of(2), fastAllowed = true).state
        // 3 of 6 is not under half, but not in the band yet either.
        state = ChallengePoint.afterBlock(state, of(3), fastAllowed = true).state
        assertTrue(state.kp)
        state = ChallengePoint.afterBlock(state, of(4), fastAllowed = true).state
        assertFalse(state.kp)
    }

    @Test
    fun harderGoesUpAStepAndTheTopStepIsTheCeilingSaidFast() {
        var state = ChallengeState(Rung.WordsInitial, step = Step(Rung.Syllables))
        state = ChallengePoint.afterBlock(state, of(6), true).state
        val up = ChallengePoint.afterBlock(state, of(6), true)
        assertEquals(Move.Harder, up.move)
        assertEquals(Step(Rung.WordsInitial), up.state.step)
        state = ChallengePoint.afterBlock(ChallengePoint.afterBlock(up.state, of(6), true).state, of(6), true).state
        assertEquals(Step(Rung.WordsInitial, fast = true), state.step)
        // Nothing harder under the ceiling: it stays, and says why.
        state = ChallengePoint.afterBlock(state, of(6), true).state
        val top = ChallengePoint.afterBlock(state, of(6), true)
        assertEquals(Move.Stay, top.move)
        assertEquals(ChallengePoint.Reason.AtTop, top.reason)
    }

    @Test
    fun theEasiestStepCanGoNoLower() {
        val bottom = ChallengePoint.afterBlock(ChallengeState(Rung.WordsInitial, step = Step(Rung.Isolated)), of(0), true)
        assertEquals(Move.Stay, bottom.move)
        assertEquals(ChallengePoint.Reason.AtBottom, bottom.reason)
        assertTrue(bottom.state.kp)
    }

    @Test
    fun aNewCeilingStartsAtItsOwnMaterial() {
        val saved = ChallengeState(Rung.WordsInitial, step = Step(Rung.Syllables), kp = true)
        assertEquals(ChallengeState(Rung.WordsMedial), ChallengePoint.resume(saved, Rung.WordsMedial, fastAllowed = true))
        assertEquals(saved, ChallengePoint.resume(saved, Rung.WordsInitial, fastAllowed = true))
        // A fast step the age no longer allows goes back to the same rung said normally.
        val fast = ChallengeState(Rung.WordsInitial, step = Step(Rung.WordsInitial, fast = true))
        assertEquals(Step(Rung.WordsInitial), ChallengePoint.resume(fast, Rung.WordsInitial, fastAllowed = false).step)
    }

    @Test
    fun theFeedbackFollowsTheLastTwoBlocks() {
        var state = ChallengeState(Rung.WordsInitial)
        assertEquals(null, state.recentSuccess)
        state = ChallengePoint.afterBlock(state, of(6), true).state
        state = ChallengePoint.afterBlock(state, of(3), true).state
        assertEquals(0.75, state.recentSuccess)
    }
}
