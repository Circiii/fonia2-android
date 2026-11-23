package tech.fonia.dsp

import kotlin.math.abs
import kotlin.math.sqrt

// Stand-ins for the vDSP reductions FoniaDSP calls. vDSP sums Float vectors in SIMD lanes, in an
// order a plain loop cannot reproduce; these accumulate in Double and round once, which lands
// closest to the exact sum that vDSP approximates. Elementwise vDSP operations (vmul, vadd, vsmul,
// vsq, zvmags) are exact IEEE Float operations and are written inline where the Swift code calls them.

/** `vDSP_sve`: sum of `count` values from `offset`. */
internal fun sve(a: FloatArray, offset: Int, count: Int): Float {
    var s0 = 0.0
    var s1 = 0.0
    var s2 = 0.0
    var s3 = 0.0
    var i = offset
    val end = offset + count
    val unrolled = offset + (count and 3.inv())
    while (i < unrolled) {
        s0 += a[i]
        s1 += a[i + 1]
        s2 += a[i + 2]
        s3 += a[i + 3]
        i += 4
    }
    while (i < end) {
        s0 += a[i]
        i += 1
    }
    return ((s0 + s1) + (s2 + s3)).toFloat()
}

/** `vDSP_dotpr`: Σ a[ao + i] · b[bo + i] for i < count. */
internal fun dotpr(a: FloatArray, ao: Int, b: FloatArray, bo: Int, count: Int): Float {
    var s0 = 0.0
    var s1 = 0.0
    var s2 = 0.0
    var s3 = 0.0
    var i = 0
    val unrolled = count and 3.inv()
    while (i < unrolled) {
        s0 += a[ao + i].toDouble() * b[bo + i]
        s1 += a[ao + i + 1].toDouble() * b[bo + i + 1]
        s2 += a[ao + i + 2].toDouble() * b[bo + i + 2]
        s3 += a[ao + i + 3].toDouble() * b[bo + i + 3]
        i += 4
    }
    while (i < count) {
        s0 += a[ao + i].toDouble() * b[bo + i]
        i += 1
    }
    return ((s0 + s1) + (s2 + s3)).toFloat()
}

/** `vDSP_svesq`: Σ a[offset + i]² for i < count. */
internal fun svesq(a: FloatArray, offset: Int, count: Int): Float = sumOfSquares(a, offset, count).toFloat()

private fun sumOfSquares(a: FloatArray, offset: Int, count: Int): Double {
    var s0 = 0.0
    var s1 = 0.0
    var s2 = 0.0
    var s3 = 0.0
    var i = offset
    val end = offset + count
    val unrolled = offset + (count and 3.inv())
    while (i < unrolled) {
        val x0 = a[i].toDouble()
        val x1 = a[i + 1].toDouble()
        val x2 = a[i + 2].toDouble()
        val x3 = a[i + 3].toDouble()
        s0 += x0 * x0
        s1 += x1 * x1
        s2 += x2 * x2
        s3 += x3 * x3
        i += 4
    }
    while (i < end) {
        val x = a[i].toDouble()
        s0 += x * x
        i += 1
    }
    return (s0 + s1) + (s2 + s3)
}

/** `vDSP_rmsqv`: sqrt(Σ x² / count). */
internal fun rmsqv(a: FloatArray, offset: Int, count: Int): Float = sqrt(sumOfSquares(a, offset, count) / count).toFloat()

/** `vDSP_maxmgv`: largest magnitude, 0 for an empty range. */
internal fun maxmgv(a: FloatArray, offset: Int, count: Int): Float {
    var peak = 0f
    for (i in offset until offset + count) {
        val magnitude = abs(a[i])
        if (magnitude > peak) peak = magnitude
    }
    return peak
}

/**
 * `vDSP_desamp`: FIR filter and decimate, `c[n] = Σ_{p < taps} a[ao + n·factor + p] · filter[p]`
 * for n < count. The correlation form, with no delay compensation, exactly as vDSP documents it.
 */
internal fun desamp(
    a: FloatArray, ao: Int, factor: Int, filter: FloatArray, c: FloatArray, count: Int, taps: Int,
) {
    for (n in 0 until count) c[n] = dotpr(a, ao + n * factor, filter, 0, taps)
}

/**
 * `vDSP.convolve(_:withKernel:)`, the Swift overlay: `vector.count - kernel.count` outputs, the kernel
 * reversed (`vDSP_conv` with a negative filter stride), `c[n] = Σ_p a[n + p] · kernel[last - p]`.
 */
internal fun convolve(vector: FloatArray, kernel: FloatArray): FloatArray {
    val count = maxOf(0, vector.size - kernel.size)
    val reversed = kernel.reversedArray()
    return FloatArray(count) { n -> dotpr(vector, n, reversed, 0, kernel.size) }
}
