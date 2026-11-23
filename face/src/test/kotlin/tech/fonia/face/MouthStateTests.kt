package tech.fonia.face

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DisplayName("Mouth measures, rest baseline, quality gate and mouth state (H2)")
class MouthStateTests {
    private val spec = CameraSpec()

    private fun measure(face: SyntheticFace): MouthMeasures = MouthMeasures().apply { measure(face.frame(0)) }

    @Test
    @DisplayName("measures are relative to the eye distance: the same face near and far gives the same numbers")
    fun distanceInvariant() {
        val face = SyntheticFace(aperture = 0.2f, mouthWidth = 0.85f)
        val near = measure(face.copy(eyePx = 160f))
        val far = measure(face.copy(eyePx = 55f, centerX = 200f, centerY = 300f))
        for ((name, a, b) in listOf(
            Triple("width", near.widthRel, far.widthRel),
            Triple("aperture", near.apertureRel, far.apertureRel),
            Triple("jaw", near.jawRel, far.jawRel),
            Triple("area", near.innerAreaRel, far.innerAreaRel),
            Triple("drop", near.mouthDropRel, far.mouthDropRel),
        )) {
            assertEquals(a, b, 1e-3f, name)
        }
        assertEquals(0.85f, near.widthRel, 1e-3f)
        // Three middle gaps of a sine-shaped opening: 0.967 of the middle one.
        assertEquals(0.2f * 0.967f, near.apertureRel, 2e-3f)
    }

    @Test
    @DisplayName("roll and yaw do not change the relative width and aperture")
    fun poseInvariant() {
        val face = SyntheticFace(aperture = 0.15f)
        val straight = measure(face)
        val rolled = measure(face.copy(rollDeg = 18f))
        val turned = measure(face.copy(yawDeg = 25f))
        val tilted = measure(face.copy(pitchDeg = 15f))
        assertEquals(straight.widthRel, rolled.widthRel, 1e-3f)
        assertEquals(straight.apertureRel, rolled.apertureRel, 1e-3f)
        assertEquals(straight.widthRel, turned.widthRel, 1e-3f)
        assertEquals(straight.apertureRel, turned.apertureRel, 1e-3f)
        assertEquals(straight.apertureRel, tilted.apertureRel, 1e-3f)
        assertEquals(Math.toRadians(18.0).toFloat(), rolled.rollRad, 1e-3f)
    }

    @Test
    @DisplayName("the rest is taken from 20 steady closed-mouth frames, and not from a moving mouth")
    fun restCalibration() {
        val engine = MouthStateEngine(spec)
        val rest = SyntheticFace(mouthWidth = 0.82f)
        // Gate needs 300 ms clean first, then 20 frames.
        var (state, t) = engine.feed(rest, 10, 0)
        assertEquals(RestSource.Default, state.rest)
        val r = engine.feed(rest, 25, t, state)
        state = r.first
        assertEquals(RestSource.Calibrated, state.rest)
        assertEquals(0.82f, engine.baseline.widthRel, 1e-3f)
        assertEquals(1f, state.width, 1e-3f)

        // A mouth that keeps moving never passes the steadiness test; after 4 s the quietest frames are used.
        val moving = MouthStateEngine(spec)
        val frame = FaceMeshFrame()
        val s = MouthState()
        var time = 0L
        for (k in 0 until 100) {
            val w = if (k % 2 == 0) 0.78f else 0.86f
            moving.process(rest.copy(mouthWidth = w).frame(time, frame), s)
            time += FRAME_NANOS
            if (k < 110) assertTrue(s.rest != RestSource.Calibrated, "frame $k")
        }
        for (k in 0 until 40) {
            moving.process(rest.copy(mouthWidth = 0.80f, aperture = if (k % 2 == 0) 0.3f else 0f).frame(time, frame), s)
            time += FRAME_NANOS
        }
        assertEquals(RestSource.Auto, s.rest)
        assertTrue(moving.baseline.aperture < 0.01f, "auto rest from closed frames: ${moving.baseline.aperture}")
    }

    @Test
    @DisplayName("rest calibrator rejects width jitter above 3 %")
    fun restCv() {
        val calibrator = RestCalibrator(RestSpec())
        val m = MouthMeasures()
        val f = FaceMeshFrame()
        var t = 0L
        for (k in 0 until 40) {
            SyntheticFace(mouthWidth = if (k % 2 == 0) 0.76f else 0.84f).frame(t, f)
            m.measure(f)
            assertNull(calibrator.add(f, m, good = true, speaking = false))
            t += FRAME_NANOS
        }
        for (k in 0 until 20) {
            SyntheticFace(mouthWidth = 0.80f + (k % 2) * 0.004f).frame(t, f)
            m.measure(f)
            calibrator.add(f, m, good = true, speaking = false)
            t += FRAME_NANOS
        }
        val b = assertNotNull(calibrator.result)
        assertEquals(RestSource.Calibrated, b.source)
        assertTrue(b.widthCv < 0.03f)
    }

    private fun calibrated(): Pair<MouthStateEngine, Long> {
        val engine = MouthStateEngine(spec)
        val (_, t) = engine.feed(SyntheticFace(), 40, 0)
        check(engine.baseline.isCalibrated)
        return Pair(engine, t)
    }

    @Test
    @DisplayName("pucker rounds, smile spreads, opening opens, closed lips close")
    fun shapes() {
        val (engine, t0) = calibrated()
        val (pucker, t1) = engine.feed(SyntheticFace(mouthWidth = 0.64f, aperture = 0.04f, pucker = 0.7f), 30, t0)
        assertTrue(pucker.round > 0.7f, "round ${pucker.round}")
        assertEquals(0f, pucker.spread, 1e-3f)
        assertFalse(pucker.lipsClosed)

        val (smile, t2) = engine.feed(SyntheticFace(mouthWidth = 0.94f, smile = 0.6f), 30, t1)
        assertTrue(smile.spread > 0.9f, "spread ${smile.spread}")
        assertTrue(smile.width > 1.15f)
        assertTrue(smile.round < 0.05f)
        assertTrue(smile.smile)

        val (open, t3) = engine.feed(SyntheticFace(aperture = 0.3f, jawExtra = 0.1f), 30, t2)
        assertTrue(open.aperture > 0.33f && open.aperture < 0.40f, "aperture ${open.aperture}")
        assertTrue(open.open > 0.6f)
        assertTrue(open.jaw > 0.2f, "jaw ${open.jaw}")
        assertTrue(open.apertureMm > 10f, "mm ${open.apertureMm}")
        assertTrue(open.round < 0.1f, "a wide-open jaw is not rounding: ${open.round}")

        val (closed, _) = engine.feed(SyntheticFace(), 30, t3)
        assertTrue(closed.lipsClosed)
        assertEquals(0f, closed.open, 0.02f)
        assertEquals(FaceQuality.Good, closed.quality)
    }

    @Test
    @DisplayName("millimetres come from the iris: a child's 10.6 mm iris scales a 0.8 E mouth to about 46 mm")
    fun millimetres() {
        val (engine, t) = calibrated()
        val (s, _) = engine.feed(SyntheticFace(), 5, t)
        // eye distance = 63/11.7 iris; width 0.8 E → 0.8·63·(10.6/11.7) mm.
        assertEquals(0.8f * 63f * 10.6f / 11.7f, s.widthMm, 0.5f)
        engine.irisMm = 11.7f
        val (adult, _) = engine.feed(SyntheticFace(), 2, t + 10 * FRAME_NANOS)
        assertEquals(0.8f * 63f, adult.widthMm, 0.5f)
    }

    @Test
    @DisplayName("smoothing removes landmark jitter on a held mouth")
    fun smoothing() {
        val (engine, t0) = calibrated()
        val frame = FaceMeshFrame()
        val s = MouthState()
        var t = t0
        val rnd = java.util.Random(7)
        val raw = ArrayList<Float>()
        val smooth = ArrayList<Float>()
        for (k in 0 until 120) {
            val a = 0.2f + (rnd.nextFloat() - 0.5f) * 0.04f
            engine.process(SyntheticFace(aperture = a).frame(t, frame), s)
            raw.add(engine.measures.apertureRel / engine.baseline.widthRel)
            smooth.add(s.aperture)
            t += FRAME_NANOS
        }
        fun sd(x: List<Float>): Double {
            val tail = x.drop(30)
            val m = tail.average()
            return kotlin.math.sqrt(tail.sumOf { (it - m) * (it - m) } / tail.size)
        }
        assertTrue(sd(smooth) < 0.6 * sd(raw), "raw ${sd(raw)} smooth ${sd(smooth)}")
    }

    @Test
    @DisplayName("quality gate: too far, too close, turned, dark, partial, absent — each after its hold")
    fun quality() {
        fun issueAfter(face: SyntheticFace?, frames: Int = 20): Pair<FaceQuality, QualityIssue> {
            val (engine, t0) = calibrated()
            val frame = FaceMeshFrame()
            val s = MouthState()
            var t = t0
            repeat(frames) {
                if (face == null) frame.clear(t) else face.frame(t, frame)
                engine.process(frame, s)
                t += FRAME_NANOS
            }
            return Pair(s.quality, s.issue)
        }
        assertEquals(Pair(FaceQuality.Weak, QualityIssue.TooFar), issueAfter(SyntheticFace(eyePx = 35f)))
        assertEquals(Pair(FaceQuality.Weak, QualityIssue.TooClose), issueAfter(SyntheticFace(eyePx = 270f, centerY = 120f)))
        assertEquals(Pair(FaceQuality.Weak, QualityIssue.TurnToCamera), issueAfter(SyntheticFace(yawDeg = 30f)))
        assertEquals(Pair(FaceQuality.Weak, QualityIssue.TooDark), issueAfter(SyntheticFace(lumaMouth = 30f)))
        assertEquals(Pair(FaceQuality.Weak, QualityIssue.PartialFace), issueAfter(SyntheticFace(centerY = 500f)))
        assertEquals(Pair(FaceQuality.Absent, QualityIssue.NoFace), issueAfter(null))
        // Hysteresis: a 100 ms problem does not drop the gate.
        assertEquals(FaceQuality.Good, issueAfter(SyntheticFace(yawDeg = 30f), frames = 3).first)
    }

    @Test
    @DisplayName("the gate drops below 12 frames per second")
    fun slowFrames() {
        val engine = MouthStateEngine(spec)
        val frame = FaceMeshFrame()
        val s = MouthState()
        var t = 0L
        repeat(30) {
            engine.process(SyntheticFace().frame(t, frame), s)
            t += 120_000_000L // 8 fps
        }
        assertEquals(QualityIssue.Unsteady, s.issue)
    }

    @Test
    @DisplayName("one-euro converges and hysteresis waits for its holds")
    fun filters() {
        val f = OneEuro(1.5f, 0.3f)
        var t = 0L
        var y = 0f
        for (k in 0 until 60) {
            y = f.filter(1f, t)
            t += FRAME_NANOS
        }
        assertTrue(abs(y - 1f) < 1e-3f)
        val h = Hysteresis(300_000_000L, 400_000_000L)
        assertFalse(h.update(true, false, 0))
        assertFalse(h.update(true, false, 299_000_000L))
        assertTrue(h.update(true, false, 300_000_000L))
        assertTrue(h.update(false, true, 400_000_000L))
        assertTrue(h.update(false, true, 799_000_000L))
        assertFalse(h.update(false, true, 800_000_000L))
    }

    @Test
    @DisplayName("the per-frame path allocates nothing once the rest is taken")
    fun noAllocation() {
        val bean = java.lang.management.ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean ?: return
        val (engine, t0) = calibrated()
        val frame = FaceMeshFrame()
        val s = MouthState()
        val frames = listOf(SyntheticFace(aperture = 0.2f), SyntheticFace(mouthWidth = 0.7f, pucker = 0.5f)).map { it.frame(0) }
        val evidence = CameraEvidence()
        val matcher = TargetMatcher(PoseSpec.bundled(tech.fonia.model.SoundId.Sh))
        val outer = FloatArray(40)
        val inner = FloatArray(40)
        var t = t0
        fun step(k: Int) {
            frame.copyFrom(frames[k % 2])
            frame.tNanos = t
            engine.process(frame, s); matcher.update(s, evidence); engine.targetRings(frame, matcher.pose.lips, outer, inner)
            t += FRAME_NANOS
        }
        repeat(60) { step(it) }
        val id = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(id)
        repeat(300) { step(it) }
        val bytes = bean.getThreadAllocatedBytes(id) - before
        assertTrue(bytes < 16_000, "$bytes bytes over 300 frames")
    }
}
