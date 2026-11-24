package tech.fonia.features.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.domain.Progression
import tech.fonia.domain.ProgressionState
import tech.fonia.domain.ProgressionStep
import tech.fonia.domain.Rung
import tech.fonia.domain.SoundID
import tech.fonia.features.app.progressionState
import tech.fonia.store.Profile
import tech.fonia.store.SoundProgress
import java.time.Instant

/** v2-flow: what waited for an adult opens by the learner's own success. */
class OwnSuccessTests {
    private val profile = Profile(
        kind = Profile.Kind.Child, ageBand = Profile.AgeBand.EightToTen, voiceGroup = Profile.VoiceGroup.Child,
        color = "cold", targetSounds = listOf("s"), track = Profile.Track.Ro,
    )

    private fun progress(vararg goods: Int) = SoundProgress(
        profileId = profile.id, sound = "s", rung = 0,
        blockHistory = goods.map { SoundProgress.Block(rung = 0, good = it, total = 6, endedAt = Instant.EPOCH) },
    )

    @Test
    fun syllablesStayShutUntilThePlainSoundCameOutWellFiveTimes() {
        val four = profile.progressionState(SoundID.S, progress(2, 2))
        assertTrue(Progression.isLocked(Rung.Syllables, four))
        assertEquals(4, OwnSuccess.dots(four.establishCorrect))
        val five = profile.progressionState(SoundID.S, progress(2, 3))
        assertFalse(Progression.isLocked(Rung.Syllables, five))
        assertEquals(OwnSuccess.TARGET, OwnSuccess.dots(12))
    }

    @Test
    fun theBlockThatReachesFiveMovesHimOnWithNoAdult() {
        val state = ProgressionState(rung = Rung.Isolated, establishCorrect = 0)
        val (after, step) = OwnSuccess.afterBlock(correct = 5, attempted = 6, state = state)
        assertEquals(ProgressionStep.Advance(Rung.Syllables), step)
        assertEquals(Rung.Syllables, after.rung)
        // The plain rule would have waited.
        assertEquals(ProgressionStep.Stay, Progression.afterBlock(correct = 5, attempted = 6, state = state).step)
    }

    @Test
    fun fewerThanFiveGoodStillWaits_andAWeakBlockStillStaysDown() {
        val state = ProgressionState(rung = Rung.Isolated, establishCorrect = 1)
        assertEquals(ProgressionStep.Stay, OwnSuccess.afterBlock(correct = 3, attempted = 6, state = state).step)
        assertEquals(ProgressionStep.Stay, OwnSuccess.afterBlock(correct = 2, attempted = 6, state = state).step)
    }

    @Test
    fun pastSentencesAGoodBlockMovesOnByItself() {
        val state = ProgressionState(rung = Rung.Sentences, adultConfirmed = true)
        assertTrue(Progression.afterBlock(correct = 6, attempted = 6, state = state).step is ProgressionStep.NeedsAdult)
        val (after, step) = OwnSuccess.afterBlock(correct = 6, attempted = 6, state = state)
        assertTrue(step is ProgressionStep.Advance)
        assertEquals(Rung.Sentences.next, after.rung)
    }

    @Test
    fun aShortBlockDecidesNothing() {
        val state = ProgressionState(rung = Rung.Isolated, establishCorrect = 4)
        assertEquals(ProgressionStep.Stay, OwnSuccess.afterBlock(correct = 3, attempted = 3, state = state).step)
    }
}
