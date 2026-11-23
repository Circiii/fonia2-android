package tech.fonia.face

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import tech.fonia.model.SoundId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DisplayName("Pose specs, target checks and camera evidence (H2, H3)")
class PoseSpecTests {
    @Test
    @DisplayName("camera.json is bundled and equals the in-code defaults")
    fun cameraSpec() {
        assertEquals(CameraSpec(), CameraSpec.BUNDLED)
    }

    @Test
    @DisplayName("every sound has a bundled pose; the tongue is declared invisible to the camera for all of them")
    fun allPoses() {
        for (sound in SoundId.entries) {
            val pose = PoseSpec.bundled(sound)
            assertEquals(sound, pose.sound)
            assertFalse(pose.visible.tongue, "$sound: the camera never sees the tongue in speech")
            assertTrue(pose.visible.note!!.isNotBlank())
            assertTrue(pose.checks.isNotEmpty())
            assertEquals("draft", pose.reviewStatus)
        }
        // Logopedic convention (F3): rounded lips for Ș, J, CE, GE; spread for S, Z, Ț.
        for (s in listOf(SoundId.Sh, SoundId.J, SoundId.Ce, SoundId.Ge)) assertTrue(PoseSpec.bundled(s).lips.round > 0.3f, "$s")
        for (s in listOf(SoundId.S, SoundId.Z, SoundId.Ts)) {
            val lips = PoseSpec.bundled(s).lips
            assertEquals(0f, lips.round)
            assertTrue(lips.wide > 1f)
        }
        assertEquals(PoseSpec.bundled(SoundId.S).lips, PoseSpec.bundled(SoundId.Z).lips)
    }

    private fun state(aperture: Float = 0.05f, width: Float = 1f, round: Float = 0f, spread: Float = 0f, t: Long = 0) =
        MouthState().apply {
            quality = FaceQuality.Good
            issue = QualityIssue.None
            rest = RestSource.Calibrated
            this.aperture = aperture
            this.width = width
            this.round = round
            this.spread = spread
            tNanos = t
            confShape = 0.7f
        }

    private fun evidenceAfter(sound: SoundId, s: MouthState, ms: Long = 500): CameraEvidence {
        val matcher = TargetMatcher(PoseSpec.bundled(sound))
        val out = CameraEvidence()
        var t = 0L
        while (t <= ms * 1_000_000L) {
            s.tNanos = t
            matcher.update(s, out)
            t += FRAME_NANOS
        }
        return out
    }

    @Test
    @DisplayName("Ș matches rounded lips and asks to round spread ones; S the other way round")
    fun shAndS() {
        val rounded = state(aperture = 0.08f, width = 0.82f, round = 0.6f)
        val spread = state(aperture = 0.06f, width = 1.12f, spread = 0.8f)
        val sh = evidenceAfter(SoundId.Sh, rounded)
        assertEquals(EvidenceStatus.Matches, sh.status)
        assertTrue(sh.matched)
        assertNull(sh.advice)
        val shSpread = evidenceAfter(SoundId.Sh, spread)
        assertEquals(EvidenceStatus.Adjust, shSpread.status)
        assertEquals(CameraAdvice.ROUND_LIPS, shSpread.advice)

        assertEquals(EvidenceStatus.Matches, evidenceAfter(SoundId.S, spread).status)
        val sRounded = evidenceAfter(SoundId.S, rounded)
        assertEquals(EvidenceStatus.Adjust, sRounded.status)
        assertEquals(CameraAdvice.SPREAD_LIPS, sRounded.advice)
        // S with the mouth too open: bring the teeth together.
        assertEquals(CameraAdvice.OPEN_LESS, evidenceAfter(SoundId.S, state(aperture = 0.4f, width = 1.05f)).advice)
    }

    @Test
    @DisplayName("R and L ask to open the mouth; L with rounded lips (L→U) asks not to round")
    fun rAndL() {
        assertEquals(CameraAdvice.OPEN_MORE, evidenceAfter(SoundId.R, state(aperture = 0.02f)).advice)
        assertEquals(EvidenceStatus.Matches, evidenceAfter(SoundId.R, state(aperture = 0.25f)).status)
        assertEquals(CameraAdvice.LESS_ROUND, evidenceAfter(SoundId.L, state(aperture = 0.25f, width = 0.8f, round = 0.7f)).advice)
    }

    @Test
    @DisplayName("matching turns on after 300 ms at m ≥ 0.75 and off after 400 ms below 0.55; a weak gate abstains")
    fun hysteresis() {
        val matcher = TargetMatcher(PoseSpec.bundled(SoundId.Sh))
        val out = CameraEvidence()
        val good = state(aperture = 0.08f, width = 0.82f, round = 0.6f)
        val bad = state(aperture = 0.08f, width = 1.1f, round = 0f)
        good.tNanos = 0; matcher.update(good, out); assertFalse(out.matched)
        good.tNanos = 299_000_000; matcher.update(good, out); assertFalse(out.matched)
        good.tNanos = 300_000_000; matcher.update(good, out); assertTrue(out.matched)
        bad.tNanos = 400_000_000; matcher.update(bad, out); assertTrue(out.matched, "not yet off")
        bad.tNanos = 799_000_000; matcher.update(bad, out); assertTrue(out.matched)
        bad.tNanos = 800_000_000; matcher.update(bad, out); assertFalse(out.matched)
        assertEquals(CameraAdvice.ROUND_LIPS, out.advice)

        val calibrating = state().apply { rest = RestSource.Default; tNanos = 850_000_000 }
        matcher.update(calibrating, out)
        assertEquals(EvidenceStatus.Abstain, out.status)
        assertEquals(AbstainReason.Calibrating, out.abstain)
        val weak = state().apply { quality = FaceQuality.Weak; issue = QualityIssue.TooDark; tNanos = 900_000_000 }
        matcher.update(weak, out)
        assertEquals(EvidenceStatus.Abstain, out.status)
        assertEquals(AbstainReason.Quality, out.abstain)
        assertEquals(QualityIssue.TooDark, out.issue)
        assertNull(out.advice)
    }

    @Test
    @DisplayName("the target ring for Ș is narrower than rest, for R it opens; it follows the head")
    fun targetRings() {
        val engine = MouthStateEngine(CameraSpec())
        val (_, t) = engine.feed(SyntheticFace(), 40, 0)
        val frame = SyntheticFace().frame(t)
        engine.process(frame, MouthState())
        val outer = FloatArray(40)
        val inner = FloatArray(40)
        fun span(ring: FloatArray, axis: Int, w: Int): Float {
            var lo = Float.MAX_VALUE
            var hi = -Float.MAX_VALUE
            for (k in 0 until 20) {
                lo = minOf(lo, ring[k * 2 + axis]); hi = maxOf(hi, ring[k * 2 + axis])
            }
            return (hi - lo) * w
        }
        assertTrue(engine.targetRings(frame, PoseSpec.bundled(SoundId.Sh).lips, outer, inner))
        val shWidth = span(outer, 0, 480)
        val restWidth = 0.8f * 120f
        assertEquals(restWidth * 0.78f, shWidth, 2f)
        engine.targetRings(frame, PoseSpec.bundled(SoundId.R).lips, outer, inner)
        assertEquals(restWidth, span(outer, 0, 480), 2f)
        assertTrue(span(inner, 1, 640) > 0.2f * restWidth, "R opening ${span(inner, 1, 640)}")
        // The head moves 40 px right: the target follows.
        val before = outer[0]
        val moved = SyntheticFace(centerX = 280f).frame(t + FRAME_NANOS)
        engine.process(moved, MouthState())
        engine.targetRings(moved, PoseSpec.bundled(SoundId.R).lips, outer, inner)
        assertEquals(40f, (outer[0] - before) * 480, 1f)
    }

    @Test
    @DisplayName("published snapshots are read whole")
    fun levels() {
        val levels = MouthLevels()
        val into = MirrorSnapshot()
        assertFalse(levels.read(into))
        val writer = Thread {
            for (k in 1..20_000) levels.publish { s ->
                s.state.aperture = k.toFloat()
                s.state.width = k.toFloat()
                s.lipsOuter[0] = k.toFloat()
            }
        }
        writer.start()
        var reads = 0
        while (writer.isAlive || reads < 10) {
            if (levels.read(into)) {
                assertEquals(into.state.aperture, into.state.width)
                assertEquals(into.state.aperture, into.lipsOuter[0])
                reads++
            }
        }
        writer.join()
        assertTrue(levels.read(into))
        assertEquals(20_000f, into.state.aperture)
    }
}
