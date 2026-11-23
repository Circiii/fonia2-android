package tech.fonia.speech

import java.io.File
import java.io.IOException
import kotlin.math.abs

/**
 * A scripted Whisper behind the runtime seam. Its tokenizer knows a few whole words and every
 * single character; a window "says" whatever [hear] reads from its samples; the decoder puts a
 * high logit on the next token of that text and a cross-attention peak where that token sits in
 * the clip. It exercises the pure port above the seam (ForcedDecoder, WhisperSession,
 * WhisperEngine), not Whisper itself.
 */
class FakeWhisper(
    words: List<String> = DEFAULT_WORDS,
    /** What a window says, from its samples (padded to 30 s); null for silence. */
    private val hear: (FloatArray) -> String?,
) : WhisperRuntimeLoader {
    val tokenizer = FakeTokenizer(words)
    private val runtime = Runtime()

    var loads = 0
        private set
    var prewarms = 0
        private set
    var encoderPasses = 0
        private set
    var unloads = 0
        private set

    /** Every sequence fed to every decoder, in order. */
    val decoderHistories = mutableListOf<List<Int>>()

    override suspend fun parseTokenizer(modelFolder: File) {
        if (!File(modelFolder, "tokenizer.json").isFile) throw IOException("no tokenizer in $modelFolder")
    }

    override suspend fun load(modelFolder: File): WhisperRuntime {
        loads += 1
        return runtime
    }

    override suspend fun prewarm(modelFolder: File) {
        prewarms += 1
    }

    /** A model folder WhisperSession accepts: generation_config.json and a tokenizer file. */
    fun modelFolder(parent: File, suppress: List<Int> = emptyList(), beginSuppress: List<Int>? = null): File {
        val folder = File(parent, "openai_whisper-base")
        folder.mkdirs()
        val begin = beginSuppress ?: listOf(tokenizer.id(" "), tokenizer.specialTokens.endToken)
        File(folder, "generation_config.json").writeText(
            """{"suppress_tokens": [${suppress.joinToString(",")}], "begin_suppress_tokens": [${begin.joinToString(",")}], "max_length": 448}""",
        )
        File(folder, "tokenizer.json").writeText("{}")
        return folder
    }

    private class Encoded(val text: List<Int>?, val positions: Int) : WhisperEncoderOutput

    private inner class Runtime : WhisperRuntime {
        override val tokenizer: WhisperTokenizer
            get() = this@FakeWhisper.tokenizer

        override val windowSamples: Int? = null

        override suspend fun encodeFeatures(audio: FloatArray): WhisperEncoderOutput {
            encoderPasses += 1
            check(audio.size == WhisperSession.DEFAULT_WINDOW_SAMPLES) { "the window is padded before encoding" }
            var last = 0
            for (i in audio.indices) if (audio[i] != 0f) last = i
            val text = hear(audio)?.let { this@FakeWhisper.tokenizer.encode(it) }
            return Encoded(text, positions = maxOf(1, minOf(1500, (last + 1) / TokenTiming.SAMPLES_PER_POSITION)))
        }

        override fun makeDecoder(encoderOutput: WhisperEncoderOutput): WhisperDecoderRuntime =
            Decoder(encoderOutput as Encoded)

        override suspend fun unload() {
            unloads += 1
        }
    }

    private inner class Decoder(private val audio: Encoded) : WhisperDecoderRuntime {
        override val kvCacheMaxSequenceLength = 224
        private val cache = IntArray(kvCacheMaxSequenceLength)
        private val history = mutableListOf<Int>().also { decoderHistories.add(it) }

        override suspend fun predictLogits(token: Int, index: Int): WhisperDecoderOutput {
            check(index < kvCacheMaxSequenceLength - 1) { "no slot left for the update mask" }
            cache[index] = token
            history.add(token)
            val fed = cache.copyOf(index + 1).toList()
            return WhisperDecoderOutput(logits(fed), alignment(fed))
        }

        override fun rewind(length: Int) {}

        private fun logits(fed: List<Int>): FloatArray {
            val specials = tokenizer.specialTokens
            val logits = FloatArray(tokenizer.size)
            val last = fed.last()
            if (last == specials.startOfTranscriptToken) {
                logits[tokenizer.id("<|nocaptions|>")] = if (audio.text == null) 12f else -4f
                logits[tokenizer.id("<|en|>")] = 6f
                return logits
            }
            val start = fed.lastIndexOf(specials.noTimestampsToken)
            if (start < 0) return logits
            val said = fed.subList(start + 1, fed.size)
            val text = audio.text
            when {
                text == null -> logits[specials.endToken] = 5f
                said.size <= text.size && said == text.subList(0, said.size) ->
                    logits[if (said.size == text.size) specials.endToken else text[said.size]] = 9f
                else -> logits[specials.endToken] = 4f
            }
            return logits
        }

        /** A peak where the next token sits: prefix rows look at the start, token r at r / (n + 1) of the clip. */
        private fun alignment(fed: List<Int>): FloatArray {
            val start = fed.lastIndexOf(tokenizer.specialTokens.noTimestampsToken)
            val n = audio.text?.size ?: 1
            val r = if (start < 0) 0 else fed.size - 1 - start
            val peak = minOf(audio.positions - 1, (r * audio.positions) / (n + 1))
            return FloatArray(1500) { if (abs(it - peak) <= 1) 1f else 0.01f }
        }
    }

    companion object {
        val DEFAULT_WORDS = listOf(
            " ship", " sheep", " Ship", " Sheep", " think", " sink", " The", " is", " here", " three", " things", " I",
        )
    }
}

/** Whole words first, then every character the tests use, then Whisper's special tokens. */
class FakeTokenizer(words: List<String>) : WhisperTokenizer {
    private val pieces: List<String>
    private val ids: Map<String, Int>

    init {
        val characters = (0x20..0x7E).map { it.toChar().toString() } + "ĂăÂâÎîȘșȚț".map { it.toString() }
        val text = (words + characters).distinct()
        pieces = text + SPECIALS
        ids = pieces.withIndex().associate { it.value to it.index }
    }

    val size: Int
        get() = pieces.size

    fun id(piece: String): Int = ids.getValue(piece)

    override val specialTokens: WhisperSpecialTokens = run {
        val end = pieces.size - SPECIALS.size
        WhisperSpecialTokens(
            endToken = end,
            startOfTranscriptToken = end + SPECIALS.indexOf("<|startoftranscript|>"),
            transcribeToken = end + SPECIALS.indexOf("<|transcribe|>"),
            noTimestampsToken = end + SPECIALS.indexOf("<|notimestamps|>"),
            startOfPreviousToken = end + SPECIALS.indexOf("<|startofprev|>"),
            // WhisperKit's SpecialTokens asks for <|nospeech|>, which this tokenizer lacks, and gets <|endoftext|>.
            noSpeechToken = end,
            specialTokenBegin = end,
        )
    }

    /** Greedy longest match. */
    override fun encode(text: String): List<Int> {
        val out = mutableListOf<Int>()
        var i = 0
        while (i < text.length) {
            val piece = pieces.subList(0, specialTokens.endToken)
                .filter { text.startsWith(it, i) }
                .maxByOrNull { it.length }
                ?: throw IllegalArgumentException("no token for '${text.substring(i)}'")
            out.add(id(piece))
            i += piece.length
        }
        return out
    }

    override fun decode(tokens: List<Int>): String = tokens.joinToString("") { pieces[it] }

    /** Like Whisper's tokenizer, an unknown name answers with <|endoftext|>. */
    override fun convertTokenToId(token: String): Int = ids[token] ?: specialTokens.endToken

    companion object {
        val SPECIALS = listOf(
            "<|endoftext|>", "<|startoftranscript|>", "<|en|>", "<|ro|>", "<|transcribe|>", "<|notimestamps|>", "<|startofprev|>", "<|nocaptions|>",
        )
    }
}
