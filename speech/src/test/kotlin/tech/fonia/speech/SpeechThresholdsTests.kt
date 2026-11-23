package tech.fonia.speech

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.exp
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** FoniaSpeechTests/SpeechThresholdsTests.swift, suite "Thresholds and calibration". */
@DisplayName("Thresholds and calibration")
class SpeechThresholdsTests {
    private fun decode(json: String): SpeechThresholds = SpeechThresholds.decode(json)

    private fun score(margin: Double, target: String = "ship", rival: String = "sheep"): PairScore =
        SpeechJudge.combinePair(
            target = target,
            rival = rival,
            language = SpeechLanguage.EN,
            transcript = transcript(listOf(Said(target, 0.5, 0.9, 0.4)), logp = -1.0),
            targetScores = listOf(scored(target, -1.0)),
            rivalScores = listOf(scored(rival, -1 - margin)),
        )

    @Test
    @DisplayName("an empty calibration file keeps every default")
    fun emptyFile() {
        assertEquals(SpeechThresholds.STANDARD, decode("{}"))
    }

    @Test
    @DisplayName("keeps the web version's numbers as defaults")
    fun defaults() {
        val t = SpeechThresholds.STANDARD
        assertEquals(MarginBand(okMargin = 0.5, wrongMargin = -1.5), t.pair.band)
        assertEquals(-1.9, t.sentence.weakLogp)
        assertEquals(-0.4, t.sentence.rescueLogp)
        assertEquals(PaceBand(slowBelow = 110.0, fastAbove = 170.0), t.freeSpeech.paceEnglish)
        assertEquals(PaceBand(slowBelow = 100.0, fastAbove = 160.0), t.freeSpeech.paceRomanian)
    }

    @Test
    @DisplayName("a partial file overrides only what it names")
    fun partialFile() {
        val t = decode("""{"pair":{"pairBias":{"sheep|ship":0.4}},"freeSpeech":{"hesitationFreePer100Words":4}}""")
        assertEquals(mapOf("sheep|ship" to 0.4), t.pair.pairBias)
        assertEquals(SpeechThresholds.STANDARD.pair.band, t.pair.band)
        assertEquals(4.0, t.freeSpeech.hesitationFreePer100Words)
        assertEquals(SpeechThresholds.STANDARD.freeSpeech.minHesitationsToFlag, t.freeSpeech.minHesitationsToFlag)
        assertEquals(SpeechThresholds.STANDARD.sentence, t.sentence)
    }

    @Test
    @DisplayName("survives an encode and decode")
    fun roundTrip() {
        var t = SpeechThresholds()
        t = t.copy(pair = t.pair.copy(contrasts = t.pair.contrasts + ("think-sink" to MarginBand(okMargin = 0.2, wrongMargin = -2.0))))
        t = t.copy(pair = t.pair.copy(pairBias = t.pair.pairBias + ("sink|think" to -0.3)))
        t = t.copy(silence = t.silence.copy(noSpeechSoft = 0.7))
        val back = SpeechThresholds.decode(t.encode())
        assertEquals(t, back)
    }

    @Test
    @DisplayName("loads a calibration file from disk")
    fun loadsFile() {
        val folder = SpeechPaths.temporaryFolder()
        try {
            val url = File(folder, "calibration.json")
            url.writeText("""{"pair":{"contrasts":{"think-sink":{"okMargin":0.1,"wrongMargin":-3}}}}""")
            val t = SpeechThresholds.contentsOf(url)
            assertEquals(MarginBand(okMargin = 0.1, wrongMargin = -3.0), t.pair.band(forContrast = "think-sink"))
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    @DisplayName("the checked-in calibration file loads")
    fun checkedInFile() {
        SpeechThresholds.contentsOf(File(SpeechPaths.evalFolder, "calibration.json"))
    }

    @Test
    @DisplayName("selects a band per contrast, and the default for an unknown one")
    fun perContrast() {
        var t = SpeechThresholds()
        t = t.copy(pair = t.pair.copy(contrasts = t.pair.contrasts + ("think-sink" to MarginBand(okMargin = 2.0, wrongMargin = -4.0))))
        assertTrue(SpeechJudge.judgePair(score(margin = 1.0), contrast = null, thresholds = t).wordHeard)
        assertTrue(SpeechJudge.judgePair(score(margin = 1.0), contrast = "wine-vine", thresholds = t).wordHeard)
        assertEquals(PairOutcome.Unclear, SpeechJudge.judgePair(score(margin = 1.0), contrast = "think-sink", thresholds = t))
        assertEquals(PairOutcome.Unclear, SpeechJudge.judgePair(score(margin = -3.0), contrast = "think-sink", thresholds = t))
        if (SpeechJudge.judgePair(score(margin = -3.0), contrast = null, thresholds = t) !is PairOutcome.Rival) {
            fail("the default band should call this the rival")
        }
    }

    @Test
    @DisplayName("corrects a pair's bias in both directions")
    fun biasBothWays() {
        var t = SpeechThresholds()
        // The model leans 1 nat to "sheep" whatever was said.
        t = t.copy(pair = t.pair.copy(pairBias = t.pair.pairBias + (PairThresholds.biasKey("sheep", "ship") to 1.0)))
        assertEquals(1.0, t.pair.bias(target = "sheep", rival = "ship"))
        assertEquals(-1.0, t.pair.bias(target = "ship", rival = "sheep"))
        // A raw lean of +0.8 towards "sheep" is really -0.2: inside the unclear band.
        assertEquals(PairOutcome.Unclear, SpeechJudge.judgePair(score(margin = 0.8, target = "sheep", rival = "ship"), thresholds = t))
        // The same audio asked the other way round has a raw lean of -0.8, also -0.2 towards "sheep".
        assertEquals(PairOutcome.Unclear, SpeechJudge.judgePair(score(margin = -0.8), thresholds = t))
    }

    @Test
    @DisplayName("reports pTarget after the bias correction")
    fun correctedProbability() {
        var t = SpeechThresholds()
        t = t.copy(pair = t.pair.copy(pairBias = t.pair.pairBias + (PairThresholds.biasKey("sheep", "ship") to 1.0)))
        val outcome = SpeechJudge.judgePair(score(margin = 2.0, target = "sheep", rival = "ship"), thresholds = t)
        if (outcome !is PairOutcome.Target) fail("expected target")
        assertTrue(abs(outcome.pTarget - 1 / (1 + exp(-1.0))) < 1e-9)
    }

    @Test
    @DisplayName("has a bias of zero without an entry")
    fun noEntry() {
        assertEquals(0.0, PairThresholds().bias(target = "think", rival = "sink"))
    }

    @Test
    @DisplayName("tells silence from a short real word")
    fun silence() {
        val s = SilenceThresholds()
        assertTrue(s.isSilent(noSpeechProb = 0.95, meanLogp = -0.1))
        assertTrue(s.isSilent(noSpeechProb = 0.7, meanLogp = -1.5))
        assertTrue(!s.isSilent(noSpeechProb = 0.64, meanLogp = -0.4))
        assertTrue(!s.isSilent(noSpeechProb = 0.1, meanLogp = -3.0))
    }
}
