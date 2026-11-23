package tech.fonia.features.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.face.CameraAdvice
import tech.fonia.face.EvidenceStatus
import tech.fonia.face.FaceQuality
import tech.fonia.face.MirrorSnapshot
import tech.fonia.face.QualityIssue

class CoachVisionTests {
    private val ms = 1_000_000L

    private fun snap(quality: FaceQuality = FaceQuality.Good, issue: QualityIssue = QualityIssue.None, status: EvidenceStatus = EvidenceStatus.Adjust, advice: CameraAdvice? = null) =
        MirrorSnapshot().apply {
            hasFace = true
            imageW = 480
            imageH = 640
            state.quality = quality
            state.issue = issue
            evidence.status = status
            evidence.advice = advice
        }

    @Test
    fun eachCameraKeyHasItsCorrection() {
        assertEquals(Correction.Smile, correctionFor(snap(advice = CameraAdvice.SPREAD_LIPS)))
        assertEquals(Correction.Round, correctionFor(snap(advice = CameraAdvice.ROUND_LIPS)))
        assertEquals(Correction.Open, correctionFor(snap(advice = CameraAdvice.OPEN_MORE)))
        assertEquals(Correction.Close, correctionFor(snap(advice = CameraAdvice.OPEN_LESS)))
        assertEquals(Correction.Closer, correctionFor(snap(FaceQuality.Weak, QualityIssue.TooFar)))
        assertEquals(Correction.Light, correctionFor(snap(FaceQuality.Weak, QualityIssue.TooDark)))
        assertEquals(Correction.Match, correctionFor(snap(status = EvidenceStatus.Matches)))
        assertEquals(Correction.NoFace, correctionFor(MirrorSnapshot()))
    }

    @Test
    fun wordsAreAtMostTwo() {
        Correction.entries.forEach { assertTrue("${it.name}: ${it.words}", it.words.isEmpty() || it.words.split(' ').size <= 2) }
    }

    @Test
    fun pickerDoesNotFlickerBetweenTwoHints() {
        val p = CorrectionPicker()
        var t = 0L
        // Alternating every 100 ms never gets past the switch delay: nothing is shown.
        repeat(20) { i ->
            p.update(if (i % 2 == 0) Correction.Smile else Correction.Open, t)
            t += 100 * ms
        }
        assertEquals(Correction.None, p.shown)
        // A steady hint comes in after 450 ms and stays at least 900 ms even if the camera changes its mind.
        p.update(Correction.Smile, t); t += 500 * ms
        assertEquals(Correction.Smile, p.update(Correction.Smile, t))
        t += 100 * ms
        p.update(Correction.Open, t); t += 500 * ms
        assertEquals(Correction.Smile, p.update(Correction.Open, t))
        t += 400 * ms
        assertEquals(Correction.Open, p.update(Correction.Open, t))
    }

    @Test
    fun aMatchIsPraisedQuickly() {
        val p = CorrectionPicker()
        p.update(Correction.Smile, 0); p.update(Correction.Smile, 500 * ms)
        p.update(Correction.Match, 600 * ms)
        assertEquals(Correction.Match, p.update(Correction.Match, 750 * ms))
    }

    @Test
    fun holdCompletesOncePerPose() {
        val h = HoldTimer(1_000 * ms)
        var t = 0L
        var done = 0
        repeat(30) { t += 50 * ms; if (h.update(true, t)) done++ }
        assertEquals(1, done)
        assertEquals(1f, h.fraction, 1e-3f)
        repeat(20) { t += 50 * ms; h.update(false, t) }
        repeat(30) { t += 50 * ms; if (h.update(true, t)) done++ }
        assertEquals(2, done)
    }

    @Test
    fun framingIsDampedClampedAndCoversThePane() {
        val f = CropFollower()
        var t = 0L
        // A small mouth low and to the right: the zoom grows toward the cap, never past it, and never jumps.
        var last = f.z
        repeat(120) {
            t += 16 * ms
            f.update(true, 700f, 1100f, 120f, 1080f, 1300f, t, reduceMotion = false)
            assertTrue("damped", f.z - last < 0.1f)
            last = f.z
            assertTrue(f.tx <= 0f && f.tx >= 1080f - 1080f * f.z - 0.01f)
            assertTrue(f.ty <= 0f && f.ty >= 1300f - 1300f * f.z - 0.01f)
        }
        assertEquals(2.0f, f.z, 0.05f)
        // No face for a while: back to the whole frame.
        repeat(200) { t += 16 * ms; f.update(false, 0f, 0f, 0f, 1080f, 1300f, t, reduceMotion = false) }
        assertEquals(1f, f.z, 0.03f)
        assertFalse(f.tx < -40f)
    }
}
