package tech.fonia.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of LiveLevelsTests.swift ("Live levels"). */
class LiveLevelsTests {
    /** Starts at rest. */
    @Test
    fun initialValues() {
        val levels = LiveLevels()
        assertEquals(0f, levels.level, 0f)
        assertEquals(0f, levels.pitchHz, 0f)
        assertEquals(0.5f, levels.sNess, 0f)
        assertEquals(0f, levels.talk, 0f)
        assertFalse(levels.isFricative)
    }

    /** Every published field reads back. */
    @Test
    fun roundTrip() {
        val levels = LiveLevels()
        levels.publish(
            level = 0.1f,
            rms = 0.2f,
            pitchHz = 220f,
            clarity = 0.3f,
            sNess = 0.9f,
            fricativeStrength = 0.4f,
            trillStrength = 0.5f,
            blowStrength = 0.6f,
            isFricative = true,
        )
        assertEquals(0.1f, levels.level, 0f)
        assertEquals(0.2f, levels.rms, 0f)
        assertEquals(220f, levels.pitchHz, 0f)
        assertEquals(0.3f, levels.clarity, 0f)
        assertEquals(0.9f, levels.sNess, 0f)
        assertEquals(0.4f, levels.fricativeStrength, 0f)
        assertEquals(0.5f, levels.trillStrength, 0f)
        assertEquals(0.6f, levels.blowStrength, 0f)
        assertTrue(levels.isFricative)
    }

    /** A frame resets omitted fields but never touches talk. */
    @Test
    fun talkIsIndependent() {
        val levels = LiveLevels()
        levels.publishTalk(0.7f)
        levels.publish(rms = 0.5f, isFricative = true)
        levels.publish()
        assertEquals(0.7f, levels.talk, 0f)
        assertEquals(0f, levels.rms, 0f)
        assertEquals(0.5f, levels.sNess, 0f)
        assertFalse(levels.isFricative)
    }
}
