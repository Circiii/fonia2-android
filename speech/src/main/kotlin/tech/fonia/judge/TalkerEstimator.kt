package tech.fonia.judge

import tech.fonia.live.Clip
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/** One take's voice pitch, from several estimators, and the talker group it implies (null: no usable voice). */
data class TalkerEstimate(
    /** The chosen F0, Hz (NaN when the take has no usable voice). */
    val f0Hz: Float,
    val group: TalkerGroup?,
    /** 0..1: the share of voiced frames whose own F0 agrees (±12 %) with [f0Hz]. */
    val confidence: Float,
    val voicedFrames: Int,
    /** Each estimator's answer, Hz (NaN = no answer): "yin", "dsp", "yinCorrected"; for the trace. */
    val estimators: Map<String, Float>,
    /** What was decided about octave errors ("dspHalved", "dspDoubled", "agree", "yinOnly", "dspOnly"). */
    val note: String,
)

/**
 * A pitch estimate for the talker group that does not fall for octave errors (owner 2026-11-24: the DSP tracker
 * sometimes halves a woman's F0, turning her into a man, and the S/Ș boundary with her).
 *
 * Two estimators: a YIN-style cumulative-mean-normalised difference on 40 ms frames of the take decimated to about
 * 12 kHz, taking the FIRST dip under the threshold (which by construction prefers the shortest period, i.e. it does not
 * halve), and the DSP tracker's median when the caller has it ([estimate] `dspMedianHz`). Frames whose F0 sits an
 * octave below the take's YIN median are folded up (a creaky or period-doubled frame), then:
 *  - DSP within ±15 % of YIN → their geometric mean ("agree");
 *  - DSP ≈ YIN / 2 (0.42–0.6) → YIN ("dspHalved"); DSP ≈ 2 × YIN → YIN unless YIN's frames are mostly unsure;
 *  - two octaves apart (one halved, one doubled) → their geometric mean ("twoOctaves");
 *  - only one answers → that one.
 * Pure Kotlin, ≈ 10–25 ms for a 1.5 s take on a PC. Thread-safe.
 */
object TalkerEstimator {
    const val MIN_HZ = 60f
    const val MAX_HZ = 600f
    private const val FRAME_S = 0.040
    private const val HOP_S = 0.010
    private const val THRESHOLD = 0.15f
    private const val LOOSE = 0.30f

    /** [dspMedianHz]: the DSP tracker's median for the same take, if the caller has it (`Measured.global[cap.f0MedianHz]`). */
    fun estimate(clip: Clip, dspMedianHz: Float = Float.NaN): TalkerEstimate =
        estimate(clip.samples, clip.sampleRate, dspMedianHz)

    fun estimate(samples: FloatArray, sampleRate: Double, dspMedianHz: Float = Float.NaN): TalkerEstimate {
        val frames = yinFrames(samples, sampleRate)
        val yinMedian = median(frames)
        // Fold frames an octave under the take's median up (creak, period doubling) and take the median again.
        val folded = if (yinMedian.isNaN()) frames else FloatArray(frames.size) { i ->
            val f = frames[i]
            if (f / yinMedian in 0.42f..0.6f) f * 2f else f
        }
        val yin = median(folded)
        val dsp = if (dspMedianHz.isNaN() || dspMedianHz < MIN_HZ || dspMedianHz > MAX_HZ) Float.NaN else dspMedianHz
        val (chosen, note) = when {
            yin.isNaN() && dsp.isNaN() -> Float.NaN to "none"
            yin.isNaN() -> dsp to "dspOnly"
            dsp.isNaN() -> yin to "yinOnly"
            abs(ln(dsp / yin)) <= ln(1.15f) -> kotlin.math.sqrt(dsp * yin) to "agree"
            dsp / yin in 0.42f..0.6f -> yin to "dspHalved"
            dsp / yin in 1.7f..2.4f -> yin to "dspDoubled"
            // Two octaves apart: one halved and the other doubled the same voice; the truth sits between.
            dsp / yin in 0.2f..0.3f || dsp / yin in 3.4f..5f -> kotlin.math.sqrt(dsp * yin) to "twoOctaves"
            else -> yin to "disagree"
        }
        val agree = if (chosen.isNaN() || folded.isEmpty()) 0f else folded.count { abs(ln(it / chosen)) <= ln(1.12f) }.toFloat() / folded.size
        return TalkerEstimate(
            f0Hz = chosen,
            group = if (chosen.isNaN() || folded.size < MIN_FRAMES && dsp.isNaN()) null else TalkerVoice.groupOf(chosen),
            confidence = agree,
            voicedFrames = folded.size,
            estimators = mapOf("yin" to yinMedian, "yinCorrected" to yin, "dsp" to dsp),
            note = note,
        )
    }

    private const val MIN_FRAMES = 3

    /** Per voiced frame F0 (Hz) by YIN's first dip; frames quieter than the loudest −30 dB are skipped. */
    internal fun yinFrames(samples: FloatArray, sampleRate: Double): FloatArray {
        val factor = max(1, (sampleRate / 12000.0).toInt())
        val rate = sampleRate / factor
        val x = decimate(samples, factor)
        val n = (FRAME_S * rate).toInt()
        val hop = (HOP_S * rate).toInt().coerceAtLeast(1)
        val minLag = (rate / MAX_HZ).toInt().coerceAtLeast(2)
        val maxLag = (rate / MIN_HZ).toInt()
        if (x.size < n + maxLag || n <= 0) return FloatArray(0)
        val count = (x.size - n - maxLag) / hop + 1
        val energy = FloatArray(count) { k ->
            var e = 0.0
            val a = k * hop
            for (j in a until a + n) e += x[j].toDouble() * x[j]
            (10 * log10(e / n + 1e-14)).toFloat()
        }
        val loud = energy.maxOrNull() ?: return FloatArray(0)
        val out = ArrayList<Float>()
        val d = FloatArray(maxLag + 2)
        for (k in 0 until count) {
            if (energy[k] < loud - 30f) continue
            val a = k * hop
            d[0] = 0f
            var running = 0.0
            var pick = -1
            var bestLag = -1
            var best = Float.MAX_VALUE
            val cm = FloatArray(maxLag + 2)
            for (tau in 1..maxLag + 1) {
                var s = 0.0
                for (j in a until a + n) {
                    val diff = x[j] - x[j + tau]
                    s += diff * diff
                }
                d[tau] = s.toFloat()
                running += s
                cm[tau] = if (running > 0) (d[tau] * tau / running).toFloat() else 1f
            }
            for (tau in minLag..maxLag) {
                if (cm[tau] < best) { best = cm[tau]; bestLag = tau }
                if (pick < 0 && cm[tau] < THRESHOLD) {
                    // Walk down to the bottom of this dip.
                    var t = tau
                    while (t + 1 <= maxLag && cm[t + 1] < cm[t]) t++
                    pick = t
                }
            }
            val lag = if (pick > 0) pick else if (best < LOOSE) bestLag else -1
            if (lag <= 0) continue
            // Parabolic refinement.
            val l = lag.toDouble() + if (lag in 2 until maxLag) {
                val y0 = cm[lag - 1]; val y1 = cm[lag]; val y2 = cm[lag + 1]
                val den = y0 - 2 * y1 + y2
                if (den != 0f) (0.5 * (y0 - y2) / den).coerceIn(-0.5, 0.5) else 0.0
            } else 0.0
            val f = (rate / l).toFloat()
            if (f in MIN_HZ..MAX_HZ) out += f
        }
        return out.toFloatArray()
    }

    private fun decimate(x: FloatArray, factor: Int): FloatArray {
        if (factor <= 1) return x
        val m = x.size / factor
        return FloatArray(m) { i ->
            var s = 0f
            val a = i * factor
            for (j in a until min(x.size, a + factor)) s += x[j]
            s / factor
        }
    }

    private fun median(v: FloatArray): Float {
        if (v.isEmpty()) return Float.NaN
        val s = v.sortedArray()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2f
    }
}
