package tech.fonia.speech

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

sealed class SpeechResamplerError(message: String) : Exception(message) {
    data object UnsupportedFormat : SpeechResamplerError("unsupported format")

    data class ConversionFailed(val detail: String) : SpeechResamplerError(detail)
}

/**
 * FoniaSpeech/SpeechResampler.swift. iOS runs AVAudioConverter with the Mastering algorithm at
 * maximum quality; Android has no public converter of that grade, so this is a band-limited
 * windowed-sinc interpolator with the same contract: zero latency (output sample k sits at
 * k / targetRate seconds, the clip treated as silent outside its ends), `ceil(n * target / source)`
 * output samples, a flat passband up to 94 % of the lower Nyquist and about 80 dB of stopband.
 * The samples are not bit-identical to Apple's converter; the tests hold it to the same tolerances.
 *
 * Swift's version is `async` and `@concurrent`. This one is a plain blocking function; callers run it
 * off the main thread (`withContext(Dispatchers.Default)` in the screens).
 */
object SpeechResampler {
    const val WHISPER_RATE = 16_000.0

    /** Zero crossings of the sinc on each side of the centre. */
    private const val ZERO_CROSSINGS = 16

    /** Kaiser window shape: about 80 dB of stopband rejection. */
    private const val KAISER_BETA = 8.6

    /** Cutoff as a share of the lower of the two Nyquist frequencies. */
    private const val ROLLOFF = 0.94

    /** Kernel samples per zero crossing; linear interpolation in between is accurate to about 1e-8. */
    private const val TABLE_STEPS = 2048

    private val kernel: DoubleArray by lazy { kernelTable() }

    /** Mono float samples. */
    fun resample(samples: FloatArray, from: Double, to: Double = WHISPER_RATE): FloatArray {
        // Swift returns the caller's array; a copy keeps the value semantics.
        if (from == to || samples.isEmpty()) return samples.copyOf()
        if (!(from > 0 && to > 0 && from.isFinite() && to.isFinite())) throw SpeechResamplerError.UnsupportedFormat
        val expected = ceil(samples.size.toDouble() * to / from).toInt()
        val step = from / to
        // The kernel's width in zero crossings per input sample: below 1 when downsampling.
        val scale = ROLLOFF * minOf(1.0, to / from)
        val halfWidth = ZERO_CROSSINGS / scale
        val table = kernel
        val out = FloatArray(expected)
        for (k in 0 until expected) {
            val x = k.toDouble() * step
            val lo = maxOf(0, ceil(x - halfWidth).toInt())
            val hi = minOf(samples.size - 1, floor(x + halfWidth).toInt())
            var sum = 0.0
            for (n in lo..hi) {
                val position = abs(x - n) * scale * TABLE_STEPS
                val index = position.toInt()
                if (index >= table.size - 1) continue
                val fraction = position - index
                val weight = table[index] + (table[index + 1] - table[index]) * fraction
                sum += samples[n] * weight
            }
            out[k] = (sum * scale).toFloat()
        }
        return out
    }

    /** sinc(z) times a Kaiser window, for z from 0 to ZERO_CROSSINGS in steps of 1 / TABLE_STEPS. */
    private fun kernelTable(): DoubleArray {
        val size = ZERO_CROSSINGS * TABLE_STEPS + 2
        val norm = besselI0(KAISER_BETA)
        return DoubleArray(size) { i ->
            val z = i.toDouble() / TABLE_STEPS
            if (z >= ZERO_CROSSINGS) {
                0.0
            } else {
                val sinc = if (i == 0) 1.0 else sin(PI * z) / (PI * z)
                val u = z / ZERO_CROSSINGS
                sinc * besselI0(KAISER_BETA * sqrt(1 - u * u)) / norm
            }
        }
    }

    private fun besselI0(x: Double): Double {
        var sum = 1.0
        var term = 1.0
        val half = x / 2
        var k = 1
        while (term > 1e-21 * sum) {
            val ratio = half / k
            term *= ratio * ratio
            sum += term
            k += 1
        }
        return sum
    }
}
