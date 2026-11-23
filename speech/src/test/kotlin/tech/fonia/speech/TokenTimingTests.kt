package tech.fonia.speech

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** FoniaSpeechTests/TokenTimingTests.swift, suite "Token timing". */
@DisplayName("Token timing")
class TokenTimingTests {
    @Test
    @DisplayName("walks a diagonal cost valley")
    fun dtw() {
        // Three rows over six columns, cheap along row r at columns 2r and 2r + 1.
        val cost = FloatArray(18) { 1f }
        for (r in 0 until 3) {
            cost[r * 6 + 2 * r] = -1f
            cost[r * 6 + 2 * r + 1] = -1f
        }
        assertEquals(listOf(0, 2, 4), TokenTiming.dtwRowStarts(cost, rows = 3, cols = 6))
    }

    @Test
    @DisplayName("median filter removes a spike and keeps a step")
    fun median() {
        assertEquals(listOf(0f, 0f, 0f, 0f, 0f), TokenTiming.medianFilter(floatArrayOf(0f, 0f, 9f, 0f, 0f), width = 3).toList())
        assertEquals(listOf(0f, 0f, 0f, 5f, 5f, 5f), TokenTiming.medianFilter(floatArrayOf(0f, 0f, 0f, 5f, 5f, 5f), width = 3).toList())
    }

    @Test
    @DisplayName("finds the frames that carry sound")
    fun activeFrames() {
        val samples = FloatArray(3200) { 1e-5f }
        for (i in 1600 until 2240) samples[i] = 0.3f
        val active = TokenTiming.activeFrames(samples)
        assertEquals(10, active.size)
        assertEquals((0 until 10).map { it == 5 || it == 6 }, active)
    }

    @Test
    @DisplayName("handles an empty clip")
    fun emptyClip() {
        assertTrue(TokenTiming.activeFrames(FloatArray(0)).isEmpty())
        val snapped = TokenTiming.snapToSound(start = 0.1, end = 0.2, active = emptyList())
        assertEquals(0.1 to 0.2, snapped)
    }

    @Test
    @DisplayName("pulls word edges in to the sound")
    fun snap() {
        val active = MutableList(100) { false }
        for (i in 30 until 40) active[i] = true
        val snapped = TokenTiming.snapToSound(start = 0.4, end = 1.2, active = active)
        assertTrue(abs(snapped.first - 0.6) < 1e-9)
        assertTrue(abs(snapped.second - 0.8) < 1e-9)
    }

    @Test
    @DisplayName("keeps a word that lies entirely in silence, and one past the end of the clip")
    fun snapLeavesAlone() {
        val active = List(10) { false }
        assertEquals(0.04 to 0.1, TokenTiming.snapToSound(start = 0.04, end = 0.1, active = active))
        assertEquals(5.0 to 6.0, TokenTiming.snapToSound(start = 5.0, end = 6.0, active = active))
    }

    private fun token(text: String, logp: Double = -0.5, id: Int = 1): TokenScore =
        TokenScore(id = id, text = text, logp = logp, topID = id, top = text, topLogp = -0.1)

    private val end = TokenScore(id = 50257, text = "", logp = -0.2, topID = 50257, top = "", topLogp = -0.2)

    @Test
    @DisplayName("groups tokens into words, punctuation sticking to the word before")
    fun groups() {
        val tokens = listOf(token(" sh", logp = -0.1), token("ip", logp = -0.3), token(".", logp = -9.0), token(" now", logp = -0.2), end)
        val words = TokenTiming.groupWords(tokens, times = listOf(0.5, 0.6, 0.7, 0.8, 0.9), duration = 2.0) { "w" }
        assertEquals(2, words.size)
        assertTrue(abs(words[0].logp - -0.4) < 1e-9)
        assertEquals(-0.3, words[0].minLogp)
        assertEquals(0.5, words[0].start)
        assertEquals(0.8, words[0].end)
        assertEquals(0.8, words[1].start)
        assertEquals(0.9, words[1].end)
    }

    @Test
    @DisplayName("does not fold a word that opens with a split diacritic into the word before")
    fun splitDiacritic() {
        val tokens = listOf(token(" vreau"), token(" $REPLACEMENT"), token("${REPLACEMENT}apte"), end)
        val words = TokenTiming.groupWords(tokens, times = listOf(0.0, 0.2, 0.3, 0.4), duration = 1.0) { "w" }
        assertEquals(2, words.size)
        assertEquals(2, words[1].tokens.size)
    }

    @Test
    @DisplayName("does not fold a word spelled with a lone space token into the word before")
    fun loneSpace() {
        val tokens = listOf(token(" ș"), token("apte"), token(" "), token("ț"), token("ări"), end)
        val words = TokenTiming.groupWords(tokens, times = listOf(0.0, 0.1, 0.2, 0.3, 0.4, 0.5), duration = 1.0) { ids -> "w${ids.size}" }
        assertEquals(listOf("w2", "w3"), words.map { it.word })
        assertEquals(3, words[1].tokens.size)
        assertEquals(0.2, words[1].start)
    }

    @Test
    @DisplayName("drops a stray space at the end")
    fun trailingSpace() {
        val words = TokenTiming.groupWords(listOf(token(" ship", id = 1), token(" ", id = 2), end), times = null, duration = 1.0) { if (it == listOf(2)) " " else "ship" }
        assertEquals(listOf("ship"), words.map { it.word })
    }

    @Test
    @DisplayName("gives a word the whole clip when there is no alignment")
    fun noAlignment() {
        val words = TokenTiming.groupWords(listOf(token(" ship"), end), times = null, duration = 2.0) { "ship" }
        assertEquals(0.0, words.firstOrNull()?.start)
        assertEquals(2.0, words.firstOrNull()?.end)
    }

    @Test
    @DisplayName("derives token starts from attention rows")
    fun starts() {
        // Four rows of attention over ten frames: a prefix row, then three tokens moving forward in time.
        fun row(peak: Int): FloatArray = FloatArray(10) { if (abs(it - peak) <= 1) 1f else 0.01f }
        val starts = assertNotNull(TokenTiming.tokenStarts(rows = listOf(row(0), row(2), row(5), row(8)), first = 1, count = 3, positions = 10))
        assertEquals(3, starts.size)
        assertEquals(starts.sorted(), starts)
        assertTrue(starts[0] < starts[1])
        assertTrue(starts[1] < starts[2])
    }

    @Test
    @DisplayName("has no token starts without enough rows")
    fun noStarts() {
        assertNull(TokenTiming.tokenStarts(rows = listOf(floatArrayOf(1f)), first = 1, count = 2, positions = 1))
    }

    @Test
    @DisplayName("keeps a short clip whole")
    fun oneWindow() {
        assertEquals(listOf(SampleRange(0, 16_000)), TokenTiming.windows(FloatArray(16_000)))
    }

    @Test
    @DisplayName("cuts long audio at the quiet spot between 22 and 29 seconds")
    fun windows() {
        val rate = 16_000
        val samples = FloatArray(40 * rate) { 0.2f }
        for (i in (25 * rate) until (25 * rate + rate / 2)) samples[i] = 0f
        val parts = TokenTiming.windows(samples)
        assertEquals(2, parts.size)
        val cut = assertNotNull(parts.firstOrNull()?.upperBound)
        assertTrue(cut >= 25 * rate)
        assertTrue(cut <= 25 * rate + rate / 2)
        assertEquals(cut, parts.lastOrNull()?.lowerBound)
        assertEquals(samples.size, parts.lastOrNull()?.upperBound)
    }

    companion object {
        /** U+FFFD, what half of a split diacritic decodes to. */
        private val REPLACEMENT = String(Character.toChars(0xFFFD))
    }
}
