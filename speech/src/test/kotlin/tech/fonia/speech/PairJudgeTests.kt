package tech.fonia.speech

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.ln
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/** FoniaSpeechTests/PairJudgeTests.swift, suite "Minimal pairs". */
@DisplayName("Minimal pairs")
class PairJudgeTests {
    private fun heard(text: String, logp: Double = -1.2): Transcript = transcript(listOf(Said(text, 0.5, 0.9, 0.4)), logp = logp)

    private fun pair(
        target: String,
        rival: String,
        targetLogps: List<Double>,
        rivalLogps: List<Double>,
        heard: Transcript? = null,
        language: SpeechLanguage = SpeechLanguage.EN,
    ): PairScore = SpeechJudge.combinePair(
        target = target,
        rival = rival,
        language = language,
        transcript = heard ?: heard(target),
        targetScores = targetLogps.map { scored(target, it) },
        rivalScores = rivalLogps.map { scored(rival, it) },
    )

    @Test
    @DisplayName("passes a clear target")
    fun clearTarget() {
        val outcome = SpeechJudge.judgePair(pair("sheep", "ship", listOf(-1.1, -2.6, -2.5), listOf(-5.3, -6.3, -6.9)))
        if (outcome !is PairOutcome.Target) fail("expected target, got $outcome")
        assertTrue(outcome.pTarget > 0.95)
        assertTrue(outcome.wordHeard)
    }

    @Test
    @DisplayName("names the rival when it was said instead")
    fun clearRival() {
        val outcome = SpeechJudge.judgePair(pair("sheep", "ship", listOf(-5.3, -6.3, -6.9), listOf(-1.1, -2.6, -2.5), heard = heard("ship")))
        if (outcome !is PairOutcome.Rival) fail("expected rival, got $outcome")
        assertFalse(outcome.wordHeard)
    }

    @Test
    @DisplayName("tolerates an accent leaning towards the target")
    fun accent() {
        // A Romanian "ee" halfway between the two vowels, but closer to the target.
        assertTrue(SpeechJudge.judgePair(pair("sheep", "ship", listOf(-3.0), listOf(-3.6))).wordHeard)
    }

    @Test
    @DisplayName("keeps a coin toss in the explicit unclear band")
    fun coinToss() {
        assertEquals(PairOutcome.Unclear, SpeechJudge.judgePair(pair("think", "sink", listOf(-4.5), listOf(-4.3), heard = heard("Sync"))))
    }

    @Test
    @DisplayName("says when it heard nothing")
    fun nothing() {
        assertEquals(PairOutcome.NotHeard, SpeechJudge.judgePair(pair("ship", "sheep", listOf(-9.0), listOf(-10.0), heard = transcript(emptyList(), logp = -2.0))))
    }

    @Test
    @DisplayName("reports a different word without its full stop")
    fun differentWord() {
        val outcome = SpeechJudge.judgePair(pair("ship", "sheep", listOf(-14.0), listOf(-15.0), heard = heard("Wine.", logp = -1.5)))
        assertEquals(PairOutcome.OtherWord(heard = "Wine"), outcome)
        assertFalse(outcome.wordHeard)
    }

    @Test
    @DisplayName("leaves stress pairs to the adult or the stress detector")
    fun stressPair() {
        val score = SpeechJudge.combinePair(target = "ANimal", rival = "aniMAL", language = SpeechLanguage.EN, transcript = heard("Animal"), targetScores = emptyList(), rivalScores = emptyList())
        assertTrue(score.same)
        assertEquals(0.5, score.pTarget)
        assertEquals(PairOutcome.SameText, SpeechJudge.judgePair(score))
    }

    @Test
    @DisplayName("sums spellings in log space")
    fun sumsSpellings() {
        val score = pair("sheep", "ship", listOf(ln(0.5), ln(0.25)), listOf(ln(0.25)))
        assertTrue(abs(score.targetLogp - ln(0.75)) < 1e-9)
        assertTrue(abs(score.margin - ln(3.0)) < 1e-9)
        assertTrue(abs(score.pTarget - 0.75) < 1e-9)
    }

    @Test
    @DisplayName("gives each outcome the web version's score")
    fun scores() {
        assertEquals(0.0, PairOutcome.NotHeard.score)
        assertEquals(0.75, PairOutcome.SameText.score)
        assertEquals(0.2, PairOutcome.OtherWord(heard = "x").score)
        assertEquals(0.8, PairOutcome.Target(pTarget = 0.6).score)
        assertEquals(0.95, PairOutcome.Target(pTarget = 0.95).score)
        assertEquals(0.4, PairOutcome.Rival(pTarget = 0.45).score)
        assertEquals(0.1, PairOutcome.Rival(pTarget = 0.1).score)
        assertEquals(0.5, PairOutcome.Unclear.score)
    }

    @Test
    @DisplayName("stub transcriber feeds the judge without a model")
    fun stub() {
        runSuspend {
            val stub = StubTranscriber(transcript = heard("sheep"), margin = 3.0)
            val score = stub.scorePair(samples16k = FloatArray(0), target = "sheep", rival = "ship", language = SpeechLanguage.EN)
            assertTrue(SpeechJudge.judgePair(score).wordHeard)
            val same = stub.scorePair(samples16k = FloatArray(0), target = "ANimal", rival = "aniMAL", language = SpeechLanguage.EN)
            assertEquals(PairOutcome.SameText, SpeechJudge.judgePair(same))
            val sentence = stub.scoreSentence(samples16k = FloatArray(0), target = "sheep", language = SpeechLanguage.EN)
            assertEquals(SentenceOutcome.Result.ALL_HEARD, SpeechJudge.judgeSentence(sentence).result)
        }
    }
}
