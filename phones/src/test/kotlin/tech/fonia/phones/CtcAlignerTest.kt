package tech.fonia.phones

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CtcAlignerTest {
    private val aligner = CtcAligner()

    @Test
    fun eachLabelGetsTheFramesItWasSaidIn() {
        // 4 blank, 2 ▁, then l a k for 3 frames each with a blank after: l at frames 6–8, a at 10–12, k at 14–16.
        val p = clean("l a k")
        val labels = listOf("▁", "l", "a", "k").map { ZipaInventory.id(it) }.toIntArray()
        val a = assertNotNull(aligner.align(p, labels))
        assertEquals(4, a.spans.size)
        assertEquals(CtcAligner.Span(6, 8), a.spans[1])
        assertEquals(CtcAligner.Span(10, 12), a.spans[2])
        assertEquals(CtcAligner.Span(14, 16), a.spans[3])
        assertTrue(a.logProb <= CtcForward().logLikelihood(p, labels) + 1e-9)
    }

    @Test
    fun repeatedLabelsNeedABlankBetweenThem() {
        val p = clean("s s")
        val labels = listOf("▁", "s", "s").map { ZipaInventory.id(it) }.toIntArray()
        val a = assertNotNull(aligner.align(p, labels))
        assertTrue(a.spans[1].last < a.spans[2].first)
    }

    @Test
    fun moreLabelsThanFramesCannotAlign() {
        val p = spoken(mapOf("<blk>" to 0.9) to 2)
        assertNull(aligner.align(p, intArrayOf(ZipaInventory.id("a"), ZipaInventory.id("a"))))
    }
}
