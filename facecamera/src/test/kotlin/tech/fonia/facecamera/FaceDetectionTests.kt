package tech.fonia.facecamera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.FloatBuffer
import kotlin.math.exp

/** The detector's post-processing on made-up model outputs; ModelParityTests runs it on the real ones. */
class FaceDetectionTests {
    private val decoder = FaceDetectionDecoder()
    private val boxes = FloatArray(FaceDetectionDecoder.NUM_BOXES * FaceDetectionDecoder.NUM_COORDS)
    private val scores = FloatArray(FaceDetectionDecoder.NUM_BOXES) { -10f }

    private fun box(index: Int, dx: Float, dy: Float, size: Float, eyeOffset: Float, rawScore: Float) {
        val b = index * FaceDetectionDecoder.NUM_COORDS
        boxes[b] = dx
        boxes[b + 1] = dy
        boxes[b + 2] = size
        boxes[b + 3] = size
        boxes[b + 4] = dx - eyeOffset // keypoint 0: the eye on the left of the image
        boxes[b + 5] = dy
        boxes[b + 6] = dx + eyeOffset // keypoint 1
        boxes[b + 7] = dy
        scores[index] = rawScore
    }

    private fun decode(width: Int, height: Int, rect: NormRect): Boolean =
        decoder.decode(FloatBuffer.wrap(boxes), FloatBuffer.wrap(scores), width, height, rect)

    private fun sigmoid(x: Float) = 1f / (1f + exp(-x))

    @Test
    fun anchors() {
        // 16×16 cells with 2 anchors, then 8×8 cells with 6, centred in their cells.
        assertEquals(0.5f / 16 to 0.5f / 16, decoder.anchor(0))
        assertEquals(0.5f / 16 to 0.5f / 16, decoder.anchor(1))
        assertEquals(1.5f / 16 to 0.5f / 16, decoder.anchor(2))
        assertEquals(15.5f / 16 to 15.5f / 16, decoder.anchor(511))
        assertEquals(0.5f / 8 to 0.5f / 8, decoder.anchor(512))
        assertEquals(0.5f / 8 to 0.5f / 8, decoder.anchor(517))
        assertEquals(1.5f / 8 to 0.5f / 8, decoder.anchor(518))
        assertEquals(7.5f / 8 to 7.5f / 8, decoder.anchor(895))
    }

    @Test
    fun noFaceBelowHalf() {
        assertFalse(decode(480, 640, NormRect()))
        scores[100] = -0.01f // sigmoid just under 0.5
        assertFalse(decode(480, 640, NormRect()))
    }

    @Test
    fun oneFaceOnAPortraitFrame() {
        // Anchor 208: cell (8, 6) of the 16×16 grid, centre (0.53125, 0.40625) of the square input.
        box(208, dx = 0f, dy = 0f, size = 32f, eyeOffset = 6.4f, rawScore = 5f)
        val rect = NormRect()
        assertTrue(decode(480, 640, rect))
        assertEquals(sigmoid(5f), decoder.score, 1e-6f)
        // The 480×640 frame sits in the middle of a 640×640 square: x = 4/3·x' − 1/6, y = y'.
        assertEquals(0.5416667f, rect.centerX, 1e-6f)
        assertEquals(0.40625f, rect.centerY, 1e-6f)
        assertEquals(0.5f, rect.width, 1e-6f) // 0.25 of 640 px is a third of 480 px, times 1.5
        assertEquals(0.375f, rect.height, 1e-6f)
        assertEquals(0f, rect.rotation, 1e-6f)
    }

    @Test
    fun weightedSuppressionMergesTheBestCluster() {
        box(208, dx = 0f, dy = 0f, size = 32f, eyeOffset = 6.4f, rawScore = 5f)
        box(209, dx = 4f, dy = 0f, size = 32f, eyeOffset = 6.4f, rawScore = 2f) // IoU 0.78 with the first
        box(900 - 10, dx = 0f, dy = 0f, size = 16f, eyeOffset = 3f, rawScore = 3f) // elsewhere: its own cluster
        val rect = NormRect()
        assertTrue(decode(128, 128, rect))
        val s1 = sigmoid(5f)
        val s2 = sigmoid(2f)
        val xmin = (0.40625f * s1 + (0.40625f + 4f / 128) * s2) / (s1 + s2)
        val xmax = (0.65625f * s1 + (0.65625f + 4f / 128) * s2) / (s1 + s2)
        assertEquals(xmin + (xmax - xmin) / 2, rect.centerX, 1e-6f)
        assertEquals((xmax - xmin) * 1.5f, rect.width, 1e-6f)
        assertEquals(s1, decoder.score, 1e-6f)
    }

    @Test
    fun rotationFollowsTheEyes() {
        // Keypoint 1 as far above keypoint 0 as to its right: eyes rising 45° to the right of the image. The region
        // turns the other way (negative: anticlockwise on screen) so that they come out level.
        box(208, dx = 0f, dy = 0f, size = 32f, eyeOffset = 6.4f, rawScore = 5f)
        boxes[208 * 16 + 7] = -12.8f
        val rect = NormRect()
        assertTrue(decode(128, 128, rect))
        assertEquals(-Math.PI.toFloat() / 4, rect.rotation, 1e-5f)
    }

    @Test
    fun normalizeRadiansWraps() {
        assertEquals(0f, normalizeRadians(0f), 0f)
        assertEquals(-Math.PI.toFloat() / 2, normalizeRadians(3 * Math.PI.toFloat() / 2), 1e-6f)
        assertEquals(-Math.PI.toFloat(), normalizeRadians(Math.PI.toFloat()), 1e-6f)
    }
}
