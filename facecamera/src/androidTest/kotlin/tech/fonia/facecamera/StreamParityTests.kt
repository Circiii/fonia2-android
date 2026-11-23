package tech.fonia.facecamera

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.max

/**
 * Stream mode (tracking from the previous frame, One Euro smoothing, losing and finding the face again) against
 * MediaPipe's VIDEO mode on 24 frames: a face sliding 4–6 px per frame, two gray frames, the face back.
 *
 * Tracking feeds each frame's landmarks into the next frame's crop, and OpenCV's 1/32-pixel resampling makes that
 * loop sensitive: a 1e-6 nudge of the first frame grows to ~5e-4 in landmarks and ~6e-3 in scores within ten frames,
 * in MediaPipe as in this port (tools/face_reference/sensitivity.py). So the bound here is looser than for stills.
 */
@RunWith(AndroidJUnit4::class)
class StreamParityTests {
    @Test
    fun videoModeMatchesMediaPipe() {
        val pipeline = TestPipeline.pipeline
        val sequence = SequenceReference(AndroidFixtures.text("sequence.json"))
        val source = AndroidFixtures.argb("smile.png")
        pipeline.reset()
        var worstLandmark = 0f
        var worstScore = 0f
        var worstRotation = 0.0
        for (k in sequence.offsets.indices) {
            val frame = RgbaFrame().apply {
                set(sequence.frame(k, source), sequence.frameWidth, sequence.frameHeight, sequence.frameWidth * 4, 0, false)
            }
            val found = pipeline.process(frame, sequence.timestampsMs[k] * 1000, stream = true)
            val ref = sequence.frames[k]
            assertEquals("frame $k: face found", ref != null, found)
            if (ref == null) continue
            for ((i, xyz) in ref.landmarks) {
                for (c in 0 until 3) worstLandmark = max(worstLandmark, abs(xyz[c] - pipeline.landmarks[i * 3 + c]))
            }
            worstScore = max(worstScore, maxAbsDiff(ref.blendshapes, pipeline.scores))
            worstRotation = max(worstRotation, maxRotationDiff(poseMatrix(pipeline.geometry), ref.matrix))
            if (k == 0 || k == 14) assertTrue("frame $k comes from the detector", pipeline.detected)
            if (k == 5) assertTrue("frame $k is tracked", !pipeline.detected)
        }
        report("stream: landmarks $worstLandmark, scores $worstScore, rotation $worstRotation over ${sequence.offsets.size} frames")
        assertTrue("landmarks $worstLandmark", worstLandmark < 2e-3f)
        assertTrue("scores $worstScore", worstScore < 0.03f)
        assertTrue("rotation $worstRotation", worstRotation < 0.01)
    }
}
