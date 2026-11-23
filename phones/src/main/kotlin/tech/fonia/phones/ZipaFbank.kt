package tech.fonia.phones

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * The front end ZIPA was trained and published with: lhotse's Kaldi-style `Fbank()` with its `FbankConfig` defaults
 * (lhotse 1.33.0 `features/kaldi/layers.py`, `Wav2LogFilterBank`), which `lingjzhu/zipa` calls as
 * `FbankConfig(num_filters=80, dither=0.0, snip_edges=False)`.
 *
 * - Input: mono samples at 16 kHz as floats in [-1, 1] (soundfile's scale, not Kaldi's ±32768). No dither, no gain,
 *   no mean or variance normalisation: the model takes the raw log energies.
 * - Frames of 400 samples (25 ms) every 160 (10 ms); `(n + 80) / 160` frames; the signal is mirrored at both ends
 *   (sample −1 is sample 0) so the first frame is centred on sample 80.
 * - Per frame: subtract the frame mean, pre-emphasis 0.97 with the first sample repeated, the "povey" window
 *   (Hann over 399 intervals, to the power 0.85), zero padding to 512, power spectrum.
 * - 80 triangular mel filters (mel = 1127 ln(1 + f/700)) between 20 Hz and 7600 Hz (Nyquist − 400), Kaldi's
 *   (torchaudio's) construction, the Nyquist bin unused; then ln(max(energy, FLT_EPSILON)).
 *
 * The window and the filter edges are rounded as the reference rounds them in float32; the per-frame arithmetic runs
 * in Double and is stored as Float, so the two differ by the reference's float32 noise (`ZipaFbankTest`: about 1e-5
 * in the log domain near each frame's peak). Inputs of at least 200 samples are framed exactly as lhotse frames them;
 * lhotse reads out of bounds below that, while this keeps mirroring.
 *
 * One instance keeps its scratch buffers: [compute] allocates nothing per frame, only its output if none is given.
 * Not thread-safe.
 */
class ZipaFbank {
    /**
     * `torch.hann_window(400, periodic=False).pow(0.85)`, rounded as torch rounds it in float32 (n × 2π/399, cos,
     * × −0.5 + 0.5, pow), so the window is the reference's to the last bit or one ulp from it.
     */
    private val window = DoubleArray(FRAME_LENGTH) { n ->
        val angle = n.toFloat() * (2.0 * PI / (FRAME_LENGTH - 1)).toFloat()
        val hann = cos(angle.toDouble()).toFloat() * -0.5f + 0.5f
        hann.toDouble().pow(0.85).toFloat().toDouble()
    }
    private val frame = DoubleArray(FRAME_LENGTH)
    private val real = DoubleArray(FFT_SIZE)
    private val imag = DoubleArray(FFT_SIZE)
    private val power = DoubleArray(FFT_SIZE / 2 + 1)
    private val fft = Fft(FFT_SIZE)

    /** First FFT bin and weights of each mel filter (Kaldi: `get_mel_banks`, bins 0…255, Nyquist bin 256 unused). */
    private val filterStart = IntArray(NUM_BINS)
    private val filterWeights: Array<DoubleArray>

    init {
        // Kaldi's construction as lhotse 1.33.0 ports it (`get_mel_banks`), with its float32 rounding: the edges and
        // the bin positions are float32 mel values (≈ 1e-4 mel apart from exact at the top of the scale) and the
        // slopes are float32 divisions. These roundings are systematic, so they are reproduced; the per-frame
        // arithmetic below runs in Double, closer to exact than the reference.
        val nyquist = SAMPLE_RATE / 2.0
        val high = nyquist + HIGH_FREQ_OFFSET
        val melLow = mel(LOW_FREQ)
        val delta = (mel(high) - melLow) / (NUM_BINS + 1)
        val binWidth = SAMPLE_RATE.toFloat() / FFT_SIZE
        val binMel = FloatArray(FFT_SIZE / 2) { k -> melFloat(binWidth * k.toFloat()) }
        filterWeights = Array(NUM_BINS) { bin ->
            val left = melLow.toFloat() + bin.toFloat() * delta.toFloat()
            val center = melLow.toFloat() + (bin + 1).toFloat() * delta.toFloat()
            val right = melLow.toFloat() + (bin + 2).toFloat() * delta.toFloat()
            val weights = DoubleArray(FFT_SIZE / 2) { k ->
                val m = binMel[k]
                val up = (m - left) / (center - left)
                val down = (right - m) / (right - center)
                max(0f, minOf(up, down)).toDouble()
            }
            val first = weights.indexOfFirst { it > 0.0 }
            val last = weights.indexOfLast { it > 0.0 }
            filterStart[bin] = max(first, 0)
            if (first < 0) DoubleArray(0) else weights.copyOfRange(first, last + 1)
        }
    }

    /**
     * Log-mel features of `samples[offset, offset + count)`, row-major `frameCount(count) × 80`, written into [out]
     * (allocated when null). Returns [out].
     */
    fun compute(samples: FloatArray, offset: Int = 0, count: Int = samples.size - offset, out: FloatArray? = null): FloatArray {
        require(offset >= 0 && count >= 0 && offset + count <= samples.size)
        val frames = frameCount(count)
        val features = out ?: FloatArray(frames * NUM_BINS)
        require(features.size >= frames * NUM_BINS) { "out holds ${features.size} values, $frames frames need ${frames * NUM_BINS}" }
        for (m in 0 until frames) {
            val origin = m * FRAME_SHIFT - LEFT_PAD
            var mean = 0.0
            for (k in 0 until FRAME_LENGTH) {
                val value = samples[offset + mirror(origin + k, count)].toDouble()
                frame[k] = value
                mean += value
            }
            mean /= FRAME_LENGTH
            // Mean removal, then pre-emphasis with the first sample repeated (backwards, in place), then the window.
            for (k in 0 until FRAME_LENGTH) frame[k] -= mean
            for (k in FRAME_LENGTH - 1 downTo 1) frame[k] -= PREEMPHASIS * frame[k - 1]
            frame[0] -= PREEMPHASIS * frame[0]
            for (k in 0 until FRAME_LENGTH) {
                real[k] = frame[k] * window[k]
                imag[k] = 0.0
            }
            for (k in FRAME_LENGTH until FFT_SIZE) {
                real[k] = 0.0
                imag[k] = 0.0
            }
            fft.transform(real, imag)
            for (k in 0..FFT_SIZE / 2) power[k] = real[k] * real[k] + imag[k] * imag[k]
            val row = m * NUM_BINS
            for (bin in 0 until NUM_BINS) {
                val weights = filterWeights[bin]
                val first = filterStart[bin]
                var energy = 0.0
                for (j in weights.indices) energy += weights[j] * power[first + j]
                features[row + bin] = ln(max(energy, ENERGY_FLOOR)).toFloat()
            }
        }
        return features
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val NUM_BINS = 80
        const val FRAME_LENGTH = 400
        const val FRAME_SHIFT = 160
        const val FFT_SIZE = 512
        const val LOW_FREQ = 20.0
        const val HIGH_FREQ_OFFSET = -400.0
        const val PREEMPHASIS = 0.97

        /** `torch.finfo(torch.float).eps`, lhotse's floor before the log. */
        val ENERGY_FLOOR: Double = Math.ulp(1.0f).toDouble()

        /** ln(FLT_EPSILON) ≈ −15.942385: the value of every bin on digital silence. */
        val LOG_FLOOR: Float = ln(ENERGY_FLOOR).toFloat()

        /** Samples mirrored before the first frame: (400 − 160) / 2. */
        private const val LEFT_PAD = (FRAME_LENGTH - FRAME_SHIFT) / 2

        /** lhotse's `compute_num_frames_from_samples` with snip_edges=False. */
        fun frameCount(samples: Int): Int = (samples + FRAME_SHIFT / 2) / FRAME_SHIFT

        fun mel(hz: Double): Double = 1127.0 * ln(1.0 + hz / 700.0)

        /** The same in float32 steps, as numpy's log runs on lhotse's float32 bin frequencies. */
        private fun melFloat(hz: Float): Float = 1127.0f * ln((1.0f + hz / 700.0f).toDouble()).toFloat()

        /** Index of padded position [n] in a signal of [length] samples, mirrored as `torch.flip` pads it (−1 → 0). */
        internal fun mirror(n: Int, length: Int): Int {
            if (length <= 0) throw IllegalArgumentException("empty signal")
            var i = n
            while (i < 0 || i >= length) {
                i = if (i < 0) -i - 1 else 2 * length - 1 - i
            }
            return i
        }
    }
}

/** In-place iterative radix-2 complex FFT (forward, unscaled), with precomputed twiddles and bit reversal. */
internal class Fft(private val size: Int) {
    private val cosTable: DoubleArray
    private val sinTable: DoubleArray
    private val reversed: IntArray

    init {
        require(size >= 2 && size and (size - 1) == 0) { "size must be a power of two" }
        cosTable = DoubleArray(size / 2) { cos(2.0 * PI * it / size) }
        sinTable = DoubleArray(size / 2) { -sin(2.0 * PI * it / size) }
        val bits = Integer.numberOfTrailingZeros(size)
        reversed = IntArray(size) { Integer.reverse(it) ushr (32 - bits) }
    }

    fun transform(real: DoubleArray, imag: DoubleArray) {
        for (i in 0 until size) {
            val j = reversed[i]
            if (j > i) {
                var t = real[i]; real[i] = real[j]; real[j] = t
                t = imag[i]; imag[i] = imag[j]; imag[j] = t
            }
        }
        var half = 1
        while (half < size) {
            val step = size / (half * 2)
            var start = 0
            while (start < size) {
                var k = 0
                for (j in start until start + half) {
                    val wr = cosTable[k]
                    val wi = sinTable[k]
                    val l = j + half
                    val tr = real[l] * wr - imag[l] * wi
                    val ti = real[l] * wi + imag[l] * wr
                    real[l] = real[j] - tr
                    imag[l] = imag[j] - ti
                    real[j] += tr
                    imag[j] += ti
                    k += step
                }
                start += half * 2
            }
            half *= 2
        }
    }
}
