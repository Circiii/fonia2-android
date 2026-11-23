package tech.fonia.phones

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 48 kHz → 16 kHz for ZIPA, the capture rate's exact third: a linear-phase low-pass FIR (Kaiser-windowed sinc, 241
 * taps, cut-off 7.8 kHz, about 80 dB of stopband from 8.3 kHz) evaluated only at every third sample. What aliases
 * (8.0–8.3 kHz folding to 7.7–8.0 kHz) lands above ZIPA's top mel filter (7.6 kHz). Zero delay: output k sits at input
 * 3k, the clip treated as silent outside its ends, `ceil(n / 3)` samples, as `SpeechResampler`.
 *
 * Why not `SpeechResampler`: its generic interpolating kernel cost 95 ms per 1.2 s take on the emulator, two thirds of
 * the full head's time; this one costs a few ms. Thread-safe (immutable taps).
 */
object Decimator3 {
    private const val TAPS = 241
    private const val HALF = TAPS / 2
    private const val CUTOFF_HZ = 7_800.0
    private const val RATE = 48_000.0
    private const val BETA = 8.0

    private val taps: FloatArray = run {
        val fc = CUTOFF_HZ / RATE
        val raw = DoubleArray(TAPS) { i ->
            val n = (i - HALF).toDouble()
            val sinc = if (n == 0.0) 2 * fc else sin(2 * PI * fc * n) / (PI * n)
            val r = n / HALF
            sinc * besselI0(BETA * sqrt(maxOf(0.0, 1 - r * r))) / besselI0(BETA)
        }
        val sum = raw.sum()
        FloatArray(TAPS) { (raw[it] / sum).toFloat() }
    }

    fun decimate(samples: FloatArray): FloatArray {
        val n = samples.size
        val out = FloatArray((n + 2) / 3)
        for (k in out.indices) {
            val centre = 3 * k
            var acc = 0f
            val first = maxOf(0, HALF - centre)
            val last = minOf(TAPS - 1, n - 1 - centre + HALF)
            for (j in first..last) acc += taps[j] * samples[centre - HALF + j]
            out[k] = acc
        }
        return out
    }

    private fun besselI0(x: Double): Double {
        var sum = 1.0
        var term = 1.0
        val q = x * x / 4
        var k = 1
        while (term > 1e-12 * sum) {
            term *= q / (k.toDouble() * k)
            sum += term
            k++
        }
        return sum
    }
}
