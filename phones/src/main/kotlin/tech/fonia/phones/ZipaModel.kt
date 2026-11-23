package tech.fonia.phones

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.io.InputStream
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.security.MessageDigest

/** Where a model file comes from and what it must hash to. The app's model manager downloads; this pins. */
data class ModelSpec(
    val name: String,
    val url: String,
    val sha256: String,
    val bytes: Long,
    val license: String,
) {
    val fileName: String get() = url.substringAfterLast('/')

    /** True when [file] has the pinned size and SHA-256. Reads the whole file. */
    fun matches(file: File): Boolean = file.isFile && file.length() == bytes && file.inputStream().use { sha256(it) } == sha256

    companion object {
        fun sha256(input: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

/** Something that turns 16 kHz audio into phone posteriors. The scorer depends on this, tests fake it. */
fun interface AcousticModel {
    fun posteriors(samples16k: FloatArray): Posteriors
}

/**
 * ZIPA-small CR-CTC on ONNX Runtime: 80-band fbank in, log-softmax over the 127 ZIPA tokens out, one row per 20 ms.
 * The graph's inputs are `x` (N, T, 80) float and `x_lens` (N) int64; its outputs `log_probs` (N, T', 127) and
 * `log_probs_len` (N), with T' = (T − 7) / 2.
 *
 * The module compiles against the desktop ONNX Runtime jar; the app ships `onnxruntime-android`, which has the same
 * `ai.onnxruntime` API, and the one ONNX Runtime of the APK (docs/PLAN.md §6). Calls are serialised on this
 * instance's fbank buffers; use one instance per thread.
 */
class ZipaModel private constructor(
    private val environment: OrtEnvironment,
    private val session: OrtSession,
) : AcousticModel, AutoCloseable {
    private val fbank = ZipaFbank()
    private var features = FloatArray(0)

    /** Wall-clock split of the last [posteriors] call, for the latency report. */
    var lastFbankNanos = 0L
        private set
    var lastModelNanos = 0L
        private set

    override fun posteriors(samples16k: FloatArray): Posteriors {
        val start = System.nanoTime()
        val frames = ZipaFbank.frameCount(samples16k.size)
        if (frames < MIN_FBANK_FRAMES) return Posteriors.empty
        if (features.size < frames * ZipaFbank.NUM_BINS) features = FloatArray(frames * ZipaFbank.NUM_BINS)
        fbank.compute(samples16k, out = features)
        val afterFbank = System.nanoTime()
        val result = run(features, frames)
        lastFbankNanos = afterFbank - start
        lastModelNanos = System.nanoTime() - afterFbank
        return result
    }

    /** Runs the network on [frames] rows of precomputed features. */
    fun run(features: FloatArray, frames: Int): Posteriors {
        require(frames >= MIN_FBANK_FRAMES) { "ZIPA needs at least $MIN_FBANK_FRAMES fbank frames, got $frames" }
        val x = OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(features, 0, frames * ZipaFbank.NUM_BINS),
            longArrayOf(1, frames.toLong(), ZipaFbank.NUM_BINS.toLong()),
        )
        try {
            val lengths = OnnxTensor.createTensor(environment, LongBuffer.wrap(longArrayOf(frames.toLong())), longArrayOf(1))
            try {
                session.run(mapOf("x" to x, "x_lens" to lengths)).use { result ->
                    val output = result.get("log_probs").get() as OnnxTensor
                    val shape = output.info.shape
                    check(shape.size == 3 && shape[0] == 1L && shape[2] == ZipaInventory.SIZE.toLong()) { "unexpected output shape ${shape.toList()}" }
                    val rows = shape[1].toInt()
                    val valid = ((result.get("log_probs_len").get() as OnnxTensor).longBuffer.get(0)).toInt()
                    val values = FloatArray(rows * ZipaInventory.SIZE)
                    output.floatBuffer.get(values)
                    return Posteriors(minOf(rows, valid), ZipaInventory.SIZE, values)
                }
            } finally {
                lengths.close()
            }
        } finally {
            x.close()
        }
    }

    override fun close() {
        session.close()
    }

    companion object {
        /** `(T − 7) / 2 ≥ 1` output rows. */
        const val MIN_FBANK_FRAMES = 9

        /**
         * ZIPA-small CR-CTC, 500k steps, int8 (dynamic quantisation of the MatMuls, as exported by the authors).
         * Pinned to the Hugging Face commit; see `phones/MODELS.md`.
         */
        val INT8 = ModelSpec(
            name = "zipa-small-crctc-500k int8",
            url = "https://huggingface.co/anyspeech/zipa-small-crctc-500k/resolve/dfe87c97662606fa6db90ad06f627d4363314744/model.int8.onnx",
            sha256 = "d0e28b68164e8b1fbd6105100c01798828aa0855000ce9bbbd1a2cec233adf13",
            bytes = 70_677_672,
            license = "CC-BY-4.0",
        )

        /** The token list published next to the model (same 127 symbols as [ZipaInventory]). */
        val TOKENS = ModelSpec(
            name = "zipa-small-crctc-500k tokens",
            url = "https://huggingface.co/anyspeech/zipa-small-crctc-500k/resolve/dfe87c97662606fa6db90ad06f627d4363314744/tokens.txt",
            sha256 = "f8e042a0c9130532b22d03ec7cae2f75a23fbec70c450c31a8efb51787b2b8fe",
            bytes = 769,
            license = "CC-BY-4.0",
        )

        /** The fp32 export of the same checkpoint, used only to check what int8 costs (not for the app: 260 MB). */
        val FP32 = ModelSpec(
            name = "zipa-small-crctc-500k fp32",
            url = "https://huggingface.co/anyspeech/zipa-small-crctc-500k/resolve/dfe87c97662606fa6db90ad06f627d4363314744/model.onnx",
            sha256 = "ec62e6f583cc3658c6a28a8841c65a6343e8c4b8b350ce1fd5bd8c717c280d96",
            bytes = 260_267_872,
            license = "CC-BY-4.0",
        )

        /**
         * Opens [model] on the CPU. [intraOpThreads] 0 lets ONNX Runtime choose (one per physical core); on a phone,
         * 2–4. The caller checks the file against its [ModelSpec] first (the model manager does, after download).
         */
        fun load(model: File, intraOpThreads: Int = 0): ZipaModel =
            open(intraOpThreads) { environment, options -> environment.createSession(model.absolutePath, options) }

        /**
         * Opens the model from memory: a direct [ByteBuffer] such as an APK asset stored uncompressed and mapped with
         * `FileChannel.map` (`:platform:ml`'s `AssetModelFile`), so nothing is copied to disk. ONNX Runtime reads the
         * graph from the buffer while the session is created; the caller may drop the mapping afterwards.
         */
        fun load(model: java.nio.ByteBuffer, intraOpThreads: Int = 0): ZipaModel =
            open(intraOpThreads) { environment, options -> environment.createSession(model, options) }

        private inline fun open(intraOpThreads: Int, create: (OrtEnvironment, OrtSession.SessionOptions) -> OrtSession): ZipaModel {
            val environment = OrtEnvironment.getEnvironment()
            // ONNX Runtime has telemetry: ETW on Windows, Microsoft's 1DS over HTTP in the Android AAR (whose start-up
            // provider the app removes). Nothing about a child's practice leaves the phone, so it is off here too.
            runCatching { environment.setTelemetry(false) }
            OrtSession.SessionOptions().use { options ->
                if (intraOpThreads > 0) {
                    options.setIntraOpNumThreads(intraOpThreads)
                    // One inference at a time per session: no inter-op pool, and no spinning threads between takes.
                    options.setInterOpNumThreads(1)
                    runCatching { options.addConfigEntry("session.intra_op.allow_spinning", "0") }
                }
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                return ZipaModel(environment, create(environment, options))
            }
        }
    }
}
