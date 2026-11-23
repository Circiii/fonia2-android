package tech.fonia.dsp

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Decodes the bundled voice clips for evaluation, standing in for Swift's `VoiceClipLibrary`.
 *
 * The clips come from the content submodule (`fonia.content`/voice), keyed like iOS's
 * `Resources/VoiceClips/manifest.json`. The JVM has no MP3 decoder, and the module takes no
 * LGPL or GPL one, so the ffmpeg command line decodes each clip once to Float32 at the file's own
 * rate, first channel, as AVAudioFile's processing format gives it on iOS. Decoded PCM is cached
 * under `dsp/build/voice-pcm`.
 *
 * Alignment matters: frames fall on different samples otherwise, and the thresholds were tuned on
 * AVAudioFile's output. AVAudioFile drops the MP3 decoder's own 529-sample delay but keeps the
 * encoder delay the LAME header declares (576 samples on these clips), which ffmpeg's gapless
 * trimming would also remove. So ffmpeg decodes untrimmed (`-flags2 +skip_manual`) and the first
 * 529 samples are dropped here. Checked against iOS's `Tests/eval-report.md`: with ffmpeg's default
 * trimming about 50 report lines differ, with this alignment two do (see `dsp/PARITY.md`).
 */
class VoiceClipLibrary private constructor(val root: File, val manifest: Map<String, String>) {
    class Clip(val pcm: FloatArray, val sampleRate: Double)

    /** Romanian texts, without the key prefix, in Swift's String order. */
    val romanianTexts: List<String>
        get() = manifest.keys.filter { it.startsWith("ro|ro|") }.map { it.substring(6) }.sortedWith(swiftStringOrder)

    fun decode(text: String): Clip? {
        val relative = manifest["ro|ro|$text"] ?: return null
        val file = File(root, relative)
        if (!file.isFile) return null
        return Ffmpeg.decodeCached(file)
    }

    /** Decodes every clip of `texts` that is not cached yet, several ffmpeg processes at a time. */
    fun prefetch(texts: List<String>) {
        val files = texts.mapNotNull { manifest["ro|ro|$it"] }.map { File(root, it) }.filter { it.isFile }
        val pool = Executors.newFixedThreadPool(maxOf(2, Runtime.getRuntime().availableProcessors() / 2))
        try {
            pool.invokeAll(files.map { Callable { Ffmpeg.decodeCached(it) } })
        } finally {
            pool.shutdown()
            pool.awaitTermination(10, TimeUnit.MINUTES)
        }
    }

    companion object {
        /** `nil` in Swift when the manifest is missing; here also when `fonia.content` is not set. */
        fun open(): VoiceClipLibrary? {
            val content = System.getProperty("fonia.content") ?: return null
            val root = File(content, "voice")
            val manifestFile = File(root, "manifest.json")
            if (!manifestFile.isFile) return null
            val parsed = runCatching { Json.parse(manifestFile.readText(Charsets.UTF_8)) }.getOrNull() as? Map<*, *> ?: return null
            val manifest = LinkedHashMap<String, String>()
            for ((key, value) in parsed) {
                if (key is String && value is String) manifest[key] = value
            }
            return VoiceClipLibrary(root, manifest)
        }
    }
}

/** The ffmpeg command line, found on PATH. */
object Ffmpeg {
    val available: Boolean by lazy {
        runCatching {
            val process = ProcessBuilder("ffmpeg", "-hide_banner", "-version").redirectErrorStream(true).start()
            process.inputStream.readBytes()
            process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0
        }.getOrDefault(false)
    }

    private val cacheDirectory: File by lazy {
        val build = System.getProperty("fonia.dsp.build") ?: "build"
        File(build, "voice-pcm").also { it.mkdirs() }
    }

    /** Decodes `file`, or reads the PCM a previous run cached for the same file size. */
    fun decodeCached(file: File): VoiceClipLibrary.Clip? {
        val cache = File(cacheDirectory, "${file.nameWithoutExtension}-${file.length()}.f32")
        if (cache.isFile) {
            runCatching { return readCache(cache) }
        }
        val clip = decode(file) ?: return null
        val partial = File(cacheDirectory, "${cache.name}.${java.util.UUID.randomUUID()}.tmp")
        DataOutputStream(partial.outputStream().buffered()).use { out ->
            out.writeInt(MAGIC)
            out.writeDouble(clip.sampleRate)
            out.writeInt(clip.pcm.size)
            val bytes = ByteBuffer.allocate(clip.pcm.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            bytes.asFloatBuffer().put(clip.pcm)
            out.write(bytes.array())
        }
        // A stale cache from an older decoding is replaced; Windows will not rename over a file.
        if (cache.exists()) cache.delete()
        if (!partial.renameTo(cache)) partial.delete()
        return clip
    }

    private fun readCache(cache: File): VoiceClipLibrary.Clip =
        DataInputStream(cache.inputStream().buffered()).use { input ->
            check(input.readInt() == MAGIC)
            val sampleRate = input.readDouble()
            val count = input.readInt()
            val bytes = ByteArray(count * 4)
            input.readFully(bytes)
            val pcm = FloatArray(count)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(pcm)
            VoiceClipLibrary.Clip(pcm, sampleRate)
        }

    // Bumped whenever the decoding below changes, so an old cache is not read back.
    private const val MAGIC = 0x46504332 // "FPC2"

    /** The MP3 decoder's own delay (528 + 1 samples), which AVAudioFile removes. */
    private const val DECODER_DELAY = 529

    private val sampleRatePattern = Regex("""Audio:[^\n]*?(\d+) Hz""")

    /**
     * Float32 little-endian at the file's own rate, first channel only, aligned as AVAudioFile
     * delivers it: no gapless trimming (`skip_manual`), then the decoder delay dropped.
     */
    fun decode(file: File): VoiceClipLibrary.Clip? {
        val process = ProcessBuilder(
            "ffmpeg", "-hide_banner", "-nostdin", "-flags2", "+skip_manual", "-i", file.absolutePath,
            "-map", "0:a:0", "-af", "pan=mono|c0=c0", "-f", "f32le", "-acodec", "pcm_f32le", "-",
        ).start()
        val errors = ByteArrayOutputStream()
        val errorReader = Thread { process.errorStream.copyTo(errors) }.also { it.start() }
        val bytes = process.inputStream.readBytes()
        errorReader.join()
        if (!process.waitFor(2, TimeUnit.MINUTES) || process.exitValue() != 0) return null
        val log = errors.toString(Charsets.UTF_8)
        val sampleRate = sampleRatePattern.find(log)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        val decoded = FloatArray(bytes.size / 4)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(decoded)
        val pcm = if (decoded.size > DECODER_DELAY) decoded.copyOfRange(DECODER_DELAY, decoded.size) else FloatArray(0)
        return VoiceClipLibrary.Clip(pcm, sampleRate)
    }
}
