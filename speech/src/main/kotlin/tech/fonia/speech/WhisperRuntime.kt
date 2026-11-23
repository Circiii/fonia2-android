package tech.fonia.speech

import java.io.File

// The platform half of Whisper: what WhisperKit and Core ML give WhisperSession and ForcedDecoder
// on iOS. The Android step implements these four interfaces (ONNX Runtime or whisper.cpp through
// the NDK). Everything above them is the pure port in ForcedDecoder, WhisperSession and
// WhisperEngine: the step-by-step decoding loop, shared prefixes, suppressed tokens, the silence
// gate, word timings from cross-attention, 30 s windows and the request queue. Nothing behind these
// interfaces may touch the network.

/** Opens a model folder, as `WhisperKit(config)` does with `download: false`. */
interface WhisperRuntimeLoader {
    /**
     * Parses the folder's tokenizer and throws if it can't (`AutoTokenizerWrapper.from(modelFolder:)`).
     * WhisperKit falls back to the Hugging Face hub when a local tokenizer fails to parse; parsing
     * first means the engine can only fail here, as [SpeechEngineError.ModelNotInstalled].
     */
    suspend fun parseTokenizer(modelFolder: File)

    /** Loads tokenizer and models (`load: true`). */
    suspend fun load(modelFolder: File): WhisperRuntime

    /** The one-time on-device preparation (`prewarm: true`), for a screen that can show a wait. */
    suspend fun prewarm(modelFolder: File)
}

/** One loaded model. */
interface WhisperRuntime {
    val tokenizer: WhisperTokenizer

    /** `featureExtractor.windowSamples`; null means `Constants.defaultWindowSamples` (30 s at 16 kHz). */
    val windowSamples: Int?

    /**
     * Log-mel spectrogram and audio encoder (`logMelSpectrogram(fromAudio:)`, `encodeFeatures`) over
     * [audio], already padded or trimmed to the window. Null when the encoder gives nothing.
     */
    suspend fun encodeFeatures(audio: FloatArray): WhisperEncoderOutput?

    /** A decoder with an empty KV cache over [encoderOutput] (`prepareDecoderInputs`); null when unavailable. */
    fun makeDecoder(encoderOutput: WhisperEncoderOutput): WhisperDecoderRuntime?

    /** `unloadModels()`. */
    suspend fun unload()
}

/** The encoder's output, read only by the runtime that made it. */
interface WhisperEncoderOutput

/** One text decoder over one encoder output: `TextDecoding.predictLogits`, with the KV cache kept by the runtime. */
interface WhisperDecoderRuntime {
    /** `kvCacheMaxSequenceLength`. */
    val kvCacheMaxSequenceLength: Int

    /**
     * Runs the decoder on [token] at cache position [index]. Slots `0 until index` hold the tokens fed
     * before it, and nothing at or past [index] may leak into the result; the token's keys and values
     * go into slot [index]. Returns the logits for the next token over the whole vocabulary, and the
     * cross-attention row over all encoder positions (WhisperKit's `alignmentWeights`: the alignment
     * heads averaged), or null when the model does not expose it.
     */
    suspend fun predictLogits(token: Int, index: Int): WhisperDecoderOutput

    /**
     * Forgets slots [length] and up (`ForcedDecoder.rewind`, which re-masks them on Core ML); the
     * next call comes with `index == length`. A runtime that slices its cache by `index` can ignore it.
     */
    fun rewind(length: Int)
}

class WhisperDecoderOutput(val logits: FloatArray, val alignmentWeights: FloatArray?)

/** WhisperKit's `WhisperTokenizer`. */
interface WhisperTokenizer {
    val specialTokens: WhisperSpecialTokens

    fun encode(text: String): List<Int>

    fun decode(tokens: List<Int>): String

    /** Null when unknown. Whisper's own tokenizer answers an unknown name with [WhisperSpecialTokens.endToken]. */
    fun convertTokenToId(token: String): Int?
}

/** The ids WhisperSession reads from WhisperKit's `SpecialTokens`. */
data class WhisperSpecialTokens(
    val endToken: Int,
    val startOfTranscriptToken: Int,
    val transcribeToken: Int,
    val noTimestampsToken: Int,
    val startOfPreviousToken: Int,
    val noSpeechToken: Int,
    val specialTokenBegin: Int,
)
