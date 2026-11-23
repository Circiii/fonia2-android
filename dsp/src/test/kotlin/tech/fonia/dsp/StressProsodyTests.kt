package tech.fonia.dsp

import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val speechRate = 16000.0

private fun summarize(signal: FloatArray): ClipSummary =
    analyzeClip(signal, sampleRate = speechRate, group = VoiceGroup.FEMALE, calibration = null)

class StressMarkTests {
    @Test
    fun readsTheCapitalisedSyllable() {
        val animal = assertNotNull(parseStress("ANimal"))
        assertTrue(animal.word == "animal" && animal.syllables == 3 && animal.stressed == 0 && animal.marked == "AN", "$animal")
        val late = assertNotNull(parseStress("aniMAL"))
        assertTrue(late.syllables == 3 && late.stressed == 2 && late.marked == "MAL", "$late")
        val chocolate = assertNotNull(parseStress("CHOColate"))
        assertTrue(chocolate.syllables == 3 && chocolate.stressed == 0, "$chocolate")
        val interesting = assertNotNull(parseStress("interESting"))
        assertTrue(interesting.syllables == 4 && interesting.stressed == 2, "$interesting")
        val family = assertNotNull(parseStress("faMIly"))
        assertTrue(family.syllables == 3 && family.stressed == 1, "$family")
    }

    @Test
    fun aLeadingCapitalAloneIsNotAMark() {
        assertNull(parseStress("Animal"))
        assertNull(parseStress("animal"))
        assertNull(parseStress(""))
    }

    @Test
    fun readsRomanianCapitals() {
        val pattern = assertNotNull(parseStress("maȘInă"))
        assertEquals(1, pattern.stressed)
        assertEquals(3, pattern.syllables)
    }
}

class StressTests {
    private val strong = SyllableSpec(0.2, 230f, 260f, 0.5f)
    private fun weak(f0: Float) = SyllableSpec(0.11, f0, f0 - 10f, 0.18f)
    private val first: FloatArray get() = speech(listOf(strong, weak(200f), weak(190f)))
    private val last: FloatArray get() = speech(listOf(weak(200f), weak(195f), SyllableSpec(0.2, 220f, 250f, 0.5f)))
    private val middle: FloatArray get() = speech(listOf(weak(200f), strong, weak(190f)))

    @Test
    fun findsALouderHigherLongerFirstSyllable() {
        val analysis = analyzeStress(summarize(first))
        assertEquals(3, analysis.syllables.size, "$analysis")
        assertEquals(0, analysis.stressed, "$analysis")
        assertTrue(analysis.confidence > 0.5f, "$analysis")
    }

    @Test
    fun findsTheStressAtTheEndAndInTheMiddle() {
        assertEquals(2, analyzeStress(summarize(last)).stressed)
        assertEquals(1, analyzeStress(summarize(middle)).stressed)
    }

    @Test
    fun passesARightStressAndCatchesAWrongOne() {
        assertEquals(StressOutcome.Correct(syllable = 0), judgeStress(analyzeStress(summarize(first)), marked = "ANimal"))
        assertEquals(StressOutcome.Wrong(heard = 2, expected = 0), judgeStress(analyzeStress(summarize(last)), marked = "ANimal"))
        assertEquals(StressOutcome.Correct(syllable = 2), judgeStress(analyzeStress(summarize(last)), marked = "aniMAL"))
        assertEquals(StressOutcome.Correct(syllable = 1), judgeStress(analyzeStress(summarize(middle)), marked = "faMIly"))
    }

    @Test
    fun abstainsWhenAllSyllablesSoundAlike() {
        val flat = analyzeStress(summarize(speech(listOf(weak(200f), weak(200f), weak(200f)))))
        assertEquals(StressOutcome.Unclear, judgeStress(flat, marked = "ANimal"))
    }

    @Test
    fun saysWhenItHeardNothing() {
        val silent = analyzeStress(summarize(withNoise(silence(1.0, speechRate), amp = 0.0005f)))
        assertEquals(StressOutcome.NotHeard, judgeStress(silent, marked = "ANimal"))
    }

    @Test
    fun aMissingSyllableIsNotAWrongStress() {
        val one = analyzeStress(summarize(speech(listOf(strong))))
        assertEquals(StressOutcome.SyllablesMissing(heard = 1, expected = 3), judgeStress(one, marked = "ANimal"))
    }

    @Test
    fun aWordWithoutAMarkIsNotJudged() {
        assertEquals(StressOutcome.NotJudgeable, judgeStress(analyzeStress(summarize(first)), marked = "animal"))
    }

    @Test
    fun cueScalesAreData() {
        val pitchOnly = DSPThresholds.v1.copy(stress = DSPThresholds.v1.stress.copy(dbUnit = 1000f, durationWeight = 0f))
        val quietHigh = speech(listOf(SyllableSpec(0.15, 300f, 320f, 0.2f), SyllableSpec(0.15, 200f, 200f, 0.5f)))
        assertEquals(0, analyzeStress(summarize(quietHigh), thresholds = pitchOnly).stressed)
    }
}

class DTWTests {
    @Test
    fun alignsMonotonicallyFromStartToEnd() {
        val a = listOf(floatArrayOf(0f), floatArrayOf(1f), floatArrayOf(2f), floatArrayOf(3f), floatArrayOf(4f))
        val b = listOf(floatArrayOf(0f), floatArrayOf(0f), floatArrayOf(1f), floatArrayOf(2f), floatArrayOf(2f), floatArrayOf(3f), floatArrayOf(4f))
        val path = dtwPath(a, b)
        val first = assertNotNull(path.firstOrNull())
        val last = assertNotNull(path.lastOrNull())
        assertTrue(first.i == 0 && first.j == 0)
        assertTrue(last.i == 4 && last.j == 6)
        for ((previous, next) in path.zipWithNext()) {
            assertTrue(next.i >= previous.i)
            assertTrue(next.j >= previous.j)
            assertTrue(next.i - previous.i <= 1 && next.j - previous.j <= 1)
        }
    }

    @Test
    fun identicalSequencesFollowTheDiagonal() {
        val a = (0 until 20).map { floatArrayOf(it.toFloat()) }
        val path = dtwPath(a, a)
        assertEquals(20, path.size)
        assertTrue(path.all { it.i == it.j })
    }

    @Test
    fun mapsAStretchedTakeBackOntoItsSource() {
        val a = (0 until 40).map { floatArrayOf(sin(it.toFloat() / 5f)) }
        val b = (0 until 60).map { floatArrayOf(sin(it.toFloat() / 7.5f)) }
        for (step in dtwPath(a, b)) {
            assertTrue(abs(step.j.toFloat() - step.i.toFloat() * 1.5f) < 6f, "$step")
        }
    }

    @Test
    fun emptyInputGivesNoPath() {
        assertTrue(dtwPath(emptyList(), listOf(floatArrayOf(1f))).isEmpty())
    }
}

class ProsodyTests {
    // Rise on the second syllable, fall at the end: a typical statement melody.
    private val melody = listOf(
        SyllableSpec(0.16, 200f, 210f, 0.4f), SyllableSpec(0.18, 220f, 270f, 0.45f),
        SyllableSpec(0.14, 240f, 220f, 0.35f), SyllableSpec(0.2, 210f, 160f, 0.4f),
    )

    private fun scaled(pitch: Float, time: Double): List<SyllableSpec> =
        melody.map { SyllableSpec(it.seconds * time, it.f0Start * pitch, it.f0End * pitch, it.amp) }

    @Test
    fun theSameTakeGetsFullMarks() {
        val model = summarize(speech(melody))
        val result = compareProsody(model = model, take = model)
        assertTrue(result.overall > 0.95f, "$result")
        assertEquals(ProsodyOutcome.GOOD, result.outcome)
        assertTrue(result.isAccepted)
    }

    @Test
    fun acceptsTheSameMelodyInAChildVoiceABitSlower() {
        val result = compareProsody(model = summarize(speech(melody)), take = summarize(speech(scaled(1.5f, 1.15))))
        assertTrue(assertNotNull(result.shape) > 0.7f, "shape ${result.shape}")
        assertTrue(assertNotNull(result.rangeRatio) > 0.7f, "range ${result.rangeRatio}")
        assertTrue(result.durationRatio > 1.05f && result.durationRatio < 1.3f, "duration ${result.durationRatio}")
        assertTrue(result.isAccepted, "overall ${result.overall}")
    }

    @Test
    fun curvesAreReadyForDrawing() {
        val result = compareProsody(model = summarize(speech(melody)), take = summarize(speech(scaled(1.5f, 1.15))))
        assertFalse(result.curves.time.isEmpty())
        assertEquals(result.curves.model.size, result.curves.take.size)
        assertEquals(result.curves.model.size, result.curves.time.size)
        assertEquals(0f, result.curves.time.firstOrNull())
        assertTrue(result.curves.model.filterNotNull().size > 20)
        assertTrue(result.curves.take.filterNotNull().size > 20)
    }

    @Test
    fun catchesAMonotoneReading() {
        val flat = melody.map { SyllableSpec(it.seconds, 210f, 210f, it.amp) }
        val result = compareProsody(model = summarize(speech(melody)), take = summarize(speech(flat)))
        assertTrue(assertNotNull(result.intonation) < 0.4f, "intonation ${result.intonation}")
        assertEquals(ProsodyOutcome.FLAT, result.outcome)
        assertFalse(result.isAccepted)
    }

    @Test
    fun catchesAnUpsideDownMelody() {
        val inverted = melody.map { SyllableSpec(it.seconds, 420f - it.f0Start, 420f - it.f0End, it.amp) }
        val result = compareProsody(model = summarize(speech(melody)), take = summarize(speech(inverted)))
        assertTrue(assertNotNull(result.shape) < 0f, "shape ${result.shape}")
        assertFalse(result.isAccepted)
    }

    @Test
    fun checksPausePlacement() {
        val withPause = summarize(speech(melody, pauseAfter = 1, pause = 0.45))
        val same = compareProsody(model = withPause, take = withPause)
        val none = compareProsody(model = withPause, take = summarize(speech(melody)))
        assertEquals(PauseMatch(model = 1, take = 1, matched = 1), same.pauses)
        assertEquals(0, none.pauses.matched)
        assertTrue(none.rhythm < same.rhythm)
    }

    @Test
    fun checksTempo() {
        val slow = compareProsody(model = summarize(speech(melody)), take = summarize(speech(scaled(1f, 1.8), gap = 0.12)))
        assertTrue(slow.durationRatio > 1.5f, "duration ${slow.durationRatio}")
        assertTrue(slow.rhythm < 0.6f, "rhythm ${slow.rhythm}")
    }

    @Test
    fun saysWhenTheLearnerWasSilent() {
        val result = compareProsody(
            model = summarize(speech(melody)), take = summarize(withNoise(silence(1.5, speechRate), amp = 0.0005f)),
        )
        assertEquals(ProsodyOutcome.NOT_HEARD, result.outcome)
        assertFalse(result.isAccepted)
        assertTrue(result.curves.time.isEmpty())
    }

    @Test
    fun weightsAreData() {
        val rhythmOnly = DSPThresholds.v1.copy(prosody = DSPThresholds.v1.prosody.copy(intonationWeight = 0f, rhythmWeight = 1f))
        val flat = melody.map { SyllableSpec(it.seconds, 210f, 210f, it.amp) }
        val result = compareProsody(model = summarize(speech(melody)), take = summarize(speech(flat)), thresholds = rhythmOnly)
        assertTrue(result.overall > 0.8f, "overall ${result.overall}")
    }
}

class DeliveryTests {
    private val base: ClipSummary by lazy {
        summarize(speech(listOf(SyllableSpec(0.5, 200f, 240f, 0.4f), SyllableSpec(0.5, 240f, 200f, 0.4f))))
    }

    @Test
    fun estimatesPaceFromSyllablesWithoutATranscript() {
        val s = base.copy(speechRate = 4f)
        val romanian = makeDeliveryReport(s, language = DeliveryLanguage.ROMANIAN)
        val english = makeDeliveryReport(s, language = DeliveryLanguage.ENGLISH)
        assertTrue(abs(romanian.wordsPerMinute - 4f * 60f * 0.42f) < 0.01f, "wpm ${romanian.wordsPerMinute}")
        assertTrue(english.wordsPerMinute > romanian.wordsPerMinute)
    }

    @Test
    fun aTranscriptOverridesTheEstimates() {
        val report = makeDeliveryReport(base, language = DeliveryLanguage.ROMANIAN, wordsPerMinute = 140f, fillersPerMinute = 0f)
        assertEquals(140f, report.wordsPerMinute)
        assertEquals(PaceBand.GOOD, report.pace)
        assertEquals(1f, report.scores.pace)
        assertEquals(1f, report.scores.fluency)
    }

    @Test
    fun paceBandsFollowTheThresholds() {
        assertEquals(PaceBand.TOO_SLOW, makeDeliveryReport(base, language = DeliveryLanguage.ROMANIAN, wordsPerMinute = 80f).pace)
        assertEquals(PaceBand.TOO_FAST, makeDeliveryReport(base, language = DeliveryLanguage.ROMANIAN, wordsPerMinute = 200f).pace)
        val slower = DSPThresholds.v1.copy(delivery = DSPThresholds.v1.delivery.copy(minWordsPerMinute = 60f))
        assertEquals(PaceBand.GOOD, makeDeliveryReport(base, language = DeliveryLanguage.ROMANIAN, wordsPerMinute = 80f, thresholds = slower).pace)
    }

    @Test
    fun toneBandsFollowPvq() {
        var s = base.copy(pvq = 0.1f, pitch = base.pitch.copy(rangeSt = 2f))
        val flat = makeDeliveryReport(s, language = DeliveryLanguage.ROMANIAN)
        assertEquals(ToneBand.MONOTONE, flat.tone)
        assertFalse(flat.isLively)
        s = s.copy(pvq = 0.3f)
        val lively = makeDeliveryReport(s, language = DeliveryLanguage.ROMANIAN)
        assertEquals(ToneBand.LIVELY, lively.tone)
        assertTrue(lively.isLively)
        assertEquals(1f, lively.scores.tone)
        s = s.copy(pvq = 0.1f, pitch = s.pitch.copy(rangeSt = 6f))
        assertTrue(makeDeliveryReport(s, language = DeliveryLanguage.ROMANIAN).isLively)
    }

    @Test
    fun aLongPauseCostsPauseScore() {
        val s = base.copy(pauses = listOf(TimeSpan(start = 0.5, end = 4.0)))
        val report = makeDeliveryReport(s, language = DeliveryLanguage.ROMANIAN)
        assertEquals(1, report.longPauses)
        assertTrue(report.scores.pauses < 1f)
    }

    @Test
    fun fillersBeyondTwoAMinuteCostFluency() {
        assertEquals(1f, makeDeliveryReport(base, language = DeliveryLanguage.ROMANIAN, fillersPerMinute = 2f).scores.fluency)
        assertTrue(makeDeliveryReport(base, language = DeliveryLanguage.ROMANIAN, fillersPerMinute = 7f).scores.fluency < 0.5f)
    }

    @Test
    fun volumeIsScoredByItsSpread() {
        var s = base.copy(loudness = base.loudness.copy(sdDb = 5f))
        assertEquals(1f, makeDeliveryReport(s, language = DeliveryLanguage.ROMANIAN).scores.volume)
        s = s.copy(loudness = s.loudness.copy(sdDb = 1f))
        assertEquals(0.6f, makeDeliveryReport(s, language = DeliveryLanguage.ROMANIAN).scores.volume)
    }

    @Test
    fun finalContourFollowsTheSlope() {
        var s = base.copy(finalSlopeSt = 3f)
        assertEquals(FinalContour.RISING, makeDeliveryReport(s, language = DeliveryLanguage.ROMANIAN).finalContour)
        s = s.copy(finalSlopeSt = -2f)
        assertEquals(FinalContour.FALLING, makeDeliveryReport(s, language = DeliveryLanguage.ROMANIAN).finalContour)
        s = s.copy(finalSlopeSt = 1f)
        assertEquals(FinalContour.LEVEL, makeDeliveryReport(s, language = DeliveryLanguage.ROMANIAN).finalContour)
    }

    @Test
    fun totalIsAPercentage() {
        val report = makeDeliveryReport(base, language = DeliveryLanguage.ROMANIAN, wordsPerMinute = 140f, fillersPerMinute = 0f)
        assertTrue(report.scores.total in 0..100)
    }
}
