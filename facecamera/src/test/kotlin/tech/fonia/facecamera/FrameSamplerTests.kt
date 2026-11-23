package tech.fonia.facecamera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.roundToInt

/**
 * The model inputs. The Python prototype (tools/face_reference/prototype.py) reproduces MediaPipe's outputs to
 * 1e-5 from inputs made by the same OpenCV fixed-point arithmetic; this sampler must give exactly its pixels.
 */
class FrameSamplerTests {
    private fun upright(name: String): Pair<RgbaFrame, Fixtures.Image> {
        val image = JvmFixtures.argb("$name.png")
        val frame = RgbaFrame().apply { set(image.rgba(), image.width, image.height, image.width * 4, 0, false) }
        return frame to image
    }

    private fun detectorInput(frame: RgbaFrame): FloatArray {
        val w = frame.uprightWidth
        val h = frame.uprightHeight
        val side = if (1f > h.toFloat() / w) w.toFloat() else h.toFloat()
        val out = FloatArray(128 * 128 * 3)
        FrameSampler.sample(frame, 0.5f * w, 0.5f * h, side, side, 0f, 128, 128, Border.ZERO, FrameSampler.valueScale(-1f, 1f), -1f, out)
        return out
    }

    private fun landmarkInput(frame: RgbaFrame, rect: FloatArray): FloatArray {
        val w = frame.uprightWidth
        val h = frame.uprightHeight
        val out = FloatArray(256 * 256 * 3)
        FrameSampler.sample(frame, rect[0] * w, rect[1] * h, rect[2] * w, rect[3] * h, rect[4], 256, 256, Border.REPLICATE, FrameSampler.valueScale(0f, 1f), 0f, out)
        return out
    }

    private fun assertSamePixels(expected: Fixtures.Image, actual: FloatArray, toByte: (Float) -> Int) {
        var different = 0
        for (i in 0 until expected.width * expected.height) {
            val argb = expected.argb[i]
            val rgb = intArrayOf((argb shr 16) and 255, (argb shr 8) and 255, argb and 255)
            for (c in 0 until 3) if (toByte(actual[i * 3 + c]) != rgb[c]) different++
        }
        assertEquals("values different from the prototype's", 0, different)
    }

    @Test
    fun detectorInputMatchesThePrototype() {
        for (name in listOf("smile", "wide_open")) {
            val (frame, _) = upright(name)
            assertSamePixels(JvmFixtures.argb("$name.detector_input.png"), detectorInput(frame)) { ((it + 1f) * 127.5f).roundToInt() }
        }
    }

    @Test
    fun landmarkInputMatchesThePrototype() {
        for (name in listOf("smile", "wide_open")) {
            val (frame, _) = upright(name)
            val rect = JvmFixtures.reference(name).prototypeRect
            assertSamePixels(JvmFixtures.argb("$name.landmark_input.png"), landmarkInput(frame, rect)) { (it * 255f).roundToInt() }
        }
    }

    @Test
    fun rotatedAndMirroredBuffersGiveTheSameInput() {
        // wide_open: a rolled head, so the landmark region is rotated, and a landscape frame.
        for (name in listOf("wide_open", "smile")) {
            val (frame, image) = upright(name)
            val rect = JvmFixtures.reference(name).prototypeRect
            val expectedDetector = detectorInput(frame)
            val expectedLandmarks = landmarkInput(frame, rect)
            for (rotation in listOf(0, 90, 180, 270)) {
                for (mirrored in listOf(false, true)) {
                    val (bytes, bw, bh) = image.asBuffer(rotation, mirrored)
                    val buffer = RgbaFrame().apply { set(bytes, bw, bh, bw * 4, rotation, mirrored) }
                    assertEquals(image.width, buffer.uprightWidth)
                    assertEquals(image.height, buffer.uprightHeight)
                    assertArrayEquals("$name $rotation° mirrored=$mirrored", expectedDetector, detectorInput(buffer), 0f)
                    assertArrayEquals("$name $rotation° mirrored=$mirrored", expectedLandmarks, landmarkInput(buffer, rect), 0f)
                }
            }
        }
    }

    @Test
    fun rowPaddingIsSkipped() {
        val (frame, image) = upright("smile")
        val stride = image.width * 4 + 64
        val padded = ByteArray(stride * image.height)
        for (y in 0 until image.height) System.arraycopy(frame.pixels, y * image.width * 4, padded, y * stride, image.width * 4)
        val withPadding = RgbaFrame().apply { set(padded, image.width, image.height, stride, 0, false) }
        assertArrayEquals(detectorInput(frame), detectorInput(withPadding), 0f)
    }

    @Test
    fun borders() {
        // A 2×2 image: red, green / blue, white.
        val pixels = byteArrayOf(-1, 0, 0, -1, 0, -1, 0, -1, 0, 0, -1, -1, -1, -1, -1, -1)
        val frame = RgbaFrame().apply { set(pixels, 2, 2, 8, 0, false) }
        val out = FloatArray(4 * 4 * 3)
        // Twice the image's size, centred: the outer ring of samples falls outside.
        FrameSampler.sample(frame, 1f, 1f, 4f, 4f, 0f, 4, 4, Border.ZERO, 1f, 0f, out)
        assertArrayEquals("far outside: zero", floatArrayOf(0f, 0f, 0f), out.copyOfRange(0, 3), 0f)
        FrameSampler.sample(frame, 1f, 1f, 4f, 4f, 0f, 4, 4, Border.REPLICATE, 1f, 0f, out)
        assertArrayEquals("far outside: the nearest pixel", floatArrayOf(255f, 0f, 0f), out.copyOfRange(0, 3), 0f)
        // Exactly on pixel (1, 1) of the image: white, with the (32767, 0, 0, 1) weights of OpenCV's table.
        FrameSampler.sample(frame, 1.5f, 1.5f, 1f, 1f, 0f, 1, 1, Border.ZERO, 1f, 0f, out)
        assertArrayEquals(floatArrayOf(255f, 255f, 255f), out.copyOfRange(0, 3), 0f)
    }
}
