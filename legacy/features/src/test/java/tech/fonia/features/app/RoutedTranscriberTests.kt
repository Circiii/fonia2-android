package tech.fonia.features.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import tech.fonia.speech.SpeechLanguage
import tech.fonia.speech.StubTranscriber
import tech.fonia.speech.Transcript
import tech.fonia.speech.TranscriptWord

class RoutedTranscriberTests {
    private fun said(text: String) = Transcript(
        text = text,
        words = text.split(' ').mapIndexed { i, w -> TranscriptWord(w, i * 0.5, i * 0.5 + 0.4, 0.9) },
        noSpeechProb = 0.0,
        logp = -0.1,
    )
    private val silence = FloatArray(16_000)
    private val whisper = StubTranscriber(said("whisper heard"))
    private val parakeet = StubTranscriber(said("parakeet a auzit"))
    private val deaf = StubTranscriber()

    @Test fun romanianGoesToParakeet() = runBlocking {
        assertEquals("parakeet a auzit", RoutedTranscriber(whisper, parakeet).transcribe(silence, SpeechLanguage.RO, true).text)
    }

    @Test fun englishGoesToWhisper() = runBlocking {
        assertEquals("whisper heard", RoutedTranscriber(whisper, parakeet).transcribe(silence, SpeechLanguage.EN, true).text)
    }

    @Test fun whisperTakesOverWhenParakeetHearsNothing() = runBlocking {
        assertEquals("whisper heard", RoutedTranscriber(whisper, deaf).transcribe(silence, SpeechLanguage.RO, true).text)
    }

    @Test fun parakeetAloneStillAnswersRomanian() = runBlocking {
        assertEquals("parakeet a auzit", RoutedTranscriber(null, parakeet).transcribe(silence, SpeechLanguage.RO, true).text)
        assertEquals("", RoutedTranscriber(null, deaf).transcribe(silence, SpeechLanguage.RO, true).text)
    }
}
