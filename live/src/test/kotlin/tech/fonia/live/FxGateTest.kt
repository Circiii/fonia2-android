package tech.fonia.live

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FxGateTest {
    private var now = 1_000_000_000L
    private val gate = FxGate(QuietRule(micOpenDelayMillis = 150, tailMillis = 150, minGapMillis = 120)) { now }
    private fun advance(ms: Long) {
        now += ms * 1_000_000L
    }

    @Test
    fun nothingPlaysWhileTheMicIsOpen() {
        assertEquals(0L, gate.tryBeginMicOpen())
        assertTrue(gate.isOpen)
        assertFalse(gate.tryEmit(100))
        assertTrue(gate.playbackWaitNanos() > 0)
        assertTrue(gate.tryBeginPlayback() > 0)
        var ran = false
        assertFalse(gate.emit(100) { ran = true })
        assertFalse(ran)
    }

    @Test
    fun theTailKeepsQuietFor150msAfterTheMicCloses() {
        gate.tryBeginMicOpen()
        advance(1000)
        gate.micClosed()
        assertFalse(gate.isOpen)
        advance(149)
        assertFalse(gate.tryEmit(100))
        assertEquals(1_000_000L, gate.playbackWaitNanos())
        advance(1)
        assertTrue(gate.tryEmit(100))
        assertEquals(0L, gate.tryBeginPlayback())
    }

    @Test
    fun theMicOpensOnly150msAfterTheLastSound() {
        assertTrue(gate.tryEmit(400))
        assertEquals(550_000_000L, gate.tryBeginMicOpen(), "400 ms of sound + 150 ms guard")
        advance(549)
        assertTrue(gate.tryBeginMicOpen() > 0)
        advance(1)
        assertEquals(0L, gate.tryBeginMicOpen())
    }

    @Test
    fun theMicNeverOpensDuringAPrompt() {
        assertEquals(0L, gate.tryBeginPlayback())
        advance(5000)
        assertTrue(gate.tryBeginMicOpen() > 0)
        gate.playbackEnded()
        assertEquals(150_000_000L, gate.tryBeginMicOpen())
        advance(150)
        assertEquals(0L, gate.tryBeginMicOpen())
    }

    @Test
    fun effectsKeepTheirGap() {
        assertTrue(gate.tryEmit(50))
        advance(119)
        assertFalse(gate.tryEmit(50))
        advance(1)
        assertTrue(gate.tryEmit(50))
    }

    @Test
    fun aFailedOpenLeavesNoTail() {
        gate.tryBeginMicOpen()
        gate.micOpenFailed()
        assertFalse(gate.isOpen)
        assertTrue(gate.tryEmit(10))
    }
}
