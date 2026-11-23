package tech.fonia.facecamera

import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * MediaPipe's One Euro smoothing. The expected values come from tools/face_reference/prototype_stream.py, a direct
 * transcription of one_euro_filter.cc and low_pass_filter.cc, whose stream mode tracks MediaPipe's VIDEO mode.
 */
class LandmarkSmootherTests {
    private val width = 480
    private val height = 640
    private val times = longArrayOf(1_000_000, 1_033_000, 1_066_000, 1_100_000, 1_133_000, 1_166_000)

    /** Two landmarks moving across six frames, 33 ms apart. */
    private fun input(k: Int) = floatArrayOf(
        0.40f + 0.01f * k, 0.50f - 0.005f * k * k, 0.01f * k,
        0.60f - 0.002f * k, 0.55f + 0.003f * k, -0.02f,
    )

    private val expected = listOf(
        floatArrayOf(0.400000006f, 0.5f, 0f, 0.600000024f, 0.550000012f, -0.0199999996f),
        floatArrayOf(0.40866974f, 0.495934874f, 0.0086697191f, 0.598865151f, 0.552169681f, -0.0199999996f),
        floatArrayOf(0.41910255f, 0.480934322f, 0.0191025548f, 0.596859634f, 0.555322766f, -0.0199999996f),
        floatArrayOf(0.429348141f, 0.455760092f, 0.0293481424f, 0.594688714f, 0.55849582f, -0.0199999996f),
        floatArrayOf(0.439430237f, 0.420698017f, 0.0394302346f, 0.592591286f, 0.561566472f, -0.0199999996f),
        floatArrayOf(0.449472785f, 0.375662833f, 0.0494727641f, 0.590537846f, 0.564601958f, -0.0199999996f),
    )

    @Test
    fun followsMediaPipe() {
        val smoother = LandmarkSmoother(2)
        val out = FloatArray(6)
        for (k in times.indices) {
            smoother.apply(input(k), width, height, times[k], out)
            assertArrayEquals("frame $k", expected[k], out, 2e-6f)
        }
    }

    @Test
    fun resetPassesTheNextFrameThrough() {
        val smoother = LandmarkSmoother(2)
        val out = FloatArray(6)
        for (k in 0 until 3) smoother.apply(input(k), width, height, times[k], out)
        smoother.reset()
        smoother.apply(input(5), width, height, times[5], out)
        assertArrayEquals(input(5), out, 0f)
    }

    @Test
    fun aRepeatedTimestampChangesNothing() {
        val smoother = LandmarkSmoother(2)
        val out = FloatArray(6)
        smoother.apply(input(0), width, height, times[0], out)
        smoother.apply(input(3), width, height, times[0], out)
        assertArrayEquals(input(3), out, 0f)
        // and the filter state is untouched: the next frame continues from frame 0.
        smoother.apply(input(1), width, height, times[1], out)
        assertArrayEquals(expected[1], out, 2e-6f)
    }
}
