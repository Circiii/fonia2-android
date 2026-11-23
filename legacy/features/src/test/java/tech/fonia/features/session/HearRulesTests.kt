package tech.fonia.features.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.domain.SoundDetector
import tech.fonia.dsp.VoiceGroup
import tech.fonia.judge.TalkerGroup
import tech.fonia.store.Profile

/** "Something always happens": every sound gets an answer at once, and no try ends in a silent reset. */
class HearRulesTests {
    @Test
    fun anySoundIsHeardWithinTwoFrames() {
        var e = 0f
        // A quiet voice: 0.15 of the range above the room floor (6 dB), two 60 Hz frames.
        repeat(2) { e = HearRules.envelope(e, 0.15f, 1f / 60f) }
        assertTrue("envelope $e", HearRules.hears(e))
        // and silence is not heard
        assertFalse(HearRules.hears(HearRules.envelope(0f, 0.01f, 1f / 60f)))
    }

    @Test
    fun theLetterVisiblySwellsWithAnySound() {
        val rest = HearRules.letterInflation(REST_INFLATION, 0f)
        val talking = HearRules.letterInflation(REST_INFLATION, 0.3f)
        assertEquals(REST_INFLATION, rest, 1e-6f)
        assertTrue("$talking", talking - rest >= 0.2f)
        assertTrue(HearRules.letterInflation(0.9f, 1f) <= 1f)
        assertTrue(HearRules.letterWobble(0.3f, 0.9f) > 0f)
        assertEquals(0f, HearRules.letterWobble(0f, 0.9f), 0f)
    }

    @Test
    fun theHeldBarFillsForTheTargetAndMovesALittleWhenClose() {
        fun s(sNess: Float, warm: Boolean = false) =
            HearRules.holdCredit(SoundDetector.Sibilant, warm, isFricative = true, sNess = sNess, trill = 0f, level = 0.5f)
        assertEquals(HearRules.CREDIT_RIGHT, s(0.6f), 0f)
        assertEquals(HearRules.CREDIT_NEAR, s(0.42f), 0f)
        assertEquals(HearRules.CREDIT_OTHER_HISS, s(0.05f), 0f)
        assertEquals(HearRules.CREDIT_RIGHT, s(0.05f, warm = true), 0f)
        assertEquals(HearRules.CREDIT_OTHER_HISS, s(0.9f, warm = true), 0f)
        // a vowel on an S screen does not fill it
        assertEquals(0f, HearRules.holdCredit(SoundDetector.Sibilant, false, false, 0.5f, 0f, 0.8f), 0f)
        assertEquals(HearRules.CREDIT_RIGHT, HearRules.holdCredit(SoundDetector.Rhotic, false, false, 0.5f, 0.5f, 0.5f), 0f)
        assertEquals(HearRules.CREDIT_NEAR, HearRules.holdCredit(SoundDetector.Rhotic, false, false, 0.5f, 0.2f, 0.5f), 0f)
    }

    @Test
    fun theWrongHissCanNeverFillTheBarInTheTimeGiven() {
        // goal 3 s, the exercise gives goal + 7 s
        assertTrue(HearRules.CREDIT_OTHER_HISS * 10f < 3f)
        // a close hiss can, slowly
        assertTrue(HearRules.CREDIT_NEAR * 10f >= 3f)
    }

    @Test
    fun aDeadMicrophoneIsNamedNotRetaken() {
        assertTrue(HearRules.micSilent(openSeconds = 2.5, peakDb = -180f))
        assertTrue(HearRules.micSilent(openSeconds = 3.0, peakDb = -80f))
        assertFalse(HearRules.micSilent(openSeconds = 1.0, peakDb = -180f))
        assertFalse(HearRules.micSilent(openSeconds = 3.0, peakDb = -40f))
        assertEquals(HearRules.Unjudged.NoMicrophone, HearRules.afterUnjudged(0, 2, micWasSilent = true))
    }

    @Test
    fun twoRetakesThenAnAlmostThatMovesOn() {
        assertEquals(HearRules.Unjudged.Retake, HearRules.afterUnjudged(0, 2, false))
        assertEquals(HearRules.Unjudged.Retake, HearRules.afterUnjudged(1, 2, false))
        assertEquals(HearRules.Unjudged.AlmostAndMoveOn, HearRules.afterUnjudged(2, 2, false))
        assertEquals(listOf(3, 2, 1), HearRules.retakeCountdown)
    }

    @Test
    fun aManOnAChildProfileIsHeardAsAManAndRemembered() {
        val store = HashMap<String, String>()
        val profile = Profile(kind = Profile.Kind.Child, ageBand = Profile.AgeBand.EightToTen, voiceGroup = Profile.VoiceGroup.Child, color = "cold", track = Profile.Track.Ro)
        val memory = TalkerMemory({ store[it] }, { k, v -> store[k] = v })
        assertEquals(VoiceGroup.CHILD, memory.group(profile))
        assertFalse(memory.heard(profile))
        assertEquals(TalkerGroup.Man, memory.observe(profile, 121f))
        assertEquals(VoiceGroup.MALE, memory.group(profile))
        // a hiss (no voice) keeps him a man
        assertEquals(TalkerGroup.Man, memory.observe(profile, Float.NaN))
        // a new session (new memory over the same preferences) remembers him
        val again = TalkerMemory({ store[it] }, { k, v -> store[k] = v })
        assertEquals(VoiceGroup.MALE, again.group(profile))
        assertTrue(again.heard(profile))
    }
}
