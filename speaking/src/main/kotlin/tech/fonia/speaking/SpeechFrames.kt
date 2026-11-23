package tech.fonia.speaking

import tech.fonia.dsp.ClipSummary
import tech.fonia.dsp.VoiceGroup
import tech.fonia.dsp.analyzeClip
import tech.fonia.dsp.resample
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Frame features of one take at a 10 ms hop, computed once and shared by the detectors, the prosodic transcript and
 * the cut planner. Frame `i` is centred on `i * hop` seconds.
 *
 * Built on the existing clip analysis (`tech.fonia.dsp.analyzeClip`: YIN pitch track, syllable nuclei, levels) and
 * adds what it lacks for hesitations: band energies and their frame-to-frame change (spectral steadiness), a
 * room-tone floor with a speech mask, and two cosmetic cues for the label ("mmm" vs "ăăă" vs "îîî").
 */
class SpeechFrames internal constructor(
    val hop: Double,
    val count: Int,
    val db: FloatArray,
    /** Shape change of the log band energies against the frame 20 ms earlier, dB (level change removed). */
    val change: FloatArray,
    /** F0 in Hz, 0 when unvoiced. */
    val hz: FloatArray,
    val speech: BooleanArray,
    /** Speech before the hangover: the frame itself is above the gate. Boundaries (pauses) are measured on this. */
    val active: BooleanArray,
    /** Energy above 1 kHz relative to below, dB. */
    val highLowDb: FloatArray,
    /** Strongest low-frequency peak (250–900 Hz), a rough F1. */
    val f1Hz: FloatArray,
    val floorDb: Float,
    val speechMedianDb: Float,
    val durationSeconds: Double,
    /** The dsp summary (syllable nuclei, pitch statistics) the frames were built with. */
    val summary: ClipSummary,
) {
    fun time(i: Int): Double = i * hop

    fun index(t: Double): Int = (t / hop).roundToInt().coerceIn(0, max(0, count - 1))

    /** Fraction of frames in [start, end) that are not speech. */
    fun silentShare(start: Double, end: Double): Double {
        val a = index(start)
        val b = max(a + 1, index(end))
        var silent = 0
        for (i in a until min(b, count)) if (!speech[i]) silent++
        return silent.toDouble() / max(1, min(b, count) - a)
    }

    /** The run of inactive frames (no hangover) that ends within [reach] seconds before [t], as start..end seconds, or null. */
    fun silenceBefore(t: Double, reach: Double): ClosedFloatingPointRange<Double>? {
        var i = index(t) - 1
        val stop = index(t - reach)
        while (i >= stop && i >= 0 && active[i]) i--
        if (i < stop || i < 0) return null
        var j = i
        while (j > 0 && !active[j - 1]) j--
        return time(j)..time(i + 1)
    }

    /** The run of inactive frames (no hangover) that starts within [reach] seconds after [t], as start..end seconds, or null. */
    fun silenceAfter(t: Double, reach: Double): ClosedFloatingPointRange<Double>? {
        var i = index(t)
        val stop = index(t + reach)
        while (i <= stop && i < count && active[i]) i++
        if (i > stop || i >= count) return null
        var j = i
        while (j < count - 1 && !active[j + 1]) j++
        return time(i)..time(j + 1)
    }

    companion object {
        fun compute(samples: FloatArray, sampleRate: Double, t: SpeakingThresholds = SpeakingThresholds.v1, group: VoiceGroup = VoiceGroup.FEMALE): SpeechFrames {
            val a = t.analysis
            val rate = a.analysisRate
            val x = if (sampleRate == rate) samples else resample(samples, sampleRate, rate)
            val duration = samples.size / sampleRate
            val summary = analyzeClip(x, rate, group, null)
            val hopN = (a.hopSeconds * rate).roundToInt()
            val n = max(1, (x.size + hopN - 1) / hopN)
            val fftN = nextPow2((a.windowSeconds * rate).roundToInt())
            val win = FloatArray(fftN) { (0.5 - 0.5 * cos(2 * PI * it / (fftN - 1))).toFloat() }
            val mel = melFilters(a.bands, a.bandLowHz, a.bandHighHz, rate, fftN)
            val cep = a.cepstra.coerceIn(1, a.bands - 1)
            val oneKBin = (1000.0 * fftN / rate).roundToInt()
            val f1Lo = (250.0 * fftN / rate).roundToInt()
            val f1Hi = (900.0 * fftN / rate).roundToInt()

            val db = FloatArray(n)
            val ceps = Array(n) { FloatArray(cep) }
            val logMel = DoubleArray(a.bands)
            val highLow = FloatArray(n)
            val f1 = FloatArray(n)
            val re = DoubleArray(fftN)
            val im = DoubleArray(fftN)
            val power = DoubleArray(fftN / 2 + 1)
            for (i in 0 until n) {
                val centre = i * hopN
                var energy = 0.0
                for (k in 0 until fftN) {
                    val s = centre - fftN / 2 + k
                    val v = if (s in x.indices) x[s].toDouble() else 0.0
                    energy += v * v
                    re[k] = v * win[k]
                    im[k] = 0.0
                }
                db[i] = (10 * log10(energy / fftN + 1e-12)).toFloat()
                fft(re, im)
                for (k in power.indices) power[k] = re[k] * re[k] + im[k] * im[k]
                for (b in 0 until a.bands) {
                    var e = 0.0
                    val w = mel[b]
                    for (k in w.indices) e += w[k] * power[k]
                    logMel[b] = 10 * log10(e + 1e-10)
                }
                // DCT-II (orthonormal) of the log mel energies: c1…cN describe the spectral envelope without the
                // harmonics, so a pitch glide does not look like a change of sound.
                for (c in 1..cep) {
                    var v = 0.0
                    for (b in 0 until a.bands) v += logMel[b] * cos(PI * c * (b + 0.5) / a.bands)
                    ceps[i][c - 1] = (v * sqrt(2.0 / a.bands)).toFloat()
                }
                var lo = 0.0
                var hi = 0.0
                for (k in 1 until power.size) if (k < oneKBin) lo += power[k] else hi += power[k]
                highLow[i] = (10 * log10((hi + 1e-12) / (lo + 1e-12))).toFloat()
                var best = f1Lo
                var bestV = -1.0
                for (k in f1Lo..min(f1Hi, power.size - 2)) {
                    val v = power[k - 1] + 2 * power[k] + power[k + 1]
                    if (v > bestV) { bestV = v; best = k }
                }
                f1[i] = (best * rate / fftN).toFloat()
            }

            // Envelope change against 20 ms earlier, as an RMS over the mel bands (dB), level (c0) excluded.
            val change = FloatArray(n)
            for (i in 2 until n) {
                var sum = 0.0
                for (c in 0 until cep) {
                    val d = ceps[i][c] - ceps[i - 2][c]
                    sum += d * d
                }
                change[i] = sqrt(sum / a.bands).toFloat()
            }
            if (n > 2) { change[0] = change[2]; change[1] = change[2] }

            // Pitch: the dsp track, nearest point in time.
            val hz = FloatArray(n)
            val track = summary.track
            if (track.isNotEmpty()) {
                var p = 0
                for (i in 0 until n) {
                    val time = i * a.hopSeconds
                    while (p < track.size - 1 && kotlin.math.abs(track[p + 1].time - time) <= kotlin.math.abs(track[p].time - time)) p++
                    val tp = track[p]
                    hz[i] = if (kotlin.math.abs(tp.time - time) <= a.hopSeconds) (if (tp.hz > 0f) tp.hz else tp.melodyHz) else 0f
                }
            }

            val sorted = db.sortedArray()
            val floor = max(-100f, percentile(sorted, a.floorPercentile))
            // Speech: clearly above the room tone, and not absurdly far below the loud parts (a pitch tracker finds
            // "voice" in hum, so voicing alone never makes a frame speech).
            val loud = percentile(sorted, 0.95)
            val gate = max(floor + a.speechMarginDb.toFloat(), loud - a.speechRangeDb.toFloat())
            val raw = BooleanArray(n) { db[it] > gate }
            val speech = raw.copyOf()
            // Hangover both ways, then drop blips.
            val hang = (a.speechHangSeconds / a.hopSeconds).roundToInt()
            for (i in 0 until n) if (raw[i]) for (k in max(0, i - hang)..min(n - 1, i + hang)) speech[k] = true
            val minRun = (a.minSpeechRunSeconds / a.hopSeconds).roundToInt() + 2 * hang
            var i = 0
            while (i < n) {
                if (!speech[i]) { i++; continue }
                var j = i
                while (j < n && speech[j]) j++
                if (j - i < minRun) for (k in i until j) speech[k] = false
                i = j
            }
            val speechDb = (0 until n).filter { speech[it] }.map { db[it] }.sorted()
            val median = if (speechDb.isEmpty()) floor else speechDb[speechDb.size / 2]
            return SpeechFrames(a.hopSeconds, n, db, change, hz, speech, raw, highLow, f1, floor, median, duration, summary)
        }

        internal fun percentile(sorted: FloatArray, p: Double): Float =
            if (sorted.isEmpty()) 0f else sorted[((sorted.size - 1) * p).roundToInt().coerceIn(0, sorted.size - 1)]

        private fun nextPow2(v: Int): Int {
            var p = 1
            while (p < v) p = p shl 1
            return p
        }

        /** Triangular mel filters over the power spectrum bins. */
        private fun melFilters(bands: Int, lowHz: Double, highHz: Double, rate: Double, fftN: Int): Array<DoubleArray> {
            fun mel(f: Double) = 2595 * log10(1 + f / 700)
            fun hz(m: Double) = 700 * (Math.pow(10.0, m / 2595) - 1)
            val m0 = mel(lowHz)
            val m1 = mel(highHz)
            val centres = DoubleArray(bands + 2) { hz(m0 + (m1 - m0) * it / (bands + 1)) * fftN / rate }
            return Array(bands) { b ->
                val l = centres[b]
                val c = centres[b + 1]
                val r = centres[b + 2]
                DoubleArray(fftN / 2 + 1) { k ->
                    when {
                        k <= l || k >= r -> 0.0
                        k <= c -> (k - l) / max(1e-9, c - l)
                        else -> (r - k) / max(1e-9, r - c)
                    }
                }
            }
        }

        /** In-place iterative radix-2 FFT. */
        internal fun fft(re: DoubleArray, im: DoubleArray) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
                j = j xor bit
                if (i < j) {
                    var t = re[i]; re[i] = re[j]; re[j] = t
                    t = im[i]; im[i] = im[j]; im[j] = t
                }
            }
            var len = 2
            while (len <= n) {
                val ang = -2 * PI / len
                val wr = cos(ang)
                val wi = sin(ang)
                var s = 0
                while (s < n) {
                    var cr = 1.0
                    var ci = 0.0
                    for (k in 0 until len / 2) {
                        val ar = re[s + k + len / 2] * cr - im[s + k + len / 2] * ci
                        val ai = re[s + k + len / 2] * ci + im[s + k + len / 2] * cr
                        re[s + k + len / 2] = re[s + k] - ar
                        im[s + k + len / 2] = im[s + k] - ai
                        re[s + k] += ar
                        im[s + k] += ai
                        val ncr = cr * wr - ci * wi
                        ci = cr * wi + ci * wr
                        cr = ncr
                    }
                    s += len
                }
                len = len shl 1
            }
        }
    }
}
