package tech.fonia.whisper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import tech.fonia.speech.SpeechEngineError
import tech.fonia.speech.WhisperDecoderOutput
import tech.fonia.speech.WhisperDecoderRuntime
import tech.fonia.speech.WhisperEncoderOutput
import tech.fonia.speech.WhisperRuntime
import tech.fonia.speech.WhisperRuntimeLoader
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Opens a model folder for [tech.fonia.speech.WhisperEngine]: what `WhisperKit(config)` does on iOS
 * with `download: false`. The folder holds the files of [WhisperCppModel]: `ggml-base.bin`
 * (whisper.cpp's f16 conversion of openai/whisper-base), `generation_config.json`, `tokenizer.json`
 * and `tokenizer_config.json`. Nothing here touches the network.
 *
 * @param threads ggml worker threads for the encoder and every decoder step.
 */
class WhisperCppLoader(
    private val threads: Int = WhisperCppThreads.preferred(),
    private val modelFileName: String = WhisperCppModel.MODEL_FILE,
) : WhisperRuntimeLoader {
    private class ParsedTokenizer(val key: String, val tokenizer: WhisperBpeTokenizer)

    @Volatile
    private var parsed: ParsedTokenizer? = null

    /** `AutoTokenizerWrapper.from(modelFolder:)`; the result is kept for the [load] that follows. */
    override suspend fun parseTokenizer(modelFolder: File) {
        tokenizer(modelFolder)
    }

    override suspend fun load(modelFolder: File): WhisperRuntime {
        val tokenizer = tokenizer(modelFolder)
        val heads = alignmentHeads(modelFolder)
        return WhisperCppRuntime.open(File(modelFolder, modelFileName), tokenizer, heads, threads)
    }

    /**
     * iOS compiles the Core ML models for the device here. whisper.cpp has nothing to compile, so this
     * loads the weights once and frees them: it proves the file loads and leaves it in the page cache.
     */
    override suspend fun prewarm(modelFolder: File) {
        load(modelFolder).unload()
    }

    private suspend fun tokenizer(modelFolder: File): WhisperBpeTokenizer = withContext(Dispatchers.IO) {
        val data = File(modelFolder, "tokenizer.json")
        val key = "${data.canonicalPath}:${data.length()}:${data.lastModified()}"
        parsed?.takeIf { it.key == key }?.let { return@withContext it.tokenizer }
        val tokenizer = WhisperBpeTokenizer.fromModelFolder(modelFolder)
        parsed = ParsedTokenizer(key, tokenizer)
        tokenizer
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * The alignment heads WhisperKit's TextDecoder averages (`alignment_heads` in
         * generation_config.json, the same list whisper.cpp ships as its base preset), flattened to
         * (layer, head) pairs. Empty when the file names none: steps then carry no alignment row.
         */
        internal fun alignmentHeads(modelFolder: File): IntArray {
            val file = File(modelFolder, "generation_config.json")
            if (!file.isFile) throw IOException("missing $file")
            val heads = json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject["alignment_heads"]?.jsonArray ?: return IntArray(0)
            return heads.flatMap { pair -> pair.jsonArray.map { it.jsonPrimitive.intOrNull ?: throw IOException("bad alignment head $pair") } }.toIntArray()
        }
    }
}

/** How many ggml threads to use: the cores of the faster clusters, at most four (Whisper base gains little beyond). */
object WhisperCppThreads {
    fun preferred(): Int {
        val cpus = Runtime.getRuntime().availableProcessors()
        val maxFrequencies = (0 until cpus).mapNotNull { cpu ->
            try {
                File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq").readText().trim().toLongOrNull()
            } catch (e: Exception) {
                null
            }
        }
        val fast = if (maxFrequencies.size == cpus && maxFrequencies.distinct().size > 1) {
            val slowest = maxFrequencies.min()
            maxFrequencies.count { it > slowest }
        } else {
            cpus
        }
        return fast.coerceIn(1, 4)
    }
}

/**
 * One loaded whisper.cpp model: the runtime half of WhisperKit that WhisperSession and ForcedDecoder
 * drive on iOS. All native calls for a model run on its own single thread, so whisper.cpp's state is
 * never touched concurrently, and every suspending call is a cancellation point.
 *
 * The native state holds one encoder output and one KV cache. Decoders remember which encoder output
 * and which tokens they own; if another encoder pass or decoder used the state in between, the next
 * step re-encodes and replays its tokens first. WhisperEngine never interleaves decoders, so that path
 * only guards against misuse.
 */
internal class WhisperCppRuntime private constructor(
    override val tokenizer: WhisperBpeTokenizer,
    private var handle: Long,
    private val dispatcher: ExecutorCoroutineDispatcher,
    dimensions: IntArray,
) : WhisperRuntime {
    private val vocabularySize = dimensions[0]
    private val audioPositions = dimensions[1]
    private val textContext = dimensions[2]
    private val hasAlignment = dimensions[4] != 0

    /** `featureExtractor.windowSamples`: whisper.cpp's 30 s window at 16 kHz. */
    override val windowSamples: Int = dimensions[3]

    // Who the native state belongs to. Read and written on the model's thread only.
    private var stateAudio: Encoded? = null
    private var stateDecoder: Decoder? = null
    private var stateLength = 0
    private val replayLogits by lazy { FloatArray(vocabularySize) }

    @Volatile
    private var closed = false

    private class Encoded(val runtime: WhisperCppRuntime, val audio: FloatArray) : WhisperEncoderOutput

    override suspend fun encodeFeatures(audio: FloatArray): WhisperEncoderOutput? {
        if (audio.isEmpty()) return null
        // WhisperSession hands over a fresh padded copy, so the runtime may keep it for a re-encode.
        val encoded = Encoded(this, if (audio.size > windowSamples) audio.copyOf(windowSamples) else audio)
        withContext(dispatcher) { encodeNow(encoded) }
        return encoded
    }

    override fun makeDecoder(encoderOutput: WhisperEncoderOutput): WhisperDecoderRuntime? {
        val encoded = encoderOutput as? Encoded ?: return null
        if (encoded.runtime !== this) return null
        return Decoder(encoded)
    }

    /** `unloadModels()`: frees the weights and the state. Decoders of this model fail from now on. */
    override suspend fun unload() {
        if (closed) return
        closed = true
        withContext(dispatcher) {
            if (handle != 0L) {
                WhisperCppNative.free(handle)
                handle = 0L
            }
            stateAudio = null
            stateDecoder = null
        }
        dispatcher.close()
    }

    private fun requireHandle(): Long {
        if (handle == 0L) throw SpeechEngineError.DecodingFailed("model unloaded")
        return handle
    }

    private fun encodeNow(encoded: Encoded) {
        val status = WhisperCppNative.encode(requireHandle(), encoded.audio)
        if (status != 0) throw SpeechEngineError.DecodingFailed("encoder failed ($status)")
        stateAudio = encoded
        stateDecoder = null
        stateLength = 0
    }

    private fun decodeNow(token: Int, index: Int, logits: FloatArray, alignment: FloatArray?) {
        val status = WhisperCppNative.decode(requireHandle(), token, index, logits, alignment)
        if (status != 0) throw SpeechEngineError.DecodingFailed("decoder step failed ($status)")
    }

    /** A decoder over [audio] with an empty cache: `prepareDecoderInputs` and `predictLogits`. */
    private inner class Decoder(private val audio: Encoded) : WhisperDecoderRuntime {
        /** WhisperKit's TextDecoder for whisper-base holds 224 KV slots (`key_cache` is 1 x 3072 x 1 x 224). */
        override val kvCacheMaxSequenceLength: Int = minOf(KV_CACHE_SLOTS, textContext)

        /** The token fed at each cache position, to rebuild the cache if the state changed hands. */
        private val fed = IntArray(kvCacheMaxSequenceLength)

        override suspend fun predictLogits(token: Int, index: Int): WhisperDecoderOutput {
            if (index !in 0 until kvCacheMaxSequenceLength) throw SpeechEngineError.DecodingFailed("cache position $index out of range")
            // Fresh arrays: ForcedDecoder keeps the logits of forced steps.
            val logits = FloatArray(vocabularySize)
            val alignment = if (hasAlignment) FloatArray(audioPositions) else null
            withContext(dispatcher) {
                claimState(index)
                decodeNow(token, index, logits, alignment)
                fed[index] = token
                stateLength = index + 1
            }
            return WhisperDecoderOutput(logits, alignment)
        }

        /** Nothing to do: each step drops the cache cells at and past its own position before it runs. */
        override fun rewind(length: Int) {}

        /** Makes cache positions `0 until index` hold this decoder's tokens over this decoder's audio. */
        private fun claimState(index: Int) {
            if (stateAudio !== audio) encodeNow(audio)
            val valid = if (stateDecoder === this) minOf(stateLength, index) else 0
            stateDecoder = this
            for (position in valid until index) {
                decodeNow(fed[position], position, replayLogits, null)
                stateLength = position + 1
            }
        }
    }

    companion object {
        const val KV_CACHE_SLOTS = 224

        suspend fun open(modelFile: File, tokenizer: WhisperBpeTokenizer, heads: IntArray, threads: Int): WhisperCppRuntime {
            if (!modelFile.isFile) throw IOException("missing $modelFile")
            val dispatcher = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "fonia-whisper").apply { isDaemon = true } }
                .asCoroutineDispatcher()
            try {
                val (handle, dimensions) = withContext(dispatcher) {
                    val handle = WhisperCppNative.load(modelFile.path, threads, heads)
                    if (handle == 0L) throw IOException("whisper.cpp could not load $modelFile")
                    handle to WhisperCppNative.dimensions(handle)
                }
                return WhisperCppRuntime(tokenizer, handle, dispatcher, dimensions)
            } catch (e: Throwable) {
                dispatcher.close()
                throw e
            }
        }
    }
}
