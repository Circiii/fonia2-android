package tech.fonia.face

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.lang.reflect.Modifier
import kotlin.concurrent.thread
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

// FoniaFaceTests/FaceTests.swift. The iOS-only suite "Camera view lifetime" and the simulator test
// of "Fake source" need UIKit and ARKit; face/PARITY.md lists them for the camera step.

@DisplayName("Blendshape mapping")
class BlendshapeMappingTests {
    private val identity = Float4x4.IDENTITY
    private val ahead = Float3(0f, 0f, 1f)

    @Test
    @DisplayName("each coefficient lands in its level")
    fun mapsEachValue() {
        val frame = FaceFrame(
            blendshapes = BlendshapeValues(
                jawOpen = 0.1f,
                mouthSmileLeft = 0.2f,
                mouthSmileRight = 0.4f,
                mouthFunnel = 0.5f,
                mouthPucker = 0.6f,
                cheekPuff = 0.7f,
                tongueOut = 0.8f,
            ),
            faceToCamera = identity,
            lookAt = ahead,
        )
        assertTrue(frame.isPresent)
        assertEquals(0.1f, frame.jawOpen)
        assertTrue(isClose(frame.smile, 0.3f, within = 0.0001f))
        assertEquals(0.5f, frame.funnel)
        assertEquals(0.6f, frame.pucker)
        assertEquals(0.6f, frame.round)
        assertEquals(0.7f, frame.puff)
        assertEquals(0.8f, frame.tongueOut)
    }

    @Test
    @DisplayName("values outside 0...1 are clamped")
    fun clamps() {
        val frame = FaceFrame(
            blendshapes = BlendshapeValues(jawOpen = 1.4f, mouthSmileLeft = -0.5f, mouthSmileRight = -0.5f),
            faceToCamera = identity,
            lookAt = ahead,
        )
        assertEquals(1f, frame.jawOpen)
        assertEquals(0f, frame.smile)
    }

    @ParameterizedTest
    @ValueSource(floats = [0f, 20f, -35f])
    @DisplayName("headYawFromMatrix")
    fun headYawFromMatrix(degrees: Float) {
        val frame = FaceFrame(blendshapes = BlendshapeValues(), faceToCamera = rotationY(degrees), lookAt = ahead)
        assertTrue(isClose(frame.headYaw, degrees))
        assertTrue(isClose(frame.headPitch, 0f))
    }

    @Test
    @DisplayName("looking up is positive pitch")
    fun headPitchFromMatrix() {
        val frame = FaceFrame(blendshapes = BlendshapeValues(), faceToCamera = rotationX(-25f), lookAt = ahead)
        assertTrue(isClose(frame.headPitch, 25f))
        assertTrue(isClose(frame.headYaw, 0f))
    }

    @Test
    @DisplayName("gaze adds the eyes to the head")
    fun gazeCombinesEyesAndHead() {
        // Head turned 20 degrees, eyes turned back the same amount: the gaze is on the camera.
        val eyesBack = Float3(sin(-20f * PI_F / 180f), 0f, cos(-20f * PI_F / 180f))
        val frame = FaceFrame(blendshapes = BlendshapeValues(), faceToCamera = rotationY(20f), lookAt = eyesBack)
        assertTrue(isClose(frame.headYaw, 20f))
        assertTrue(isClose(frame.gazeYaw, 0f))
        assertTrue(frame.gazeAtCamera > 0.99f)
    }

    @Test
    @DisplayName("the gaze ignores the face's position, only its direction counts")
    fun translationDoesNotMoveGaze() {
        val pose = Float4x4.IDENTITY.copy(c3 = Float4(0.1f, -0.2f, -0.4f, 1f))
        val frame = FaceFrame(blendshapes = BlendshapeValues(), faceToCamera = pose, lookAt = ahead)
        assertTrue(isClose(frame.gazeYaw, 0f))
        assertTrue(isClose(frame.gazePitch, 0f))
    }

    @Test
    @DisplayName("a zero-length gaze point reads as straight ahead rather than NaN")
    fun zeroLookAt() {
        val frame = FaceFrame(blendshapes = BlendshapeValues(), faceToCamera = identity, lookAt = Float3.ZERO)
        assertEquals(0f, frame.gazeYaw)
        assertEquals(0f, frame.gazePitch)
    }

    @Test
    @DisplayName("gaze-at-camera falls to zero at 30 degrees")
    fun gazeAtCameraRange() {
        assertEquals(1f, FaceFrame().gazeAtCamera)
        assertTrue(isClose(FaceFrame(gazeYaw = 15f).gazeAtCamera, 0.5f))
        assertEquals(0f, FaceFrame(gazeYaw = 30f).gazeAtCamera)
        assertEquals(0f, FaceFrame(gazeYaw = 80f).gazeAtCamera)
        assertEquals(0f, FaceFrame.ABSENT.gazeAtCamera)
    }
}

@DisplayName("Landmark mapping")
class LandmarkMappingTests {
    /** A mouth with the given width and inner gap, in units of the pupil distance. */
    private fun landmarks(width: Float, gap: Float, rollDegrees: Float = 0f, scale: Float = 100f): MouthLandmarks {
        val a = rollDegrees * PI_F / 180f
        fun place(x: Float, y: Float): Float2 = Float2(x * cos(a) - y * sin(a), x * sin(a) + y * cos(a)) * scale + Float2(320f, 240f)
        return MouthLandmarks(
            leftPupil = place(-0.5f, 1f),
            rightPupil = place(0.5f, 1f),
            outerLips = listOf(place(-width / 2, 0f), place(0f, gap / 2 + 0.1f), place(width / 2, 0f), place(0f, -gap / 2 - 0.1f)),
            innerLips = listOf(place(-0.2f, 0f), place(0f, gap / 2), place(0.2f, 0f), place(0f, -gap / 2)),
        )
    }

    @Test
    @DisplayName("a resting mouth reads as none of the shapes")
    fun rest() {
        val shape = assertNotNull(MouthShape(landmarks(width = 0.8f, gap = 0.02f)))
        assertEquals(MouthShape(jaw = 0f, smile = 0f, round = 0f), shape)
    }

    @Test
    @DisplayName("a wide-open jaw reaches the top of the range")
    fun openJaw() {
        val shape = assertNotNull(MouthShape(landmarks(width = 0.8f, gap = 0.7f)))
        assertEquals(1f, shape.jaw)
        assertEquals(0f, shape.smile)
    }

    @Test
    @DisplayName("wide lips read as smile, narrow lips as round")
    fun widthDrivesSmileAndRound() {
        val wide = assertNotNull(MouthShape(landmarks(width = 1.2f, gap = 0.02f)))
        assertEquals(1f, wide.smile)
        assertEquals(0f, wide.round)
        val narrow = assertNotNull(MouthShape(landmarks(width = 0.45f, gap = 0.02f)))
        assertEquals(0f, narrow.smile)
        assertEquals(1f, narrow.round)
    }

    @ParameterizedTest
    @ValueSource(floats = [-25f, 10f, 40f])
    @DisplayName("headRollChangesNothing")
    fun headRollChangesNothing(rollDegrees: Float) {
        val upright = assertNotNull(MouthShape(landmarks(width = 1.0f, gap = 0.3f)))
        val tilted = assertNotNull(MouthShape(landmarks(width = 1.0f, gap = 0.3f, rollDegrees = rollDegrees)))
        assertTrue(isClose(tilted.jaw, upright.jaw))
        assertTrue(isClose(tilted.smile, upright.smile))
    }

    @Test
    @DisplayName("distance from the camera changes nothing")
    fun scaleChangesNothing() {
        val near = assertNotNull(MouthShape(landmarks(width = 1.0f, gap = 0.3f, scale = 200f)))
        val far = assertNotNull(MouthShape(landmarks(width = 1.0f, gap = 0.3f, scale = 40f)))
        assertTrue(isClose(near.jaw, far.jaw))
        assertTrue(isClose(near.smile, far.smile))
    }

    @Test
    @DisplayName("missing lip points or coincident pupils give no shape")
    fun degenerate() {
        val noLips = landmarks(width = 1f, gap = 0.3f).copy(outerLips = emptyList())
        assertNull(MouthShape(noLips))
        val base = landmarks(width = 1f, gap = 0.3f)
        val samePupils = base.copy(rightPupil = base.leftPupil)
        assertNull(MouthShape(samePupils))
    }
}

@DisplayName("Levels and mirror")
class LevelsTests {
    private val frame = FaceFrame(
        jawOpen = 0.1f,
        smile = 0.2f,
        funnel = 0.3f,
        pucker = 0.4f,
        puff = 0.5f,
        tongueOut = 0.6f,
        gazeYaw = 7f,
        gazePitch = -8f,
        headYaw = 9f,
        headPitch = -10f,
    )

    @Test
    @DisplayName("levels hand back exactly what was published, and start absent")
    fun roundTrip() {
        val levels = FaceLevels()
        assertFalse(levels.isPresent)
        levels.publish(frame)
        assertEquals(frame, levels.snapshot())
        assertEquals(0.4f, levels.round)
        levels.publish(FaceFrame.ABSENT)
        assertFalse(levels.isPresent)
    }

    @Test
    @DisplayName("levels can be written and read from different threads")
    fun concurrentAccess() {
        val levels = FaceLevels()
        val frame = frame
        val writer = thread { repeat(10_000) { levels.publish(frame) } }
        val reader = thread { repeat(10_000) { levels.snapshot() } }
        writer.join()
        reader.join()
        assertEquals(frame, levels.snapshot())
    }

    @Test
    @DisplayName("the mirror follows the face like the web's mirrorFromFace")
    fun mirrorInput() {
        val input = assertNotNull(MirrorInput(frame))
        assertEquals(0.1f, input.jaw)
        assertEquals(0.2f, input.smile)
        assertEquals(0.4f, input.round)
        assertEquals(0.5f, input.puff)
        assertTrue(input.tongueOut)
    }

    @Test
    @DisplayName("no face means no mirror input")
    fun mirrorWithoutFace() {
        assertNull(MirrorInput(FaceFrame.ABSENT))
        assertNull(MirrorInput(levels = FaceLevels()))
    }

    @Test
    @DisplayName("the tongue counts as out only past the threshold")
    fun tongueThreshold() {
        val below = assertNotNull(MirrorInput(FaceFrame(tongueOut = 0.3f)))
        assertFalse(below.tongueOut)
        val above = assertNotNull(MirrorInput(FaceFrame(tongueOut = 0.4f)))
        assertTrue(above.tongueOut)
    }
}

@DisplayName("Fake source")
@Timeout(60)
class FakeSourceTests {
    @Test
    @DisplayName("it plays its script and reports its capabilities")
    fun playsScript() {
        val levels = FaceLevels()
        val source = FakeFaceSource(
            script = listOf(
                FakeFaceSource.Step(frame = FaceFrame(jawOpen = 0.25f), hold = 5.milliseconds),
                FakeFaceSource.Step(frame = FaceFrame(jawOpen = 0.75f), hold = 5.milliseconds),
            ),
            loops = false,
        )
        assertEquals(FaceSourceKind.FAKE, source.kind)
        assertTrue(source.isSupported)

        runSuspend { source.start(levels) }
        assertEquals(0.25f, levels.jawOpen)
        while (levels.jawOpen != 0.75f) Thread.sleep(5)
        source.stop()
    }

    @Test
    @DisplayName("stopping marks the face absent and no later frame brings it back")
    fun stopIsFinal() {
        val levels = FaceLevels()
        val source = FakeFaceSource()
        runSuspend { source.start(levels) }
        assertTrue(levels.isPresent)
        source.stop()
        assertFalse(levels.isPresent)
        Thread.sleep(300)
        assertFalse(levels.isPresent)
    }

    @Test
    @DisplayName("stop is safe when nothing is running")
    fun stopTwice() {
        val source = FakeFaceSource()
        source.stop()
        source.stop()
    }
}

@DisplayName("Presence")
class PresenceTests {
    private fun sample(milliseconds: Int, yaw: Float = 0f, pitch: Float = 0f, present: Boolean = true): PresenceSample =
        PresenceSample(
            time = milliseconds.toDouble() / 1000,
            isPresent = present,
            gazeYaw = yaw,
            gazePitch = pitch,
            headYaw = yaw,
            headPitch = pitch,
        )

    private fun addRange(tracker: PresenceTracker, from: Int, through: Int, yaw: Float = 0f, pitch: Float = 0f, present: Boolean = true) {
        for (t in from..through step 50) tracker.add(sample(t, yaw = yaw, pitch = pitch, present = present))
    }

    @Test
    @DisplayName("eye contact is measured against the first moments of the talk")
    fun relativeToStart() {
        val tracker = PresenceTracker()
        // Phone held low: "looking at the camera" is pitch -12 for this person.
        addRange(tracker, from = 0, through = 2000, pitch = -12f)
        assertEquals(true, tracker.isLookingAtCameraNow)
        addRange(tracker, from = 2050, through = 4000, yaw = 30f, pitch = -12f)
        assertEquals(false, tracker.isLookingAtCameraNow)
        addRange(tracker, from = 4050, through = 6000, pitch = -10f)

        val report = tracker.report()
        assertEquals(6.0, report.seconds)
        assertTrue(report.eyeContactFraction > 0.6 && report.eyeContactFraction < 0.75)
        assertTrue(report.eyeContactSeconds > 3.9 && report.eyeContactSeconds < 4.1)
        assertTrue(report.longestLookAwaySeconds > 1.8 && report.longestLookAwaySeconds < 2.2)
        assertEquals(6, report.timeline.size)
        assertEquals(0.0, report.timeline[3])
        assertEquals(1.0, report.timeline[0])
    }

    @Test
    @DisplayName("nothing is judged while calibrating")
    fun calibrating() {
        val tracker = PresenceTracker()
        addRange(tracker, from = 0, through = 1000)
        assertNull(tracker.isLookingAtCameraNow)
    }

    @Test
    @DisplayName("with no face there is no verdict and no measure")
    fun noFace() {
        val tracker = PresenceTracker()
        addRange(tracker, from = 0, through = 3000, present = false)
        assertNull(tracker.isLookingAtCameraNow)
        val report = tracker.report()
        assertEquals(0.0, report.eyeContactFraction)
        assertEquals(0.0, report.faceVisibleFraction)
        assertEquals(3.0, report.seconds)
        assertTrue(report.facts().isEmpty())
    }

    @Test
    @DisplayName("too few samples give an empty report")
    fun tooFew() {
        val tracker = PresenceTracker()
        tracker.add(sample(0))
        assertEquals(PresenceReport(), tracker.report())
    }

    @Test
    @DisplayName("a face gone from view counts as looking away, not as contact")
    fun faceLeaves() {
        val tracker = PresenceTracker()
        addRange(tracker, from = 0, through = 2000)
        addRange(tracker, from = 2050, through = 4000, present = false)
        addRange(tracker, from = 4050, through = 5000)
        val report = tracker.report()
        assertEquals(1.0, report.eyeContactFraction)
        assertTrue(report.faceVisibleFraction > 0.59 && report.faceVisibleFraction < 0.61)
        assertTrue(report.longestLookAwaySeconds > 1.99 && report.longestLookAwaySeconds < 2.01)
        assertNull(report.timeline[3])
    }

    @Test
    @DisplayName("head movement is the spread of the head's direction")
    fun headMovement() {
        val still = PresenceTracker()
        addRange(still, from = 0, through = 3000)
        assertEquals(0.0, still.report().headMovementDegrees)

        val moving = PresenceTracker()
        addRange(moving, from = 0, through = 2000)
        addRange(moving, from = 2050, through = 4000, yaw = 10f)
        val degrees = moving.report().headMovementDegrees
        assertTrue(degrees > 4 && degrees < 6)
    }

    @Test
    @DisplayName("uneven frame rates do not skew the share of eye contact")
    fun timeWeighted() {
        val tracker = PresenceTracker()
        addRange(tracker, from = 0, through = 2000)
        // Away for 2 s at a low frame rate, back for 2 s at a high one.
        tracker.add(sample(2050, yaw = 40f))
        tracker.add(sample(3050, yaw = 40f))
        for (t in 4050..6050 step 10) tracker.add(sample(t))
        val fraction = tracker.report().eyeContactFraction
        assertTrue(fraction > 0.65 && fraction < 0.68)
    }

    @Test
    @DisplayName("the facts are neutral sentences with correct Romanian plurals")
    fun factsWording() {
        val tracker = PresenceTracker()
        addRange(tracker, from = 0, through = 2000, pitch = -12f)
        addRange(tracker, from = 2050, through = 4000, yaw = 30f, pitch = -12f)
        addRange(tracker, from = 4050, through = 6000, pitch = -12f)
        val facts = tracker.report().facts()
        assertEquals(
            listOf(
                "Ai privit spre cameră 67% din timp.",
                "Te-ai uitat în altă parte cel mult 2 secunde la rând.",
                "Ai mișcat capul cam 14 grade.",
            ),
            facts,
        )
        assertEquals("Ai stat cu fața spre cameră 67% din timp.", tracker.report().facts(eyeGazeAvailable = false).first())
    }

    @ParameterizedTest
    @MethodSource("plurals")
    @DisplayName("plural")
    fun plural(count: Int, expected: String) {
        assertEquals(expected, romanianCount(count, one = "secundă", few = "secunde"))
    }

    @Test
    @DisplayName("the report has no field for emotion, confidence, expressiveness or smiling")
    fun noEmotionFields() {
        val forbidden = listOf("emotion", "express", "confiden", "smile", "mood", "nervous")
        val fields = PresenceReport::class.java.declaredFields.filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }.map { it.name }
        assertTrue(fields.isNotEmpty())
        for (field in fields) {
            for (word in forbidden) {
                assertFalse(field.lowercase().contains(word), "$field looks like an emotion measure")
            }
        }
    }

    @Test
    @DisplayName("smiling while looking at the camera changes nothing, since the sample has no smile")
    fun samplesCarryNoExpression() {
        val levels = FaceLevels()
        levels.publish(FaceFrame(smile = 1f, gazeYaw = 2f, gazePitch = 3f, headYaw = 4f, headPitch = 5f))
        val sample = PresenceSample(levels = levels, time = 7.0)
        assertEquals(PresenceSample(time = 7.0, isPresent = true, gazeYaw = 2f, gazePitch = 3f, headYaw = 4f, headPitch = 5f), sample)
        val fields = PresenceSample::class.java.declaredFields.filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }.map { it.name }
        assertFalse(fields.any { it.lowercase().contains("smile") })
    }

    companion object {
        @JvmStatic
        fun plurals(): List<Arguments> = listOf(
            Arguments.of(0, "0 secunde"), Arguments.of(1, "1 secundă"), Arguments.of(2, "2 secunde"), Arguments.of(19, "19 secunde"),
            Arguments.of(20, "20 de secunde"), Arguments.of(21, "21 de secunde"), Arguments.of(100, "100 de secunde"),
            Arguments.of(101, "101 secunde"), Arguments.of(119, "119 secunde"), Arguments.of(120, "120 de secunde"),
        )
    }
}
