package tech.fonia.speech

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.ceil
import kotlin.math.exp

/**
 * A loaded model and everything that touches its runtime objects. [WhisperEngine] owns the only
 * instance and runs one request at a time, so nothing here is ever used concurrently.
 */
internal class WhisperSession private constructor(
    private val runtime: WhisperRuntime,
    config: GenerationConfig,
) {
    class EncodedClip(
        val output: WhisperEncoderOutput,
        /** Encoder positions that hold real audio; the rest is padding. */
        val positions: Int,
        val duration: Double,
        val active: List<Boolean>,
    )

    data class WindowTranscript(val text: String, val words: List<TranscriptWord>, val noSpeechProb: Double, val logp: Double)

    @Serializable
    private class GenerationConfig(
        @SerialName("suppress_tokens") val suppressTokens: List<Int>,
        @SerialName("begin_suppress_tokens") val beginSuppressTokens: List<Int>,
    )

    private val tokenizer = runtime.tokenizer
    private val specials = tokenizer.specialTokens

    // SpecialTokens looks up <|nospeech|>, which this tokenizer calls <|nocaptions|>, and it
    // answers an unknown name with the id of <|endoftext|> rather than nil.
    private val noSpeechToken: Int = listOf("<|nocaptions|>", "<|nospeech|>").asSequence()
        .mapNotNull { tokenizer.convertTokenToId(it) }
        .firstOrNull { it != specials.endToken } ?: specials.noSpeechToken
    private val suppressed: Set<Int> = config.suppressTokens.toSet()
    private val suppressedAtStart: Set<Int> = config.beginSuppressTokens.toSet()

    suspend fun unload() {
        runtime.unload()
    }

    suspend fun encode(clip: FloatArray): EncodedClip {
        val windowSamples = runtime.windowSamples ?: DEFAULT_WINDOW_SAMPLES
        val output = runtime.encodeFeatures(padOrTrim(clip, windowSamples))
            ?: throw SpeechEngineError.DecodingFailed("encoder returned no output")
        return EncodedClip(
            output = output,
            positions = maxOf(1, minOf(1500, clip.size / TokenTiming.SAMPLES_PER_POSITION)),
            duration = clip.size.toDouble() / SAMPLE_RATE.toDouble(),
            active = TokenTiming.activeFrames(clip),
        )
    }

    fun makeDecoder(clip: EncodedClip): ForcedDecoder {
        val decoder = runtime.makeDecoder(clip.output) ?: throw SpeechEngineError.DecodingFailed("decoder inputs unavailable")
        return ForcedDecoder(decoder, positions = clip.positions)
    }

    private fun textTokens(text: String): List<Int> = tokenizer.encode(text).filter { it < specials.specialTokenBegin }

    private fun prefix(language: SpeechLanguage, verbatim: Boolean): List<Int> {
        val languageToken = tokenizer.convertTokenToId("<|${language.rawValue}|>")
            ?: throw SpeechEngineError.DecodingFailed("no token for language ${language.rawValue}")
        val start = listOf(specials.startOfTranscriptToken, languageToken, specials.transcribeToken, specials.noTimestampsToken)
        return if (verbatim) listOf(specials.startOfPreviousToken) + textTokens(verbatimPrompt(language)) + start else start
    }

    private fun noSpeechProb(prefix: List<Int>, decoder: ForcedDecoder): Double {
        val startIndex = prefix.indexOf(specials.startOfTranscriptToken).takeIf { it >= 0 } ?: 0
        return exp(decoder.steps[startIndex].logp(noSpeechToken))
    }

    private fun token(id: Int, step: ForcedDecoder.Step): TokenScore = TokenScore(
        id = id,
        text = if (id == specials.endToken) "" else tokenizer.decode(listOf(id)),
        logp = step.logp(id),
        topID = step.topID,
        top = if (step.topID >= specials.endToken) "" else tokenizer.decode(listOf(step.topID)),
        topLogp = step.topLogp,
    )

    private fun words(tokens: List<TokenScore>, decoder: ForcedDecoder, firstRow: Int, clip: EncodedClip): List<WordScore> {
        val rows = decoder.steps.mapNotNull { it.alignment }
        val times = if (rows.size == decoder.steps.size) {
            TokenTiming.tokenStarts(rows = rows, first = firstRow, count = tokens.size, positions = clip.positions)
        } else {
            null
        }
        return TokenTiming.groupWords(tokens, times, clip.duration) { tokenizer.decode(it) }.map { word ->
            val (start, end) = TokenTiming.snapToSound(word.start, word.end, clip.active)
            word.copy(start = start, end = end)
        }
    }

    private fun isSuppressed(id: Int, atStart: Boolean): Boolean {
        if (id == specials.endToken) return atStart && id in suppressedAtStart
        return id > specials.endToken || id in suppressed || (atStart && id in suppressedAtStart)
    }

    /** The token greedy decoding takes next. Nearly always the model's own top token. */
    private fun nextToken(step: ForcedDecoder.Step, atStart: Boolean): Int {
        if (!isSuppressed(step.topID, atStart)) return step.topID
        var best = specials.endToken
        var bestLogit = Float.NEGATIVE_INFINITY
        for (id in step.logits.indices) {
            val logit = step.logits[id]
            if (logit > bestLogit && !isSuppressed(id, atStart)) {
                best = id
                bestLogit = logit
            }
        }
        return best
    }

    /** Greedy transcription that shares its prefix steps with whatever is scored next on this decoder. */
    suspend fun transcribe(clip: EncodedClip, decoder: ForcedDecoder, language: SpeechLanguage, verbatim: Boolean, silence: SilenceThresholds): WindowTranscript {
        val prefix = prefix(language, verbatim)
        decoder.run(prefix)
        val noSpeech = noSpeechProb(prefix, decoder)
        try {
            // Speech runs at 3 to 4 tokens a second; the cap stops the rare repetition loop early.
            val maxNew = minOf(decoder.maxLength - prefix.size, ceil(clip.duration * 9).toInt() + 8)
            val ids = mutableListOf<Int>()
            val tokens = mutableListOf<TokenScore>()
            var step = decoder.steps[prefix.size - 1]
            for (unused in 0 until maxNew) {
                val id = nextToken(step, atStart = ids.isEmpty())
                if (id == specials.endToken) break
                tokens.add(token(id, step))
                ids.add(id)
                step = decoder.feed(id, keepLogits = false)
            }
            tokens.add(token(specials.endToken, step))

            var logp = 0.0
            for (t in tokens) logp += t.logp
            if (silence.isSilent(noSpeechProb = noSpeech, meanLogp = logp / tokens.size.toDouble())) {
                return WindowTranscript(text = "", words = emptyList(), noSpeechProb = noSpeech, logp = logp)
            }
            val scored = words(tokens, decoder, firstRow = prefix.size - 1, clip = clip)
            return WindowTranscript(
                text = SwiftString.trimmingWhitespacesAndNewlines(tokenizer.decode(ids)),
                words = scored.map { TranscriptWord(word = it.word, start = it.start, end = it.end, prob = it.prob) },
                noSpeechProb = noSpeech,
                logp = logp,
            )
        } finally {
            decoder.rewind(prefix.size)
        }
    }

    /** Teacher-forced scores, in the order of `texts`. Decoded in token order so that neighbours share the most steps. */
    suspend fun score(texts: List<String>, clip: EncodedClip, decoder: ForcedDecoder, language: SpeechLanguage): List<TextScore> {
        val prefix = prefix(language, verbatim = false)
        val encoded = texts.map { text ->
            val clean = SwiftString.trimmingWhitespacesAndNewlines(text)
            clean to (if (clean.isEmpty()) emptyList() else textTokens(" $clean"))
        }
        // lexicographicallyPrecedes, through a stable sort.
        val order = encoded.indices.sortedWith { a, b -> compareLexicographically(encoded[a].second, encoded[b].second) }
        val scores = arrayOfNulls<TextScore>(texts.size)
        for (index in order) {
            val (clean, ids) = encoded[index]
            decoder.run(prefix + ids)
            val first = prefix.size - 1
            val tokens = (ids + specials.endToken).mapIndexed { offset, id -> token(id, decoder.steps[first + offset]) }
            var logp = 0.0
            for (t in tokens) logp += t.logp
            scores[index] = TextScore(
                text = clean,
                logp = logp,
                meanLogp = logp / tokens.size.toDouble(),
                eotLogp = tokens[tokens.size - 1].logp,
                noSpeechProb = noSpeechProb(prefix, decoder),
                tokens = tokens,
                words = words(tokens, decoder, firstRow = first, clip = clip),
            )
        }
        return scores.filterNotNull()
    }

    companion object {
        /** `WhisperKit.sampleRate`. */
        const val SAMPLE_RATE = 16_000

        /** `Constants.defaultWindowSamples`: 30 s. */
        const val DEFAULT_WINDOW_SAMPLES = 480_000

        private val configJson = Json { ignoreUnknownKeys = true }

        /**
         * Whisper copies the register of its prompt, so a disfluent prompt keeps "um", "like", "deci"
         * that it would otherwise drop. Never shown to anyone.
         */
        fun verbatimPrompt(language: SpeechLanguage): String = when (language) {
            SpeechLanguage.EN -> " Umm, let me think like, hmm... Okay, here's what I'm, like, thinking. So, um, basically, uh, you know."
            SpeechLanguage.RO -> " Ăăă, deci, practic, mmm... Păi, adică, gen, nu știu, ăăă, mă rog."
        }

        suspend fun load(modelFolder: File, loader: WhisperRuntimeLoader): WhisperSession {
            val configData = try {
                val data = File(modelFolder, "generation_config.json").readText(Charsets.UTF_8)
                // A local tokenizer that fails to parse would send WhisperKit to the network.
                // Parsing first means the engine can only fail here, and never makes a request.
                loader.parseTokenizer(modelFolder)
                data
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw SpeechEngineError.ModelNotInstalled
            }
            val config = configJson.decodeFromString(GenerationConfig.serializer(), configData)
            return WhisperSession(loader.load(modelFolder), config)
        }

        suspend fun prewarm(modelFolder: File, loader: WhisperRuntimeLoader) {
            loader.prewarm(modelFolder)
        }

        /** `audioProcessor.padOrTrim(fromArray:startAt: 0, toLength:)`: zeros after the clip, or its start. */
        fun padOrTrim(clip: FloatArray, length: Int): FloatArray = clip.copyOf(length)

        private fun compareLexicographically(a: List<Int>, b: List<Int>): Int {
            for (k in 0 until minOf(a.size, b.size)) {
                if (a[k] != b[k]) return a[k].compareTo(b[k])
            }
            return a.size.compareTo(b.size)
        }
    }
}
