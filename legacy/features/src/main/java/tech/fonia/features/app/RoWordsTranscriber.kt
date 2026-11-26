package tech.fonia.features.app

import tech.fonia.ml.RoAsrHost
import tech.fonia.speech.PairScore
import tech.fonia.speech.SentenceScore
import tech.fonia.speech.SpeechLanguage
import tech.fonia.speech.Transcriber
import tech.fonia.speech.Transcript
import tech.fonia.speech.TranscriptWord
import kotlin.math.ln

/**
 * The Romanian word recogniser of the APK behind the [Transcriber] the speaking screens already use, for the one
 * thing it does: writing what was said, with word times. [RoutedTranscriber] asks it first for Romanian.
 *
 * It does not score a target against a rival or force a sentence: those calls are not answered, and the screens that
 * need them keep their own path.
 */
class RoWordsTranscriber(private val host: RoAsrHost) : Transcriber {
    override suspend fun transcribe(samples16k: FloatArray, language: SpeechLanguage, verbatim: Boolean): Transcript {
        val nothing = Transcript(text = "", words = emptyList(), noSpeechProb = 1.0, logp = 0.0)
        if (language != SpeechLanguage.RO || samples16k.isEmpty()) return nothing
        val seconds = samples16k.size / 16_000.0
        val heard = host.hear(samples16k, 16_000.0, timeoutMs = (10_000 + 500 * seconds).toLong()) ?: return nothing
        // Long recordings (a talk, a reading) are the last thing asked of it for a while: free its memory.
        if (seconds > 20) host.trim()
        if (heard.words.isEmpty()) return nothing
        return Transcript(
            text = heard.text,
            words = heard.words.map { TranscriptWord(it.text, it.start, it.end, it.confidence) },
            noSpeechProb = if (heard.blankShare > 0.97) 1.0 else 0.0,
            logp = ln(heard.confidence.coerceIn(1e-6, 1.0)),
        )
    }

    override suspend fun scorePair(samples16k: FloatArray, target: String, rival: String, language: SpeechLanguage): PairScore =
        throw UnsupportedOperationException("the word recogniser does not compare candidates")

    override suspend fun scoreSentence(samples16k: FloatArray, target: String, language: SpeechLanguage): SentenceScore =
        throw UnsupportedOperationException("the word recogniser does not force a sentence")
}

/**
 * Romanian to the APK's word recogniser, everything else to Whisper. Parakeet-Ro writes Romanian far better than
 * Whisper base (FLEURS-ro, tools/asr/eval_fleurs.py) and keeps repeats and false starts the way they were said, which
 * is what the speaking screens look for. When it hears nothing (or is not in this APK) Whisper gets the clip.
 * Pair and sentence scores need a decoder that can be forced, so they always go to Whisper.
 */
class RoutedTranscriber(private val whisper: Transcriber?, private val romanian: Transcriber?) : Transcriber {
    override suspend fun transcribe(samples16k: FloatArray, language: SpeechLanguage, verbatim: Boolean): Transcript {
        val ro = romanian
        if (language == SpeechLanguage.RO && ro != null) {
            val heard = try {
                ro.transcribe(samples16k, language, verbatim)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (heard != null && heard.words.isNotEmpty()) return heard
            if (whisper == null) return heard ?: Transcript(text = "", words = emptyList(), noSpeechProb = 1.0, logp = 0.0)
        }
        return (whisper ?: romanian ?: error("no recogniser")).transcribe(samples16k, language, verbatim)
    }

    override suspend fun scorePair(samples16k: FloatArray, target: String, rival: String, language: SpeechLanguage): PairScore =
        (whisper ?: throw UnsupportedOperationException("no Whisper model")).scorePair(samples16k, target, rival, language)

    override suspend fun scoreSentence(samples16k: FloatArray, target: String, language: SpeechLanguage): SentenceScore =
        (whisper ?: throw UnsupportedOperationException("no Whisper model")).scoreSentence(samples16k, target, language)
}

/**
 * What writes down his words in the speaking exercises: Romanian through the APK's word recogniser when it is there,
 * English (and Romanian without it) through Whisper when its model is on the phone, otherwise nothing.
 */
val AppModel.wordsTranscriber: Transcriber?
    get() {
        val romanian = judge?.words?.let { RoWordsTranscriber(it) }
        val whisper = speech.transcriber
        return if (romanian == null && whisper == null) null else RoutedTranscriber(whisper, romanian)
    }
