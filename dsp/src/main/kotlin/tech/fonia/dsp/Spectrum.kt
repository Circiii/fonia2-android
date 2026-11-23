package tech.fonia.dsp

import kotlin.math.cos
import kotlin.math.sin

/**
 * One DFT setup per length for the whole process, standing in for `vDSP_DFT_zrop_CreateSetup(nil, N,
 * .FORWARD)`. A setup holds only read-only tables (bit reversal and twiddles), so concurrent use is
 * safe, as the vDSP header documents for its own. They are never destroyed: only a handful of
 * lengths are ever used.
 *
 * The transform is a radix-2 complex FFT of N/2 points on the even/odd packed input, then the
 * real-input split, all in Double. Its output follows vDSP's packed real format: twice the
 * mathematical DFT, with the Nyquist bin in slot 0 of the imaginary part.
 */
internal class SharedDFTSetup private constructor(val length: Int) {
    /** N/2: the complex transform length. */
    val half: Int = length / 2
    val bitReverse = IntArray(half)
    /** cos and sin of 2πk/(N/2), k < N/4: twiddles of the half-length complex FFT. */
    val halfCos = DoubleArray(maxOf(1, half / 2))
    val halfSin = DoubleArray(maxOf(1, half / 2))
    /** cos and sin of 2πk/N, k < N/2: twiddles of the real-input split. */
    val splitCos = DoubleArray(half)
    val splitSin = DoubleArray(half)

    init {
        // vDSP's zrop setup accepts f·2^n with f in {1, 3, 5, 15} and n >= 4; FoniaDSP only asks for
        // powers of two, which is all this radix-2 transform does.
        require(length >= 16 && length and (length - 1) == 0) { "vDSP cannot transform length $length" }
        val bits = Integer.numberOfTrailingZeros(half)
        for (i in 0 until half) bitReverse[i] = Integer.reverse(i) ushr (32 - bits)
        for (k in 0 until half / 2) {
            val angle = 2.0 * Math.PI * k / half
            halfCos[k] = cos(angle)
            halfSin[k] = sin(angle)
        }
        for (k in 0 until half) {
            val angle = 2.0 * Math.PI * k / length
            splitCos[k] = cos(angle)
            splitSin[k] = sin(angle)
        }
    }

    companion object {
        private val cache = HashMap<Int, SharedDFTSetup>()

        fun forLength(length: Int): SharedDFTSetup = synchronized(cache) {
            cache.getOrPut(length) { SharedDFTSetup(length) }
        }
    }
}

/** Real-input power spectrum, |X|²/N², with length/2 bins. Bin k is k * sampleRate / length Hz. */
internal class PowerTransform(val length: Int) {
    val binCount: Int = length / 2
    private val shared = SharedDFTSetup.forLength(length)
    private val real = DoubleArray(binCount)
    private val imag = DoubleArray(binCount)
    // vDSP's forward real transform returns twice the mathematical DFT.
    private val scale: Float = 1f / (4f * length.toFloat() * length.toFloat())

    /** `input` holds `length` samples from `inputOffset`; `power` receives `binCount` values. */
    fun transform(input: FloatArray, inputOffset: Int, power: FloatArray, powerOffset: Int = 0) {
        val half = binCount
        val reverse = shared.bitReverse
        // vDSP_ctoz: even samples to the real part, odd samples to the imaginary part.
        for (n in 0 until half) {
            val slot = reverse[n]
            real[slot] = input[inputOffset + 2 * n].toDouble()
            imag[slot] = input[inputOffset + 2 * n + 1].toDouble()
        }
        fft()
        // Slot 0 of vDSP's imaginary output stores the Nyquist bin, which is not among the returned
        // bins, so bin 0 is the DC term alone.
        val dc = (2.0 * (real[0] + imag[0])).toFloat()
        power[powerOffset] = (dc * dc + 0f * 0f) * scale
        val cosines = shared.splitCos
        val sines = shared.splitSin
        for (k in 1 until half) {
            val zr = real[k]
            val zi = imag[k]
            val cr = real[half - k]
            val ci = -imag[half - k]
            // Even part E = (Z[k] + conj Z[M-k]) / 2, odd part O = (Z[k] - conj Z[M-k]) / 2i.
            val er = (zr + cr) * 0.5
            val ei = (zi + ci) * 0.5
            val or = (zi - ci) * 0.5
            val oi = -(zr - cr) * 0.5
            // X[k] = E + e^(-2πik/N) O
            val wr = cosines[k]
            val wi = -sines[k]
            val xr = er + (wr * or - wi * oi)
            val xi = ei + (wr * oi + wi * or)
            val outReal = (2.0 * xr).toFloat()
            val outImag = (2.0 * xi).toFloat()
            // vDSP_zvmags then vDSP_vsmul, in Float.
            power[powerOffset + k] = (outReal * outReal + outImag * outImag) * scale
        }
    }

    /** In-place iterative radix-2 FFT of the bit-reversed `real`/`imag`, forward (e^-iθ). */
    private fun fft() {
        val n = binCount
        val cosines = shared.halfCos
        val sines = shared.halfSin
        var size = 2
        while (size <= n) {
            val halfSize = size / 2
            val step = n / size
            var start = 0
            while (start < n) {
                var k = 0
                while (k < halfSize) {
                    val wr = cosines[k * step]
                    val wi = -sines[k * step]
                    val j = start + k
                    val l = j + halfSize
                    val tr = wr * real[l] - wi * imag[l]
                    val ti = wr * imag[l] + wi * real[l]
                    real[l] = real[j] - tr
                    imag[l] = imag[j] - ti
                    real[j] += tr
                    imag[j] += ti
                    k += 1
                }
                start += size
            }
            size *= 2
        }
    }
}

/**
 * Blackman-windowed frame spectrum. Blackman is the family the web's AnalyserNode used, which keeps
 * its level thresholds meaningful here.
 */
internal class FrameSpectrum(length: Int) {
    val transform = PowerTransform(length)
    val power = FloatBuffer(transform.binCount)
    private val window = FloatBuffer(length)
    private val windowed = FloatBuffer(length)

    init {
        val denominator = (length - 1).toFloat()
        for (i in 0 until length) {
            val x = 2f * PI_F * i.toFloat() / denominator
            window[i] = 0.42f - 0.5f * cos(x) + 0.08f * cos(2f * x)
        }
    }

    val binCount: Int get() = transform.binCount

    fun compute(frame: FloatArray, offset: Int = 0) {
        // vDSP_vmul
        for (i in 0 until transform.length) windowed[i] = frame[offset + i] * window[i]
        transform.transform(windowed, 0, power)
    }
}
