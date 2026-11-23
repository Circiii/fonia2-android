package tech.fonia.features.speaking

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.dsp.TimeSpan
import tech.fonia.speech.SpeechLanguage
import tech.fonia.speech.SpeechResampler

/**
 * Reading accuracy against a known text (V6): the transcript says where each sentence is, sentences with any word
 * written differently get teacher-forced, and a recogniser's slip is told apart from a reading slip. Then the pauses
 * at punctuation. Synthetic speech with a scripted recogniser first, then a recorded clip with Whisper's real
 * transcript.
 */
class ReadAloudTests {
    private val text = MarkedText.parse(
        "Astăzi vorbim despre somn. Elevii dorm prea puțin, mai ales iarna. Somnul pune ordine în tot ce am învățat.",
    )

    private fun index(word: String) = text.words.indexOfFirst { it.bare == word }

    @Test
    fun aSentenceReadAsWrittenNeedsNoSecondLook() {
        val spoken = SyntheticSpeech.say(text.words.map { it.shown }, pauseAfter = SyntheticSpeech.readerPauses(text.words))
        val recogniser = ScriptedTranscriber().also { it.add(spoken) }
        val check = runBlocking { ReadAloud.check(text, SpeechResampler.resample(spoken.samples, spoken.rate), recogniser, SpeechLanguage.RO) }
        assertEquals(listOf("transcribe"), recogniser.calls)
        assertEquals(text.words.size, check.readAsWritten)
        assertEquals(1f, check.accuracy)
        assertEquals(0, check.scoredSentences)
        assertEquals(3, check.sentences)
        assertTrue(check.toListen.isEmpty())
        // Each word keeps the recogniser's time for it.
        assertEquals(spoken.words.map { TimeSpan(it.start, it.end) }, check.spans)
    }

    @Test
    fun aRecogniserSlipIsReadButAReadingSlipIsNot() {
        val slip = index("Astăzi")
        val misread = index("Elevii")
        val skipped = index("ales")
        val spoken = SyntheticSpeech.say(
            text.words.map { it.shown },
            pauseAfter = SyntheticSpeech.readerPauses(text.words),
            heard = { i, word ->
                when (i) {
                    slip -> "Astați"
                    misread -> "Elevul"
                    else -> word
                }
            },
            dropped = setOf(skipped),
        )
        val recogniser = ScriptedTranscriber().also { it.add(spoken, readWrong = setOf("elevii", "ales")) }
        val check = runBlocking { ReadAloud.check(text, SpeechResampler.resample(spoken.samples, spoken.rate), recogniser, SpeechLanguage.RO) }
        // The first two sentences each had a word written differently, so each was scored on its own; not the third.
        assertEquals(
            listOf("transcribe", "score: Astăzi vorbim despre somn.", "score: Elevii dorm prea puțin, mai ales iarna."),
            recogniser.calls,
        )
        assertEquals(2, check.scoredSentences)
        assertEquals(ReadStatus.UNSURE, check.words[slip].status)
        assertEquals("Astați", check.words[slip].heard)
        assertEquals(ReadStatus.DIFFERENT, check.words[misread].status)
        assertEquals("Elevul", check.words[misread].heard)
        assertEquals(ReadStatus.DIFFERENT, check.words[skipped].status)
        assertNull(check.spans[skipped])
        assertEquals(text.words.size - 2, check.readAsWritten)
        assertEquals((text.words.size - 2).toFloat() / text.words.size, check.accuracy, 1e-6f)
        assertEquals(listOf(slip, misread, skipped), check.toListen.map { it.index })
        // The forced times are the slice's, put back on the take's clock.
        val astazi = check.words[slip].span!!
        assertEquals(spoken.words[slip].start, astazi.start, 0.002)
        assertEquals(spoken.words[slip].end, astazi.end, 0.002)
    }

    @Test
    fun withoutTeacherForcingTheTranscriptDecides() {
        // The recorded clip: an ElevenLabs voice reading its script, and what Whisper wrote for it on the emulator
        // ("Astați", "adolescente", "ce", "doar"). Teacher forcing needs the model, which the JVM does not have, so every
        // word Whisper wrote differently counts as read differently: the floor of the accuracy, 25 of 29.
        val take = Recorded.take("ro-speech")
        val recorded = MarkedText.parse(take.script)
        val recogniser = ScriptedTranscriber().also { it.add(take.samples, take.words, forcedScores = false) }
        val check = runBlocking { ReadAloud.check(recorded, take.samples, recogniser, SpeechLanguage.RO) }
        assertEquals(0, check.scoredSentences)
        assertEquals(29, check.words.size)
        assertEquals(25, check.readAsWritten)
        val differences = check.words.filter { it.status != ReadStatus.READ }.map { recorded.words[it.index].bare to it.heard }
        assertEquals(listOf("Astăzi" to "Astați", "adolescenții" to "adolescente", "cei" to "ce", "dorm" to "doar"), differences)
        // "nouă" and "șapte" came back as digits, which count as the same words.
        assertEquals(ReadStatus.READ, check.words[recorded.words.indexOfFirst { it.bare == "nouă" }].status)
        assertEquals(ReadStatus.READ, check.words.last().status)
    }

    @Test
    fun pausesAtPunctuationAndElsewhere() {
        // Every mark paused at, except the comma after "puțin"; one stop in the middle of the last sentence.
        val puțin = index("puțin")
        val tot = index("tot")
        val spoken = SyntheticSpeech.say(
            text.words.map { it.shown },
            pauseAfter = SyntheticSpeech.readerPauses(text.words, skip = setOf(puțin), extra = mapOf(tot to 0.45)),
        )
        val summary = tech.fonia.dsp.analyzeClip(spoken.samples, spoken.rate, tech.fonia.dsp.VoiceGroup.FEMALE, null)
        val pauses = PauseCheck.measure(text.words, spoken.words.map { TimeSpan(it.start, it.end) }, summary.pauses)
        assertEquals(3, pauses.marks)
        assertEquals(2, pauses.pausedMarks)
        assertEquals(2f / 3f, pauses.markShare!!, 1e-6f)
        assertEquals(1, pauses.otherPauses)
        assertTrue(pauses.minutes > 0.05)
        assertEquals(1 / pauses.minutes, pauses.otherPausesPerMinute!!, 1e-9)
        // A word not found leaves both its boundaries out.
        val withHole = PauseCheck.measure(text.words, spoken.words.mapIndexed { i, w -> if (i == puțin) null else TimeSpan(w.start, w.end) }, summary.pauses)
        assertEquals(2, withHole.marks)
        assertEquals(text.words.size - 3, withHole.boundaries.size)
    }

    @Test
    fun aSilenceBetweenTwoWordsCountsEvenWhenTheirTimesTouch() {
        // The recogniser put the boundary in the middle of the silence, so its two words touch; the clip analysis
        // still sees the silence around the boundary.
        val words = MarkedText.parse("Prima propoziție. A doua.").words
        val spans = listOf(TimeSpan(0.0, 0.4), TimeSpan(0.4, 1.0), TimeSpan(1.0, 1.4), TimeSpan(1.4, 1.8))
        val pauses = PauseCheck.measure(words, spans, listOf(TimeSpan(0.88, 1.15)))
        assertEquals(1, pauses.marks)
        assertEquals(1, pauses.pausedMarks)
        assertEquals(0.27, pauses.boundaries[1].seconds, 1e-9)
        assertEquals(0.0, pauses.boundaries[0].seconds, 1e-9)
        // A silence that reaches into the next word's sound is not this boundary's.
        assertEquals(0, PauseCheck.measure(words, spans, listOf(TimeSpan(0.88, 1.5))).pausedMarks)
    }

    @Test
    fun theRecordedReadingPausesWhereWhisperSawGaps() {
        // The ElevenLabs voice stops after "tuturor." (0.28 s between the words) and barely after "somn." (0.20 s);
        // it runs on through the commas. The clip analysis finds no silence long enough of its own.
        val take = Recorded.take("ro-speech")
        val recorded = MarkedText.parse(take.script)
        val heard = ReadAloud.align(recorded.words, tech.fonia.speech.Transcript(take.script, take.words, 0.0, 0.0), SpeechLanguage.RO)
        val pauses = PauseCheck.measure(recorded.words, heard.map { it?.span }, take.summary.pauses)
        assertTrue(take.summary.pauses.isEmpty())
        assertEquals(5, pauses.marks)
        assertEquals(1, pauses.pausedMarks)
        assertEquals(0.28, pauses.boundaries.first { it.after == 2 }.seconds, 1e-6)
        assertEquals(0, pauses.otherPauses)
    }
}
