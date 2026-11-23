package tech.fonia.mouth3d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.model.SoundId
import kotlin.math.abs

class MotionTest {
    private val lib = TestData.library

    @Test
    fun `critically damped spring reaches the target without overshoot`() {
        val s = SpringVector(1)
        val target = floatArrayOf(1f)
        var maxV = 0f
        repeat(120) { s.step(target, 18f, 1f / 60f); maxV = maxOf(maxV, s.value[0]) }
        assertTrue("overshoot $maxV", maxV <= 1.0001f)
        assertEquals(1f, s.value[0], 1e-3f)
        assertTrue(s.settled(target))
    }

    @Test
    fun `a long frame is capped so nothing jumps`() {
        val s = SpringVector(1)
        s.step(floatArrayOf(1f), 18f, 2f) // a 2 s hiccup counts as 1/20 s
        assertTrue(s.value[0] < 0.4f)
    }

    @Test
    fun `reduce motion moves in a straight line in 150 ms`() {
        val s = SpringVector(1)
        val span = floatArrayOf(1f)
        s.linear(floatArrayOf(1f), 0.05f, 0.15f, span)
        assertEquals(1f / 3, s.value[0], 1e-4f)
        repeat(2) { s.linear(floatArrayOf(1f), 0.05f, 0.15f, span) }
        assertEquals(1f, s.value[0], 1e-4f)
    }

    @Test
    fun `progress interpolates rest to pose and settles`() {
        val scene = MouthScene()
        val s = lib.forSound(SoundId.S)
        val inp = SceneInputs(s, lib.rest, progress = 0f, inside = false)
        // the idle breath moves only the jaw, by at most 2 × BREATH_JAW
        fun tol(c: PoseChannel) = if (c == PoseChannel.JawOpen) 2 * MouthScene.BREATH_JAW + 2e-3f else 2e-3f
        val first = scene.step(0f, inp).pose
        PoseChannel.entries.forEach { assertEquals(it.key, lib.rest.pose[it], first[it], tol(it)) }
        var f = scene.step(1f / 60, inp.copy(progress = 1f))
        assertTrue(f.animating && !f.ambient)
        repeat(240) { f = scene.step(1f / 60, inp.copy(progress = 1f)) }
        PoseChannel.entries.forEach { assertEquals(it.key, s.pose[it], f.pose[it], tol(it)) }
        assertTrue("settled: only the idle moves", f.ambient)
        repeat(240) { f = scene.step(1f / 60, inp.copy(progress = 0.5f)) }
        val half = lib.rest.pose.lerp(s.pose, 0.5f)
        PoseChannel.entries.forEach { assertEquals(it.key, half[it], f.pose[it], tol(it)) }
        val still = MouthScene()
        repeat(240) { f = still.step(1f / 60, inp.copy(progress = 1f, reduceMotion = true)) }
        assertFalse("no idle with reduce motion", f.animating)
    }

    @Test
    fun `affricate animation closes then releases, and loops`() {
        val ts = lib.forSound(SoundId.Ts)
        val scene = MouthScene()
        val inp = SceneInputs(ts, lib.rest, progress = 1f, inside = false)
        scene.step(0f, inp)
        var f = scene.step(1f / 60, inp)
        repeat(20) { f = scene.step(1f / 60, inp) } // ~0.35 s: closure held
        val closed = f.pose[PoseChannel.TongueTip]
        repeat(40) { f = scene.step(1f / 60, inp) } // ~1.0 s: released to S
        val released = f.pose[PoseChannel.TongueTip]
        assertTrue("closure $closed vs release $released", closed > released + 0.5f)
        assertTrue(f.animating) // a loop keeps asking for frames
        val reduced = MouthScene()
        repeat(120) { f = reduced.step(1f / 60, inp.copy(reduceMotion = true)) }
        assertEquals(ts.pose[PoseChannel.TongueTip], f.pose[PoseChannel.TongueTip], 1e-3f)
        assertFalse("no loop with reduce motion", f.animating)
    }

    @Test
    fun `R flutters around its pose, not with reduce motion`() {
        val r = lib.forSound(SoundId.R)
        val scene = MouthScene()
        val inp = SceneInputs(r, lib.rest, progress = 1f, inside = false)
        val tips = (0 until 120).map { scene.step(1f / 60, inp).pose[PoseChannel.TongueTip] }.drop(60)
        assertTrue(tips.max() - tips.min() > 0.15f)
        val calm = MouthScene()
        val still = (0 until 120).map { calm.step(1f / 60, inp.copy(reduceMotion = true)).pose[PoseChannel.TongueTip] }.drop(60)
        assertTrue(still.max() - still.min() < 1e-3f)
    }

    @Test
    fun `inside view turns the camera to the side`() {
        val scene = MouthScene()
        val s = lib.forSound(SoundId.L)
        val inp = SceneInputs(s, lib.rest, progress = 1f, inside = false)
        assertEquals(0f, scene.step(0f, inp).insideAmount, 0f)
        var f = scene.step(1f / 60, inp.copy(inside = true))
        repeat(180) { f = scene.step(1f / 60, inp.copy(inside = true)) }
        assertEquals(1f, f.insideAmount, 1e-2f)
        assertEquals(s.inside.yawDeg, f.yawDeg, 0.5f)
    }

    @Test
    fun `turning past the profile opens the cut only where allowed`() {
        val s = lib.forSound(SoundId.S)
        for (allowed in listOf(true, false)) {
            val scene = MouthScene()
            val inp = SceneInputs(s, lib.rest, progress = 1f, inside = false, cutAllowed = allowed)
            var f = scene.step(0f, inp)
            scene.orbit.drag(95f, 0f)
            repeat(90) { f = scene.step(1f / 60, inp) }
            assertEquals(95f, f.yawDeg, 0.5f)
            assertEquals(if (allowed) 1f else 0f, f.cutAmount, 0.02f)
        }
        // asking for the inside swings to the profile and opens the cut
        val scene = MouthScene()
        val inp = SceneInputs(s, lib.rest, progress = 1f, inside = true)
        var f = scene.step(0f, inp)
        repeat(120) { f = scene.step(1f / 60, inp) }
        assertEquals(1f, f.cutAmount, 0.02f)
        assertEquals(Orbit.MAX_YAW, 100f, 0f)
    }

    @Test
    fun `orbit drags within limits and returns after two seconds`() {
        val o = Orbit()
        o.drag(500f, -500f)
        assertEquals(Orbit.MAX_YAW, o.yaw, 0f)
        assertEquals(-Orbit.MAX_PITCH, o.pitch, 0f)
        o.release()
        repeat(60) { o.step(1f / 60, false) }
        assertEquals(Orbit.MAX_YAW, o.yaw, 0f) // still waiting at 1 s
        repeat(300) { o.step(1f / 60, false) }
        assertTrue(abs(o.yaw) < 0.1f && abs(o.pitch) < 0.1f)
        assertFalse(o.step(1f / 60, false))
    }
}
