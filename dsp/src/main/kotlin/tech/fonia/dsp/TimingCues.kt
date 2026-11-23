package tech.fonia.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

// E2 feature 6 (docs/v2/03 §4): closure, burst, rise and duration of frication on a 1 ms envelope, plus the
// sonorant dip used at R and L. Additive; pure; runs after the take.

/** Second-order RBJ filter, Direct Form I, in Double. */
class Biquad private constructor(
    private val b0: Double, private val b1: Double, private val b2: Double,
    private val a1: Double, private val a2: Double,
) {
    fun run(x: FloatArray): FloatArray {
        val y = FloatArray(x.size)
        var x1 = 0.0
        var x2 = 0.0
        var y1 = 0.0
        var y2 = 0.0
        for (i in x.indices) {
            val v = b0 * x[i] + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = x[i].toDouble()
            y2 = y1
            y1 = v
            y[i] = v.toFloat()
        }
        return y
    }

    companion object {
        private fun make(kind: Int, hz: Double, rate: Double, q: Double): Biquad {
            val w = 2 * PI * (hz / rate).coerceIn(1e-5, 0.4999)
            val alpha = sin(w) / (2 * q)
            val c = cos(w)
            val a0 = 1 + alpha
            return if (kind == 0) {
                Biquad((1 - c) / 2 / a0, (1 - c) / a0, (1 - c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
            } else {
                Biquad((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
            }
        }

        fun lowpass(hz: Double, rate: Double, q: Double = 0.7071): Biquad = make(0, hz, rate, q)

        fun highpass(hz: Double, rate: Double, q: Double = 0.7071): Biquad = make(1, hz, rate, q)
    }
}

/** RMS in dB over a Hann window of [windowSeconds], every [hopSeconds]; value i is centred on `i·hop`. */
class FineEnvelope(val db: FloatArray, val hopSeconds: Double) {
    fun index(seconds: Double): Int = ((seconds / hopSeconds) + 0.5).toInt().coerceIn(0, kotlin.math.max(0, db.size - 1))

    fun time(index: Int): Double = index * hopSeconds

    /** The 10th percentile: the floor of this band over the clip. */
    fun floorDb(): Float {
        if (db.isEmpty()) return -120f
        val sorted = db.copyOf().also { it.sort() }
        return sorted[(sorted.size - 1) / 10]
    }
}

fun fineEnvelope(
    pcm: FloatArray, sampleRate: Double, hopSeconds: Double = 0.001, windowSeconds: Double = 0.005,
): FineEnvelope {
    val hop = kotlin.math.max(1, (hopSeconds * sampleRate + 0.5).toInt())
    val half = kotlin.math.max(1, (windowSeconds * sampleRate / 2 + 0.5).toInt())
    val window = DoubleArray(2 * half + 1) { 0.5 - 0.5 * cos(2 * PI * (it + 0.5) / (2 * half + 1)) }
    val wsum = window.sum()
    val count = pcm.size / hop + 1
    val out = FloatArray(count)
    for (i in 0 until count) {
        val centre = i * hop
        var e = 0.0
        for (j in -half..half) {
            val k = centre + j
            if (k < 0 || k >= pcm.size) continue
            val v = pcm[k].toDouble()
            e += window[j + half] * v * v
        }
        out[i] = kotlin.math.max(-120.0, 10.0 * log10(e / wsum + 1e-14)).toFloat()
    }
    return FineEnvelope(out, hop / sampleRate)
}

/** The three envelopes the timing cues read: broadband above 700 Hz, frication above 2 kHz, voice below 1 kHz. */
class TimingEnvelopes(val mid: FineEnvelope, val high: FineEnvelope, val low: FineEnvelope) {
    val midFloorDb = mid.floorDb()
    val highFloorDb = high.floorDb()
    val lowFloorDb = low.floorDb()
    /** The 99th percentile of the > 700 Hz envelope: the loud part of the take. */
    val midPeakDb: Float = if (mid.db.isEmpty()) -120f else mid.db.copyOf().also { it.sort() }[(mid.db.size - 1) * 99 / 100]

    companion object {
        fun of(pcm: FloatArray, sampleRate: Double): TimingEnvelopes {
            val mid = Biquad.highpass(700.0, sampleRate).run(pcm)
            val high = Biquad.highpass(2000.0, sampleRate).run(Biquad.highpass(2000.0, sampleRate).run(pcm))
            val low = Biquad.lowpass(1000.0, sampleRate).run(Biquad.lowpass(1000.0, sampleRate).run(pcm))
            return TimingEnvelopes(fineEnvelope(mid, sampleRate), fineEnvelope(high, sampleRate), fineEnvelope(low, sampleRate))
        }
    }
}

/** Feature 6. NaN = not measurable. */
data class AffricateCues(
    /** The longest stretch under the floor + margin in the lookback before the frication (closure). */
    val gapMs: Float,
    /** False when the stretch reaches the start of the lookback with nothing audible before it (word-initial). */
    val gapValid: Boolean,
    /** Mean low-band (< 1 kHz) level over the gap, above the low floor: the voice bar of a voiced stop. */
    val voiceBarDb: Float,
    /** The largest 5 ms rise of the > 2 kHz envelope near the frication onset, ending where that band dominates. */
    val burstDb: Float,
    /** Refined frication onset and end, seconds. */
    val startSeconds: Double = Double.NaN,
    val endSeconds: Double = Double.NaN,
    /** 10 % → 90 % of the frication envelope peak (linear amplitude), ms. */
    val riseMs: Float,
    /** Stretch around the peak where frication (> 2 kHz) stays within 15 dB of its peak and over the voice band (voiced:
     *  within 12 dB of the peak, inside the coarse span), ms. */
    val fricDurMs: Float,
)

fun affricateCues(
    env: TimingEnvelopes,
    fricStart: Double,
    fricEnd: Double,
    voiced: Boolean = false,
    lookbackSeconds: Double = 0.15,
    gapMarginDb: Float = 6f,
): AffricateCues {
    val mid = env.mid
    val high = env.high
    val low = env.low
    val s0 = high.index(fricStart)
    val e0 = kotlin.math.max(s0 + 1, high.index(fricEnd))
    // Duration first: the frication is the stretch around the strongest value of [fricStart, fricEnd] where the > 2 kHz
    // band stays within 15 dB of it and above the < 1 kHz band (12 dB under it allowed when voiced). Closure, burst and
    // rise are then measured from its refined onset, not from the coarse 10 ms detector frames.
    val margin = if (voiced) -12f else 0f
    // The strongest frication value: only where the > 2 kHz band dominates, so a loud vowel edge inside the coarse span
    // cannot pose as the peak. With no such value, the coarse span stands.
    var peakIndex = -1
    for (i in s0..kotlin.math.min(e0, high.db.size - 1)) {
        if (high.db[i] - low.db[i] > margin && (peakIndex < 0 || high.db[i] > high.db[peakIndex])) peakIndex = i
    }
    val coarse = peakIndex < 0
    if (coarse) peakIndex = (s0 + e0) / 2
    val peakDb = high.db[peakIndex]
    // A voiced fricative keeps a strong voice bar, so for it the stretch is only "within 12 dB of the peak", inside the
    // coarse span; unvoiced frication must also stay above the voice band.
    fun fricative(i: Int): Boolean =
        if (voiced) high.db[i] > peakDb - 12f && i >= s0 && i <= e0 else high.db[i] > peakDb - 15f && high.db[i] - low.db[i] > margin
    var a = if (coarse) s0 else peakIndex
    while (!coarse && a > 0 && fricative(a - 1)) a--
    var b = if (coarse) kotlin.math.min(e0, high.db.size - 1) else peakIndex
    while (!coarse && b < high.db.size - 1 && fricative(b + 1)) b++
    val dur = ((b - a + 1) * high.hopSeconds * 1000).toFloat()
    val s = a
    // Closure: longest run under floor + margin in the lookback. Voiced stops keep a voice bar under 1 kHz, which the
    // > 700 Hz envelope ignores; the bar is reported separately.
    val lookFrom = kotlin.math.max(0, s - (lookbackSeconds / mid.hopSeconds).toInt())
    // Floor + margin (plan 03 §4), but never more than 40 dB under the take's loud part: on clean synthetic or coded
    // audio the floor sits near digital silence and a real closure would never reach it.
    val limit = kotlin.math.max(env.midFloorDb + gapMarginDb, env.midPeakDb - 40f)
    var best = 0
    var bestStart = -1
    var run = 0
    for (i in lookFrom until s) {
        if (mid.db[i] < limit) {
            run++
            if (run > best) {
                best = run
                bestStart = i - run + 1
            }
        } else {
            run = 0
        }
    }
    // Valid only with something audible before the closure, inside the lookback or just before it.
    var audibleBefore = false
    if (bestStart >= 0) {
        val from = kotlin.math.max(0, bestStart - (0.1 / mid.hopSeconds).toInt())
        for (i in from until bestStart) if (mid.db[i] > env.midFloorDb + 15f) audibleBefore = true
    }
    val gapValid = best == 0 || audibleBefore
    var bar = Float.NaN
    if (best > 0) {
        var sum = 0.0
        for (i in bestStart until bestStart + best) sum += low.db[i] - env.lowFloorDb
        bar = (sum / best).toFloat()
    }
    // Burst: the largest 5 ms rise of the high envelope from 30 ms before to 20 ms after the onset, ending on a value
    // where the > 2 kHz band dominates (a vowel onset rises too, but in the voice band).
    val step = kotlin.math.max(1, (0.005 / high.hopSeconds).toInt())
    var burst = 0f
    val bFrom = kotlin.math.max(0, s - (0.030 / high.hopSeconds).toInt())
    val bTo = kotlin.math.min(high.db.size - 1 - step, s + (0.020 / high.hopSeconds).toInt())
    for (i in bFrom..bTo) {
        if (high.db[i + step] - low.db[i + step] <= margin) continue
        burst = kotlin.math.max(burst, high.db[i + step] - high.db[i])
    }
    // Rise: the plateau is the upper quartile of the frication (a single loud frame would push a "peak" past it).
    // Scan back from the first value at the plateau to the last under 10 % of it, then forward to 90 %.
    val fricEndIndex = b + 1
    val plateau = high.db.copyOfRange(s, kotlin.math.max(s + 1, fricEndIndex)).also { it.sort() }.let { it[it.size * 3 / 4] }
    val db10 = plateau - 20f
    val db90 = plateau + (20f * log10(0.9f))
    var reach = s
    while (reach < fricEndIndex - 1 && high.db[reach] < plateau - 1f) reach++
    var i10 = reach
    val riseFloor = kotlin.math.max(0, s - (0.06 / high.hopSeconds).toInt())
    while (i10 > riseFloor && high.db[i10] >= db10) i10--
    var i90 = i10
    while (i90 < reach && high.db[i90] < db90) i90++
    val rise = ((i90 - i10) * high.hopSeconds * 1000).toFloat()
    // The envelope crosses the limit only once its 5 ms window has left the louder neighbours, so a bounded run is
    // one window plus one hop shorter than the closure (checked on synthetic gaps of 15–60 ms).
    val windowHops = if (best > 0) (0.005 / mid.hopSeconds).toInt() + 1 else 0
    return AffricateCues(
        gapMs = ((best + windowHops) * mid.hopSeconds * 1000).toFloat(), gapValid = gapValid, voiceBarDb = bar,
        burstDb = burst, startSeconds = high.time(a), endSeconds = high.time(b), riseMs = rise, fricDurMs = dur,
    )
}

/**
 * Frication heard before the voice starts at the onset of a take, ms: from the first value where the > 2 kHz band rises
 * 12 dB over its floor to the first where the < 1 kHz band does. Word-initial S gives its length, T its burst and
 * aspiration (about 10–30 ms), a vowel-initial word about 0. Searched from [from] to [to] seconds.
 */
fun onsetFricationMs(env: TimingEnvelopes, from: Double, to: Double): Float {
    val h = env.high
    val l = env.low
    val a = h.index(kotlin.math.max(0.0, from))
    val b = h.index(to)
    var voice = -1
    for (i in a..b) if (l.db[i] > env.lowFloorDb + 15f && l.db[i] >= h.db[i]) { voice = i; break }
    if (voice < 0) return Float.NaN
    // Walk back from the voice onset while the high band stays audible.
    var i = voice
    while (i > a && h.db[i - 1] > env.highFloorDb + 12f && h.db[i - 1] - l.db[i - 1] > -6f) i--
    return ((voice - i) * h.hopSeconds * 1000).toFloat()
}

/** The amplitude dip between two vowel nuclei: a tap is deep and narrow, an L shallow and wide, a vowel or glide flat. */
data class DipCues(
    /** Mean of the two nuclei minus the minimum between them, dB. */
    val depthDb: Float,
    /** Time the level stays more than 3 dB under the lower nucleus, ms. */
    val widthMs: Float,
)

fun dipBetween(env: TimingEnvelopes, firstNucleus: Double, secondNucleus: Double): DipCues {
    val m = env.mid
    val a = m.index(firstNucleus)
    val b = m.index(secondNucleus)
    if (b - a < 5) return DipCues(Float.NaN, Float.NaN)
    // Smooth over 7 ms so the pitch-period ripple of the envelope does not read as a dip.
    val smooth = FloatArray(b - a + 1) { k ->
        var sum = 0f
        var n = 0
        for (j in -3..3) {
            val i = a + k + j
            if (i in m.db.indices) { sum += m.db[i]; n++ }
        }
        sum / n
    }
    fun median(x: List<Float>): Float = x.sorted()[x.size / 2]
    val quarter = kotlin.math.max(1, smooth.size / 4)
    val left = median(smooth.take(quarter))
    val right = median(smooth.takeLast(quarter))
    val minimum = smooth.min()
    val lower = kotlin.math.min(left, right)
    val width = smooth.count { it < lower - 3f }
    return DipCues((left + right) / 2 - minimum, (width * m.hopSeconds * 1000).toFloat())
}
