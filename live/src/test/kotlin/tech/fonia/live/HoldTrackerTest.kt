package tech.fonia.live

import kotlin.test.Test
import kotlin.test.assertEquals

class HoldTrackerTest {
    @Test
    fun countsRealTimeOnTarget() {
        val h = HoldTracker()
        repeat(100) { h.push(true, 0.01) }
        assertEquals(1.0, h.seconds, 1e-9)
        assertEquals(1.0, h.bestSeconds, 1e-9)
    }

    @Test
    fun aShortBreakOnlyPauses() {
        val h = HoldTracker(graceSeconds = 0.15)
        repeat(50) { h.push(true, 0.01) }
        repeat(10) { h.push(false, 0.01) }
        repeat(50) { h.push(true, 0.01) }
        assertEquals(1.0, h.seconds, 1e-9)
    }

    @Test
    fun aLongBreakRestartsUnlessCumulative() {
        val h = HoldTracker(graceSeconds = 0.15)
        repeat(50) { h.push(true, 0.01) }
        repeat(20) { h.push(false, 0.01) }
        assertEquals(0.0, h.seconds)
        repeat(10) { h.push(true, 0.01) }
        assertEquals(0.1, h.seconds, 1e-9)
        assertEquals(0.5, h.bestSeconds, 1e-9)
        assertEquals(0.6, h.totalSeconds, 1e-9)

        val c = HoldTracker(graceSeconds = 0.15, resetsOnBreak = false)
        repeat(50) { c.push(true, 0.01) }
        repeat(50) { c.push(false, 0.01) }
        repeat(10) { c.push(true, 0.01) }
        assertEquals(0.6, c.seconds, 1e-9)
    }
}
