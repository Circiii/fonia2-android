package tech.fonia.speech

import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest

data class SpeechModelFile(
    val url: URI,
    /** Where the file lives inside the model folder. */
    val path: String,
    val byteCount: Long,
    val sha256: String,
)

data class SpeechModelManifest(val name: String, val files: List<SpeechModelFile>) {
    val byteCount: Long
        get() = files.fold(0L) { sum, file -> sum + file.byteCount }

    companion object {
        private class Pinned(val path: String, val bytes: Long, val sha256: String)

        // The Core ML build iOS downloads. Android's runtime will read other files (ONNX or GGML);
        // only tokenizer.json and tokenizer_config.json can stay. The list changes in the runtime step.
        private val whisperBaseModelFiles = listOf(
            Pinned("AudioEncoder.mlmodelc/analytics/coremldata.bin", 243L, "c4e096b2abd561f00b9b698401df3fbe1a0d0c8d2476ff19cb4e1995680e827e"),
            Pinned("AudioEncoder.mlmodelc/coremldata.bin", 347L, "e316980638e2099e83cb1a93b903717dc12b3c2168d0ab69113764c3767696ba"),
            Pinned("AudioEncoder.mlmodelc/metadata.json", 1863L, "d6785fae31c3f86d60058c14c79902f716f106dd9e36af7d3fe78b9c34867fce"),
            Pinned("AudioEncoder.mlmodelc/model.mil", 579_125L, "dc74813e26fce790fe22a33dcef8be535b9dce566f38a8bdb677f5e07dcf9138"),
            Pinned("AudioEncoder.mlmodelc/model.mlmodel", 79853L, "1d42038f84b508da5ce9b953302387ffedc097c346d36a56b765109002b6080e"),
            Pinned("AudioEncoder.mlmodelc/weights/weight.bin", 41_189_632L, "061ff4d74e5de3937b31288465d6c6f2697f92d121c80b23f51dd26bbdfe642b"),
            Pinned("MelSpectrogram.mlmodelc/analytics/coremldata.bin", 243L, "7f77e6457285248f99cd7aa3fd4cc2efbb17733e63e7023ac53abe1f95785d07"),
            Pinned("MelSpectrogram.mlmodelc/coremldata.bin", 328L, "dabdc5aa69f6ef4d97dc9499f5c30514e00e96b53b750b33a5a6471363c71662"),
            Pinned("MelSpectrogram.mlmodelc/metadata.json", 1848L, "f2b08d80d9cdd39fc0ccdbb5fac86a5f8dd9bcaa839706c3568be6fe8abd82d4"),
            Pinned("MelSpectrogram.mlmodelc/model.mil", 10176L, "b8063d8e57c113472ac7c2d248e44383568a018978a753c1884ac406b997a374"),
            Pinned("MelSpectrogram.mlmodelc/weights/weight.bin", 354_080L, "35d74417ef9c765e70f4ef85fe7405015a7086e9af05e3b63a5c2c7c748b2efc"),
            Pinned("TextDecoder.mlmodelc/analytics/coremldata.bin", 243L, "6ac1227740ecc2fd7a03df50ac6e2a7f7946acfa77069cf2c486ae0255356b95"),
            Pinned("TextDecoder.mlmodelc/coremldata.bin", 633L, "9f1f6fe409486e2797d3f0c65d9a6d5af596771760548cd86f41939c54cdbe7c"),
            Pinned("TextDecoder.mlmodelc/metadata.json", 4753L, "0a64b3686b9a4b2eff0792e7df3cfe1b20467ec5d5ac52c0e01c3dbf6697b65d"),
            Pinned("TextDecoder.mlmodelc/model.mil", 205_217L, "45b9e61a0f286cddcad96e2c890c7a68460adcaa3319c4907ee4f962dfeb2c8b"),
            Pinned("TextDecoder.mlmodelc/model.mlmodel", 164_481L, "ae260ff7b95d0c957c3c1f4df4dbeaa0ae6c76bacc55eb86caca8f6820d346f0"),
            Pinned("TextDecoder.mlmodelc/weights/weight.bin", 104_122_162L, "72325d42a4a4ccc8a6fa974ede6cdf2e0770685a5c4f9da94f41495b94d8d174"),
            Pinned("config.json", 1464L, "67e25477d03bf3a1c34bfd137724beed94b5218d0457e8c1d25f70379a61d9d5"),
            Pinned("generation_config.json", 2762L, "662a99e3db3067708549d04d5f141910eb455f0935aed0c9b06d707f44bfbcaf"),
        )

        private val whisperBaseTokenizerFiles = listOf(
            Pinned("tokenizer.json", 2_480_466L, "27fc476bfe7f17299480be2273fc0608e4d5a99aba2ab5dec5374b4482d1a566"),
            Pinned("tokenizer_config.json", 282_683L, "2a4c4281cf9f51ac6ccc406fdc711a087afe6530f671fa7b80953edc498275ce"),
        )

        /**
         * Whisper base with pinned hashes, from wherever the files are hosted. WhisperKit needs the
         * tokenizer next to the model, or it fetches it from the Hugging Face hub at load time.
         */
        fun whisperBase(modelBaseURL: URI, tokenizerBaseURL: URI): SpeechModelManifest {
            val model = whisperBaseModelFiles.map {
                SpeechModelFile(url = appendingPath(modelBaseURL, it.path), path = it.path, byteCount = it.bytes, sha256 = it.sha256)
            }
            val tokenizer = whisperBaseTokenizerFiles.map {
                SpeechModelFile(url = appendingPath(tokenizerBaseURL, it.path), path = it.path, byteCount = it.bytes, sha256 = it.sha256)
            }
            return SpeechModelManifest(name = "openai_whisper-base", files = model + tokenizer)
        }

        /**
         * The same files at the revisions they were hashed from. A shipping build should host them
         * itself: fetching from Hugging Face discloses the device's address to a third party.
         */
        val WHISPER_BASE_FROM_HUGGING_FACE: SpeechModelManifest = whisperBase(
            modelBaseURL = URI("https://huggingface.co/argmaxinc/whisperkit-coreml/resolve/0f63a7800b00dd0226abd051b906c246e1907482/openai_whisper-base"),
            tokenizerBaseURL = URI("https://huggingface.co/openai/whisper-base/resolve/e37978b90ca9030d5170a5c07aadb050351a65bb"),
        )

        /** `URL.appending(path:)`: one slash between the base and the path. */
        private fun appendingPath(base: URI, path: String): URI = URI(base.toString().trimEnd('/') + "/" + path)
    }
}

data class SpeechModelProgress(val bytesDone: Long, val bytesTotal: Long) {
    val fraction: Double
        get() = if (bytesTotal > 0) SwiftMath.min(1.0, bytesDone.toDouble() / bytesTotal.toDouble()) else 0.0
}

sealed class SpeechModelStoreError(message: String) : Exception(message) {
    /** Kept for parity; Android passes the root in, so it cannot happen there. */
    data object ApplicationSupportUnavailable : SpeechModelStoreError("application support unavailable")

    data class BadResponse(val file: String, val status: Int) : SpeechModelStoreError("bad response $status for $file")

    data class IntegrityCheckFailed(val file: String) : SpeechModelStoreError("integrity check failed for $file")
}

/**
 * The store's only network piece: fetch one manifest file into a temporary file the caller then
 * owns. The Android step plugs in plain HTTPS GETs on a client with no cookies and no cache,
 * Wi-Fi only unless `allowsCellular`, reporting bytes written as they arrive (iOS uses an
 * ephemeral URLSession with a download delegate for the same reasons).
 */
fun interface SpeechModelFetcher {
    suspend fun fetch(url: URI, allowsCellular: Boolean, onWrite: (Long) -> Unit): FetchedFile
}

/** A fetched file and its HTTP status; the status is null for a URL that is not HTTP, such as `file:`. */
data class FetchedFile(val file: File, val status: Int?)

/** Copies `file:` URLs, as URLSession does for them, so a model can be served from local files. */
object LocalFileFetcher : SpeechModelFetcher {
    override suspend fun fetch(url: URI, allowsCellular: Boolean, onWrite: (Long) -> Unit): FetchedFile {
        if (url.scheme != "file") throw IOException("no fetcher for $url: the Android HTTPS fetcher goes here")
        val source = File(url)
        val kept = File.createTempFile("fonia-speech-", null)
        try {
            source.inputStream().use { input ->
                kept.outputStream().use { output ->
                    val buffer = ByteArray(1 shl 16)
                    var written = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        written += n
                        onWrite(written)
                    }
                }
            }
        } catch (e: Exception) {
            kept.delete()
            throw e
        }
        return FetchedFile(kept, status = null)
    }
}

/**
 * Keeps the model on disk. The only network traffic is plain GETs for the files in the manifest;
 * nothing about the user is ever sent.
 *
 * iOS defaults `root` to Application Support/FoniaSpeech and marks the folders excluded from
 * backup. Android has no default without a Context: the app passes
 * `File(context.noBackupFilesDir, "FoniaSpeech")`, a directory Auto Backup never copies, so there
 * is nothing to mark. Swift's actor has no state beyond its two constants, so this is a plain class.
 */
class SpeechModelStore(
    private val manifest: SpeechModelManifest,
    private val root: File,
    private val fetcher: SpeechModelFetcher = LocalFileFetcher,
    /** The platform runtime, for [prewarm] and [makeEngine]. */
    private val loader: WhisperRuntimeLoader? = null,
) {
    val modelFolder: File
        get() = File(root, manifest.name)

    private val partialFolder: File
        get() = File(root, manifest.name + ".partial")

    /** Sizes only: hashes were checked when the files arrived, and this runs on the way to a screen. */
    val isReady: Boolean
        get() = manifest.files.all { size(File(modelFolder, it.path)) == it.byteCount }

    /**
     * Fetches whatever is missing. Files already verified by an interrupted run are kept, so a
     * retry resumes file by file. Cancel the calling coroutine to stop.
     */
    suspend fun download(allowsCellular: Boolean = false, progress: (SpeechModelProgress) -> Unit = {}) {
        if (isReady) return
        Files.createDirectories(root.toPath())

        val total = manifest.byteCount
        var done = 0L
        progress(SpeechModelProgress(bytesDone = 0, bytesTotal = total))
        for (file in manifest.files) {
            val destination = File(partialFolder, file.path)
            if (size(destination) == file.byteCount && sha256(destination) == file.sha256) {
                done += file.byteCount
                progress(SpeechModelProgress(bytesDone = done, bytesTotal = total))
                continue
            }
            val base = done
            val (temporary, status) = fetcher.fetch(file.url, allowsCellular) { written ->
                progress(SpeechModelProgress(bytesDone = base + minOf(written, file.byteCount), bytesTotal = total))
            }
            try {
                if (status != null && status != 200) throw SpeechModelStoreError.BadResponse(file = file.path, status = status)
                if (sha256(temporary) != file.sha256) throw SpeechModelStoreError.IntegrityCheckFailed(file = file.path)
                Files.createDirectories(destination.parentFile.toPath())
                destination.deleteRecursively()
                Files.move(temporary.toPath(), destination.toPath())
                done += file.byteCount
                progress(SpeechModelProgress(bytesDone = done, bytesTotal = total))
            } finally {
                temporary.delete()
            }
        }
        modelFolder.deleteRecursively()
        Files.move(partialFolder.toPath(), modelFolder.toPath())
    }

    /** Unload any engine using these files first. */
    fun delete() {
        for (folder in listOf(modelFolder, partialFolder)) {
            if (folder.exists() && !folder.deleteRecursively()) throw IOException("could not delete $folder")
        }
    }

    /** Runs the on-device model preparation up front, for a screen that can show a wait. */
    suspend fun prewarm() {
        if (!isReady) throw SpeechEngineError.ModelNotInstalled
        WhisperSession.prewarm(modelFolder, requireLoader())
    }

    fun makeEngine(thresholds: SpeechThresholds = SpeechThresholds.STANDARD): WhisperEngine {
        if (!isReady) throw SpeechEngineError.ModelNotInstalled
        return WhisperEngine(modelFolder, requireLoader(), thresholds)
    }

    private fun requireLoader(): WhisperRuntimeLoader =
        loader ?: throw IllegalStateException("SpeechModelStore needs a WhisperRuntimeLoader to prewarm or build an engine")

    companion object {
        private fun size(file: File): Long? = if (file.isFile) file.length() else null

        internal fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val chunk = ByteArray(1 shl 20)
                while (true) {
                    val n = input.read(chunk)
                    if (n <= 0) break
                    digest.update(chunk, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
