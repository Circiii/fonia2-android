package tech.fonia.facecamera

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.max

/**
 * The three models on LiteRT, with this module's pre- and post-processing, against the official MediaPipe Face
 * Landmarker (Python 1.2.2, IMAGE mode) on the five still frames: same face found, same 478 landmarks, same 52
 * scores, same head pose.
 */
@RunWith(AndroidJUnit4::class)
class ModelParityTests {
    private val pipeline get() = TestPipeline.pipeline

    @Test
    fun stillImagesMatchMediaPipe() {
        var worstLandmark = 0f
        var worstScore = 0f
        var worstRotation = 0.0
        var worstTranslation = 0.0
        for (name in FACE_NAMES) {
            val ref = AndroidFixtures.reference(name)
            val frame = AndroidFixtures.frame(name)
            assertEquals(ref.width, frame.uprightWidth)
            assertTrue("$name: no face", pipeline.process(frame, 0, stream = false))
            val landmark = maxAbsDiff(ref.landmarks, pipeline.landmarks)
            val score = maxAbsDiff(ref.blendshapes, pipeline.scores)
            val ours = poseMatrix(pipeline.geometry)
            val rotation = maxRotationDiff(ours, ref.matrix)
            var translation = 0.0
            for (row in 0 until 3) translation = max(translation, abs(ours[row * 4 + 3] - ref.matrix[row * 4 + 3]))
            report("$name: landmarks ${"%.2e".format(landmark)}, scores ${"%.2e".format(score)}, rotation ${"%.2e".format(rotation)}, translation ${"%.2e".format(translation)} cm, presence ${pipeline.presence}, tongue ${pipeline.tongueOut}")
            worstLandmark = max(worstLandmark, landmark)
            worstScore = max(worstScore, score)
            worstRotation = max(worstRotation, rotation)
            worstTranslation = max(worstTranslation, translation)
        }
        report("worst: landmarks $worstLandmark, scores $worstScore, rotation $worstRotation, translation $worstTranslation cm")
        // Landmarks are 0...1 across the frame: 5e-4 is a quarter of a pixel at 480 px.
        assertTrue("landmarks $worstLandmark", worstLandmark < 5e-4f)
        assertTrue("scores $worstScore", worstScore < 5e-3f)
        assertTrue("rotation $worstRotation", worstRotation < 2e-3)
        assertTrue("translation $worstTranslation", worstTranslation < 0.05)
    }

    @Test
    fun noFaceInAGrayFrame() {
        val gray = ByteArray(480 * 640 * 4) { if (it % 4 == 3) -1 else 128.toByte() }
        val frame = RgbaFrame().apply { set(gray, 480, 640, 480 * 4, 0, false) }
        assertTrue(!pipeline.process(frame, 0, stream = false))
    }
}
