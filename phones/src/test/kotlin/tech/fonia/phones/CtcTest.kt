package tech.fonia.phones

import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The CTC forward pass against brute force over every frame path, on small random posteriors. */
class CtcTest {
    /** Random log-softmax rows over [vocabulary] tokens (token 0 is the blank). */
    private fun randomPosteriors(frames: Int, vocabulary: Int, seed: Int): Posteriors {
        val random = Random(seed)
        val values = FloatArray(frames * vocabulary)
        for (t in 0 until frames) {
            val logits = DoubleArray(vocabulary) { random.nextDouble(-3.0, 3.0) }
            val norm = ln(logits.sumOf { exp(it) })
            for (v in 0 until vocabulary) values[t * vocabulary + v] = (logits[v] - norm).toFloat()
        }
        return Posteriors(frames, vocabulary, values)
    }

    /** log Σ over all frame paths that collapse (merge repeats, drop blanks) to [labels]. */
    private fun bruteForce(posteriors: Posteriors, labels: List<Int>): Double {
        val frames = posteriors.frames
        val vocabulary = posteriors.vocabulary
        var total = 0.0
        val path = IntArray(frames)
        fun collapse(): List<Int> {
            val out = ArrayList<Int>()
            var previous = -1
            for (token in path) {
                if (token != 0 && token != previous) out += token
                previous = token
            }
            return out
        }
        fun visit(t: Int, logProb: Double) {
            if (t == frames) {
                if (collapse() == labels) total += exp(logProb)
                return
            }
            for (v in 0 until vocabulary) {
                path[t] = v
                visit(t + 1, logProb + posteriors[t, v])
            }
        }
        visit(0, 0.0)
        return ln(total)
    }

    @Test
    fun forwardEqualsTheSumOverAllPaths() {
        val ctc = CtcForward(initialLabels = 1)
        val cases = listOf(listOf(1), listOf(1, 2), listOf(2, 2), listOf(1, 2, 1), listOf(3, 1, 3), listOf(1, 1, 2), emptyList())
        for ((index, labels) in cases.withIndex()) {
            for (frames in 1..6) {
                val posteriors = randomPosteriors(frames, 4, seed = 31 * index + frames)
                val expected = bruteForce(posteriors, labels)
                val actual = ctc.logLikelihood(posteriors, labels.toIntArray())
                if (expected == Double.NEGATIVE_INFINITY) {
                    assertEquals(Double.NEGATIVE_INFINITY, actual, "$labels in $frames frames is impossible")
                } else {
                    assertTrue(abs(expected - actual) < 1e-6, "$labels, $frames frames: brute force $expected, forward $actual")
                }
            }
        }
    }

    @Test
    fun repeatedLabelsNeedABlankBetweenThem() {
        // "a a" needs at least three frames (a, blank, a); "a b" fits in two.
        val posteriors = randomPosteriors(2, 4, seed = 7)
        assertEquals(Double.NEGATIVE_INFINITY, CtcForward().logLikelihood(posteriors, intArrayOf(1, 1)))
        assertTrue(CtcForward().logLikelihood(posteriors, intArrayOf(1, 2)) > Double.NEGATIVE_INFINITY)
    }

    @Test
    fun probabilitiesOverAllLabelSequencesSumToOne() {
        // With 2 tokens + blank and 3 frames, the label sequences of length ≤ 3 cover every path exactly once.
        val posteriors = randomPosteriors(3, 3, seed = 11)
        val ctc = CtcForward()
        val sequences = ArrayList<IntArray>()
        sequences += IntArray(0)
        for (a in 1..2) {
            sequences += intArrayOf(a)
            for (b in 1..2) {
                sequences += intArrayOf(a, b)
                for (c in 1..2) sequences += intArrayOf(a, b, c)
            }
        }
        val total = sequences.sumOf { exp(ctc.logLikelihood(posteriors, it)) }
        assertEquals(1.0, total, 1e-6)
    }

    @Test
    fun aWarmForwardPassAllocatesNothing() {
        val ctc = CtcForward()
        val posteriors = randomPosteriors(120, ZipaInventory.SIZE, seed = 3)
        val labels = intArrayOf(3, 15, 4, 14, 22, 23)
        assertEquals(0L, allocationAfterWarmUp { ctc.logLikelihood(posteriors, labels) })
    }

    @Test
    fun logAddIsStable() {
        assertEquals(1000.0 + ln(2.0), logAdd(1000.0, 1000.0), 1e-12)
        assertEquals(-5.0, logAdd(Double.NEGATIVE_INFINITY, -5.0))
        assertEquals(ln(exp(-1.0) + exp(-30.0)), logAdd(-1.0, -30.0), 1e-12)
    }
}
