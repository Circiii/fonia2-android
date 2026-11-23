package tech.fonia.speech

import java.io.File
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

sealed class SpeechEngineError(message: String) : Exception(message) {
    data object ModelNotInstalled : SpeechEngineError("model not installed")

    data class TextTooLong(val tokens: Int, val limit: Int) : SpeechEngineError("text too long: $tokens tokens, limit $limit")

    data class DecodingFailed(val detail: String) : SpeechEngineError(detail)
}

/**
 * Whisper running entirely on this device. One encoder pass per clip is shared by the transcript
 * and every scored text, so a pair costs one encoder pass however many spellings it scores.
 * Requests run one at a time, in the order they arrive.
 *
 * Swift's actor and its FIFO of continuations become [RequestQueue]. The model itself comes from
 * [loader], the platform runtime.
 */
class WhisperEngine(
    private val modelFolder: File,
    private val loader: WhisperRuntimeLoader,
    thresholds: SpeechThresholds = SpeechThresholds.STANDARD,
) : Transcriber {
    private class Analysis(val transcript: Transcript, val scores: List<TextScore>, val cost: InferenceCost)

    private val silence = thresholds.silence
    private val queue = RequestQueue()

    @Volatile
    private var session: WhisperSession? = null

    val isLoaded: Boolean
        get() = session != null

    /** Loads the model now rather than on the first request. First load runs for seconds. */
    suspend fun load() {
        queue.serial { loadedSession() }
    }

    /** Frees the model's memory; the next request loads it again. */
    suspend fun unload() {
        queue.serial {
            session?.unload()
            session = null
        }
    }

    override suspend fun transcribe(samples16k: FloatArray, language: SpeechLanguage, verbatim: Boolean): Transcript =
        queue.serial { analyze(samples16k, language, transcribing = true, verbatim = verbatim, texts = emptyList()).transcript }

    /** Teacher-forced scores of `texts` against the first 30 s of the clip. */
    suspend fun scoreTexts(samples16k: FloatArray, language: SpeechLanguage, texts: List<String>): List<TextScore> =
        queue.serial { analyze(samples16k, language, transcribing = false, verbatim = false, texts = texts).scores }

    override suspend fun scorePair(samples16k: FloatArray, target: String, rival: String, language: SpeechLanguage): PairScore =
        queue.serial {
            val variants = SpeechText.pairVariants(target = target, rival = rival)
            val texts = variants?.let { it.target + it.rival } ?: emptyList()
            val analysis = analyze(samples16k, language, transcribing = true, verbatim = false, texts = texts)
            val split = variants?.target?.size ?: 0
            SpeechJudge.combinePair(
                target = target,
                rival = rival,
                language = language,
                transcript = analysis.transcript,
                targetScores = analysis.scores.take(split),
                rivalScores = analysis.scores.drop(split),
            ).copy(cost = analysis.cost)
        }

    override suspend fun scoreSentence(samples16k: FloatArray, target: String, language: SpeechLanguage): SentenceScore =
        queue.serial {
            val analysis = analyze(samples16k, language, transcribing = true, verbatim = false, texts = listOf(target))
            SentenceScore(target = target, language = language, transcript = analysis.transcript, score = analysis.scores[0], cost = analysis.cost)
        }

    private suspend fun loadedSession(): WhisperSession {
        session?.let { return it }
        val loaded = WhisperSession.load(modelFolder, loader)
        session = loaded
        return loaded
    }

    private suspend fun analyze(samples: FloatArray, language: SpeechLanguage, transcribing: Boolean, verbatim: Boolean, texts: List<String>): Analysis {
        val session = loadedSession()
        val started = System.nanoTime()
        val audio = if (samples.isEmpty()) FloatArray(TokenTiming.SAMPLES_PER_POSITION) else samples
        val parts = mutableListOf<String>()
        val words = mutableListOf<TranscriptWord>()
        var noSpeech = 1.0
        var logp = 0.0
        var scores = emptyList<TextScore>()
        var encoderPasses = 0
        var decoderSteps = 0

        for ((index, window) in TokenTiming.windows(audio).withIndex()) {
            if (index > 0 && !transcribing) break
            // Swift checks Task cancellation here; the runtime's suspending calls are the cancellation points.
            val clip = session.encode(audio.copyOfRange(window.lowerBound, window.upperBound))
            encoderPasses += 1
            val decoder = session.makeDecoder(clip)
            if (transcribing) {
                val result = session.transcribe(clip, decoder, language, verbatim, silence)
                if (result.text.isNotEmpty()) parts.add(result.text)
                val offset = window.lowerBound.toDouble() / SpeechResampler.WHISPER_RATE
                words += result.words.map { TranscriptWord(word = it.word, start = it.start + offset, end = it.end + offset, prob = it.prob) }
                noSpeech = SwiftMath.min(noSpeech, result.noSpeechProb)
                logp += result.logp
            }
            if (index == 0 && texts.isNotEmpty()) {
                scores = session.score(texts, clip, decoder, language)
            }
            decoderSteps += decoder.stepsRun
        }

        val cost = InferenceCost(
            milliseconds = (System.nanoTime() - started).toDouble() / 1e6,
            encoderPasses = encoderPasses,
            decoderSteps = decoderSteps,
        )
        val transcript = Transcript(text = parts.joinToString(" "), words = words, noSpeechProb = noSpeech, logp = logp, cost = cost)
        return Analysis(transcript, scores, cost)
    }
}

/**
 * The engine's `acquire()` and `release()`: requests wait in a FIFO of continuations and each one
 * runs alone. Stdlib-only, so `:speech` needs no coroutine library; like Swift's
 * `withCheckedContinuation`, a waiting request is not cancellable.
 */
internal class RequestQueue {
    private val lock = Any()
    private var busy = false
    private val waiting = ArrayDeque<Continuation<Unit>>()

    suspend fun <T> serial(block: suspend () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }

    private suspend fun acquire() = suspendCoroutine<Unit> { continuation ->
        val proceed = synchronized(lock) {
            if (busy) {
                waiting.addLast(continuation)
                false
            } else {
                busy = true
                true
            }
        }
        if (proceed) continuation.resume(Unit)
    }

    private fun release() {
        val next = synchronized(lock) {
            if (waiting.isEmpty()) {
                busy = false
                null
            } else {
                waiting.removeFirst()
            }
        }
        next?.resume(Unit)
    }
}
