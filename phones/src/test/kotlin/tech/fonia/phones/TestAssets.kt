package tech.fonia.phones

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Files the integration tests need but the repository must not hold: the model and the authors' sample, downloaded
 * once into the cache folder Gradle passes as `fonia.models` (`~/.gradle/fonia-models`), checked against their pinned
 * size and SHA-256. `-Pfonia.models.offline=true` (Gradle) turns the download off; a missing file then skips the tests.
 */
object ModelCache {
    /** ZIPA's own example, LibriSpeech dev-clean 1272-128104-0000 (CC BY 4.0), at the pinned commit of `lingjzhu/zipa`. */
    val SAMPLE = ModelSpec(
        name = "lingjzhu/zipa sample.wav",
        url = "https://raw.githubusercontent.com/lingjzhu/zipa/21e9e8aa4cc6c130cb05d836a1979886f9245302/sample.wav",
        sha256 = "799f78ed4beb4de7ceae3a809262d4ce242394342ccd1d58cef7d49dbc2def46",
        bytes = 187_404,
        license = "CC-BY-4.0 (LibriSpeech)",
    )

    private val results = ConcurrentHashMap<String, Result<File>>()

    /** The cached file of [spec], downloaded if needed, or a failure whose message says why it is missing. */
    fun fetch(spec: ModelSpec, folder: String = "zipa-small-crctc-500k"): Result<File> =
        results.getOrPut(spec.url) { runCatching { obtain(spec, folder) } }

    private fun obtain(spec: ModelSpec, folder: String): File {
        val root = System.getProperty("fonia.models") ?: error("fonia.models is not set: run the tests through Gradle")
        val file = File(File(root, folder), spec.fileName)
        if (spec.matches(file)) return file
        check(System.getProperty("fonia.models.offline") != "true") { "${spec.name} is not in $root and downloads are off" }
        file.parentFile.mkdirs()
        val partial = File(file.parentFile, "${file.name}.part")
        val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build()
        val request = HttpRequest.newBuilder(URI(spec.url)).timeout(Duration.ofMinutes(15)).GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofFile(partial.toPath()))
        check(response.statusCode() == 200) { "${spec.url} answered HTTP ${response.statusCode()}" }
        check(spec.matches(partial)) {
            partial.delete()
            "${spec.name}: the download does not match the pinned size and SHA-256"
        }
        Files.move(partial.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        return file
    }
}

/**
 * Opens the model for a test. On Windows the test JVM is the copy of the JDK that `phones/build.gradle.kts` makes with
 * the system's VC++ runtime: ONNX Runtime 1.31 crashes in the older msvcp140.dll Android Studio's JBR ships, and a
 * DLL loaded later from System32 cannot replace the one the JVM loaded at start-up.
 */
fun openModel(file: File, intraOpThreads: Int = 0): ZipaModel = ZipaModel.load(file, intraOpThreads)

/**
 * The recorded Romanian clips of the content submodule (`voice/manifest.json`, key `ro|ro|<text>`), decoded by the
 * ffmpeg command line (the JVM has no MP3 decoder and the module takes no LGPL/GPL one) straight to 16 kHz mono
 * float with the SoX resampler, the closest to the `librosa.resample` (soxr) of ZIPA's own inference script.
 * Decoded audio is cached in `phones/build/voice16k`.
 */
class VoiceClips private constructor(private val root: File, private val manifest: Map<String, String>) {
    val romanianTexts: List<String> get() = manifest.keys.filter { it.startsWith("ro|ro|") }.map { it.substring(6) }.sorted()

    fun has(text: String): Boolean = manifest.containsKey("ro|ro|$text")

    fun decode(text: String): FloatArray? {
        val relative = manifest["ro|ro|$text"] ?: return null
        val file = File(root, relative)
        return if (file.isFile) Ffmpeg.decode16k(file) else null
    }

    companion object {
        fun open(): VoiceClips? {
            val content = System.getProperty("fonia.content") ?: return null
            val root = File(content, "voice")
            val manifestFile = File(root, "manifest.json")
            if (!manifestFile.isFile) return null
            val json = Json.parseToJsonElement(manifestFile.readText(Charsets.UTF_8)).jsonObject
            return VoiceClips(root, json.mapValues { it.value.jsonPrimitive.content })
        }
    }
}

object Ffmpeg {
    val available: Boolean by lazy {
        runCatching {
            val process = ProcessBuilder("ffmpeg", "-hide_banner", "-version").redirectErrorStream(true).start()
            process.inputStream.readBytes()
            process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0
        }.getOrDefault(false)
    }

    private val cache: File by lazy { File("build/voice16k").also { it.mkdirs() } }
    private const val MAGIC = 0x50483136 // "PH16"

    fun decode16k(file: File): FloatArray? {
        val cached = File(cache, "${file.nameWithoutExtension}-${file.length()}.f32")
        if (cached.isFile) {
            runCatching {
                DataInputStream(cached.inputStream().buffered()).use { input ->
                    check(input.readInt() == MAGIC)
                    val bytes = ByteArray(input.readInt() * 4)
                    input.readFully(bytes)
                    return floats(bytes)
                }
            }
        }
        val samples = run(file, "pan=mono|c0=c0,aresample=16000:resampler=soxr")
            ?: run(file, "pan=mono|c0=c0,aresample=16000")
            ?: return null
        val partial = File(cache, "${cached.name}.${ProcessHandle.current().pid()}.tmp")
        DataOutputStream(partial.outputStream().buffered()).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(samples.size)
            val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            bytes.asFloatBuffer().put(samples)
            out.write(bytes.array())
        }
        if (cached.exists()) cached.delete()
        if (!partial.renameTo(cached)) partial.delete()
        return samples
    }

    private fun run(file: File, filter: String): FloatArray? {
        val process = ProcessBuilder(
            "ffmpeg", "-hide_banner", "-nostdin", "-loglevel", "error", "-i", file.absolutePath,
            "-map", "0:a:0", "-af", filter, "-f", "f32le", "-acodec", "pcm_f32le", "-",
        ).start()
        val errors = ByteArrayOutputStream()
        val reader = Thread { process.errorStream.copyTo(errors) }.also { it.start() }
        val bytes = process.inputStream.readBytes()
        reader.join()
        if (!process.waitFor(2, TimeUnit.MINUTES) || process.exitValue() != 0) return null
        return floats(bytes)
    }

    private fun floats(bytes: ByteArray): FloatArray {
        val out = FloatArray(bytes.size / 4)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
        return out
    }
}

/** 16-bit PCM WAV to floats on soundfile's scale (sample / 32768), as ZIPA's inference script reads it. */
fun readWav16(bytes: ByteArray): Pair<FloatArray, Int> {
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    require(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE")
    var offset = 12
    var rate = 0
    var bits = 0
    var channels = 1
    while (offset + 8 <= bytes.size) {
        val id = String(bytes, offset, 4, Charsets.US_ASCII)
        val size = buffer.getInt(offset + 4)
        if (id == "fmt ") {
            channels = buffer.getShort(offset + 10).toInt()
            rate = buffer.getInt(offset + 12)
            bits = buffer.getShort(offset + 22).toInt()
        } else if (id == "data") {
            require(bits == 16) { "only 16-bit PCM" }
            val frames = size / 2 / channels
            return FloatArray(frames) { buffer.getShort(offset + 8 + it * 2 * channels) / 32768f } to rate
        }
        offset += 8 + size + (size and 1)
    }
    error("no data chunk")
}
