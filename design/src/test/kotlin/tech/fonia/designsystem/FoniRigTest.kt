package tech.fonia.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.designsystem.foni.FoniMood
import tech.fonia.designsystem.foni.FoniPose
import tech.fonia.designsystem.foni.FoniRig
import kotlin.math.abs

/** Foni's rig: finite poses, distinct moods, hops that leave the ground, no sad pose, still under reduced motion. */
class FoniRigTest {
    private fun run(mood: FoniMood, seconds: Float, voice: Float = 0f, reduce: Boolean = false, each: (FoniPose) -> Unit = {}): FoniRig {
        val rig = FoniRig()
        rig.reduceMotion = reduce
        rig.setMood(mood)
        var t = 0f
        while (t < seconds) { rig.update(1f / 60f, voice); each(rig.pose); t += 1f / 60f }
        return rig
    }

    @Test fun everyMoodGivesFinitePoses() {
        for (m in FoniMood.entries) run(m, 8f, voice = 0.4f) { p ->
            for (v in listOf(p.head, p.lean, p.rise, p.squash, p.wing, p.wingLift, p.tail, p.beak, p.blink, p.eyeDx, p.eyeDy, p.scale, p.facing)) {
                assertTrue("$m: $v", v.isFinite())
            }
        }
    }

    @Test fun cheeringHopsWithSquashAndStretch() {
        var maxRise = 0f; var maxSquash = 0f; var minSquash = 0f
        run(FoniMood.Cheering, 3f) { maxRise = maxOf(maxRise, it.rise); maxSquash = maxOf(maxSquash, it.squash); minSquash = minOf(minSquash, it.squash) }
        assertTrue("rise $maxRise", maxRise > 0.05f)
        assertTrue("squash $maxSquash", maxSquash > 0.08f)
        assertTrue("stretch $minSquash", minSquash < -0.04f)
    }

    @Test fun unsureIsAHmmNeverSad() {
        run(FoniMood.Unsure, 10f) { p ->
            assertEquals(0f, p.closed, 1e-4f) // eyes never shut in sorrow
            assertTrue("head up, not hung: ${p.head}", p.head > 0f)
        }
    }

    @Test fun talkingLipSyncFollowsTheVoice() {
        val loud = run(FoniMood.Talking, 1f, voice = 1f).pose.beak
        assertTrue("beak $loud", loud > 25f)
        // listening never opens the beak with the child's voice
        var beak = 0f
        run(FoniMood.Listening, 2f, voice = 1f) { beak = maxOf(beak, it.beak) }
        assertTrue("listening beak $beak", beak < 1f)
    }

    @Test fun talkingBabblesWithoutALevel() {
        var beak = 0f
        run(FoniMood.Talking, 6f) { beak = maxOf(beak, it.beak) }
        assertTrue(beak > 8f)
    }

    @Test fun moodsBlendInsteadOfSnapping() {
        val rig = run(FoniMood.Idle, 2f)
        val before = rig.pose.wing
        rig.setMood(FoniMood.Waving)
        rig.update(1f / 60f, 0f)
        assertTrue("first frame after the change moves little: ${rig.pose.wing}", abs(rig.pose.wing - before) < 25f)
        repeat(60) { rig.update(1f / 60f, 0f) }
        assertTrue("after a second the wing is up: ${rig.pose.wing}", rig.pose.wing < -60f)
    }

    @Test fun reducedMotionHoldsStill() {
        var first: Float? = null
        run(FoniMood.Cheering, 3f, reduce = true) { p ->
            if (first == null) first = p.rise
            assertEquals(first!!, p.rise, 1e-6f)
        }
    }

    @Test fun turnsTowardsATargetOnHisRight() {
        val rig = FoniRig()
        repeat(120) { rig.update(1f / 60f, 0f, lookX = 1f, lookY = 0f, lookSide = 1f) }
        assertTrue("facing ${rig.pose.facing}", rig.pose.facing < -0.9f)
    }
}
