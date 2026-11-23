package tech.fonia.facecamera

import kotlin.math.cos
import kotlin.math.sin

/**
 * A camera frame or still image as packed RGBA bytes, plus what turns it upright and unmirrored: the clockwise
 * rotation CameraX reports (`ImageInfo.rotationDegrees`) and whether the buffer is a mirror image. The whole
 * pipeline works in upright, unmirrored pixels; the buffer itself is never rotated or copied, only sampled.
 */
internal class RgbaFrame {
    var pixels: ByteArray = ByteArray(0)
        private set
    var width = 0
        private set
    var height = 0
        private set
    var rowStride = 0
        private set
    var rotationDegrees = 0
        private set
    var mirrored = false
        private set

    val uprightWidth: Int get() = if (rotationDegrees % 180 == 0) width else height
    val uprightHeight: Int get() = if (rotationDegrees % 180 == 0) height else width

    fun set(pixels: ByteArray, width: Int, height: Int, rowStride: Int, rotationDegrees: Int, mirrored: Boolean) {
        require(rotationDegrees == 0 || rotationDegrees == 90 || rotationDegrees == 180 || rotationDegrees == 270) {
            "rotation $rotationDegrees"
        }
        require(rowStride >= width * 4 && pixels.size >= rowStride * (height - 1) + width * 4) { "buffer too small" }
        this.pixels = pixels
        this.width = width
        this.height = height
        this.rowStride = rowStride
        this.rotationDegrees = rotationDegrees
        this.mirrored = mirrored
    }
}

internal enum class Border { ZERO, REPLICATE }

/**
 * MediaPipe's CPU `ImageToTensorCalculator`: `cv::warpPerspective` with `INTER_LINEAR` of a rotated rectangle onto
 * the model's input, then `convertTo(scale, offset)`. OpenCV interpolates 8-bit images in fixed point: source
 * coordinates are rounded to 1/32 pixel and the four weights are 15-bit integers. This reproduces that arithmetic,
 * so a still image gives the same tensor as MediaPipe (checked in FrameSamplerTests and ModelParityTests).
 *
 * The rectangle is in upright pixels. Each sample position is rounded in upright space, then mapped with integers
 * onto the rotated or mirrored buffer, which gives the same pixels and weights as rotating the image first.
 */
internal object FrameSampler {
    private const val TAB_BITS = 5
    private const val TAB_SIZE = 1 shl TAB_BITS
    private const val COEF_BITS = 15
    private const val ROUND = 1 shl (COEF_BITS - 1)

    /**
     * Fills [out] (outHeight × outWidth × RGB, row after row) with `pixel * valueScale + valueOffset`.
     * @param rotation radians, as MediaPipe's `NormalizedRect.rotation`.
     */
    fun sample(
        frame: RgbaFrame,
        centerX: Float,
        centerY: Float,
        width: Float,
        height: Float,
        rotation: Float,
        outWidth: Int,
        outHeight: Int,
        border: Border,
        valueScale: Float,
        valueOffset: Float,
        out: FloatArray,
    ) {
        // cv::RotatedRect(center, size, rotation * 180.f / M_PI) and cv::boxPoints, in OpenCV's precision.
        val angle = ((rotation * 180f).toDouble() / Math.PI).toFloat()
        val radians = angle.toDouble() * Math.PI / 180.0
        val b = cos(radians).toFloat() * 0.5f
        val a = sin(radians).toFloat() * 0.5f
        val p0x = centerX - a * height - b * width // bottom left: tensor (0, outHeight)
        val p0y = centerY + b * height - a * width
        val p1x = centerX + a * height - b * width // top left: tensor (0, 0)
        val p1y = centerY - b * height - a * width
        val p2x = 2f * centerX - p0x // top right: tensor (outWidth, 0)
        val p2y = 2f * centerY - p0y
        val x0 = p1x.toDouble()
        val y0 = p1y.toDouble()
        val xPerU = (p2x.toDouble() - x0) / outWidth
        val yPerU = (p2y.toDouble() - y0) / outWidth
        val xPerV = (p0x.toDouble() - x0) / outHeight
        val yPerV = (p0y.toDouble() - y0) / outHeight

        // Upright position (1/32 pixel) to buffer position (1/32 pixel), with integer coefficients.
        val w = frame.width
        val h = frame.height
        val mirrored = frame.mirrored
        val mirrorOffset = (frame.uprightWidth - 1) * TAB_SIZE
        val lastX = (w - 1) * TAB_SIZE
        val lastY = (h - 1) * TAB_SIZE
        val mxx: Int
        val mxy: Int
        val mx0: Int
        val myx: Int
        val myy: Int
        val my0: Int
        when (frame.rotationDegrees) {
            0 -> { mxx = 1; mxy = 0; mx0 = 0; myx = 0; myy = 1; my0 = 0 }
            90 -> { mxx = 0; mxy = 1; mx0 = 0; myx = -1; myy = 0; my0 = lastY }
            180 -> { mxx = -1; mxy = 0; mx0 = lastX; myx = 0; myy = -1; my0 = lastY }
            else -> { mxx = 0; mxy = -1; mx0 = lastX; myx = 1; myy = 0; my0 = 0 }
        }

        val pixels = frame.pixels
        val stride = frame.rowStride
        val replicate = border == Border.REPLICATE
        var o = 0
        for (v in 0 until outHeight) {
            for (u in 0 until outWidth) {
                val rounded = Math.rint((x0 + xPerU * u + xPerV * v) * TAB_SIZE).toInt()
                val ux = if (mirrored) mirrorOffset - rounded else rounded
                val uy = Math.rint((y0 + yPerU * u + yPerV * v) * TAB_SIZE).toInt()
                val bx = mxx * ux + mxy * uy + mx0
                val by = myx * ux + myy * uy + my0
                val sx = bx shr TAB_BITS
                val sy = by shr TAB_BITS
                val fx = bx and (TAB_SIZE - 1)
                val fy = by and (TAB_SIZE - 1)
                // OpenCV's BilinearTab_i: (1 - fy, fy) x (1 - fx, fx) in 1/32 steps, scaled to 2^15. The
                // all-on-the-pixel entry is (32767, 0, 0, 1): 32768 does not fit a short.
                val exact = fx == 0 && fy == 0
                val w00 = if (exact) 32767 else (TAB_SIZE - fy) * (TAB_SIZE - fx) * TAB_SIZE
                val w01 = (TAB_SIZE - fy) * fx * TAB_SIZE
                val w10 = fy * (TAB_SIZE - fx) * TAB_SIZE
                val w11 = if (exact) 1 else fy * fx * TAB_SIZE
                var r: Int
                var g: Int
                var bl: Int
                if (sx >= 0 && sy >= 0 && sx < w - 1 && sy < h - 1) {
                    val i00 = sy * stride + sx * 4
                    val i01 = i00 + 4
                    val i10 = i00 + stride
                    val i11 = i10 + 4
                    r = ((pixels[i00].toInt() and 255) * w00 + (pixels[i01].toInt() and 255) * w01 +
                        (pixels[i10].toInt() and 255) * w10 + (pixels[i11].toInt() and 255) * w11 + ROUND) shr COEF_BITS
                    g = ((pixels[i00 + 1].toInt() and 255) * w00 + (pixels[i01 + 1].toInt() and 255) * w01 +
                        (pixels[i10 + 1].toInt() and 255) * w10 + (pixels[i11 + 1].toInt() and 255) * w11 + ROUND) shr COEF_BITS
                    bl = ((pixels[i00 + 2].toInt() and 255) * w00 + (pixels[i01 + 2].toInt() and 255) * w01 +
                        (pixels[i10 + 2].toInt() and 255) * w10 + (pixels[i11 + 2].toInt() and 255) * w11 + ROUND) shr COEF_BITS
                } else if (!replicate && (sx >= w || sx + 1 < 0 || sy >= h || sy + 1 < 0)) {
                    r = 0; g = 0; bl = 0
                } else {
                    // Partly outside: each neighbour on its own, clamped (REPLICATE) or zero (ZERO).
                    val xa = clampOrOut(sx, w, replicate)
                    val xb = clampOrOut(sx + 1, w, replicate)
                    val ya = clampOrOut(sy, h, replicate)
                    val yb = clampOrOut(sy + 1, h, replicate)
                    r = ROUND; g = ROUND; bl = ROUND
                    if (xa >= 0 && ya >= 0) {
                        val i = ya * stride + xa * 4
                        r += (pixels[i].toInt() and 255) * w00; g += (pixels[i + 1].toInt() and 255) * w00; bl += (pixels[i + 2].toInt() and 255) * w00
                    }
                    if (xb >= 0 && ya >= 0) {
                        val i = ya * stride + xb * 4
                        r += (pixels[i].toInt() and 255) * w01; g += (pixels[i + 1].toInt() and 255) * w01; bl += (pixels[i + 2].toInt() and 255) * w01
                    }
                    if (xa >= 0 && yb >= 0) {
                        val i = yb * stride + xa * 4
                        r += (pixels[i].toInt() and 255) * w10; g += (pixels[i + 1].toInt() and 255) * w10; bl += (pixels[i + 2].toInt() and 255) * w10
                    }
                    if (xb >= 0 && yb >= 0) {
                        val i = yb * stride + xb * 4
                        r += (pixels[i].toInt() and 255) * w11; g += (pixels[i + 1].toInt() and 255) * w11; bl += (pixels[i + 2].toInt() and 255) * w11
                    }
                    r = r shr COEF_BITS; g = g shr COEF_BITS; bl = bl shr COEF_BITS
                }
                out[o] = r * valueScale + valueOffset
                out[o + 1] = g * valueScale + valueOffset
                out[o + 2] = bl * valueScale + valueOffset
                o += 3
            }
        }
    }

    /** OpenCV's `borderInterpolate`: replicate clamps, constant gives -1 (the border value) outside. */
    private fun clampOrOut(i: Int, size: Int, replicate: Boolean): Int = when {
        i in 0 until size -> i
        !replicate -> -1
        i < 0 -> 0
        else -> size - 1
    }

    /** `GetValueRangeTransformation(0, 255, min, max)`: the scale and offset `convertTo` applies. */
    fun valueScale(rangeMin: Float, rangeMax: Float): Float = (rangeMax - rangeMin) / (255f - 0f)
}
