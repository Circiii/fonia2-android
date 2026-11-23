package tech.fonia.phones

import kotlin.math.exp
import kotlin.math.ln1p

internal const val NEG_INF = Double.NEGATIVE_INFINITY

/** log(e^a + e^b) without overflow; −∞ is the log of zero. */
internal fun logAdd(a: Double, b: Double): Double {
    if (a == NEG_INF) return b
    if (b == NEG_INF) return a
    return if (a > b) a + ln1p(exp(b - a)) else b + ln1p(exp(a - b))
}

/**
 * The CTC forward algorithm (Graves et al., 2006) in the log domain: log P(labels | X), summed over every alignment
 * of the labels to the frames, with the blank between labels optional except between two equal labels.
 *
 * This is the "CTC loss" both terms of GOP-SF are made of (Cao et al., IEEE TASLPRO 2026, eq. 11–12): no Viterbi
 * path, no segmentation. Written from the paper and the textbook recursion; the published GOP code has no licence
 * and was not used.
 *
 * The two scratch rows and the extended label sequence are kept between calls and grow only when a longer sequence
 * arrives, so a call allocates nothing per frame. Not thread-safe.
 */
class CtcForward(initialLabels: Int = 32) {
    private var alpha = DoubleArray(2 * initialLabels + 1)
    private var next = DoubleArray(2 * initialLabels + 1)
    private var extended = IntArray(2 * initialLabels + 1)

    fun logLikelihood(posteriors: Posteriors, labels: IntArray, count: Int = labels.size, blank: Int = ZipaInventory.BLANK): Double {
        require(count in 0..labels.size)
        val states = 2 * count + 1
        if (alpha.size < states) {
            alpha = DoubleArray(states)
            next = DoubleArray(states)
            extended = IntArray(states)
        }
        val ext = extended
        for (s in 0 until states) ext[s] = if (s % 2 == 0) blank else labels[s / 2]
        val frames = posteriors.frames
        if (frames == 0) return if (count == 0) 0.0 else NEG_INF
        val lp = posteriors.logProbs
        val vocabulary = posteriors.vocabulary
        var current = alpha
        var following = next
        current.fill(NEG_INF, 0, states)
        current[0] = lp[ext[0]].toDouble()
        if (states > 1) current[1] = lp[ext[1]].toDouble()
        for (t in 1 until frames) {
            val row = t * vocabulary
            // Only states from which the end is still reachable in the frames left need updating.
            val lowest = maxOf(0, states - 2 * (frames - t))
            for (s in 0 until lowest) following[s] = NEG_INF
            for (s in lowest until states) {
                var a = current[s]
                if (s >= 1) a = logAdd(a, current[s - 1])
                if (s >= 2 && ext[s] != blank && ext[s] != ext[s - 2]) a = logAdd(a, current[s - 2])
                following[s] = if (a == NEG_INF) NEG_INF else a + lp[row + ext[s]]
            }
            val swap = current
            current = following
            following = swap
        }
        alpha = current
        next = following
        return if (states == 1) current[0] else logAdd(current[states - 1], current[states - 2])
    }
}
