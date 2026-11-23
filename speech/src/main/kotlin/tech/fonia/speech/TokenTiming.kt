package tech.fonia.speech

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.sqrt

/** Swift's `Range<Int>`: samples `lowerBound until upperBound`. Kotlin's IntRange calls every empty range equal. */
internal data class SampleRange(val lowerBound: Int, val upperBound: Int) {
    val count: Int
        get() = upperBound - lowerBound
}

/**
 * Token and word timing from the decoder's cross-attention, as in openai/whisper `find_alignment`.
 * The arithmetic stays in Float where Swift uses Float, in the same order.
 */
internal object TokenTiming {
    const val SAMPLES_PER_POSITION = 320
    const val SECONDS_PER_POSITION = 0.02
    private const val MEDIAN_WIDTH = 7

    /** Frames (20 ms) that carry sound, relative to the clip's own loudest frames. */
    fun activeFrames(samples: FloatArray, hop: Int = SAMPLES_PER_POSITION): List<Boolean> {
        if (samples.isEmpty()) return emptyList()
        val count = (samples.size + hop - 1) / hop
        val levels = FloatArray(count) { frame ->
            val from = frame * hop
            val to = minOf(samples.size, from + hop)
            var energy = 0f
            for (i in from until to) energy += samples[i] * samples[i]
            10f * log10(energy / maxOf(1, to - from).toFloat() + 1e-12f)
        }
        val sorted = levels.sortedArray()
        val peak = sorted[((count - 1).toDouble() * 0.98).toInt()]
        val floorLevel = sorted[((count - 1).toDouble() * 0.1).toInt()]
        // Weak fricatives (the TH in "think") sit about 35 dB under the vowels; room noise sits at the floor.
        val threshold = SwiftMath.max(peak - 40f, floorLevel + 6f)
        return levels.map { it > threshold }
    }

    /** Pulls word edges in to the nearest frames with sound, since DTW smears leading silence and pauses into words. */
    fun snapToSound(start: Double, end: Double, active: List<Boolean>): Pair<Double, Double> {
        if (active.isEmpty()) return start to end
        var a = maxOf(0, floor(start / SECONDS_PER_POSITION).toInt())
        var b = minOf(active.size - 1, ceil(end / SECONDS_PER_POSITION).toInt() - 1)
        if (!(a < active.size && b >= 0)) return start to end
        while (a < b && !active[a]) a += 1
        while (b > a && !active[b]) b -= 1
        if (!active[a]) return start to end
        val snappedStart = a.toDouble() * SECONDS_PER_POSITION
        return snappedStart to SwiftMath.max((b + 1).toDouble() * SECONDS_PER_POSITION, snappedStart + 0.04)
    }

    /** Classic DTW over a rows-by-cols cost matrix; returns the column at which each row starts. */
    fun dtwRowStarts(cost: FloatArray, rows: Int, cols: Int): List<Int> {
        val width = cols + 1
        val c = DoubleArray((rows + 1) * width) { Double.POSITIVE_INFINITY }
        val trace = ByteArray((rows + 1) * width)
        c[0] = 0.0
        if (rows > 0 && cols > 0) {
            for (j in 1..cols) {
                for (i in 1..rows) {
                    val diagonal = c[(i - 1) * width + j - 1]
                    val up = c[(i - 1) * width + j]
                    val left = c[i * width + j - 1]
                    var best = diagonal
                    var move: Byte = 0
                    if (up < best) {
                        best = up
                        move = 1
                    }
                    if (left < best) {
                        best = left
                        move = 2
                    }
                    c[i * width + j] = cost[(i - 1) * cols + j - 1].toDouble() + best
                    trace[i * width + j] = move
                }
            }
        }
        for (j in 0..cols) trace[j] = 2
        for (i in 0..rows) trace[i * width] = 1
        val starts = IntArray(rows)
        var i = rows
        var j = cols
        while (i > 0 || j > 0) {
            // The path is walked backwards, so the last write per row is its first column.
            if (i > 0 && j > 0) starts[i - 1] = j - 1
            when (trace[i * width + j].toInt()) {
                0 -> {
                    i -= 1
                    j -= 1
                }
                1 -> i -= 1
                else -> j -= 1
            }
        }
        return starts.toList()
    }

    /** Median over a centred window, the ends repeated. `values[offset until offset + length]` is Swift's ArraySlice. */
    fun medianFilter(values: FloatArray, width: Int, offset: Int = 0, length: Int = values.size - offset): FloatArray {
        val half = width / 2
        val last = length - 1
        return FloatArray(length) { center ->
            val window = FloatArray(2 * half + 1)
            var n = 0
            for (k in (center - half)..(center + half)) {
                window[n] = values[offset + minOf(last, maxOf(0, k))]
                n += 1
            }
            window.sort()
            window[half]
        }
    }

    /**
     * Start time of every predicted token. `rows` holds one cross-attention row per decoder input,
     * cropped to the real audio; rows `first until first + count` are those that predict the tokens.
     */
    fun tokenStarts(rows: List<FloatArray>, first: Int, count: Int, positions: Int): List<Double>? {
        if (!(rows.size >= first + count && count > 0 && positions > 0)) return null
        val height = rows.size
        val weights = FloatArray(height * positions)
        for (r in 0 until height) {
            val row = rows[r]
            if (row.size < positions) return null
            var sum = 0f
            for (f in 0 until positions) sum += row[f]
            val inverse = if (sum > 0) 1f / sum else 0f
            for (f in 0 until positions) weights[r * positions + f] = row[f] * inverse
        }
        // Each frame normalised over the token axis, then median-filtered along time.
        for (f in 0 until positions) {
            var mean = 0f
            for (r in 0 until height) mean += weights[r * positions + f]
            mean /= height.toFloat()
            var variance = 0f
            for (r in 0 until height) {
                val d = weights[r * positions + f] - mean
                variance += d * d
            }
            val root = sqrt(variance / height.toFloat())
            val deviation = if (root > 0) root else 1f
            for (r in 0 until height) weights[r * positions + f] = (weights[r * positions + f] - mean) / deviation
        }
        val cost = FloatArray(count * positions)
        for (r in 0 until count) {
            val filtered = medianFilter(weights, MEDIAN_WIDTH, offset = (first + r) * positions, length = positions)
            for (f in 0 until positions) cost[r * positions + f] = -filtered[f]
        }
        return dtwRowStarts(cost, rows = count, cols = positions).map { it.toDouble() * SECONDS_PER_POSITION }
    }

    /**
     * Groups tokens into words: a leading space starts a word, punctuation sticks to the previous one.
     * The last token must be end of text.
     */
    fun groupWords(tokens: List<TokenScore>, times: List<Double>?, duration: Double, decode: (List<Int>) -> String): List<WordScore> {
        class Group(val ids: MutableList<Int>, val scored: MutableList<TokenScore>, val first: Int, var last: Int)

        val groups = mutableListOf<Group>()
        for ((k, token) in tokens.dropLast(1).withIndex()) {
            val punctuation = isPunctuation(token.text)
            val startsWord = SwiftString.characters(token.text).firstOrNull()?.let(SwiftString::isWhitespace) == true && !punctuation
            val current = groups.lastOrNull()
            if (current != null && !(startsWord && current.scored.isNotEmpty())) {
                current.ids.add(token.id)
                if (!punctuation) current.scored.add(token)
                current.last = k
            } else {
                groups.add(Group(mutableListOf(token.id), if (punctuation) mutableListOf() else mutableListOf(token), first = k, last = k))
            }
        }
        return groups.mapNotNull { group ->
            val word = SwiftString.trimmingWhitespacesAndNewlines(decode(group.ids))
            val firstScored = group.scored.firstOrNull()
            if (firstScored == null || word.isEmpty()) return@mapNotNull null
            val logps = group.scored.map { it.logp }
            val start = times?.get(group.first) ?: 0.0
            val nextStart = times?.let { if (group.last + 1 < it.size) it[group.last + 1] else null }
            val end = if (times == null) duration else (nextStart ?: duration)
            var sum = 0.0
            for (logp in logps) sum += logp
            var probs = 0.0
            for (logp in logps) probs += exp(logp)
            WordScore(
                word = word,
                logp = sum,
                prob = probs / logps.size.toDouble(),
                minLogp = SwiftMath.minOrNull(logps) ?: 0.0,
                top = SwiftString.trimmingWhitespacesAndNewlines(firstScored.top),
                topLogp = firstScored.topLogp,
                start = start,
                end = SwiftMath.max(end, start + 0.02),
                tokens = group.scored.toList(),
            )
        }
    }

    /**
     * Whisper spells " țări" as " " + "ț" + "ă" + "ri". Taking that lone space for punctuation
     * would fold the word into the one before it, and likewise a diacritic split across two
     * tokens, whose halves decode to U+FFFD.
     */
    private fun isPunctuation(text: String): Boolean =
        !SpeechText.hasWordCharacter(text) && text.indexOf('\uFFFD') < 0 && !SwiftString.characters(text).all(SwiftString::isWhitespace)

    /** Splits long audio into windows of at most 30 s, cutting at the quietest 100 ms between 22 and 29 s. */
    fun windows(samples: FloatArray, sampleRate: Int = 16_000): List<SampleRange> {
        val maxSamples = 30 * sampleRate
        val out = mutableListOf<SampleRange>()
        var a = 0
        while (samples.size - a > maxSamples) {
            val low = a + 22 * sampleRate
            val high = a + 29 * sampleRate
            val span = sampleRate / 10
            var best = high
            var bestEnergy = Float.POSITIVE_INFINITY
            var s = low
            while (s + span <= high) {
                var energy = 0f
                for (i in s until s + span) energy += samples[i] * samples[i]
                if (energy < bestEnergy) {
                    bestEnergy = energy
                    best = s + span / 2
                }
                s += span / 2
            }
            out.add(SampleRange(a, best))
            a = best
        }
        out.add(SampleRange(a, samples.size))
        return out
    }
}
