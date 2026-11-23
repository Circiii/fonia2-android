package tech.fonia.whisper

import tech.fonia.speech.SpeechModelFile
import tech.fonia.speech.SpeechModelManifest
import java.net.URI
import kotlin.math.roundToLong

/**
 * The Android model: the same openai/whisper-base weights iOS runs, in whisper.cpp's ggml f16
 * conversion, plus the tokenizer and generation config files iOS pins byte for byte.
 *
 * iOS downloads 21 Core ML files (149 482 602 bytes). Android needs four: the weights, `tokenizer.json`
 * and `tokenizer_config.json` (same revision and hashes as iOS), and `generation_config.json` (the very
 * file iOS reads for `suppress_tokens`, `begin_suppress_tokens` and the alignment heads).
 */
object WhisperCppModel {
    const val MODEL_FILE = "ggml-base.bin"

    /** Same folder name as iOS (`openai_whisper-base`) so logs and paths read the same. */
    const val NAME = "openai_whisper-base"

    private class Pinned(val path: String, val bytes: Long, val sha256: String)

    // ggerganov/whisper.cpp at 5359861c739e955e79d9a303bcbc70fb988958b1, LFS object sha256.
    private val model = Pinned(MODEL_FILE, 147_951_465L, "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe")

    // argmaxinc/whisperkit-coreml at 0f63a7800b00dd0226abd051b906c246e1907482, openai_whisper-base/: the iOS pin.
    private val generationConfig = Pinned("generation_config.json", 2762L, "662a99e3db3067708549d04d5f141910eb455f0935aed0c9b06d707f44bfbcaf")

    // openai/whisper-base at e37978b90ca9030d5170a5c07aadb050351a65bb: the iOS pins.
    private val tokenizerFiles = listOf(
        Pinned("tokenizer.json", 2_480_466L, "27fc476bfe7f17299480be2273fc0608e4d5a99aba2ab5dec5374b4482d1a566"),
        Pinned("tokenizer_config.json", 282_683L, "2a4c4281cf9f51ac6ccc406fdc711a087afe6530f671fa7b80953edc498275ce"),
    )

    /**
     * Whisper base with pinned hashes, from wherever the files are hosted: the weights under
     * [modelBaseURL], `generation_config.json` under [configBaseURL], the tokenizer under [tokenizerBaseURL].
     */
    fun whisperBase(modelBaseURL: URI, configBaseURL: URI, tokenizerBaseURL: URI): SpeechModelManifest {
        fun file(base: URI, pinned: Pinned) =
            SpeechModelFile(url = appendingPath(base, pinned.path), path = pinned.path, byteCount = pinned.bytes, sha256 = pinned.sha256)
        return SpeechModelManifest(
            name = NAME,
            files = listOf(file(modelBaseURL, model), file(configBaseURL, generationConfig)) + tokenizerFiles.map { file(tokenizerBaseURL, it) },
        )
    }

    /**
     * The same files at the revisions they were hashed from (150 717 376 bytes). As on iOS, a shipping
     * build should host them itself: fetching from Hugging Face discloses the device's address to a
     * third party.
     */
    val WHISPER_BASE_FROM_HUGGING_FACE: SpeechModelManifest = whisperBase(
        modelBaseURL = URI("https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1"),
        configBaseURL = URI("https://huggingface.co/argmaxinc/whisperkit-coreml/resolve/0f63a7800b00dd0226abd051b906c246e1907482/openai_whisper-base"),
        tokenizerBaseURL = URI("https://huggingface.co/openai/whisper-base/resolve/e37978b90ca9030d5170a5c07aadb050351a65bb"),
    )

    /**
     * The size to show before anything is downloaded, in decimal megabytes ("151 MB" for
     * [WHISPER_BASE_FROM_HUGGING_FACE]). iOS hard-codes "147 MB", the Core ML model without its
     * tokenizer; this counts every file the download fetches.
     */
    fun megabytes(manifest: SpeechModelManifest = WHISPER_BASE_FROM_HUGGING_FACE): Long =
        (manifest.byteCount.toDouble() / 1_000_000.0).roundToLong()

    /** `URL.appending(path:)`: one slash between the base and the path. */
    private fun appendingPath(base: URI, path: String): URI = URI(base.toString().trimEnd('/') + "/" + path)
}
