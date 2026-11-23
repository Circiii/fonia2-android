package tech.fonia.face

import kotlin.math.PI
import kotlin.math.sqrt

// The few simd types FoniaFace uses, in Float as on iOS. Products are written out unfused; simd
// may fuse a multiply-add, which can move the last bit and nothing the tests can see.

/** `Float.pi`. */
internal val PI_F: Float = PI.toFloat()

/** `SIMD2<Float>`. */
data class Float2(val x: Float, val y: Float) {
    operator fun plus(other: Float2) = Float2(x + other.x, y + other.y)

    operator fun minus(other: Float2) = Float2(x - other.x, y - other.y)

    operator fun times(scale: Float) = Float2(x * scale, y * scale)

    operator fun div(scale: Float) = Float2(x / scale, y / scale)

    /** `simd_dot`. */
    fun dot(other: Float2): Float = x * other.x + y * other.y

    /** `simd_length`. */
    val length: Float
        get() = sqrt(x * x + y * y)
}

/** `SIMD3<Float>`. */
data class Float3(val x: Float, val y: Float, val z: Float) {
    /** `simd_length`. */
    val length: Float
        get() = sqrt(x * x + y * y + z * z)

    companion object {
        val ZERO = Float3(0f, 0f, 0f)
    }
}

/** `SIMD4<Float>`. */
data class Float4(val x: Float, val y: Float, val z: Float, val w: Float) {
    operator fun plus(other: Float4) = Float4(x + other.x, y + other.y, z + other.z, w + other.w)

    operator fun times(scale: Float) = Float4(x * scale, y * scale, z * scale, w * scale)
}

/** `simd_float4x4`: four columns, so `columns.2` is the third. */
data class Float4x4(val c0: Float4, val c1: Float4, val c2: Float4, val c3: Float4) {
    val columns: List<Float4>
        get() = listOf(c0, c1, c2, c3)

    /** Matrix times column vector. */
    operator fun times(v: Float4): Float4 = c0 * v.x + c1 * v.y + c2 * v.z + c3 * v.w

    operator fun times(m: Float4x4): Float4x4 = Float4x4(this * m.c0, this * m.c1, this * m.c2, this * m.c3)

    companion object {
        /** `matrix_identity_float4x4`. */
        val IDENTITY = Float4x4(
            Float4(1f, 0f, 0f, 0f),
            Float4(0f, 1f, 0f, 0f),
            Float4(0f, 0f, 1f, 0f),
            Float4(0f, 0f, 0f, 1f),
        )

        /** Sixteen values in column order, as ARKit and MediaPipe store a 4x4 matrix. */
        fun columnMajor(values: FloatArray): Float4x4 {
            require(values.size == 16) { "a 4x4 matrix has 16 values" }
            fun column(k: Int) = Float4(values[4 * k], values[4 * k + 1], values[4 * k + 2], values[4 * k + 3])
            return Float4x4(column(0), column(1), column(2), column(3))
        }
    }
}
