package tech.fonia.judge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import kotlin.test.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tech.fonia.model.ErrorKind
import tech.fonia.model.SoundId
import tech.fonia.model.Verdict
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** v2 (2026-11-24): talker estimate, the visual block, personal references, bursts, the self policy. */
class JudgeV2Tests {
    private val spec = loadSpec()
    private val detector = TakeJudge(spec, unlock = UnlockView.everythingUnlocked())

    private fun word(sound: SoundId, text: String, talker: TalkerGroup = TalkerGroup.Woman, level: Level = Level.Word) =
        JudgeRequest(sound, level, text, Position.Medial, talker)

    // ------------------------------------------------------------------------------------------ TalkerEstimator

    /** A voice whose odd harmonics are weak: autocorrelation trackers tend to halve it. */
    private fun octaveTrap(seconds: Double, f0: Float): FloatArray {
        val out = FloatArray(n(seconds))
        for (k in 1..12) {
            val g = (if (k % 2 == 1) 0.25f else 1f) / k
            val step = 2 * PI * f0 * k / RATE
            for (i in out.indices) out[i] += (g * sin(i * step)).toFloat()
        }
        // A sub-harmonic ripple at f0/2, the period-doubling that fools a tracker.
        val sub = 2 * PI * f0 / 2 / RATE
        for (i in out.indices) out[i] += (0.05f * sin(i * sub)).toFloat()
        val peak = out.maxOf { abs(it) }
        for (i in out.indices) out[i] *= 0.3f / peak
        return out
    }

    @Test
    fun theEstimatorFindsAWomansPitchAndHerGroup() {
        val e = TalkerEstimator.estimate(clip(cat(quiet(0.2), vowel(0.6, 210f), quiet(0.2))))
        assertTrue(abs(e.f0Hz - 210f) < 12f, "${e.f0Hz}")
        assertEquals(TalkerGroup.Woman, e.group)
    }

    @Test
    fun aHalvedDspMedianDoesNotMakeHerAMan() {
        val take = clip(cat(quiet(0.2), octaveTrap(0.6, 220f), quiet(0.2)))
        val e = TalkerEstimator.estimate(take, dspMedianHz = 110f)
        assertEquals("dspHalved", e.note)
        assertEquals(TalkerGroup.Woman, e.group, "${e.f0Hz} ${e.estimators}")
    }

    @Test
    fun aMansVoiceStaysAMan() {
        val e = TalkerEstimator.estimate(clip(cat(quiet(0.2), vowel(0.6, 115f), quiet(0.2))), dspMedianHz = 117f)
        assertEquals(TalkerGroup.Man, e.group)
        assertEquals("agree", e.note)
    }

    @Test
    fun aHissHasNoPitch() {
        val e = TalkerEstimator.estimate(clip(cat(quiet(0.2), sHiss(0.5), quiet(0.2))))
        assertNull(e.group)
    }

    // ------------------------------------------------------------------------------------------ the visual block

    @Test
    fun aGoodSHasNoHintAndSitsInTheSZone() {
        val r = detector.judge(word(SoundId.S, "asa"), clip(vcv(sHiss(0.15))))
        val v = assertNotNull(r.visual)
        val axis = assertNotNull(v.placeAxis)
        assertTrue(axis.value > 0.5f, "${axis.value}")
        assertEquals(0.6f, axis.zoneLow, 1e-6f)
        assertEquals(false, v.voicing?.expected)
        if (r.verdict == Verdict.Correct) assertNull(v.hint)
    }

    @Test
    fun anSSaidAsShPointsToTheLipsAndOutOfTheZone() {
        val r = detector.judge(word(SoundId.S, "asa"), clip(vcv(shHiss(0.15))))
        val v = r.visual!!
        assertFalse(v.placeAxis!!.inZone, "${v.placeAxis}")
        assertEquals(HintKey.LipsSpread, v.hint?.key, "$v")
    }

    @Test
    fun aShSaidAsSAsksForRoundLips() {
        val r = detector.judge(word(SoundId.Sh, "așa"), clip(vcv(sHiss(0.15))))
        assertEquals(HintKey.LipsRound, r.visual?.hint?.key, "${r.visual}")
    }

    @Test
    fun aVoicelessZAsksForVoiceAndTheCueSaysSo() {
        val r = detector.judge(word(SoundId.Z, "aza"), clip(vcv(sHiss(0.15))))
        val v = r.visual!!
        assertEquals(true, v.voicing?.expected)
        assertEquals(false, v.voicing?.present, "$v")
        if (r.verdict != Verdict.Correct) assertEquals(HintKey.AddVoice, v.hint?.key, "$v ${r.verdict}")
    }

    @Test
    fun anAffricateReportsClosureAndRelease() {
        val take = clip(cat(quiet(0.3), vowel(0.22), quiet(0.05, 0.0002f), sHiss(0.08), vowel(0.25), quiet(0.3)))
        val v = detector.judge(word(SoundId.Ts, "ața"), take).visual!!
        val c = assertNotNull(v.closure)
        assertEquals(true, c.closure, "$c")
        assertEquals(true, c.release, "$c")
    }

    @Test
    fun aShortHissFallsShortOfTheDuration() {
        val v = detector.judge(word(SoundId.S, "asa"), clip(vcv(sHiss(0.045)))).visual!!
        val d = v.duration
        if (d != null) assertEquals(d.value >= d.needed, d.ok)
    }

    @Test
    fun theHintFromAKindFollowsTheKind() {
        val t = VisualThresholds()
        val req = word(SoundId.R, "mare")
        val v = TakeVisuals.of(req, emptyMap(), Verdict.Unsure, ErrorKind.RtoL, 0.9f, 0.05f, t)
        assertEquals(HintKey.TapTongue, v.hint?.key)
        val g = TakeVisuals.of(word(SoundId.Ge, "ger"), emptyMap(), Verdict.Unsure, ErrorKind.Deaffrication, 0.8f, 0.1f, t)
        assertEquals(HintKey.StopThenRelease, g.hint?.key)
        val low = TakeVisuals.of(req, emptyMap(), Verdict.Unsure, ErrorKind.RtoL, 0.2f, 0.5f, t)
        assertNull(low.hint, "a weak kind with no cue gives no hint")
    }

    @Test
    fun aGateHasNoVisual() {
        assertNull(detector.judge(word(SoundId.S, "asa"), clip(quiet(1.5))).visual)
    }

    // ------------------------------------------------------------------------------------------ personal reference

    private fun fakeResult(sound: SoundId, sNess: Float, centroid: Float, pFull: Float = 0.95f, pFast: Float = 0.9f, head: Head = Head.Full) =
        TakeResult(
            verdict = Verdict.Correct, confidence = pFull, head = head,
            evidence = JudgeEvidence(
                mapOf(Features.S_NESS to sNess, Features.CENTROID to centroid, Features.PEAK to centroid * 0.9f,
                    Features.BAND_RATIO to (if (sNess > 0.5f) 8f else -4f), Features.TILT to 0f, Features.F0 to 120f,
                    Features.REL_LEVEL to -10f, Features.FRIC_DUR to 120f),
                null, null, emptyList(), emptySet(), emptyList(),
            ),
            versions = SpecVersions("", "", "", "", ""), sound = sound, fastCorrect = pFast,
        )

    @Test
    fun onlyTakesBothHeadsAcceptJoinTheReference() {
        val ref = PersonalReference()
        assertFalse(ref.update(fakeResult(SoundId.S, 0.95f, 7000f, pFast = 0.4f)), "the fast head doubted it")
        assertFalse(ref.update(fakeResult(SoundId.S, 0.95f, 7000f, head = Head.Fast)), "no phoneme model")
        assertFalse(ref.update(fakeResult(SoundId.S, 0.95f, 7000f, pFull = 0.7f)), "the full head was not sure")
        assertTrue(ref.update(fakeResult(SoundId.S, 0.95f, 7000f)))
        assertEquals(1, ref.takes)
    }

    @Test
    fun afterEnoughAnchorsHisBoundaryIsUsedAndSurvivesJson() {
        val ref = PersonalReference()
        assertNull(ref.calibration())
        repeat(5) {
            ref.update(fakeResult(SoundId.S, 0.9f, 6200f + it * 50))
            ref.update(fakeResult(SoundId.Sh, 0.05f, 3900f + it * 40))
        }
        assertTrue(ref.active)
        val cal = assertNotNull(ref.calibration())
        assertEquals(10, cal.anchors.size)
        assertEquals(TalkerGroup.Man, ref.talker())
        val applied = ref.apply(word(SoundId.S, "sare", TalkerGroup.Child))
        assertEquals(TalkerGroup.Man, applied.talker)
        assertNotNull(applied.calibration)
        val back = PersonalReference.fromJson(ref.toJson())
        assertTrue(back.active)
        assertEquals(ref.takes, back.takes)
        assertEquals(ref.f0Hz, back.f0Hz, 1e-3f)
        assertNotNull(back.loudnessFloor(SoundId.S, VisualThresholds()))
    }

    @Test
    fun theGuidedCalibrationGivesHisBoundaryAndGroupAtOnceAndSurvivesJson() {
        val ref = PersonalReference()
        fun a(c: Float) = tech.fonia.dsp.SibilantAnchor(tech.fonia.dsp.SibilantPlace.S, c, c + 400f, 6f, -3f)
        val room = PersonalReference.Room(floorDb = -72f, speechDb = -31f, f0Hz = 110f, atMillis = 1L)
        assertTrue(ref.calibrate(room, (0 until 5).map { a(6000f + it * 60) }, (0 until 5).map { a(3800f + it * 50) }))
        assertNotNull(ref.calibration())
        assertEquals(TalkerGroup.Man, ref.talker(), "the calibration's aaa says who speaks")
        assertEquals(110f, ref.apply(word(SoundId.S, "sare", TalkerGroup.Child)).childF0Hz)
        ref.placed(0.86f, Float.NaN)
        val back = PersonalReference.fromJson(ref.toJson())
        assertTrue(back.active)
        assertEquals(-72f, back.room?.floorDb)
        assertEquals(0.86f, back.room?.sPlace)
        assertNull(back.room?.shPlace)
        assertEquals(TalkerGroup.Man, back.talker())
    }

    @Test
    fun anUnclearPlaceNeverBecomesAnAnchor() {
        val ref = PersonalReference()
        repeat(6) { ref.update(fakeResult(SoundId.S, 0.55f, 5000f)) }
        assertFalse(ref.active)
        assertEquals(6, ref.takes)
    }

    @Test
    fun aBrokenSaveGivesAnEmptyReference() {
        assertEquals(0, PersonalReference.fromJson("{not json").takes)
        assertEquals(0, PersonalReference.fromJson(null).takes)
    }

    // ------------------------------------------------------------------------------------------ self policy

    @Test
    fun theLevelFollowsTheVoice() {
        assertEquals(PolicyLevel.Self, PolicyLevel.select(TalkerGroup.Man))
        assertEquals(PolicyLevel.Self, PolicyLevel.select(TalkerGroup.Woman))
        assertEquals(PolicyLevel.Child, PolicyLevel.select(TalkerGroup.Child))
        assertEquals(PolicyLevel.Self, PolicyLevel.select(TalkerGroup.Child, profileIsAdult = true))
    }

    @Test
    fun selfNamesAConfidentErrorAChildDoesNot() {
        val shipped = TakeJudge(spec)
        val take = clip(vcv(shHiss(0.15)))
        val m = shipped.measure(word(SoundId.S, "asa"), take)
        val child = shipped.decide(m)
        val self = shipped.decide(m, runtime = RuntimeContext(policy = PolicyLevel.Self))
        assertFalse(child.verdict is Verdict.NamedError, "nothing is unlocked for children: ${child.verdict}")
        val min = spec.unlock.rules["self"]?.minConfidence
        if (min != null && self.topErrorProbability >= maxOf(min, spec.fusionFor(SoundId.S).tauErr(ErrorKind.PlaceSwap) + 0.05f)) {
            assertTrue(self.verdict is Verdict.NamedError, "${self.verdict}")
        }
        assertFalse(shipped.unlock.hintAllowed(child, RuntimeContext()))
    }
}
