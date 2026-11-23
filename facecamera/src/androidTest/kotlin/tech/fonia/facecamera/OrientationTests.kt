package tech.fonia.facecamera

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Rotation and mirroring. A camera hands over frames in sensor orientation (front cameras are usually mounted at
 * 270°) and CameraX says how to turn them upright; the analysis buffer is not mirrored, the preview is. Whatever the
 * buffer's orientation, the pipeline must see the upright, unmirrored image ARKit's portrait camera space describes.
 */
@RunWith(AndroidJUnit4::class)
class OrientationTests {
    private val pipeline get() = TestPipeline.pipeline

    private class Result(val landmarks: FloatArray, val scores: FloatArray, val headYaw: Float)

    private fun analyse(frame: RgbaFrame): Result {
        assertTrue(pipeline.process(frame, 0, stream = false))
        val observation = pipeline.observation(BlendshapeCalibration.IDENTITY, FloatArray(7))
        return Result(pipeline.landmarks.copyOf(), pipeline.scores.copyOf(), observation.toFrame().headYaw)
    }

    @Test
    fun sensorOrientationsGiveTheSameFace() {
        for (name in listOf("smile", "wide_open", "pout")) {
            val image = AndroidFixtures.argb("$name.png")
            val upright = analyse(AndroidFixtures.frame(name))
            for (rotation in listOf(90, 180, 270)) {
                for (mirrored in listOf(false, true)) {
                    val (bytes, w, h) = image.asBuffer(rotation, mirrored)
                    val turned = analyse(RgbaFrame().apply { set(bytes, w, h, w * 4, rotation, mirrored) })
                    assertArrayEquals("$name $rotation° mirrored=$mirrored", upright.landmarks, turned.landmarks, 1e-6f)
                    assertArrayEquals("$name $rotation° mirrored=$mirrored", upright.scores, turned.scores, 1e-6f)
                }
            }
        }
    }

    @Test
    fun anUnflaggedMirrorImageIsAnotherFace() {
        // Fed a mirror image as if it were the camera's view, the head turns the other way and the face's left and
        // right swap: the convention (positive yaw towards the right of the unmirrored image) depends on the flag.
        val image = AndroidFixtures.argb("pout.png")
        val upright = analyse(AndroidFixtures.frame("pout"))
        val (bytes, w, h) = image.asBuffer(0, mirrored = true)
        val mirror = analyse(RgbaFrame().apply { set(bytes, w, h, w * 4, 0, false) })
        report("pout head yaw ${upright.headYaw}°, mirror image ${mirror.headYaw}°")
        assertTrue(upright.headYaw > 30f)
        assertTrue(mirror.headYaw < -30f)
        assertEquals(upright.headYaw, -mirror.headYaw, 3f)

        val smile = analyse(AndroidFixtures.frame("smile"))
        val (sb, sw, sh) = AndroidFixtures.argb("smile.png").asBuffer(0, mirrored = true)
        val smileMirror = analyse(RgbaFrame().apply { set(sb, sw, sh, sw * 4, 0, false) })
        val left = Blendshapes.NAMES.indexOf("mouthSmileLeft")
        val right = Blendshapes.NAMES.indexOf("mouthSmileRight")
        report("smile L/R ${smile.scores[left]}/${smile.scores[right]}, mirror image ${smileMirror.scores[left]}/${smileMirror.scores[right]}")
        // The network is not exactly symmetric, so the values do not swap exactly, but the stronger side does.
        assertTrue(smile.scores[left] < smile.scores[right])
        assertTrue(smileMirror.scores[left] > smileMirror.scores[right])
    }
}
