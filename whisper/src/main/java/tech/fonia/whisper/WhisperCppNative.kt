package tech.fonia.whisper

import android.os.Build
import java.io.File

/**
 * The JNI functions of libfonia_whisper (src/main/cpp/fonia_jni.cpp). Not thread-safe per handle:
 * [WhisperCppRuntime] calls them for one model from one thread only.
 */
internal object WhisperCppNative {
    /** Which build of the library this process loaded (see src/main/cpp/CMakeLists.txt). */
    val library: String = NativeLibraryChoice.choose(Build.SUPPORTED_ABIS.firstOrNull(), NativeLibraryChoice.cpuFeatures(File("/proc/cpuinfo")))

    init {
        System.loadLibrary(library)
    }

    /** A model handle, or 0 if whisper.cpp could not load the file. `heads` holds (layer, head) pairs. */
    @JvmStatic external fun load(modelPath: String, threads: Int, heads: IntArray): Long

    @JvmStatic external fun free(handle: Long)

    /** `[n_vocab, n_audio_ctx, n_text_ctx, window_samples, has_alignment]`. */
    @JvmStatic external fun dimensions(handle: Long): IntArray

    /** Log-mel spectrogram and encoder over [samples] (at most one window). 0 on success. */
    @JvmStatic external fun encode(handle: Long, samples: FloatArray): Int

    /**
     * One decoder step: [token] at cache position [nPast]. Fills [logits] (n_vocab) and, when the model
     * has alignment heads, [alignment] (n_audio_ctx). 0 on success.
     */
    @JvmStatic external fun decode(handle: Long, token: Int, nPast: Int, logits: FloatArray, alignment: FloatArray?): Int

    /** ggml's CPU feature line, for logs and reports. */
    @JvmStatic external fun systemInfo(): String
}

/** Picks the build of libfonia_whisper this CPU can run; plain Kotlin so the JVM tests can check it. */
internal object NativeLibraryChoice {
    const val BASELINE = "fonia_whisper"
    const val DOTPROD = "fonia_whisper_dotprod"

    /**
     * The dotprod build only on arm64 when every core lists `asimddp` (the ARMv8.2 dot product);
     * everything else, ARMv8.0 cores such as Cortex-A53 included, gets the baseline NEON build.
     */
    fun choose(abi: String?, features: List<Set<String>>): String =
        if (abi == "arm64-v8a" && features.isNotEmpty() && features.all { "asimddp" in it }) DOTPROD else BASELINE

    /** The `Features` line of every processor in a /proc/cpuinfo file; empty if it cannot be read. */
    fun cpuFeatures(cpuinfo: File): List<Set<String>> = try {
        parseFeatures(cpuinfo.readText())
    } catch (e: Exception) {
        emptyList()
    }

    fun parseFeatures(cpuinfo: String): List<Set<String>> =
        cpuinfo.lineSequence()
            .filter { it.startsWith("Features") }
            .map { line -> line.substringAfter(':').trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet() }
            .toList()
}
