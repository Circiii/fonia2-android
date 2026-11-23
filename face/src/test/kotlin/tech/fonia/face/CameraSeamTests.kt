package tech.fonia.face

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Not iOS tests: ARKitFaceSource's control flow runs only on a device there. These run the ported
 * flow ([TrackerFaceSource], [FaceFrameReceiver]) over a scripted tracker, the way the Android
 * camera source will drive it.
 */
@DisplayName("Camera seam")
class CameraSeamTests {
    private class ScriptedTracker(
        override val isSupported: Boolean = true,
        private val permission: () -> Unit = {},
    ) : FaceTracker {
        override val canDetectTongue = false
        override val canEstimateEyeGaze = true
        var sink: FaceObservationSink? = null
        var runs = 0
        var pauses = 0

        override suspend fun requireCameraPermission() = permission()

        override fun run(sink: FaceObservationSink) {
            this.sink = sink
            runs += 1
        }

        override fun pause() {
            pauses += 1
        }
    }

    private val smiling = FaceObservation(
        blendshapes = BlendshapeValues(jawOpen = 0.5f, mouthSmileLeft = 0.2f, mouthSmileRight = 0.4f),
        faceToCamera = Float4x4.IDENTITY,
        lookAt = Float3(0f, 0f, 1f),
    )

    @Test
    @DisplayName("a tracker frame becomes a face reading")
    fun framesBecomeReadings() {
        val tracker = ScriptedTracker()
        val source = TrackerFaceSource(tracker, FaceSourceKind.ARKIT)
        val levels = FaceLevels()
        runSuspend { source.start(levels) }
        assertEquals(1, tracker.runs)
        tracker.sink!!.onFrame(smiling)
        assertTrue(levels.isPresent)
        assertEquals(0.5f, levels.jawOpen)
        assertTrue(isClose(levels.smile, 0.3f, within = 0.0001f))
        assertFalse(source.canDetectTongue)
        tracker.sink!!.onFrame(null)
        assertFalse(levels.isPresent)
    }

    @Test
    @DisplayName("stopping marks the face absent and drops every later frame")
    fun stopIsFinal() {
        val tracker = ScriptedTracker()
        val source = TrackerFaceSource(tracker, FaceSourceKind.ARKIT)
        val levels = FaceLevels()
        runSuspend { source.start(levels) }
        val sink = tracker.sink!!
        sink.onFrame(smiling)
        source.stop()
        assertEquals(1, tracker.pauses)
        assertFalse(levels.isPresent)
        sink.onFrame(smiling)
        assertFalse(levels.isPresent)
        source.stop()
    }

    @Test
    @DisplayName("an unsupported tracker refuses to start")
    fun unsupported() {
        val tracker = ScriptedTracker(isSupported = false)
        val source = TrackerFaceSource(tracker, FaceSourceKind.VISION)
        assertSame(FaceSourceError.Unsupported, assertFailsWith<FaceSourceError> { runSuspend { source.start(FaceLevels()) } })
        assertEquals(0, tracker.runs)
    }

    @Test
    @DisplayName("a refused camera reports it and never runs")
    fun denied() {
        val tracker = ScriptedTracker(permission = { throw FaceSourceError.CameraDenied })
        val source = TrackerFaceSource(tracker, FaceSourceKind.ARKIT)
        assertSame(FaceSourceError.CameraDenied, assertFailsWith<FaceSourceError> { runSuspend { source.start(FaceLevels()) } })
        assertEquals(0, tracker.runs)
    }

    @Test
    @DisplayName("a mirror that left the screen during the permission prompt keeps the camera off")
    fun stoppedDuringPrompt() {
        lateinit var source: TrackerFaceSource
        val tracker = ScriptedTracker(permission = { source.stop() })
        source = TrackerFaceSource(tracker, FaceSourceKind.ARKIT)
        val levels = FaceLevels()
        runSuspend { source.start(levels) }
        assertEquals(0, tracker.runs)
        assertFalse(levels.isPresent)
    }

    @Test
    @DisplayName("interruptions and failures mark the face absent")
    fun interruptions() {
        val receiver = FaceFrameReceiver()
        val levels = FaceLevels()
        receiver.attach(levels)
        receiver.onFrame(smiling)
        receiver.onInterrupted()
        assertFalse(levels.isPresent)
        receiver.onFrame(smiling)
        receiver.onFailure(IllegalStateException("tracking failed"))
        assertFalse(levels.isPresent)
    }

    @Test
    @DisplayName("scores map by ARKit name, and a model without tongueOut reads 0")
    fun scoresByName() {
        val values = BlendshapeValues.fromScores(
            mapOf("_neutral" to 0.9f, "jawOpen" to 0.4f, "mouthSmileLeft" to 0.1f, "mouthSmileRight" to 0.3f, "mouthFunnel" to 0.2f, "mouthPucker" to 0.6f, "cheekPuff" to 0.05f),
        )
        assertEquals(BlendshapeValues(jawOpen = 0.4f, mouthSmileLeft = 0.1f, mouthSmileRight = 0.3f, mouthFunnel = 0.2f, mouthPucker = 0.6f, cheekPuff = 0.05f, tongueOut = 0f), values)
        assertEquals(7, BlendshapeValues.NAMES.size)
    }

    @Test
    @DisplayName("a column-major matrix keeps its columns")
    fun columnMajor() {
        val m = Float4x4.columnMajor(FloatArray(16) { it.toFloat() })
        assertEquals(Float4(8f, 9f, 10f, 11f), m.c2)
        assertEquals(m, m * Float4x4.IDENTITY)
        assertEquals(Float4(12f, 13f, 14f, 15f), m * Float4(0f, 0f, 0f, 1f))
    }

    @Test
    @DisplayName("the landmark path puts the head direction in the gaze and the mouth from geometry")
    fun landmarkReading() {
        val landmarks = MouthLandmarks(
            leftPupil = Float2(270f, 340f),
            rightPupil = Float2(370f, 340f),
            outerLips = listOf(Float2(260f, 240f), Float2(320f, 252f), Float2(380f, 240f), Float2(320f, 228f)),
            innerLips = listOf(Float2(300f, 240f), Float2(320f, 241f), Float2(340f, 240f), Float2(320f, 239f)),
        )
        val frame = FaceFrame.fromLandmarks(yawRadians = 0.1f, pitchRadians = -0.2f, landmarks = landmarks)
        assertTrue(isClose(frame.gazeYaw, 5.7296f, within = 0.001f))
        assertEquals(frame.gazeYaw, frame.headYaw)
        assertEquals(frame.gazePitch, frame.headPitch)
        assertEquals(1f, frame.smile)
        assertEquals(0f, frame.pucker)
        assertEquals(0f, frame.tongueOut)
        val noShape = FaceFrame.fromLandmarks(yawRadians = null, pitchRadians = null, landmarks = null)
        assertEquals(FaceFrame(), noShape)
        assertNull(MouthShape(landmarks.copy(innerLips = emptyList())))
    }
}
