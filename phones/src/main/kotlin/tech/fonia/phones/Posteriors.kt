package tech.fonia.phones

/**
 * Frame-level log-posteriors of the acoustic model: [frames] rows of [vocabulary] natural-log probabilities
 * (log-softmax), row-major in [logProbs]. ZIPA-small gives one row per 20 ms (50 Hz): its fbank runs at 100 Hz and
 * the encoder halves it, `(fbankFrames − 7) / 2` rows.
 */
class Posteriors(val frames: Int, val vocabulary: Int, val logProbs: FloatArray) {
    init {
        require(frames >= 0 && vocabulary > 0 && logProbs.size >= frames * vocabulary)
    }

    operator fun get(frame: Int, token: Int): Float = logProbs[frame * vocabulary + token]

    /** Greedy CTC decoding: best token per frame, repeats merged, blanks dropped. For logs and checks, not scoring. */
    fun greedy(): List<String> {
        val out = ArrayList<String>()
        var previous = -1
        for (t in 0 until frames) {
            val best = argmax(t)
            if (best != ZipaInventory.BLANK && best != previous) out += ZipaInventory.tokens[best]
            previous = best
        }
        return out
    }

    fun argmax(frame: Int): Int {
        val row = frame * vocabulary
        var best = 0
        for (v in 1 until vocabulary) if (logProbs[row + v] > logProbs[row + best]) best = v
        return best
    }

    /** Σ_t max_v log p_t(v): the log probability of the single best frame path, with no constraint on the labels. */
    fun bestPathLogProb(): Double {
        var sum = 0.0
        for (t in 0 until frames) sum += logProbs[t * vocabulary + argmax(t)]
        return sum
    }

    companion object {
        val empty = Posteriors(0, ZipaInventory.SIZE, FloatArray(0))
    }
}
