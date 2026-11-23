package tech.fonia.dsp

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun segment(
    place: SibilantPlace, start: Double = 0.2, end: Double = 0.35, sNess: Float? = null, borderline: Boolean = false,
    voiced: Boolean = false, diffuse: Boolean = false, lateral: Boolean = false, measurable: Boolean = true,
): FricativeSegment = FricativeSegment(
    start = start, end = end, coreStart = start, coreEnd = end, place = place,
    sNess = sNess ?: (if (place == SibilantPlace.S) 0.9f else 0.1f), isBorderline = borderline, voiced = voiced,
    voicingMeasurable = measurable, diffuse = diffuse, lateralHint = lateral, centroidHz = 0f, peakHz = 0f, bandRatioDb = 0f,
    tiltDb = 0f, lowBandDb = 0f, flatness = 0f, aboveFloorDb = 30f,
)

/** Swift analyses this carrier on every call; the analysis is deterministic, so once is the same. */
private val carrierSummary: ClipSummary by lazy { analyze(carrier(sNoise(0.16))) }

private fun summary(
    fricatives: List<FricativeSegment> = emptyList(), taps: Int = 0, trills: Int = 0,
    clipped: Boolean = false, quiet: Boolean = false, short: Boolean = false, sibilantsOK: Boolean = true,
    speech: ClosedFloatingPointRange<Double> = 0.1..1.1, peakDb: Float = -20f, floorDb: Float = -70f,
): ClipSummary {
    val base = carrierSummary
    return base.copy(
        fricatives = fricatives,
        taps = (0 until taps).map { TapEvent(time = 0.3 + it.toDouble() * 0.2, closureMs = 26f, depthDb = 12f, score = 1f) },
        trills = (0 until trills).map { TrillSegment(start = 0.3, end = 0.6, rate = 26f, periodicity = 0.8f) },
        quality = base.quality.copy(isClipped = clipped, isTooQuiet = quiet, isTooShort = short, canJudgeSibilants = sibilantsOK),
        speechStart = speech.start,
        speechEnd = speech.endInclusive,
        peakDb = peakDb,
        floorDb = floorDb,
    )
}

class ExpectedFricativeTests {
    private fun graphemes(text: String): List<String> = expectedFricatives(text).map { it.grapheme }

    @Test
    fun findsSibilantsInOrder() {
        assertEquals(listOf("s"), graphemes("sanie"))
        assertEquals(listOf("ș"), graphemes("șapcă"))
        assertEquals(listOf("ce", "s"), graphemes("ceas"))
        assertEquals(listOf("ci", "ș"), graphemes("cireșe"))
        assertEquals(listOf("ge"), graphemes("gem"))
        assertEquals(listOf("z"), graphemes("zebră"))
        assertEquals(listOf("ț"), graphemes("țap"))
        assertEquals(listOf("j"), graphemes("jar"))
    }

    @Test
    fun softensCAndGOnlyBeforeEAndI() {
        assertTrue(graphemes("cal").isEmpty())
        assertTrue(graphemes("gât").isEmpty())
        assertEquals(listOf("f"), graphemes("chef"))
        assertTrue(graphemes("ghem").isEmpty())
    }

    @Test
    fun scIsNotAnAffricate() {
        assertEquals(listOf("s"), graphemes("scenă"))
    }

    @Test
    fun otherFricativesAreTrackedWithoutAPlace() {
        val fa = expectedFricatives("fată")
        assertNull(fa.firstOrNull()?.place)
        assertEquals("f", fa.firstOrNull()?.grapheme)
        assertEquals(listOf("v", "z"), graphemes("vază"))
        assertEquals(listOf("ș", "h"), graphemes("șah"))
        assertTrue(graphemes("chip").isEmpty())
    }

    @Test
    fun placeAndVoicingAreRight() {
        val expected = expectedFricatives("zăpadă jar ceas")
        assertTrue(expected[0].place == SibilantPlace.S && expected[0].voiced)
        assertTrue(expected[1].place == SibilantPlace.SH && expected[1].voiced)
        assertTrue(expected[2].place == SibilantPlace.SH && !expected[2].voiced)
    }

    @Test
    fun aProlongationIsOneSound() {
        assertEquals(listOf("s"), graphemes("ssssss"))
        assertEquals(listOf("ș"), graphemes("șșșșșș"))
        assertEquals(listOf("ș", "ș"), graphemes("șa-șa"))
        assertEquals(1, rhoticPositions("rrrrrr").size)
    }

    @Test
    fun handlesCapitalsAndLegacyCedillas() {
        assertEquals(listOf("ș"), graphemes("ŞAPCĂ"))
        assertEquals(listOf("ț"), graphemes("ţap"))
        assertEquals(listOf("ș"), graphemes("Șapca"))
    }

    @Test
    fun marksWordFinalLetters() {
        val expected = expectedFricatives("orez bun")
        assertEquals(true, expected.firstOrNull()?.isWordFinal)
        assertEquals(false, expectedFricatives("zebră").firstOrNull()?.isWordFinal)
    }

    @Test
    fun positionsFollowTheText() {
        val expected = expectedFricatives("sanie ceas")
        assertEquals(0f, expected.firstOrNull()?.position)
        assertEquals(1f, expected.lastOrNull()?.position)
    }

    @Test
    fun emptyTextHasNothing() {
        assertTrue(graphemes("").isEmpty())
    }
}

class AlignmentTests {
    @Test
    fun picksTheDetectionNearestTheLetter() {
        // Speech 0.1 to 1.1. S is at the start of the text; the later detection is a final stop burst.
        val s = summary(fricatives = listOf(segment(SibilantPlace.S, start = 0.12, end = 0.2), segment(SibilantPlace.S, start = 1.0, end = 1.05)))
        val matches = alignFricatives(text = "sat", summary = s)
        assertEquals(1, matches.size)
        assertEquals(0.12, assertNotNull(matches[0].detected).start)
    }

    @Test
    fun aDetectionGoesToTheSibilantBeforeAWeakFricative() {
        // Only the S is heard; F, V and H often go unheard.
        val s = summary(fricatives = listOf(segment(SibilantPlace.S, start = 0.12, end = 0.2)))
        val matches = alignFricatives(text = "safe", summary = s)
        assertEquals("s", matches[0].expected.grapheme)
        assertTrue(matches[0].detected != null)
        assertNull(matches[1].detected)
    }

    @Test
    fun noDetectionsMeansAllMissing() {
        val matches = alignFricatives(text = "sașa", summary = summary())
        assertEquals(2, matches.size)
        assertTrue(matches.all { it.detected == null })
    }
}

class SibilantOutcomeTests {
    private fun judge(text: String, graphemes: List<String>, s: ClipSummary): SoundOutcome =
        judgeSibilant(text = text, graphemes = graphemes, summary = s)

    private fun judge(s: ClipSummary): SoundOutcome = judge("sare", listOf("s"), s)

    @Test
    fun aMatchingPlaceIsGood() {
        val outcome = judge(summary(fricatives = listOf(segment(SibilantPlace.S, start = 0.12, end = 0.25))))
        assertEquals(SoundOutcome.Good, outcome)
        assertTrue(outcome.isAccepted())
        assertFalse(outcome.isAbstention)
    }

    @Test
    fun theOtherPlaceIsReportedWithCounts() {
        val outcome = judge(
            "sasa", listOf("s"),
            summary(fricatives = listOf(segment(SibilantPlace.SH, start = 0.15, end = 0.3), segment(SibilantPlace.S, start = 0.6, end = 0.8))),
        )
        assertEquals(SoundOutcome.WrongPlace(heard = SibilantPlace.SH, count = 1, of = 2), outcome)
        assertFalse(outcome.isAccepted())
    }

    @Test
    fun nothingHeardAtTheTargetIsMissing() {
        assertEquals(SoundOutcome.Missing, judge(summary()))
    }

    @Test
    fun nothingHeardInAVeryShortTakeIsTooShortNotMissing() {
        assertEquals(SoundOutcome.TooShort, judge(summary(speech = 0.1..0.3)))
    }

    @Test
    fun someTargetsMissingIsPartial() {
        val outcome = judge("sasa", listOf("s"), summary(fricatives = listOf(segment(SibilantPlace.S, start = 0.12, end = 0.3))))
        assertEquals(SoundOutcome.Partial(heard = 1, of = 2), outcome)
    }

    @Test
    fun aBorderlineTokenAbstains() {
        val outcome = judge(summary(fricatives = listOf(segment(SibilantPlace.S, start = 0.12, end = 0.25, sNess = 0.55f, borderline = true))))
        assertEquals(SoundOutcome.Unclear(UnclearReason.BORDERLINE), outcome)
        assertTrue(outcome.isAbstention)
    }

    @Test
    fun aDiffuseTokenIsWeak() {
        assertEquals(SoundOutcome.Weak, judge(summary(fricatives = listOf(segment(SibilantPlace.S, start = 0.12, end = 0.25, diffuse = true)))))
    }

    @Test
    fun anUnvoicedZIsUnvoiced() {
        assertEquals(
            SoundOutcome.Unvoiced,
            judge("zare", listOf("z"), summary(fricatives = listOf(segment(SibilantPlace.S, start = 0.12, end = 0.25, voiced = false)))),
        )
    }

    @Test
    fun aVoicedZIsGood() {
        assertEquals(
            SoundOutcome.Good,
            judge("zare", listOf("z"), summary(fricatives = listOf(segment(SibilantPlace.S, start = 0.12, end = 0.25, voiced = true)))),
        )
    }

    @Test
    fun wordFinalDevoicingIsNotAFault() {
        assertEquals(
            SoundOutcome.Good,
            judge("orez", listOf("z"), summary(fricatives = listOf(segment(SibilantPlace.S, start = 0.9, end = 1.05, voiced = false)))),
        )
    }

    @Test
    fun tooShortToMeasureVoicingIsNotAFault() {
        val affricate = segment(SibilantPlace.SH, start = 0.12, end = 0.16, voiced = false, measurable = false)
        assertEquals(SoundOutcome.Good, judge("gem", listOf("ge"), summary(fricatives = listOf(affricate))))
    }

    @Test
    fun aLateralShapeIsAHintWithNoVerdict() {
        val outcome = judge(summary(fricatives = listOf(segment(SibilantPlace.S, start = 0.12, end = 0.25, lateral = true))))
        assertEquals(SoundOutcome.LateralHint, outcome)
        assertFalse(outcome.isAccepted())
        assertTrue(outcome.isAbstention)
    }

    @Test
    fun aBorderlineLateralShapeStillOnlyHints() {
        val token = segment(SibilantPlace.SH, start = 0.12, end = 0.25, sNess = 0.45f, borderline = true, lateral = true)
        assertEquals(SoundOutcome.LateralHint, judge(summary(fricatives = listOf(token))))
    }

    @Test
    fun theHintOnlyAppliesToTheSFamily() {
        val outcome = judge("șarpe", listOf("ș"), summary(fricatives = listOf(segment(SibilantPlace.SH, start = 0.12, end = 0.25, lateral = true))))
        assertEquals(SoundOutcome.Good, outcome)
    }

    @Test
    fun textWithoutTheSoundIsNotJudged() {
        assertEquals(SoundOutcome.Unclear(UnclearReason.NO_TARGET), judge("lac", listOf("s"), summary()))
    }

    @Test
    fun aNarrowCaptureAbstainsInsteadOfGuessing() {
        val outcome = judge(summary(fricatives = listOf(segment(SibilantPlace.SH, start = 0.12, end = 0.25)), sibilantsOK = false))
        assertEquals(SoundOutcome.Unclear(UnclearReason.LOW_BANDWIDTH), outcome)
    }

    @Test
    fun graphemeListsCombine() {
        val s = summary(fricatives = listOf(segment(SibilantPlace.SH, start = 0.12, end = 0.25)))
        assertEquals(SoundOutcome.Good, judge("cireașă", listOf("ce", "ci"), s))
    }

    @Test
    fun captureFailuresOutrankPronunciation() {
        val wrong = listOf(segment(SibilantPlace.SH, start = 0.12, end = 0.25))
        assertEquals(SoundOutcome.Clipped, judge(summary(fricatives = wrong, clipped = true)))
        assertEquals(SoundOutcome.NotHeard, judge(summary(fricatives = wrong, quiet = true)))
        assertEquals(SoundOutcome.TooShort, judge(summary(fricatives = wrong, short = true)))
        assertEquals(SoundOutcome.NotHeard, judge(summary(fricatives = wrong, short = true, peakDb = -60f, floorDb = -65f)))
        assertEquals(SoundOutcome.NotHeard, judge(summary(fricatives = wrong, speech = 0.1..0.15)))
    }

    @Test
    fun endToEndSOnAnSWordIsGood() {
        assertEquals(SoundOutcome.Good, judge("asa", listOf("s"), analyze(carrier(sNoise(0.16)))))
    }

    @Test
    fun endToEndShOnAnSWordIsWrongPlace() {
        assertEquals(
            SoundOutcome.WrongPlace(heard = SibilantPlace.SH, count = 1, of = 1),
            judge("asa", listOf("s"), analyze(carrier(shNoise(0.16)))),
        )
    }

    @Test
    fun endToEndAVowelOnAnSWordIsMissing() {
        assertEquals(SoundOutcome.Missing, judge("asa", listOf("s"), analyze(withNoise(silence(0.2) + vowel(0.6) + silence(0.2)))))
    }

    @Test
    fun endToEndAClippedTakeIsNotAPronunciationError() {
        val loud = (silence(0.2) + vowel(0.5, f0 = 200f, amp = 3f) + silence(0.2)).let { s -> FloatArray(s.size) { max(-1f, min(1f, s[it])) } }
        assertEquals(SoundOutcome.Clipped, judge(analyze(withNoise(loud, amp = 0.0005f))))
    }

    @Test
    fun outcomesCarryScores() {
        assertTrue(SoundOutcome.Good.score() > SoundOutcome.Partial(heard = 1, of = 2).score())
        assertTrue(SoundOutcome.Partial(heard = 1, of = 2).score() > SoundOutcome.WrongPlace(heard = SibilantPlace.S, count = 1, of = 1).score())
        assertTrue(SoundOutcome.WrongPlace(heard = SibilantPlace.S, count = 1, of = 2).score() > SoundOutcome.NotHeard.score())
    }
}

class RhoticOutcomeTests {
    @Test
    fun aTapPassesAWord() {
        val outcome = judgeRhotic(text = "pară", summary = summary(taps = 1))
        assertEquals(SoundOutcome.Tap, outcome)
        assertTrue(outcome.isAccepted())
    }

    @Test
    fun aTrillPassesAWordToo() {
        val outcome = judgeRhotic(text = "pară", summary = summary(trills = 1))
        assertEquals(SoundOutcome.Trill, outcome)
        assertTrue(outcome.isAccepted())
    }

    @Test
    fun wordsNeverRequireATrill() {
        assertTrue(judgeRhotic(text = "pară", summary = summary(taps = 1, trills = 0)).isAccepted())
    }

    @Test
    fun nothingAtAnIntervocalicRIsMissing() {
        assertEquals(SoundOutcome.Missing, judgeRhotic(text = "pară", summary = summary()))
    }

    @Test
    fun nothingAtAWordEdgeAbstains() {
        assertEquals(SoundOutcome.Unclear(UnclearReason.RHOTIC_CONTEXT), judgeRhotic(text = "rac", summary = summary()))
        assertEquals(SoundOutcome.Unclear(UnclearReason.RHOTIC_CONTEXT), judgeRhotic(text = "măr", summary = summary()))
        assertEquals(SoundOutcome.Unclear(UnclearReason.RHOTIC_CONTEXT), judgeRhotic(text = "tractor", summary = summary()))
    }

    @Test
    fun someOfSeveralIntervocalicRsIsPartial() {
        assertEquals(SoundOutcome.Partial(heard = 1, of = 3), judgeRhotic(text = "rărire", summary = summary(taps = 1)))
    }

    @Test
    fun aShortfallOnlyAtUnobservablePositionsStillPasses() {
        assertEquals(SoundOutcome.Tap, judgeRhotic(text = "tractor", summary = summary(taps = 1)))
    }

    @Test
    fun noRInTheTextIsNotJudged() {
        assertEquals(SoundOutcome.Unclear(UnclearReason.NO_TARGET), judgeRhotic(text = "lac", summary = summary()))
    }

    @Test
    fun captureFailuresOutrankPronunciation() {
        assertEquals(SoundOutcome.Clipped, judgeRhotic(text = "pară", summary = summary(clipped = true)))
        assertEquals(SoundOutcome.NotHeard, judgeRhotic(text = "pară", summary = summary(quiet = true)))
    }

    @Test
    fun endToEndATapInAVowelFrameIsATap() {
        val s = analyze(withNoise(silence(0.2) + tapSignal(closureMs = 26.0) + silence(0.2)))
        assertEquals(SoundOutcome.Tap, judgeRhotic(text = "ara", summary = s))
    }

    @Test
    fun endToEndATrillIsATrill() {
        val s = analyze(withNoise(silence(0.2) + trillSignal(0.6, rate = 25.0) + silence(0.2)))
        assertEquals(SoundOutcome.Trill, judgeRhotic(text = "ara", summary = s))
    }

    @Test
    fun endToEndAPlainVowelIsMissing() {
        assertEquals(SoundOutcome.Missing, judgeRhotic(text = "ara", summary = analyze(withNoise(silence(0.2) + vowel(0.5) + silence(0.2)))))
    }
}

class EnglishTHOutcomeTests {
    @Test
    fun weakFrictionIsGood() {
        assertEquals(SoundOutcome.Good, judgeEnglishTH(summary = summary(fricatives = listOf(segment(SibilantPlace.S, start = 0.12, end = 0.2, diffuse = true)))))
    }

    @Test
    fun noFrictionAtAllStillPasses() {
        assertEquals(SoundOutcome.Good, judgeEnglishTH(summary = summary()))
    }

    @Test
    fun aSharpHissAtTheStartBecameS() {
        assertEquals(
            SoundOutcome.WrongPlace(heard = SibilantPlace.S, count = 1, of = 1),
            judgeEnglishTH(summary = summary(fricatives = listOf(segment(SibilantPlace.S, start = 0.12, end = 0.2)))),
        )
    }

    @Test
    fun aSharpHissLaterIsAnotherSound() {
        assertEquals(SoundOutcome.Good, judgeEnglishTH(summary = summary(fricatives = listOf(segment(SibilantPlace.S, start = 0.8, end = 0.9)))))
    }

    @Test
    fun notHeardIsReported() {
        assertEquals(SoundOutcome.NotHeard, judgeEnglishTH(summary = summary(quiet = true)))
    }

    @Test
    fun endToEnd() {
        val th = withNoise(
            silence(0.2) + vowel(0.05, amp = 0.05f) + diffuseNoise(0.14) + vowel(0.3, amp = 0.05f) + silence(0.2), amp = 0.003f,
        )
        assertEquals(SoundOutcome.Good, judgeEnglishTH(summary = analyze(th)))
        val s = withNoise(silence(0.2) + sNoise(0.16) + vowel(0.3) + silence(0.2))
        assertEquals(SoundOutcome.WrongPlace(heard = SibilantPlace.S, count = 1, of = 1), judgeEnglishTH(summary = analyze(s)))
    }
}
