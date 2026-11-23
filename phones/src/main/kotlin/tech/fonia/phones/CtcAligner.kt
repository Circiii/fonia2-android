package tech.fonia.phones

/**
 * CTC forced alignment (Viterbi over the same extended label sequence as [CtcForward]): the single best path of
 * [labels] through the frames, and for each label the frames it occupies (plan 03 §5 item 4, `CtcAligner`; 20 ms
 * resolution with ZIPA). The judge uses the target's interval as an alignment check and in the trace, not to score.
 *
 * Allocates a frames × states back-pointer table per call (a word is ~50 × 25); not thread-safe.
 */
class CtcAligner {
    /** One label's frames, inclusive; empty labels cannot happen on a valid path (each label takes ≥ 1 frame). */
    data class Span(val first: Int, val last: Int)

    data class Alignment(
        /** Log probability of the best path (≤ the forward log-likelihood). */
        val logProb: Double,
        /** One span per label, in order. */
        val spans: List<Span>,
    )

    /** Null when the labels cannot fit the frames (more labels, with forced blanks, than frames). */
    fun align(posteriors: Posteriors, labels: IntArray, blank: Int = ZipaInventory.BLANK): Alignment? {
        val frames = posteriors.frames
        val count = labels.size
        val states = 2 * count + 1
        if (frames == 0) return null
        val ext = IntArray(states) { s -> if (s % 2 == 0) blank else labels[s / 2] }
        val lp = posteriors.logProbs
        val vocabulary = posteriors.vocabulary
        var current = DoubleArray(states) { NEG_INF }
        var next = DoubleArray(states)
        val back = Array(frames) { ByteArray(states) } // 0 stay, 1 from s−1, 2 from s−2
        current[0] = lp[ext[0]].toDouble()
        if (states > 1) current[1] = lp[ext[1]].toDouble()
        for (t in 1 until frames) {
            val row = t * vocabulary
            for (s in 0 until states) {
                var best = current[s]
                var from = 0
                if (s >= 1 && current[s - 1] > best) { best = current[s - 1]; from = 1 }
                if (s >= 2 && ext[s] != blank && ext[s] != ext[s - 2] && current[s - 2] > best) { best = current[s - 2]; from = 2 }
                next[s] = if (best == NEG_INF) NEG_INF else best + lp[row + ext[s]]
                back[t][s] = from.toByte()
            }
            val swap = current
            current = next
            next = swap
        }
        var s = when {
            states == 1 -> 0
            current[states - 1] >= current[states - 2] -> states - 1
            else -> states - 2
        }
        val logProb = current[s]
        if (logProb == NEG_INF) return null
        val first = IntArray(count) { -1 }
        val last = IntArray(count) { -1 }
        for (t in frames - 1 downTo 0) {
            if (s % 2 == 1) {
                val label = s / 2
                if (last[label] < 0) last[label] = t
                first[label] = t
            }
            if (t > 0) s -= back[t][s].toInt()
        }
        if (first.any { it < 0 }) return null
        return Alignment(logProb, (0 until count).map { Span(first[it], last[it]) })
    }
}
