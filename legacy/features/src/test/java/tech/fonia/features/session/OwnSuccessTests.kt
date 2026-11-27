package tech.fonia.features.session

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.domain.DaySpread
import tech.fonia.domain.Progression
import tech.fonia.domain.ProgressionState
import tech.fonia.domain.ProgressionStep
import tech.fonia.domain.Rung
import tech.fonia.domain.SoundID
import tech.fonia.features.app.progressionState
import tech.fonia.store.Profile
import tech.fonia.store.SoundProgress

/** The learner's own success moves him on, except past the plain sound while the lock waits for an adult. */
class OwnSuccessTests {
    private val profile = Profile(
        kind = Profile.Kind.Child,
        ageBand = Profile.AgeBand.EightToTen,
        voiceGroup = Profile.VoiceGroup.Child,
        color = "cold",
        targetSounds = listOf("s"),
        track = Profile.Track.Ro,
    )

    private fun progress(vararg goods: Int) = SoundProgress(
        profileId = profile.id,
        sound = "s",
        rung = 0,
        blockHistory = goods.map { SoundProgress.Block(rung = 0, good = it, total = 6, endedAt = Instant.EPOCH) },
    )

    @Test
    fun withTheLockOnSyllablesWaitForAnAdultEvenAfterFiveGood() {
        val four = profile.progressionState(SoundID.S, progress(2, 2))
        assertTrue(Progression.isLocked(Rung.Syllables, four))
        assertEquals(4, OwnSuccess.dots(four.establishCorrect))
        assertEquals("Se deschide când îți iese sunetul de 5 ori.", OwnSuccess.lockLine(four.establishCorrect))
        val five = profile.progressionState(SoundID.S, progress(2, 3))
        assertTrue(Progression.isLocked(Rung.Syllables, five))
        assertEquals("Un adult te ascultă, apoi se deschide.", OwnSuccess.lockLine(five.establishCorrect))
        assertEquals(OwnSuccess.TARGET, OwnSuccess.dots(12))
        val listened = profile.copy(confirmedSounds = listOf("s")).progressionState(SoundID.S, progress(2, 3))
        assertFalse(Progression.isLocked(Rung.Syllables, listened))
    }

    @Test
    fun theBlockThatReachesFiveAsksForAnAdultWhileTheLockIsOn() {
        val state = ProgressionState(rung = Rung.Isolated, establishCorrect = 0)
        val (after, step) = OwnSuccess.afterBlock(correct = 5, attempted = 6, state = state)
        assertEquals(ProgressionStep.NeedsAdult(Rung.Syllables), step)
        assertEquals(Rung.Isolated, after.rung)
        assertEquals(5, after.establishCorrect)
        // The plain rule would have waited for 12 without asking.
        assertEquals(ProgressionStep.Stay, Progression.afterBlock(correct = 5, attempted = 6, state = state).step)
    }

    @Test
    fun withTheLockOffOrAfterTheAdultFiveGoodMoveHimOn() {
        for (state in listOf(
            ProgressionState(rung = Rung.Isolated, establishCorrect = 0, lockEnabled = false),
            ProgressionState(rung = Rung.Isolated, establishCorrect = 0, adultConfirmed = true),
        )) {
            val (after, step) = OwnSuccess.afterBlock(correct = 5, attempted = 6, state = state)
            assertEquals(ProgressionStep.Advance(Rung.Syllables), step)
            assertEquals(Rung.Syllables, after.rung)
        }
    }

    @Test
    fun fewerThanFiveGoodStillWaits_andAWeakBlockStillStaysDown() {
        val state = ProgressionState(rung = Rung.Isolated, establishCorrect = 1)
        assertEquals(ProgressionStep.Stay, OwnSuccess.afterBlock(correct = 3, attempted = 6, state = state).step)
        assertEquals(ProgressionStep.Stay, OwnSuccess.afterBlock(correct = 2, attempted = 6, state = state).step)
    }

    /** past sentences and retelling the phone stays quiet: a good block waits for an adult, as on iOS */
    @Test
    fun pastSentencesAGoodBlockWaitsForAnAdult() {
        for (rung in listOf(Rung.Sentences, Rung.Retell)) {
            val state = ProgressionState(rung = rung, adultConfirmed = true)
            for (spread in listOf(DaySpread.NotRequired, DaySpread.Required(earlierGoodDay = true), DaySpread.Required(earlierGoodDay = false))) {
                val (after, step) = OwnSuccess.afterBlock(6, Progression.blockSize, state, spread)
                assertEquals(ProgressionStep.NeedsAdult(rung.next!!), step)
                assertEquals(rung, after.rung)
            }
        }
    }

    @Test
    fun aShortBlockDecidesNothing() {
        val state = ProgressionState(rung = Rung.Isolated, establishCorrect = 4)
        assertEquals(ProgressionStep.Stay, OwnSuccess.afterBlock(correct = 3, attempted = 3, state = state).step)
    }

    @Test
    fun talkBackReadsTheDotsInWordsNotCounts() {
        assertEquals(
            listOf("Abia începem", "Mai puțin de jumătate", "Mai puțin de jumătate", "Peste jumătate", "Peste jumătate", "Gata", "Gata"),
            (0..6).map(OwnSuccess::spokenDots),
        )
        assertFalse((0..6).any { Regex("\\d").containsMatchIn(OwnSuccess.spokenDots(it)) })
    }

    /** once the whole path is open the adult isn't asked, and a child moves on only after good blocks on two days */
    @Test
    fun anOpenPathPastSentencesKeepsTheTwoDayRule() {
        val sentences = ProgressionState(rung = Rung.Sentences, adultConfirmed = true, openedByParent = true)
        assertEquals(
            ProgressionStep.Stay,
            OwnSuccess.afterBlock(6, Progression.blockSize, sentences, DaySpread.Required(earlierGoodDay = false)).step,
        )
        assertEquals(
            ProgressionStep.Advance(Rung.Retell),
            OwnSuccess.afterBlock(6, Progression.blockSize, sentences, DaySpread.Required(earlierGoodDay = true)).step,
        )
        assertEquals(ProgressionStep.Advance(Rung.Retell), OwnSuccess.afterBlock(6, Progression.blockSize, sentences).step)
    }
}
