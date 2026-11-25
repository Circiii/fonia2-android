package tech.fonia.speaking.coach

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class CoachEngineTest {
    private val spec = CoachSpec.v1

    @Test
    fun specFileMatchesTheCodeDefaults() {
        val root = System.getProperty("fonia.root") ?: return
        val file = File(root, "spec/thresholds/speech-coach.json")
        assertEquals(CoachSpec(), CoachSpec.parse(file.readText()), "spec/thresholds/speech-coach.json and CoachSpec() drifted")
        assertEquals(CoachSpec(), spec)
    }

    @Test
    fun textsFollowTheRules() {
        val texts = CoachText.allFocus.flatMap { listOf(it.title, it.tip) } + Cue.entries.map { it.word } +
            MomentKind.entries.map { it.label } + Area.entries.map { it.title } + CoachPrompts.v1.prompts.flatMap { listOf(it.title, it.hint) }
        for (t in texts) {
            assertFalse(t.contains('ş') || t.contains('ţ') || t.contains('Ş') || t.contains('Ţ'), "cedilla in „$t”")
            assertFalse(t.lowercase().contains("greșit"), "„$t”")
            for (word in listOf("fericit", "trist", "nervos", "furios", "emoți", "diagnostic", "terapie")) {
                assertFalse(t.lowercase().contains(word), "„$t” names $word")
            }
        }
        for (f in CoachText.allFocus) assertTrue(f.tip.split(' ').size <= 8, "tip over 8 words: ${f.tip}")
        assertTrue(CoachPrompts.v1.prompts.size >= 20)
        assertEquals(CoachPrompts.v1.prompts.size, CoachPrompts.v1.prompts.map { it.id }.toSet().size)
    }

    @Test
    fun paceFollowsTheSyllableRate() {
        for (rate in listOf(3.0, 4.5, 6.5)) {
            val take = Bursts.render(seconds = 12.0, rate = rate)
            // Clean bursts: every syllable is found, so no recall correction here.
            val coach = AudioCoach(16000.0, spec.copy(pace = spec.pace.copy(envelopeRecall = 1.0)))
            val seen = ArrayList<Double>()
            Bursts.run(take, coach) { t -> if (t > 6.0 && !coach.rate.isNaN()) seen += coach.rate }
            val median = seen.sorted()[seen.size / 2]
            assertTrue(abs(median - rate) / rate < 0.15, "rate $rate read as $median")
            val summary = coach.summary()
            assertTrue(abs(summary.articulationRate - rate) / rate < 0.15, "whole-take rate $rate read as ${summary.articulationRate}")
        }
    }

    @Test
    fun theModelsVowelsGiveThePaceWithoutALoudnessThreshold() {
        // 5 vowels a second for 4 s, a 2 s pause, then 7 a second: the rate follows, and the pause does not lower it.
        val take = Bursts.render(seconds = 14.0, rate = 4.5)
        val coach = AudioCoach(16000.0, spec)
        val vowels = (0 until 20).map { 0.1 + it * 0.2 } + (0 until 42).map { 6.1 + it / 7.0 }
        var chunk = 0.0
        val seen = ArrayList<Pair<Double, Double>>()
        Bursts.run(take, coach) { t ->
            while (t >= chunk + 2.4) {
                coach.addVowels(chunk, chunk + 2.0, vowels.filter { it >= chunk && it < chunk + 2.0 }.toDoubleArray())
                chunk += 2.0
            }
            if (coach.rateFromModel) seen += t to coach.rate
        }
        val early = seen.filter { it.first in 4.5..6.2 }.map { it.second }
        val late = seen.filter { it.first > 12.6 }.map { it.second }
        assertTrue(early.isNotEmpty() && early.all { abs(it - 5.0) < 0.3 }, "early $early")
        assertTrue(late.isNotEmpty() && late.all { abs(it - 7.0) < 0.5 }, "late $late")
    }

    @Test
    fun aQuietStretchIsQuietAgainstHisOwnLevel() {
        val take = Bursts.render(seconds = 26.0, rate = 4.5, gainAt = { t -> if (t > 10.0) -16.0 else 0.0 })
        val coach = AudioCoach(16000.0, spec)
        var quietEarly = false
        var quietLate = false
        Bursts.run(take, coach) { t ->
            if (t in 6.0..9.5 && coach.quiet) quietEarly = true
            if (t > 13.0 && coach.quiet) quietLate = true
            // A long quiet stretch does not become his new normal.
            if (t > 24.0) assertTrue(coach.quiet || !coach.speaking, "quiet forgotten at $t")
        }
        assertFalse(quietEarly)
        assertTrue(quietLate)
    }

    @Test
    fun aFlatMelodyHasASmallSpread() {
        val flat = Bursts.render(seconds = 12.0, rate = 4.5, pitch = { 110.0 })
        val lively = Bursts.render(seconds = 12.0, rate = 4.5, pitch = { t -> 110.0 * Math.pow(2.0, 5.0 * sin(2 * PI * 0.4 * t) / 12.0) })
        val a = AudioCoach(16000.0, spec).also { Bursts.run(flat, it) }
        val b = AudioCoach(16000.0, spec).also { Bursts.run(lively, it) }
        assertTrue(a.pitchSpreadSt < spec.pitch.flatSpreadSt, "flat ${a.pitchSpreadSt}")
        assertTrue(b.pitchSpreadSt > spec.pitch.livelySpreadSt, "lively ${b.pitchSpreadSt}")
    }

    @Test
    fun longPausesAreFound() {
        val take = Bursts.render(seconds = 14.0, rate = 4.5, phraseSyllables = 12, pauseSeconds = 2.6)
        val coach = AudioCoach(16000.0, spec).also { Bursts.run(take, it) }
        assertTrue(coach.summary().longPauses.isNotEmpty())
    }

    private fun runFace(script: String, seconds: Double, baseline: Boolean = true): FaceCoach {
        val track = ScriptedFaceTrack(script)
        val face = FaceCoach(spec)
        val s = FaceSample()
        var t = 0.0
        if (baseline) {
            face.startBaseline()
            val calm = ScriptedFaceTrack("calm:10")
            while (t < 2.0) { calm.sample(t, t, s); face.onFrame(s); t += 1 / 15.0 }
            face.endBaseline()
        }
        val start = t
        while (t < start + seconds) { track.sample(t - start, t, s); face.onFrame(s); t += 1 / 15.0 }
        return face
    }

    @Test
    fun lookingAtTheLensAndAway() {
        val calm = runFace("calm:20", 6.0)
        assertTrue(calm.baselineTaken)
        assertTrue(calm.lookingNow)
        assertTrue(calm.awayFraction < 0.1)
        val away = runFace("away:20", 6.0)
        assertFalse(away.lookingNow)
        assertTrue(away.awayFraction > spec.gaze.awayFractionCue)
        val side = runFace("side:20", 6.0)
        assertFalse(side.lookingNow)
        val gone = runFace("gone:20", 6.0)
        // No face is a picture problem, not a look-away: no reading, and the hint says why.
        assertTrue(gone.awayFraction.isNaN())
        assertEquals(InputIssue.NoFace, gone.issueNow)
    }

    @Test
    fun swayingIsMovement() {
        val calm = runFace("calm:20", 6.0)
        assertTrue(calm.motionIndex < 0.5, "calm ${calm.motionIndex}")
        val sway = runFace("sway:20", 6.0)
        assertTrue(sway.motionIndex > spec.motion.cueIndex, "sway ${sway.motionIndex}")
    }

    @Test
    fun aMovingFaceIsLivelierThanAStillOne() {
        val still = runFace("calm:20", 7.0)
        val lively = runFace("lively:20", 7.0)
        assertTrue(still.faceActivity01 < 0.2, "still ${still.faceActivity01}")
        assertTrue(lively.faceActivity01 > 0.5, "lively ${lively.faceActivity01}")
    }

    @Test
    fun aCueNeverFlickers() {
        val arbiter = CueArbiter(spec)
        val active = BooleanArray(Cue.entries.size)
        val shown = ArrayList<Pair<Double, Cue?>>()
        var t = 0.0
        // The condition blinks every 0.3 s: never held long enough, nothing shows.
        while (t < 6.0) {
            active[Cue.Slower.ordinal] = ((t / 0.3).toInt() % 2) == 0
            shown += t to arbiter.update(t, active, warm = true, speaking = true)
            t += 0.1
        }
        assertTrue(shown.none { it.second == Cue.Slower })
        // Held: shows after onSeconds, stays at least minShow even when the condition clears at once.
        active.fill(false)
        active[Cue.Slower.ordinal] = true
        var shownAt = Double.NaN
        while (t < 20.0) {
            val c = arbiter.update(t, active, warm = true, speaking = true)
            if (c == Cue.Slower && shownAt.isNaN()) { shownAt = t; active[Cue.Slower.ordinal] = false }
            if (!shownAt.isNaN() && t - shownAt < spec.cues.minShowSeconds - 0.05) assertEquals(Cue.Slower, c)
            t += 0.1
        }
        assertFalse(shownAt.isNaN())
        // After it cleared it cannot return within the cooldown.
        val back = CueArbiter(spec)
        active.fill(false)
        active[Cue.Louder.ordinal] = true
        t = 0.0
        var firstClear = Double.NaN
        var lastSeen = Double.NaN
        while (t < 30.0) {
            val c = back.update(t, active, warm = true, speaking = true)
            if (c == Cue.Louder) lastSeen = t
            if (c == Cue.Louder && t > 3.0 && firstClear.isNaN()) active[Cue.Louder.ordinal] = false
            if (c != Cue.Louder && !lastSeen.isNaN() && firstClear.isNaN()) { firstClear = t; active[Cue.Louder.ordinal] = true }
            if (!firstClear.isNaN() && c == Cue.Louder) {
                assertTrue(t - firstClear >= spec.cues.cooldownSeconds - 0.11, "back after ${t - firstClear}")
                break
            }
            t += 0.1
        }
    }

    @Test
    fun oneCueAtATimeAndPriorityOrder() {
        val arbiter = CueArbiter(spec)
        val active = BooleanArray(Cue.entries.size)
        active[Cue.Livelier.ordinal] = true
        active[Cue.Look.ordinal] = true
        var t = 0.0
        var first: Cue? = null
        // "Mai rar" starts half a second later, so both are ready in the same tick (look holds 2.5 s, slower 2.0 s).
        while (t < 8.0 && first == null) {
            active[Cue.Slower.ordinal] = t >= 0.45
            first = arbiter.update(t, active, true, true)
            t += 0.1
        }
        assertEquals(Cue.Look, first)
    }

    @Test
    fun goodShowsWhenAllIsInRange() {
        val arbiter = CueArbiter(spec)
        val active = BooleanArray(Cue.entries.size)
        var t = 0.0
        var good = false
        while (t < 6.0) { if (arbiter.update(t, active, true, true) == Cue.Good) good = true; t += 0.1 }
        assertTrue(good)
        // Not while he is silent.
        val quiet = CueArbiter(spec)
        t = 0.0
        while (t < 6.0) { assertNull(quiet.update(t, active, true, speaking = false)); t += 0.1 }
    }

    @Test
    fun envelopeRisesFastFallsSlowly() {
        val e = Envelope(0.08, 0.3)
        var t = 0.0
        while (t < 0.08) { e.step(1.0, 1 / 60.0); t += 1 / 60.0 }
        assertTrue(e.value > 0.55 && e.value < 0.75, "attack ${e.value}")
        e.snap(1.0)
        t = 0.0
        while (t < 0.08) { e.step(0.0, 1 / 60.0); t += 1 / 60.0 }
        assertTrue(e.value > 0.7, "release ${e.value}")
    }

    @Test
    fun sessionPutsCuesOnTheTimelineAndTheReportFindsThem() {
        val session = CoachSession(spec)
        val take = Bursts.render(seconds = 24.0, rate = 7.0)
        val coach = session.attachAudio(16000.0)
        val track = ScriptedFaceTrack("calm:8,away:8,calm:8")
        val s = FaceSample()
        session.face.startBaseline()
        for (i in 0 until 30) { ScriptedFaceTrack("calm:9").sample(i / 15.0, i / 15.0 - 2.0, s); session.face.onFrame(s) }
        session.face.endBaseline()
        session.startTalk(0.0)
        var nextFace = 0.0
        var nextTick = 0.0
        val cues = HashSet<Cue>()
        Bursts.run(take, coach) { t ->
            while (nextFace <= t) { track.sample(nextFace, nextFace, s); session.face.onFrame(s); nextFace += 1 / 15.0 }
            while (nextTick <= t) { session.tick(nextTick)?.let { cues += it }; nextTick += 0.1 }
        }
        assertTrue(Cue.Slower in cues, "cues $cues")
        assertTrue(Cue.Look in cues, "cues $cues")
        val report = CoachReportBuilder.build(session.timeline(), 24.0, coach.summary(), listOf(Span(5.0, 5.4)), previous = null, spec = spec)
        assertTrue(report.moments.any { it.kind == MomentKind.TooFast })
        assertTrue(report.moments.any { it.kind == MomentKind.LookedAway && it.start in 7.0..11.0 })
        assertTrue(report.moments.any { it.kind == MomentKind.Filler })
        assertNotNull(report.scores[Area.Gaze])
        assertEquals(Area.Pace, report.focus.area, "focus ${report.focus} scores ${report.scores}")
        assertEquals(CoachText.tooFast, report.focus)
    }

    @Test
    fun reportComparesWithTheLastAttempt() {
        val ticks = (0 until 80).map { i ->
            CoachTick(t = i * 0.25, speaking = true, rate = 4.6, quiet = false, loudness01 = 0.7, looking = true, motion = 0.3, liveliness = 0.7, cue = null)
        }
        val previous = CoachScores(mapOf(Area.Pace to 100, Area.Voice to 100, Area.Gaze to 60, Area.Calm to 100, Area.Lively to 100))
        val r = CoachReportBuilder.build(ticks, 20.0, null, emptyList(), previous, spec)
        assertEquals(100, r.scores[Area.Gaze])
        assertEquals(40, r.changes[Area.Gaze])
        assertEquals(CoachText.better(Area.Gaze, 40), r.changeLine)
        assertEquals(CoachText.keep, r.focus)
    }

    @Test
    fun noCameraMeansNoCameraScores() {
        val ticks = (0 until 40).map { i ->
            CoachTick(t = i * 0.25, speaking = true, rate = 4.6, quiet = false, loudness01 = 0.7, looking = null, motion = Double.NaN, liveliness = 0.5, cue = null)
        }
        val r = CoachReportBuilder.build(ticks, 10.0, null, emptyList(), null, spec)
        assertNull(r.scores[Area.Gaze])
        assertNull(r.scores[Area.Calm])
        assertNull(r.eyeContact)
    }
}
