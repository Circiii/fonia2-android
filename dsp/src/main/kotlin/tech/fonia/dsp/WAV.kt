package tech.fonia.dsp

import java.io.ByteArrayOutputStream
import kotlin.math.cos
import kotlin.math.sin

sealed class WAVError(message: String) : Exception(message) {
    data object NotWAV : WAVError("Not a RIFF/WAVE file")

    data class UnsupportedBitDepth(val bits: Int) : WAVError("Unsupported bit depth $bits")

    data object MissingData : WAVError("No data chunk")
}

/** Swift's `(pcm: [Float], sampleRate: Double)`. */
class DecodedWAV(val pcm: FloatArray, val sampleRate: Double)

/** 16-bit PCM mono. */
fun encodeWAV(pcm: FloatArray, sampleRate: Int): ByteArray {
    val data = ByteArrayOutputStream(44 + pcm.size * 2)
    fun append(text: String) = data.write(text.toByteArray(Charsets.UTF_8))
    fun append32(value: Long) {
        for (shift in 0 until 32 step 8) data.write(((value ushr shift) and 0xFF).toInt())
    }
    fun append16(value: Int) {
        data.write(value and 0xFF)
        data.write((value ushr 8) and 0xFF)
    }
    val byteCount = pcm.size.toLong() * 2
    append("RIFF")
    append32(36 + byteCount)
    append("WAVE")
    append("fmt ")
    append32(16)
    append16(1)
    append16(1)
    append32(sampleRate.toLong())
    append32(sampleRate.toLong() * 2)
    append16(2)
    append16(16)
    append("data")
    append32(byteCount)
    for (sample in pcm) {
        val clipped = max(-1f, min(1f, sample))
        // Swift's Int16(Float) truncates toward zero.
        val value = (if (clipped < 0f) clipped * 32768f else clipped * 32767f).toInt().toShort()
        append16(value.toInt())
    }
    return data.toByteArray()
}

/** Parses 16-bit PCM, 24-bit PCM or 32-bit float WAV, mono or the first channel. */
fun decodeWAV(data: ByteArray): DecodedWAV {
    val bytes = data
    fun tag(offset: Int): String = String(bytes, offset, 4, Charsets.UTF_8)
    fun u16(offset: Int): Long = (bytes[offset].toLong() and 0xFF) or ((bytes[offset + 1].toLong() and 0xFF) shl 8)
    fun u32(offset: Int): Long = u16(offset) or (u16(offset + 2) shl 16)
    if (bytes.size < 12 || tag(0) != "RIFF" || tag(8) != "WAVE") throw WAVError.NotWAV

    var offset = 12L
    var format = 1L
    var channels = 1L
    var sampleRate = 44100L
    var bits = 16L
    while (offset + 8 <= bytes.size) {
        val at = offset.toInt()
        val identifier = tag(at)
        val size = u32(at + 4)
        if (identifier == "fmt " && offset + 24 <= bytes.size) {
            format = u16(at + 8)
            channels = maxOf(1L, u16(at + 10))
            sampleRate = u32(at + 12)
            bits = u16(at + 22)
        } else if (identifier == "data") {
            val width = bits / 8
            if (width <= 0) throw WAVError.UnsupportedBitDepth(bits.toInt())
            val available = minOf(size, bytes.size - offset - 8)
            val frames = (available / width / channels).toInt()
            val pcm = FloatArray(maxOf(0, frames))
            for (i in 0 until frames) {
                val p = (offset + 8 + i.toLong() * channels * width).toInt()
                pcm[i] = when {
                    format == 3L && bits == 32L -> Float.fromBits(u32(p).toInt())
                    bits == 16L -> u16(p).toInt().toShort().toFloat() / 32768f
                    bits == 24L -> {
                        val value = ((u16(p) or (u16(p + 1) shl 8)).toInt() shl 8) shr 8
                        value.toFloat() / 8_388_608f
                    }
                    else -> throw WAVError.UnsupportedBitDepth(bits.toInt())
                }
            }
            return DecodedWAV(pcm, sampleRate.toDouble())
        }
        offset += 8 + size + (size % 2)
    }
    throw WAVError.MissingData
}

/**
 * Resamples with a Hamming-windowed sinc low-pass when downsampling. For speech models that take
 * 16 kHz input; S/Ș analysis needs the capture rate.
 */
fun resample(pcm: FloatArray, from: Double, to: Double): FloatArray {
    val source = from
    val target = to
    if (source == target || pcm.isEmpty()) return pcm
    var input = pcm
    if (target < source) {
        val cutoff = (0.45 * target / source).toFloat()
        val taps = 31
        val kernel = FloatArray(taps) { i ->
            val m = i.toFloat() - (taps - 1).toFloat() / 2f
            val sinc = if (m == 0f) 2f * cutoff else sin(2f * PI_F * cutoff * m) / (PI_F * m)
            sinc * (0.54f - 0.46f * cos(2f * PI_F * i.toFloat() / (taps - 1).toFloat()))
        }
        var sum = 0f
        for (value in kernel) sum += value
        // vDSP.divide(kernel, sum)
        for (i in kernel.indices) kernel[i] = kernel[i] / sum
        // The overlay returns `count - kernel.count` samples; this padding gives back `pcm.count`.
        val padded = FloatArray(taps / 2) + pcm + FloatArray(taps / 2 + 1)
        input = convolve(padded, kernel)
    }
    val ratio = source / target
    val count = (input.size.toDouble() / ratio).toInt()
    return FloatArray(count) { i ->
        val x = i.toDouble() * ratio
        val i0 = x.toInt()
        val fraction = (x - i0.toDouble()).toFloat()
        val a = input[min(i0, input.size - 1)]
        val b = input[min(i0 + 1, input.size - 1)]
        a * (1f - fraction) + b * fraction
    }
}

/** Trims leading and trailing silence relative to the loudest 20 ms, keeping a small margin. */
fun trimSilence(pcm: FloatArray, sampleRate: Double, marginSeconds: Double = 0.12): FloatArray {
    val window = max(1, (sampleRate * 0.02).toInt())
    if (pcm.size < window) return pcm
    val levels = (0..pcm.size - window step window).map { start -> rmsqv(pcm, start, window) }
    val loudest = levels.maxOrNull()
    if (loudest == null || !(loudest > 0f)) return pcm
    val threshold = loudest * 0.06f
    val first = levels.indexOfFirst { it > threshold }
    val last = levels.indexOfLast { it > threshold }
    if (first < 0 || last < 0) return pcm
    val margin = (marginSeconds * sampleRate).toInt()
    val start = max(0, first * window - margin)
    val end = min(pcm.size, (last + 1) * window + margin)
    return pcm.copyOfRange(start, end)
}
