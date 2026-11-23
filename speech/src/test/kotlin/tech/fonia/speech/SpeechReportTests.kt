package tech.fonia.speech

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** FoniaSpeechTests/SpeechReportTests.swift, suite "Free speech". */
@DisplayName("Free speech")
class SpeechReportTests {
    @Test
    @DisplayName("finds fillers, but not the verb like")
    fun englishFillers() {
        val report = SpeechJudge.speechReport(talk("Um, I like pizza. So, basically, it is, like, the best, you know, food."), language = SpeechLanguage.EN, lexicon = TestLexicon.EN)
        assertEquals(mapOf("um" to 1, "so" to 1, "basically" to 1, "like" to 1, "you know" to 1), report.fillerCounts)
        assertTrue(report.fillers.all { it.source == FillerHit.Source.TRANSCRIPT })
    }

    @Test
    @DisplayName("handles Romanian fillers, multi-word ones first")
    fun romanianFillers() {
        val report = SpeechJudge.speechReport(talk("Deci, practic, era de genul foarte frumos. Ăăă, mă rog, ne-a plăcut."), language = SpeechLanguage.RO, lexicon = TestLexicon.RO)
        assertEquals(mapOf("deci" to 1, "practic" to 1, "de genul" to 1, "ăă" to 1, "mă rog" to 1), report.fillerCounts)
    }

    @Test
    @DisplayName("adds hesitations the transcript left out, counts repeats and unclear words")
    fun hesitationsRepeatsUnclear() {
        val spoken = talk("I I want to to talk about about sleep today.")
        val t = spoken.copy(words = spoken.words.mapIndexed { i, word -> if (i == 9) word.copy(prob = 0.2) else word })
        val report = SpeechJudge.speechReport(t, language = SpeechLanguage.EN, lexicon = TestLexicon.EN, hesitations = listOf(TimeSpan(start = 0.1, end = 0.45), TimeSpan(start = 0.9, end = 1.1)))
        assertEquals(listOf("i", "to", "about"), report.repeats.map { it.words })
        assertEquals(listOf("today"), report.unclear.map { it.word })
        // The second hesitation overlaps a word, so only the first one counts.
        assertEquals(listOf(FillerHit(word = "um", start = 0.1, end = 0.45, kind = FillerHit.Kind.HESITATION, source = FillerHit.Source.ACOUSTIC)), report.fillers)
    }

    @Test
    @DisplayName("measures pace over speaking time, without long pauses")
    fun pace() {
        val report = SpeechJudge.speechReport(talk("one two three four five six seven eight || nine ten eleven twelve"), language = SpeechLanguage.EN, lexicon = TestLexicon.EN)
        assertEquals(1, report.pauses.size)
        // 12 words in about 4.6 s of speech, whatever the pause.
        assertTrue(report.wordsPerMinute > 140)
        assertTrue(report.wordsPerMinute < 165)
        assertEquals(SpeechReport.Pace.GOOD, report.pace)
    }

    @Test
    @DisplayName("flags a fast take")
    fun fast() {
        val report = SpeechJudge.speechReport(talk("a b c d e f g h i j k l m n o p", step = 0.25), language = SpeechLanguage.EN, lexicon = TestLexicon.EN)
        assertEquals(SpeechReport.Pace.FAST, report.pace)
        assertEquals(SpeechReport.Focus.Fast, report.focus)
        assertEquals(0.8, report.score)
    }

    @Test
    @DisplayName("does not call 170 wpm in English wrong")
    fun nearTheTopOfTheBand() {
        // 16 words over 16 steps with the last word short: 170 wpm is a step of about 0.35 s.
        val report = SpeechJudge.speechReport(talk("a b c d e f g h i j k l m n o p", step = 0.36), language = SpeechLanguage.EN, lexicon = TestLexicon.EN)
        assertTrue(report.wordsPerMinute > 160)
        assertEquals(SpeechReport.Pace.GOOD, report.pace)
    }

    @Test
    @DisplayName("does not judge the pace of a short take")
    fun tooShortForPace() {
        assertEquals(SpeechReport.Pace.UNKNOWN, SpeechJudge.speechReport(talk("one two three"), language = SpeechLanguage.EN, lexicon = TestLexicon.EN).pace)
    }

    @Test
    @DisplayName("uses the Romanian band for Romanian")
    fun romanianPace() {
        // About 165 wpm: past the Romanian band, inside the English one.
        val t = talk("a b c d e f g h i j k l m n o p", step = 0.36)
        assertEquals(SpeechReport.Pace.FAST, SpeechJudge.speechReport(t, language = SpeechLanguage.RO, lexicon = TestLexicon.RO).pace)
        assertEquals(SpeechReport.Pace.GOOD, SpeechJudge.speechReport(t, language = SpeechLanguage.EN, lexicon = TestLexicon.EN).pace)
    }

    @Test
    @DisplayName("gives one focus when hesitations run far above normal")
    fun manyHesitations() {
        val report = SpeechJudge.speechReport(talk("Um so um like, I um think, like, um it is um good, like, um yes."), language = SpeechLanguage.EN, lexicon = TestLexicon.EN)
        assertEquals(SpeechReport.Focus.ManyHesitations(count = 6), report.focus)
        assertTrue(report.score < 1)
    }

    @Test
    @DisplayName("says it heard nothing")
    fun notHeard() {
        assertEquals(SpeechReport.Focus.NotHeard, SpeechJudge.speechReport(transcript(emptyList()), language = SpeechLanguage.EN, lexicon = TestLexicon.EN).focus)
    }

    /** Distinct words, since a repeated one would count as a repetition. */
    private fun words(count: Int, insert: Map<Int, String> = emptyMap()): String =
        (0 until count).joinToString(" ") { i -> insert[i]?.let { "$it w$i" } ?: "w$i" }

    @Test
    @DisplayName("does not penalise hesitations at a normal conversational rate")
    fun normalRate() {
        // 1.7 per 100 words is the published median; 2 in 110 words sits right on it.
        val report = SpeechJudge.speechReport(talk(words(110, mapOf(20 to "um", 70 to "uh")), step = 0.45), language = SpeechLanguage.EN, lexicon = TestLexicon.EN)
        assertEquals(2, report.fillers.size)
        assertTrue(report.hesitationsPer100Words < 3)
        assertEquals(1.0, report.score)
        assertEquals(SpeechReport.Focus.Clean, report.focus)
    }

    @Test
    @DisplayName("does not penalise a rate up to three per 100 words")
    fun atTheLimit() {
        val report = SpeechJudge.speechReport(talk(words(100, mapOf(10 to "um", 50 to "um", 90 to "um")), step = 0.45), language = SpeechLanguage.EN, lexicon = TestLexicon.EN)
        assertTrue(report.hesitationsPer100Words <= 3)
        assertEquals(1.0, report.score)
        assertEquals(SpeechReport.Focus.Clean, report.focus)
    }

    @Test
    @DisplayName("does not read one um in a short take as a high rate")
    fun shortTake() {
        val report = SpeechJudge.speechReport(talk("Um, today I want to talk about sleep.", step = 0.45), language = SpeechLanguage.EN, lexicon = TestLexicon.EN)
        assertTrue(report.hesitationsPer100Words > 3)
        assertEquals(1.0, report.score)
        assertEquals(SpeechReport.Focus.Clean, report.focus)
    }

    @Test
    @DisplayName("reports discourse markers as information and never penalises them")
    fun discourseMarkers() {
        val report = SpeechJudge.speechReport(
            talk("So, basically, it is, like, good. So, well, it works, like, fine. So, okay, it is, like, nice.", step = 0.45),
            language = SpeechLanguage.EN,
            lexicon = TestLexicon.EN,
        )
        assertTrue(report.fillers.size > 6)
        assertTrue(report.fillers.all { it.kind == FillerHit.Kind.DISCOURSE_MARKER })
        assertEquals(1.0, report.score)
        assertEquals(SpeechReport.Focus.Clean, report.focus)
    }

    @Test
    @DisplayName("counts an acoustic hesitation towards the rate")
    fun acousticCounts() {
        val spans = (0 until 4).map { TimeSpan(start = 100 + it.toDouble(), end = 100.5 + it.toDouble()) }
        val report = SpeechJudge.speechReport(talk(words(20), step = 0.45), language = SpeechLanguage.EN, lexicon = TestLexicon.EN, hesitations = spans)
        assertEquals(SpeechReport.Focus.ManyHesitations(count = 4), report.focus)
    }
}
