package tech.fonia.speech

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** FoniaSpeechTests/SentenceJudgeTests.swift, suite "Sentences". */
@DisplayName("Sentences")
class SentenceJudgeTests {
    private val target = "I think three things."

    private fun judge(heard: Transcript, forced: TextScore): SentenceOutcome =
        SpeechJudge.judgeSentence(SentenceScore(target = target, language = SpeechLanguage.EN, transcript = heard, score = forced))

    @Test
    @DisplayName("passes when every word is heard")
    fun allHeard() {
        val outcome = judge(
            transcript(listOf(Said("I", 0.5, 0.6), Said("think", 0.6, 0.8), Said("three", 0.8, 1.1), Said("things.", 1.1, 1.7))),
            scored(target, -0.7),
        )
        assertEquals(SentenceOutcome.Result.ALL_HEARD, outcome.result)
        assertTrue(outcome.wordsHeard)
        assertEquals(listOf(WordStatus.GOOD, WordStatus.GOOD, WordStatus.GOOD, WordStatus.GOOD), outcome.words.map { it.status })
        assertEquals(listOf("I", "think", "three", "things"), outcome.words.map { it.word })
    }

    @Test
    @DisplayName("flags TH to S and T substitutions with what was heard, and keeps times for replay")
    fun substitutions() {
        val outcome = judge(
            transcript(listOf(Said("I", 0.5, 0.6), Said("sink", 0.6, 0.9, 0.4), Said("tree", 0.9, 1.2, 0.4), Said("sings.", 1.2, 1.9, 0.6))),
            scored(target, -5.0, perWord = listOf(-0.3, -1.3, -1.9, -0.9)),
        )
        assertFalse(outcome.wordsHeard)
        assertEquals(SentenceOutcome.Result.SEVERAL_MISSED, outcome.result)
        assertEquals(listOf("I", "think", "three", "things"), outcome.words.map { it.word })
        assertEquals(listOf(WordStatus.GOOD, WordStatus.MISSED, WordStatus.MISSED, WordStatus.MISSED), outcome.words.map { it.status })
        assertEquals(listOf(null, "sink", "tree", "sings"), outcome.words.map { it.heard })
        assertTrue(outcome.words[1].end > outcome.words[1].start)
    }

    @Test
    @DisplayName("accepts digits for number words, marks inflection slips as weak and lists extra words")
    fun digitsSlipsExtras() {
        val outcome = judge(
            transcript(listOf(Said("I", 0.5, 0.6), Said("think", 0.6, 0.8), Said("3", 0.8, 1.1), Said("thing", 1.1, 1.5), Said("now.", 1.5, 1.8))),
            scored(target, -2.0, perWord = listOf(-0.1, -0.1, -0.2, -1.2)),
        )
        // Dictionary(uniqueKeysWithValues:) traps on a repeated key; associate would hide one.
        assertEquals(outcome.words.size, outcome.words.map { it.word }.toSet().size)
        val statuses = outcome.words.associate { it.word to it.status }
        assertEquals(mapOf("I" to WordStatus.GOOD, "think" to WordStatus.GOOD, "three" to WordStatus.GOOD, "things" to WordStatus.WEAK, "now" to WordStatus.EXTRA), statuses)
        assertNull(outcome.words.first { it.word == "now" }.index)
        assertEquals(SentenceOutcome.Result.SOME_WEAK, outcome.result)
        assertTrue(outcome.wordsHeard)
    }

    @Test
    @DisplayName("reports a missing word by name")
    fun missingWord() {
        val outcome = judge(
            transcript(listOf(Said("I", 0.5, 0.6), Said("think", 0.6, 0.8), Said("things.", 1.1, 1.7))),
            scored(target, -3.0, perWord = listOf(-0.1, -0.1, -3.0, -0.1)),
        )
        assertEquals(SentenceOutcome.Result.ONE_MISSED, outcome.result)
        assertEquals(listOf("three"), outcome.missed.map { it.word })
        assertNull(outcome.missed.firstOrNull()?.heard)
    }

    @Test
    @DisplayName("says it heard nothing on an empty transcript")
    fun nothing() {
        val outcome = judge(transcript(emptyList()), scored(target, -20.0))
        assertEquals(SentenceOutcome.Result.NOT_HEARD, outcome.result)
        assertTrue(outcome.words.all { it.status == WordStatus.MISSED })
        assertTrue(outcome.heard.isEmpty())
    }

    @Test
    @DisplayName("rescues a homophone the model clearly expected")
    fun homophone() {
        val outcome = SpeechJudge.judgeSentence(
            SentenceScore(
                target = "I see the sea",
                language = SpeechLanguage.EN,
                transcript = transcript(listOf(Said("I", 0.0, 0.1), Said("see", 0.1, 0.2), Said("the", 0.2, 0.3), Said("see", 0.3, 0.4))),
                score = scored("I see the sea", -1.0, perWord = listOf(-0.1, -0.1, -0.1, -0.3)),
            ),
        )
        assertEquals(WordStatus.WEAK, outcome.words[3].status)
        assertEquals("see", outcome.words[3].heard)
    }

    @Test
    @DisplayName("asks again when most words were not there at all")
    fun differentSentence() {
        val outcome = SpeechJudge.judgeSentence(
            SentenceScore(
                target = "The quick brown fox jumps",
                language = SpeechLanguage.EN,
                transcript = transcript(listOf(Said("The", 0.0, 0.1))),
                score = scored("The quick brown fox jumps", -30.0, perWord = listOf(-0.1, -9.0, -9.0, -9.0, -9.0)),
            ),
        )
        assertEquals(SentenceOutcome.Result.UNCLEAR, outcome.result)
        assertFalse(outcome.wordsHeard)
    }

    @Test
    @DisplayName("reads its limits from the thresholds")
    fun thresholdsApply() {
        val standard = SpeechThresholds()
        val strict = standard.copy(sentence = standard.sentence.copy(weakLogp = -0.05))
        val outcome = SpeechJudge.judgeSentence(
            SentenceScore(
                target = target,
                language = SpeechLanguage.EN,
                transcript = transcript(listOf(Said("I", 0.5, 0.6), Said("think", 0.6, 0.8), Said("three", 0.8, 1.1), Said("things.", 1.1, 1.7))),
                score = scored(target, -0.7),
            ),
            thresholds = strict,
        )
        assertEquals(SentenceOutcome.Result.SOME_WEAK, outcome.result)
    }
}
