package tech.fonia.speaking

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tech.fonia.live.Clip
import tech.fonia.speech.StubTranscriber
import tech.fonia.speech.Transcript
import tech.fonia.speech.TranscriptWord
import tech.fonia.speaking.SyntheticVoice.Kind
import tech.fonia.speaking.SyntheticVoice.Part
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProsodicTranscriptTest {
    private val voice = SyntheticVoice.render(
        listOf(Part(Kind.PAUSE, 0.3), Part(Kind.SPEECH, 1.4), Part(Kind.PAUSE, 0.4), Part(Kind.HESITATION, 0.5), Part(Kind.PAUSE, 0.3), Part(Kind.SPEECH, 2.2), Part(Kind.PAUSE, 0.3)),
    )

    @Test
    fun `without a transcript the take becomes chunks and a hesitation`() {
        val a = SpeakingAnalyzer().analyze(voice.samples, voice.sampleRate)
        val t = a.transcript
        assertTrue(!t.hasTranscript)
        assertTrue(t.words.size >= 3, "${t.words}")
        assertTrue(t.words.any { it.kind == SpanKind.HESITATION && it.text in setOf("ăăă", "îîî", "mmm") }, "${t.words}")
        assertTrue(t.words.filter { it.kind == null }.all { it.text.all { c -> c == '•' } }, "${t.words}")
        assertTrue(t.words.all { it.loudness in 0f..1f })
        assertTrue(t.words.zipWithNext().all { (x, y) -> x.start <= y.start })
        assertNull(t.summary.wordsPerMinute)
        assertTrue(t.summary.syllablesPerSecond > 0)
        assertTrue(t.summary.pauseCount >= 2, "${t.summary} ${t.pauses}")
    }

    @Test
    fun `with a transcript every recognised word is there, pauses before words measured`() {
        val sp1 = 0.3
        val sp2 = 0.3 + 1.4 + 0.4 + 0.5 + 0.3
        val words = listOf(
            TranscriptWord("Bună", sp1, sp1 + 0.6, 0.9), TranscriptWord("ziua.", sp1 + 0.7, sp1 + 1.4, 0.9),
            TranscriptWord("Astăzi", sp2, sp2 + 0.7, 0.9), TranscriptWord("vorbim", sp2 + 0.75, sp2 + 1.4, 0.9), TranscriptWord("puțin.", sp2 + 1.45, sp2 + 2.2, 0.9),
        )
        val a = SpeakingAnalyzer().analyze(voice.samples, voice.sampleRate, Transcript("", words, 0.0, 0.0))
        val t = a.transcript
        assertTrue(t.hasTranscript)
        assertEquals(listOf("Bună", "ziua.", "Astăzi", "vorbim", "puțin."), t.words.filter { it.fromTranscript }.map { it.text })
        // The hesitation the recogniser left out shows up between the words, from the sound.
        val hes = t.words.single { !it.fromTranscript }
        assertEquals(SpanKind.HESITATION, hes.kind)
        assertTrue(t.words.indexOf(hes) == 2)
        assertNotNull(t.summary.wordsPerMinute)
        assertEquals(5, t.summary.words)
        val astazi = t.words.first { it.text == "Astăzi" }
        assertTrue(astazi.pauseBefore > 0.2, "pause before: ${astazi.pauseBefore}")
        assertEquals(2, t.wordAt(hes.start + 0.01))
        assertEquals(-1, t.wordAt(0.0))
        assertEquals("Bună ziua. Astăzi vorbim puțin.", t.plainText)
    }

    @Test
    fun `pipeline falls back to the sound when the recogniser hears nothing`() = runTest {
        val clip = Clip(voice.samples, voice.sampleRate)
        val pipeline = SpeakingPipeline(transcriber = StubTranscriber())
        val a = pipeline.analyze(clip)
        assertTrue(!a.usedTranscript)
        assertTrue(a.spans.any { it.kind == SpanKind.HESITATION })
        val clean = pipeline.clean(clip, a)
        assertTrue(clean.samples.size < voice.samples.size)
    }

    @Test
    fun `fake audio records the canned take and plays to the end`() = runTest {
        val clip = Clip(voice.samples, voice.sampleRate)
        val audio = FakeSpeakingAudio(clip, realtime = false)
        var levels = 0
        val got = audio.record(60.0) { _, _ -> levels++ }
        assertEquals(voice.samples.size, got.samples.size)
        assertTrue(levels > 100)
        assertTrue(audio.play(got.samples, got.sampleRate))
        assertTrue(audio.playbackSeconds().isNaN())
    }
}
