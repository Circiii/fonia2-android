package tech.fonia.judge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tech.fonia.model.ErrorKind
import tech.fonia.model.SoundId
import tech.fonia.model.Verdict

/** The whole chain P0–P5 on synthetic takes. */
class TakeJudgeTests {
    private val spec = loadSpec()
    private val judge = TakeJudge(spec)
    private val detector = TakeJudge(spec, unlock = UnlockView.everythingUnlocked())

    private fun word(sound: SoundId, text: String, position: Position = Position.Medial, talker: TalkerGroup = TalkerGroup.Woman) =
        JudgeRequest(sound, Level.Word, text, position, talker)

    @Test
    fun silenceIsARetake() {
        val r = judge.judge(word(SoundId.S, "asa"), clip(quiet(1.5)))
        assertEquals(Verdict.Retake, r.verdict)
        assertTrue(r.retakeReason == RetakeReason.NoSpeech || r.retakeReason == RetakeReason.TooQuiet, "${r.retakeReason}")
        assertEquals(Head.Gate, r.head)
    }

    @Test
    fun anEmptyClipIsARetake() {
        assertEquals(RetakeReason.NoSpeech, judge.judge(word(SoundId.S, "asa"), tech.fonia.live.Clip.Empty).retakeReason)
    }

    @Test
    fun clippingIsARetake() {
        val loud = vcv(sHiss(0.15)).map { (it * 40f).coerceIn(-1f, 1f) }.toFloatArray()
        assertEquals(RetakeReason.Clipped, judge.judge(word(SoundId.S, "asa"), clip(loud)).retakeReason)
    }

    @Test
    fun aVeryQuietTakeIsARetake() {
        val faint = vcv(sHiss(0.15)).map { it * 0.001f }.toFloatArray()
        val r = judge.judge(word(SoundId.S, "asa"), clip(faint))
        assertEquals(Verdict.Retake, r.verdict)
    }

    @Test
    fun droppedSamplesAreARetake() {
        assertEquals(RetakeReason.Dropped, judge.judge(word(SoundId.S, "asa"), clip(vcv(sHiss(0.15)), dropped = 480)).retakeReason)
    }

    @Test
    fun aTruncatedTakeIsARetake() {
        val r = judge.judge(word(SoundId.S, "asa"), clip(cat(quiet(0.3), vowel(0.06), quiet(0.3))))
        assertEquals(Verdict.Retake, r.verdict)
    }

    @Test
    fun aLowVoiceOnAChildTargetIsAnAdult() {
        val r = judge.judge(word(SoundId.S, "asa", talker = TalkerGroup.Child), clip(vcv(sHiss(0.15), f0 = 110f)))
        assertEquals(RetakeReason.AdultVoice, r.retakeReason)
    }

    @Test
    fun aGoodSIsNotAnError() {
        val r = detector.judge(word(SoundId.S, "asa"), clip(vcv(sHiss(0.16))))
        assertTrue(r.verdict == Verdict.Correct || r.verdict == Verdict.Almost, "${r.verdict} ${r.probabilities}")
        assertTrue(r.probabilities.getValue("correct") > r.probabilities.getValue("placeSwap"))
        assertEquals(1f, r.evidence.features[Features.PRESENT])
    }

    @Test
    fun anSJudgedAsShIsAPlaceSwapHeardAsS() {
        val r = detector.judge(word(SoundId.Sh, "așa"), clip(vcv(sHiss(0.16))))
        assertEquals(ErrorKind.PlaceSwap, r.topError, "${r.probabilities}")
        if (r.verdict is Verdict.NamedError) assertEquals(SoundId.S, (r.verdict as Verdict.NamedError).heardAs)
    }

    @Test
    fun aShJudgedAsSIsAPlaceSwapHeardAsSh() {
        val r = detector.judge(word(SoundId.S, "asa"), clip(vcv(shHiss(0.16))))
        assertEquals(ErrorKind.PlaceSwap, r.topError, "${r.probabilities}")
        if (r.verdict is Verdict.NamedError) assertEquals(SoundId.Sh, (r.verdict as Verdict.NamedError).heardAs)
    }

    @Test
    fun withTheShippedUnlockFileTheSwapIsWithheldForTheAdult() {
        val r = judge.judge(word(SoundId.S, "asa"), clip(vcv(shHiss(0.16))))
        assertTrue(r.verdict !is Verdict.NamedError, "${r.verdict}")
        if (r.withheldKind != null) assertTrue(r.adultNote!!.inTrial)
    }

    @Test
    fun aVowelInitialTakeForAnInitialSIsAnOmission() {
        val r = detector.judge(word(SoundId.S, "sa", Position.Initial), clip(cat(quiet(0.3), vowel(0.35), quiet(0.3))))
        assertEquals(ErrorKind.Omission, r.topError, "${r.probabilities} ${r.evidence.features}")
        assertTrue(r.verdict != Verdict.Correct)
    }

    @Test
    fun anUnvoicedZIsDevoicing() {
        val r = detector.judge(word(SoundId.Z, "aza"), clip(vcv(sHiss(0.16))))
        assertEquals(ErrorKind.Devoicing, r.topError, "${r.probabilities} ${r.evidence.features}")
    }

    @Test
    fun theResultCarriesItsVersionsAndEvidence() {
        val r = judge.judge(word(SoundId.S, "asa"), clip(vcv(sHiss(0.16))))
        assertEquals(spec.population.version, r.versions.thresholds)
        assertEquals(spec.fusionFor(SoundId.S).version, r.versions.fusion)
        assertTrue(r.evidence.capture!!.bandwidthHz > 11000f)
        assertTrue(r.evidence.segments.isNotEmpty())
        assertTrue("analyzeClip" in r.micros)
    }

    @Test
    fun phonemeEvidenceSelectsTheFullHead() {
        val m = judge.measure(word(SoundId.S, "asa"), clip(vcv(sHiss(0.16))))
        val fast = judge.decide(m)
        val all8 = listOf("ʃ", "ts", "z", "t", "θ", "ɬ", "ɕ", "s̪", "∅").associateWith { 8f }
        val full = judge.decide(m, PhoneEvidence(all8, fitPerLabel = 0.2f, frames = 40, gop = -0.01f))
        assertEquals(Head.Fast, fast.head)
        assertEquals(Head.Full, full.head)
        assertEquals(Verdict.Correct, fast.verdict)
        assertEquals(Verdict.Correct, full.verdict, "every candidate 8 nats behind the target agrees with the DSP")
        // The phone model hearing [ʃ] clearly turns the same measurement into a place error.
        val swapped = judge.decide(m, PhoneEvidence(all8 + ("ʃ" to -8f), fitPerLabel = 0.2f, frames = 40, gop = -8f))
        assertEquals(ErrorKind.PlaceSwap, swapped.topError)
        assertTrue(swapped.verdict != Verdict.Correct)
        // A poor word fit from the phoneme model is another word.
        assertEquals(RetakeReason.OtherWord, judge.decide(m, PhoneEvidence(emptyMap(), fitPerLabel = -5f, frames = 30)).retakeReason)
    }

    @Test
    fun withTheFullHeadAGoodLIsCorrectAndAnLSaidAsRIsNamed() {
        val m = judge.measure(word(SoundId.L, "ala"), clip(vcv(sHiss(0.16))))
        assertEquals(Verdict.Unsure, judge.decide(m).verdict, "no DSP detector for a good L")
        val good = PhoneEvidence(mapOf("j" to 8f, "w" to 8f, "r" to 8f, "∅" to 8f, "j+w" to 7.5f), fitPerLabel = 0.2f, frames = 40, gop = -0.01f)
        val r = judge.decide(m, good)
        assertEquals(Head.Full, r.head)
        assertEquals(Verdict.Correct, r.verdict)
        val asR = PhoneEvidence(mapOf("j" to 6f, "w" to 6f, "r" to -8f, "∅" to 5f, "j+w" to 5.5f), fitPerLabel = 0.2f, frames = 40, gop = -8f)
        val named = judge.decide(m, asR)
        assertEquals(ErrorKind.LtoR, named.topError)
        assertTrue(named.verdict is Verdict.NamedError || named.withheldKind == ErrorKind.LtoR || named.adultNote?.kind == ErrorKind.LtoR, named.toString())
    }

    @Test
    fun anUnreliableAlignmentFallsBackToTheFastHead() {
        val m = judge.measure(word(SoundId.L, "ala"), clip(vcv(sHiss(0.16))))
        val r = judge.decide(m, PhoneEvidence(mapOf("j" to 8f, "w" to 8f, "r" to 8f, "∅" to 8f), fitPerLabel = -2.5f, frames = 40, reliable = false, notes = listOf("poorFit")))
        assertEquals(Head.Fast, r.head)
        assertTrue("phonesUnreliable" in r.evidence.notes && "zipa:poorFit" in r.evidence.notes, r.evidence.notes.toString())
    }

    @Test
    fun theFitGateIsForWordsOnly() {
        val m = judge.measure(JudgeRequest(SoundId.S, Level.Syllable, "asa", Position.Medial, TalkerGroup.Woman), clip(vcv(sHiss(0.16))))
        val r = judge.decide(m, PhoneEvidence(mapOf("ʃ" to 4f), fitPerLabel = -5f, frames = 30, reliable = false))
        assertTrue(r.verdict != Verdict.Retake, r.toString())
    }

    @Test
    fun aTextWithoutTheTargetIsUnsureNotAGuess() {
        val r = judge.judge(word(SoundId.R, "asa"), clip(vcv(sHiss(0.16))))
        assertEquals(Verdict.Unsure, r.verdict)
        assertTrue("noTarget" in r.evidence.notes)
    }
}
