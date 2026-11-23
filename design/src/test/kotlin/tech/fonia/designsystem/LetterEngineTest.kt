package tech.fonia.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.designsystem.letter.Affine
import tech.fonia.designsystem.letter.AffinePathSink
import tech.fonia.designsystem.letter.CueMotion
import tech.fonia.designsystem.letter.LetterCue
import tech.fonia.designsystem.letter.LetterDrive
import tech.fonia.designsystem.letter.LetterEngine
import tech.fonia.designsystem.letter.LetterPose
import tech.fonia.designsystem.letter.LiveWordEngine
import tech.fonia.designsystem.motion.Motion
import tech.fonia.designsystem.tokens.FoniaMotion
import tech.fonia.designsystem.tokens.FoniaSticker
import tech.fonia.glyphs.AlloOutlines
import tech.fonia.model.ErrorKind
import java.io.File
import java.lang.management.ManagementFactory

/** The pure part of the living letter: motion maths, cue final frames, geometry, and zero allocation per frame. */
class LetterEngineTest {
    private val root = File(System.getProperty("fonia.root") ?: "..")
    private val font: AlloOutlines by lazy { File(root, "spec/glyphs/allo-hero.json").inputStream().use { AlloOutlines.load(it) } }

    private class Recorder : AffinePathSink {
        override val matrix = Affine()
        var moves = 0; var segs = 0; var closes = 0
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        private fun see(x: Float, y: Float) {
            val px = matrix.mapX(x, y); val py = matrix.mapY(x, y)
            if (px < minX) minX = px; if (px > maxX) maxX = px; if (py < minY) minY = py; if (py > maxY) maxY = py
        }
        fun reset() { moves = 0; segs = 0; closes = 0; minX = Float.MAX_VALUE; maxX = -Float.MAX_VALUE; minY = Float.MAX_VALUE; maxY = -Float.MAX_VALUE }
        override fun moveTo(x: Float, y: Float) { moves++; see(x, y) }
        override fun lineTo(x: Float, y: Float) { segs++; see(x, y) }
        override fun quadTo(x1: Float, y1: Float, x2: Float, y2: Float) { segs++; see(x2, y2) }
        override fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) { segs++; see(x3, y3) }
        override fun close() { closes++ }
    }

    private fun engine(text: String = "S"): LetterEngine = LetterEngine(font).apply {
        setText(text); emPx = 200f; anchorX = 300f; anchorY = 300f
    }

    private fun run(e: LetterEngine, d: LetterDrive, seconds: Float, from: Long = 0L): Long {
        var t = from
        val frames = (seconds * 60).toInt()
        repeat(frames) { t += 16_666_667L; e.advance(t, d) }
        return t
    }

    @Test fun springStepsOvershootLikeTheTokens() {
        var peak = 0f
        var t = 0f
        while (t < 1f) { peak = maxOf(peak, Motion.step(t, FoniaMotion.bounce)); t += 0.001f }
        assertTrue("bounce peak $peak", peak in 1.08f..1.2f)
        assertEquals(1f, Motion.step(2f, FoniaMotion.bounce), 1e-3f)
        assertEquals(0f, Motion.kick(2f, FoniaMotion.reward), 1e-3f)
        assertEquals(1f, Motion.out(1f), 1e-4f)
        assertEquals(0.5f, Motion.cubic(0.5f, 0.25f, 0.25f, 0.75f, 0.75f), 0.01f)
    }

    @Test fun everyCueSettlesByItsDuration() {
        val x = tech.fonia.designsystem.letter.CueContext()
        for (c in LetterCue.vocabulary) {
            if (c == LetterCue.Listen || c == LetterCue.Rest || c == LetterCue.Breathe) continue
            val d = CueMotion.duration(c)
            val a = LetterPose().also { CueMotion.apply(c, d, it, x) }
            val b = LetterPose().also { CueMotion.apply(c, d + 0.02f, it, x) }
            assertEquals("${c.name} scale", a.scale, b.scale, 0.01f)
            assertEquals("${c.name} sy", a.sy, b.sy, 0.01f)
            assertEquals("${c.name} rot", a.rot, b.rot, 0.2f)
            if (!CueMotion.holds(c)) {
                assertEquals("${c.name} ends at rest (scale)", 1f, a.scale, 0.03f)
                assertEquals("${c.name} ends at rest (ty)", 0f, a.ty, 0.02f)
            }
        }
    }

    @Test fun correctEndsParrotWithCheck() {
        val e = engine()
        val d = LetterDrive()
        e.cue(LetterCue.Correct)
        run(e, d, 1.5f)
        assertEquals(FoniaSticker.Parrot, e.curSet)
        assertEquals(LetterPose.BADGE_CHECK, e.pose.badge)
        assertEquals(1f, e.pose.sy, 0.01f)
        e.cue(LetterCue.Rest)
        run(e, d, 0.5f, 2_000_000_000L)
        assertEquals(FoniaSticker.Voice, e.curSet)
        assertEquals(0, e.pose.badge)
    }

    @Test fun reducedMotionJumpsToTheLastFrame() {
        val e = engine("Ș")
        e.reduceMotion = true
        val d = LetterDrive()
        e.cue(LetterCue.Almost)
        run(e, d, 0.05f)
        assertTrue("sheared at once", e.pose.shear > 0.15f)
        assertEquals(0.5f, e.pose.fillLine, 0.01f)
        e.cue(LetterCue.Pop)
        run(e, d, 0.05f, 1_000_000_000L)
        assertFalse("no particles", e.particles.alive)
        assertEquals(LetterPose.BADGE_CHECK, e.pose.badge)
    }

    @Test fun instructionsResolvePerPlanTable() {
        assertEquals(LetterCue.CommaDrop, LetterCue.resolve(ErrorKind.PlaceSwap, "Ș", "S"))
        assertEquals(LetterCue.CommaLift, LetterCue.resolve(ErrorKind.PlaceSwap, "S", "Ș"))
        assertEquals(LetterCue.SwapGlide("J"), LetterCue.resolve(ErrorKind.PlaceSwap, "Z", null))
        assertEquals(LetterCue.CommaDrop, LetterCue.resolve(ErrorKind.AffricatePlace, "Ț", null))
        assertEquals(LetterCue.SwapGlide("L"), LetterCue.resolve(ErrorKind.RtoL, "R", null))
        assertEquals(LetterCue.TrillRipple(true), LetterCue.resolve(ErrorKind.RUvular, "R", null))
        assertEquals(LetterCue.VoicingWave, LetterCue.resolve(ErrorKind.Devoicing, "Z", null))
        assertEquals(LetterCue.AirToCentre, LetterCue.resolve(ErrorKind.Lateral, "S", null))
        assertEquals(LetterCue.TongueBehindTeeth, LetterCue.resolve(ErrorKind.Interdental, "S", null))
        assertEquals(LetterCue.ClosureSnap, LetterCue.resolve(ErrorKind.Deaffrication, "Ț", null))
        assertEquals(LetterCue.Stretch, LetterCue.resolve(ErrorKind.Stopping, "S", null))
        assertEquals(LetterCue.SagAndRefill, LetterCue.resolve(ErrorKind.WeakOrShort, "S", null))
        for (k in ErrorKind.entries) LetterCue.resolve(k, "S", null) // total
    }

    @Test fun commaLettersHaveTheirCommaAndItLandsAttached() {
        for (g in listOf("Ș", "ș", "Ț", "ț")) assertTrue(g, engine(g).hasComma())
        assertFalse(engine("S").hasComma())
        val e = engine("Ș")
        val d = LetterDrive()
        e.cue(LetterCue.CommaDrop)
        run(e, d, 1.4f)
        assertEquals(0f, e.pose.commaDy, 0.01f)
        assertEquals(100f, e.rond, 0.5f) // rounded
        val r = Recorder()
        assertTrue(e.emitComma(r))
        assertTrue("comma under the baseline", r.minY > e.baselinePx() - 5f)
    }

    @Test fun bodyIsCentredOnItsShape() {
        val e = engine("S")
        run(e, LetterDrive(), 0.5f)
        val r = Recorder()
        e.emitBody(r)
        assertTrue(r.moves >= 1 && r.closes == r.moves)
        val cx = (r.minX + r.maxX) / 2
        assertEquals(300f, cx, 3f)
        assertEquals(300f, (r.minY + r.maxY) / 2, 12f) // cap centre on the anchor (S overshoots a little)
    }

    @Test fun inflationFollowsTheDriveWithoutRecomputingWhenStill() {
        val e = engine()
        val d = LetterDrive().apply { inflation = 1f }
        run(e, d, 1.5f)
        assertEquals(1f, e.inflation, 0.01f)
        assertEquals(900f, e.wght, 2f)
        d.inflation = 0f
        run(e, d, 1.5f, 2_000_000_000L)
        assertEquals(0f, e.inflation, 0.01f)
        assertTrue("thin (breathing may add up to 30)", e.wght in 179f..212f)
    }

    @Test fun inflateWithVoicePops() {
        val e = engine()
        var pops = 0
        e.onPop = { pops++ }
        val d = LetterDrive().apply { inflateWithVoice = true; holdGoalSeconds = 1f; speaking = true; level = 0.6f }
        run(e, d, 1.4f)
        assertEquals(1, pops)
        assertTrue("particles from the outline", e.particles.count in 60..80)
    }

    @Test fun aFrameAllocatesNothing() {
        val e = engine("Ș")
        val d = LetterDrive().apply { speaking = true; level = 0.5f; listening = true; sharpness = 0.3f }
        val r = Recorder()
        var t = 0L
        fun frames(n: Int, cue: LetterCue?) {
            if (cue != null) e.cue(cue)
            repeat(n) {
                t += 16_666_667L
                d.inflation = 0.5f + 0.4f * kotlin.math.sin(t / 3e8f)
                e.advance(t, d)
                r.reset(); e.emitBody(r); e.emitComma(r)
            }
        }
        frames(400, LetterCue.Unsure) // warm up every path (noise route included)
        frames(200, LetterCue.Correct)
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val id = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(id)
        frames(600, null)
        val bytes = bean.getThreadAllocatedBytes(id) - before
        assertTrue("allocated $bytes bytes in 600 frames", bytes < 2048)
    }

    @Test fun wordSlotsDoNotMoveWhileTheTargetInflates() {
        val w = LiveWordEngine(font)
        w.setText("șarpe", listOf(0..0))
        assertEquals(5, w.count)
        val before = (0 until 5).map { w.slotCenterPx(it) }
        val d = LetterDrive().apply { inflation = 1f }
        var t = 0L
        repeat(60) { t += 16_666_667L; w.advance(t, d) }
        assertEquals(before, (0 until 5).map { w.slotCenterPx(it) })
        assertTrue(w.isTarget(0) && !w.isTarget(1))
    }
}
