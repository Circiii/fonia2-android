package tech.fonia.whisper

import androidx.test.platform.app.InstrumentationRegistry
import tech.fonia.speech.FillerLexicon
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

// FoniaSpeechTests/Support.swift, the parts the device tests use.

/** The lists the web app passes today. */
object TestLexicon {
    val EN = FillerLexicon(
        hesitationLabel = "um",
        fillers = listOf("um", "uh", "er", "erm", "ah", "hmm", "you know", "i mean", "kind of", "sort of", "like", "basically", "actually", "literally", "so", "well", "okay"),
        ambiguous = listOf("like", "so", "well", "okay", "ok", "actually", "i mean", "kind of", "sort of", "you know", "right"),
    )
    val RO = FillerLexicon(
        hesitationLabel = "ăă",
        fillers = listOf("ăă", "ăăă", "îî", "îîî", "mmm", "deci", "practic", "adică", "gen", "de genul", "nu știu", "cum să zic", "cum să spun", "păi", "na", "mă rog", "în fine", "efectiv", "fix", "oarecum", "sincer", "știi", "înțelegi", "like", "basically", "literally"),
        ambiguous = listOf("deci", "practic", "adică", "gen", "nu știu", "fix", "na", "sincer", "efectiv", "oarecum", "știi", "înțelegi"),
    )
}

object SpeechPaths {
    private val arguments get() = InstrumentationRegistry.getArguments()

    /**
     * `FONIA_WHISPER_MODEL` on iOS: the instrumentation argument `foniaWhisperModel`, which
     * whisper/build.gradle.kts sets to the folder its `whisperModelToDevice` task fills. Null when the
     * folder holds no model, which skips the suite as iOS does.
     */
    val modelFolder: File?
        get() = arguments.getString("foniaWhisperModel")?.takeIf { it.isNotEmpty() }?.let(::File)
            ?.takeIf { File(it, WhisperCppModel.MODEL_FILE).canRead() }

    /** `FONIA_SPEECH_DOWNLOAD_TEST=1`: `-Pandroid.testInstrumentationRunnerArguments.foniaSpeechDownloadTest=1`. */
    val runsDownload: Boolean
        get() = arguments.getString("foniaSpeechDownloadTest") == "1"

    fun temporaryFolder(): File {
        val folder = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "fonia-speech-${UUID.randomUUID()}")
        check(folder.mkdirs())
        return folder
    }
}

/**
 * `fixtureSamples`: a 16 kHz mono 16-bit WAV from the test APK's assets (copied from the web app's
 * `e2e/fixtures/speech`), as Float32 the way AVAudioFile converts it (sample / 32768).
 */
fun fixtureSamples(name: String): FloatArray {
    val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("speech/$name.wav").use { it.readBytes() }
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    check(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE") { "$name is not a WAV file" }
    var offset = 12
    var channels = 0
    var rate = 0
    var bits = 0
    while (offset + 8 <= bytes.size) {
        val id = String(bytes, offset, 4, Charsets.US_ASCII)
        val size = buffer.getInt(offset + 4)
        val body = offset + 8
        when (id) {
            "fmt " -> {
                check(buffer.getShort(body).toInt() == 1) { "$name is not PCM" }
                channels = buffer.getShort(body + 2).toInt()
                rate = buffer.getInt(body + 4)
                bits = buffer.getShort(body + 14).toInt()
            }
            "data" -> {
                check(channels == 1 && rate == 16_000 && bits == 16) { "$name is not mono 16 kHz" }
                val count = minOf(size, bytes.size - body) / 2
                return FloatArray(count) { buffer.getShort(body + 2 * it) / 32768f }
            }
        }
        offset = body + size + (size and 1)
    }
    throw IllegalStateException("$name has no data chunk")
}

/** Same near-silent noise as the web check. */
fun quietNoise(count: Int = 32_000): FloatArray {
    var state = 7u
    return FloatArray(count) {
        state = state * 1_664_525u + 1_013_904_223u
        (state.toFloat() / 4_294_967_296f - 0.5f) * 0.002f
    }
}
