package tech.fonia.app.mirror

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.app.text.T
import tech.fonia.face.CameraAdvice
import tech.fonia.face.PoseSpec
import tech.fonia.face.QualityIssue
import tech.fonia.model.SoundId

class MirrorCoachTest {
    private val ms = 1_000_000L

    private fun reading(
        good: Boolean = true, issue: String = "none", calibrating: Boolean = false, matches: Boolean = false,
        advice: String? = null, rest: Float = 1f,
    ) = MirrorReading().apply {
        hasData = true; this.good = good; issueKey = issue; this.calibrating = calibrating; this.matches = matches
        adviceKey = advice; restProgress = rest
    }

    /** Runs [r] for [millis] at 60 frames a second from [start]; returns the time reached. */
    private fun MirrorCoach.run(r: MirrorReading, start: Long, millis: Long): Long {
        var t = start
        while (t < start + millis * ms) { update(r, t); t += 16 * ms }
        return t
    }

    @Test fun everyAdviceKeyHasAShortChildLine() {
        for (a in CameraAdvice.entries) {
            val line = T.mirrorAdvice(a.key)
            assertNotNull("no text for ${a.key}", line)
            assertTrue("'$line' too long", words(line!!) <= 8)
        }
        assertNull(T.mirrorAdvice("unknown.key"))
        assertEquals("Rotunjește buzele.", T.mirrorAdvice(CameraAdvice.ROUND_LIPS.key))
        assertEquals("Deschide gura mai mult.", T.mirrorAdvice(CameraAdvice.OPEN_MORE.key))
    }

    @Test fun everyQualityIssueWithAHintIsShortAndNoneSaysNothing() {
        for (q in QualityIssue.entries) {
            val line = T.mirrorQuality(q.key)
            if (q == QualityIssue.None || q == QualityIssue.Unsteady) {
                assertNull(line)
            } else {
                assertNotNull("no text for ${q.key}", line)
                assertTrue(words(line!!) <= 8)
            }
        }
        assertEquals("Vino mai aproape.", T.mirrorQuality(QualityIssue.TooFar.key))
        assertEquals("Mai multă lumină.", T.mirrorQuality(QualityIssue.TooDark.key))
    }

    @Test fun everyPoseHasAShapeLineAndAllMirrorLinesAreShortAndKind() {
        val lines = SoundId.entries.map { T.mirrorShape(PoseSpec.bundled(it).lips.klass) } +
            listOf(T.mirrorRest, T.mirrorLook, T.mirrorMatched, T.mirrorCheer, T.mirrorTongue, T.mirrorNoCamera) +
            CameraAdvice.entries.mapNotNull { T.mirrorAdvice(it.key) } + QualityIssue.entries.mapNotNull { T.mirrorQuality(it.key) }
        for (l in lines) {
            assertTrue("'$l' has ${words(l)} words", words(l) <= 8)
            for (bad in listOf("greșit", "greșeal", "nu e bine", "rău")) assertFalse("'$l' contains $bad", l.lowercase().contains(bad))
        }
        // the shape is said in words (never the generic fallback) for the nine poses
        assertTrue(SoundId.entries.none { T.mirrorShape(PoseSpec.bundled(it).lips.klass) == T.mirrorLook })
    }

    @Test fun restComesFirstAndTheRingShowsItsProgress() {
        val c = MirrorCoach("funnel")
        c.update(reading(calibrating = true, rest = 0.4f), 1 * ms)
        assertEquals(MirrorTone.Rest, c.line.tone)
        assertEquals(T.mirrorRest, c.line.text)
        assertEquals(0.4f, c.ring, 1e-4f)
        // without steady closed-mouth frames the engine's progress stays low; the ring still fills with time
        c.run(reading(calibrating = true, rest = 0f), 1 * ms, 2000)
        assertTrue(c.ring in 0.4f..0.6f)
        assertEquals(MirrorTone.Rest, c.line.tone)
    }

    @Test fun adviceWaitsHalfASecondThenSpeaks() {
        val c = MirrorCoach("funnel")
        var t = c.run(reading(), 1 * ms, 100)
        assertEquals(MirrorTone.Look, c.line.tone)
        assertEquals(T.mirrorShape("funnel"), c.line.text)
        t = c.run(reading(advice = "lips.round"), t, 300)
        assertEquals(MirrorTone.Look, c.line.tone) // not yet: no flicker
        c.run(reading(advice = "lips.round"), t, 300)
        assertEquals(MirrorLine("Rotunjește buzele.", MirrorTone.Advice), c.line)
    }

    @Test fun qualityHintsSpeakFirstOnceTheyLasted() {
        val c = MirrorCoach("smileTeethClose")
        var t = c.run(reading(), 1 * ms, 100)
        t = c.run(reading(good = false, issue = "tooFar"), t, 400)
        assertEquals(MirrorTone.Look, c.line.tone)
        t = c.run(reading(good = false, issue = "tooFar"), t, 400)
        assertEquals(MirrorLine("Vino mai aproape.", MirrorTone.Quality), c.line)
        // an unsteady frame between hints keeps the hint (no flicker); a good face goes back to coaching
        t = c.run(reading(good = false, issue = "unsteady"), t, 100)
        assertEquals(MirrorTone.Quality, c.line.tone)
        c.run(reading(), t, 100)
        assertEquals(MirrorTone.Look, c.line.tone)
    }

    @Test fun aMatchHeldOneSecondCheersExactlyOnceAndReArms() {
        val c = MirrorCoach("funnel")
        var t = c.run(reading(matches = true), 1 * ms, 500)
        assertEquals(MirrorTone.Matched, c.line.tone)
        assertFalse(c.consumeCheer())
        assertTrue(c.ring in 0.4f..0.6f)
        t = c.run(reading(matches = true), t, 600)
        assertEquals(MirrorLine(T.mirrorCheer, MirrorTone.Cheer), c.line)
        assertTrue(c.consumeCheer())
        assertFalse(c.consumeCheer())
        t = c.run(reading(matches = true), t, 2000)
        assertFalse("one cheer per hold", c.consumeCheer())
        // a short break does not re-arm; a real one does
        t = c.run(reading(advice = "lips.round"), t, 200)
        t = c.run(reading(matches = true), t, 1200)
        assertFalse(c.consumeCheer())
        t = c.run(reading(advice = "lips.round"), t, 800)
        c.run(reading(matches = true), t, 1100)
        assertTrue(c.consumeCheer())
    }

    @Test fun aShortUnsteadyGapIsForgivenALongOneBreaksTheHold() {
        val c = MirrorCoach("funnel")
        var t = c.run(reading(matches = true), 1 * ms, 700)
        t = c.run(reading(good = false, issue = "unsteady"), t, 80)
        c.run(reading(matches = true), t, 400)
        assertTrue("80 ms gap forgiven", c.consumeCheer())

        val d = MirrorCoach("funnel")
        t = d.run(reading(matches = true), 1 * ms, 700)
        t = d.run(reading(good = false, issue = "unsteady"), t, 400)
        d.run(reading(matches = true), t, 500)
        assertFalse("400 ms gap restarts the hold", d.consumeCheer())
    }

    private fun words(s: String) = s.split(Regex("\\s+")).count { w -> w.any { it.isLetter() } }
}
