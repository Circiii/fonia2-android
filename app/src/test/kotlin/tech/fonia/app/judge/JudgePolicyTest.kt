package tech.fonia.app.judge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.judge.AdultNote
import tech.fonia.judge.Feedback
import tech.fonia.judge.Head
import tech.fonia.judge.JudgeEvidence
import tech.fonia.judge.JudgeSpec
import tech.fonia.judge.RetakeReason
import tech.fonia.judge.RuntimeContext
import tech.fonia.judge.SpecVersions
import tech.fonia.judge.TakeResult
import tech.fonia.judge.UnlockEntry
import tech.fonia.judge.UnlockSpec
import tech.fonia.model.ErrorKind
import tech.fonia.model.SoundId
import tech.fonia.model.Verdict
import tech.fonia.store.JSONValue
import java.io.File

/** The unlock policy of plan 03 §7 / Q3 as the lesson applies it (the judge adapter's mapping). */
class JudgePolicyTest {
    private val spec = JudgeSpec.load(File(System.getProperty("fonia.root") ?: "..", "spec"))
    private val feedback = Feedback(spec.feedback)
    private val versions = SpecVersions("t", "f", "u", "fb", "e2.v0")
    private val evidence = JudgeEvidence(mapOf("sNess" to 0.9f, "bad" to Float.NaN), null, null, listOf(0.1 to 0.4), emptySet(), listOf("note"))

    private fun result(v: Verdict, note: AdultNote? = null, withheld: ErrorKind? = null, retake: RetakeReason? = null) = TakeResult(
        verdict = v, confidence = 0.9f, retakeReason = retake, head = Head.Fast,
        probabilities = mapOf("correct" to 0.05f, "placeSwap" to 0.9f), topError = ErrorKind.PlaceSwap, topErrorProbability = 0.9f,
        withheldKind = withheld, adultNote = note, evidence = evidence, versions = versions,
    )

    private fun unlock(state: String, level: String?) = UnlockSpec(
        version = "test",
        entries = listOf(UnlockEntry("s", "placeSwap", 1, state, level)),
    )

    @Test fun shippedSpecNamesNothingToTheChild() {
        // the committed unlock.json: wave 1 is trial, so the judge never returns a NamedError; a trial kind is adult-only
        val r = result(Verdict.Almost, AdultNote(ErrorKind.PlaceSwap, 0.9f, SoundId.Sh, inTrial = true), withheld = ErrorKind.PlaceSwap)
        val j = JudgePolicy.map(r, SoundId.S, spec.unlock, feedback, RuntimeContext())
        assertEquals(Verdict.Almost, j.verdict)
        assertNull("no child line without a named error", j.childLine)
        val adult = j.adult!!
        assertTrue(adult.inTrial)
        assertEquals(SoundId.Sh, adult.heardAs)
        assertEquals("S-ul seamănă cu Ș; cereți un zâmbet larg.", adult.line)
    }

    @Test fun aLockedWithheldKindIsSeenByNobody() {
        val j = JudgePolicy.map(result(Verdict.Unsure, note = null, withheld = ErrorKind.PlaceSwap), SoundId.S, unlock("locked", null), feedback, RuntimeContext())
        assertEquals(Verdict.Unsure, j.verdict)
        assertNull(j.adult)
        assertNull(j.childLine)
        assertEquals("Telefonul nu e sigur. Cum vi s-a părut?", j.adultReason)
        // but the trace keeps it
        assertEquals(JSONValue.String("placeSwap"), j.detail["withheldKind"])
    }

    @Test fun anUnlockedChildrenKindIsShownWithTheFeedbackLine() {
        val j = JudgePolicy.map(result(Verdict.NamedError(ErrorKind.PlaceSwap, SoundId.Sh)), SoundId.S, unlock("unlocked", "children"), feedback, RuntimeContext())
        assertEquals(ChildFacing.Show, j.facing)
        assertEquals("Zâmbește și ține limba în față.", j.childLine)
        assertEquals(false, j.adult!!.inTrial)
    }

    @Test fun anImitationsKindWaitsForTheAdultsThumb() {
        val j = JudgePolicy.map(result(Verdict.NamedError(ErrorKind.PlaceSwap, SoundId.Sh)), SoundId.S, unlock("unlocked", "imitations"), feedback, RuntimeContext(adultPresent = true, calibrationTier = 1))
        assertEquals(ChildFacing.AdultFirst, j.facing)
        assertNotNull(j.childLine)
    }

    @Test fun retakeCarriesTheChildAndAdultLinesAndNeverAnInstruction() {
        val j = JudgePolicy.map(result(Verdict.Retake, retake = RetakeReason.AdultVoice), SoundId.S, spec.unlock, feedback, RuntimeContext())
        assertEquals(Verdict.Retake, j.verdict)
        assertEquals("Acum tu!", j.childLine)
        assertEquals("Pare vocea unui adult; lăsați copilul să spună.", j.adultReason)
        assertNull(j.adult)
    }

    @Test fun traceDropsNaNAndKeepsEverythingElse() {
        val j = JudgePolicy.map(result(Verdict.Correct), SoundId.S, spec.unlock, feedback, RuntimeContext(adultPresent = true, calibrationTier = 1))
        val features = (j.detail["features"] as JSONValue.Object).value
        assertTrue("sNess" in features && "bad" !in features)
        assertEquals(JSONValue.String("correct"), j.detail["verdict"])
        assertTrue("probabilities" in j.detail && "versions" in j.detail && "runtime" in j.detail && "segments" in j.detail)
    }

    @Test fun runtimeOffsetsAreCappedAt097() {
        val ctx = JudgeContext(
            talker = Talker.Child, adultPresent = true,
            calibration = SoundCalibration(confirmedGood = 6, tier = 1, childOkOffset = 0.05f, childErrOffset = mapOf(ErrorKind.PlaceSwap to 0.5f)),
        )
        val judge = tech.fonia.judge.TakeJudge(spec)
        val rt = RealJudge.runtime(ctx, judge, SoundId.S)
        val tau = spec.fusionFor(SoundId.S).tauErr(ErrorKind.PlaceSwap)
        assertEquals(RealJudge.MAX_TAU, tau + rt.childErrOffset.getValue(ErrorKind.PlaceSwap), 1e-4f)
        assertEquals(1, rt.calibrationTier)
        assertTrue(rt.adultPresent)
    }

    @Test fun adultTalkerSkipsTheChildF0() {
        val ctx = JudgeContext(talker = Talker.AdultLow, calibration = SoundCalibration(childF0Hz = 280f))
        val req = RealJudge.jvmRequest(TakeRequest(SoundId.S, tech.fonia.model.Rung.WordsInitial, "sare", ctx))
        assertEquals(tech.fonia.judge.TalkerGroup.Man, req.talker)
        assertNull(req.childF0Hz)
        assertEquals(tech.fonia.judge.Position.Initial, req.position)
    }
}
