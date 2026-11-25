package tech.fonia.words

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The features the Romanian word model was trained on (NeMo `AudioToMelSpectrogramPreprocessor`, checked against it to
 * 1e-4): 16 kHz, pre-emphasis 0.97, 25 ms Hann window every 10 ms in a 512-point FFT, zero padding at the edges,
 * 80 Slaney mel bands to 8 kHz, log(power + 2^-24), each band normalised over the clip (mean, standard deviation with
 * N − 1). The output is band after band (`[band][frame]`), the layout the model takes.
 */
object RoAsrFeatures {
    const val RATE = 16_000
    const val BANDS = 80
    private const val FFT = 512
    private const val WINDOW = 400
    private const val HOP = 160
    private const val PRE = 0.97
    private val GUARD = Math.pow(2.0, -24.0)

    private val window = DoubleArray(FFT).also { w ->
        val offset = (FFT - WINDOW) / 2
        for (n in 0 until WINDOW) w[offset + n] = 0.5 - 0.5 * cos(2 * PI * n / (WINDOW - 1))
    }
    private val mel: Array<DoubleArray> = melBank()

    fun frameCount(samples: Int): Int = samples / HOP

    /** [out] gets `BANDS × frames` values, band after band; returns the frame count. */
    fun compute(samples16k: FloatArray, out: FloatArray): Int {
        val frames = frameCount(samples16k.size)
        require(out.size >= frames * BANDS)
        if (frames == 0) return 0
        val n = samples16k.size
        val x = DoubleArray(n)
        x[0] = samples16k[0].toDouble()
        for (i in 1 until n) x[i] = samples16k[i] - PRE * samples16k[i - 1]
        val fft = Fft(FFT)
        val re = DoubleArray(FFT)
        val im = DoubleArray(FFT)
        val power = DoubleArray(FFT / 2 + 1)
        val logMel = DoubleArray(BANDS * frames)
        for (t in 0 until frames) {
            val start = t * HOP - FFT / 2
            for (k in 0 until FFT) {
                val i = start + k
                re[k] = if (i in 0 until n) x[i] * window[k] else 0.0
                im[k] = 0.0
            }
            fft.transform(re, im)
            for (k in power.indices) power[k] = re[k] * re[k] + im[k] * im[k]
            for (b in 0 until BANDS) {
                val weights = mel[b]
                var sum = 0.0
                for (k in power.indices) {
                    val w = weights[k]
                    if (w != 0.0) sum += w * power[k]
                }
                logMel[b * frames + t] = ln(sum + GUARD)
            }
        }
        for (b in 0 until BANDS) {
            var mean = 0.0
            for (t in 0 until frames) mean += logMel[b * frames + t]
            mean /= frames
            var variance = 0.0
            for (t in 0 until frames) {
                val d = logMel[b * frames + t] - mean
                variance += d * d
            }
            val std = sqrt(variance / max(1, frames - 1)) + 1e-5
            for (t in 0 until frames) out[b * frames + t] = ((logMel[b * frames + t] - mean) / std).toFloat()
        }
        return frames
    }

    /** librosa's `filters.mel(norm="slaney", htk=False)`: triangles on the Slaney mel scale, each of unit area. */
    private fun melBank(): Array<DoubleArray> {
        val bins = FFT / 2 + 1
        val step = 200.0 / 3
        val logStart = 1000.0
        val logStartMel = logStart / step
        val logStep = ln(6.4) / 27
        fun toMel(hz: Double) = if (hz < logStart) hz / step else logStartMel + ln(hz / logStart) / logStep
        fun toHz(m: Double) = if (m < logStartMel) m * step else logStart * Math.exp(logStep * (m - logStartMel))
        val top = toMel(RATE / 2.0)
        val edges = DoubleArray(BANDS + 2) { toHz(top * it / (BANDS + 1)) }
        return Array(BANDS) { b ->
            val lo = edges[b]
            val mid = edges[b + 1]
            val hi = edges[b + 2]
            val norm = 2.0 / (hi - lo)
            DoubleArray(bins) { k ->
                val f = k * RATE.toDouble() / FFT
                max(0.0, min((f - lo) / (mid - lo), (hi - f) / (hi - mid))) * norm
            }
        }
    }
}

/** A plain radix-2 FFT, in place. */
internal class Fft(private val size: Int) {
    private val cosTable = DoubleArray(size / 2) { cos(2 * PI * it / size) }
    private val sinTable = DoubleArray(size / 2) { sin(2 * PI * it / size) }
    private val reversed = IntArray(size).also { r ->
        val bits = Integer.numberOfTrailingZeros(size)
        for (i in 0 until size) r[i] = Integer.reverse(i) ushr (32 - bits)
    }

    fun transform(re: DoubleArray, im: DoubleArray) {
        for (i in 0 until size) {
            val j = reversed[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var half = 1
        while (half < size) {
            val step = size / (half * 2)
            var i = 0
            while (i < size) {
                var k = 0
                for (j in i until i + half) {
                    val wr = cosTable[k]
                    val wi = -sinTable[k]
                    val xr = re[j + half] * wr - im[j + half] * wi
                    val xi = re[j + half] * wi + im[j + half] * wr
                    re[j + half] = re[j] - xr
                    im[j + half] = im[j] - xi
                    re[j] += xr
                    im[j] += xi
                    k += step
                }
                i += half * 2
            }
            half *= 2
        }
    }
}
