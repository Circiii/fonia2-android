package tech.fonia.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The slow, endless motion steps on one beat, and a calm bird only repaints for what a child can see move. */
class AmbientMotionTests {
    @Test
    fun loopsReadingOneFrameShareItsBeat() {
        val frame = 5_000_000_000L + AmbientBeat.NANOS / 3
        assertEquals(AmbientBeat.of(frame), AmbientBeat.of(frame))
        assertEquals(AmbientBeat.of(frame), AmbientBeat.of(frame + AmbientBeat.NANOS / 3))
        assertNotEquals(AmbientBeat.of(frame), AmbientBeat.of(frame + AmbientBeat.NANOS))
    }

    @Test
    fun aHoldingBirdOnlyBreathes() {
        val held = ParrotMotion.pose(time = 1.0, mood = FoniMood.idle, sinceMood = 100.0, sinceHop = 100.0, voice = 0.0, seed = 11.0)
        val breathing = held.copy(breath = held.breath + 0.2, lean = held.lean + 0.1)
        assertFalse(breathing.movesFrom(held))
        assertTrue(held.copy(head = held.head + 2).movesFrom(held))
        assertTrue(held.copy(blink = if (held.blink > 0.5) 0.0 else 1.0).movesFrom(held))
        assertTrue(held.copy(eyes = ParrotPose.Eyes.smile).movesFrom(held))
    }

    @Test
    fun theSessionBarSpeaksInWords() {
        assertEquals("Abia începem", sessionBarSpoken(0.0))
        assertEquals("Mai puțin de jumătate", sessionBarSpoken(0.3))
        assertEquals("Peste jumătate", sessionBarSpoken(0.7))
        assertEquals("Gata", sessionBarSpoken(1.0))
    }
}
