package tech.fonia.speaking

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import tech.fonia.live.Clip
import tech.fonia.speech.SpeechLanguage
import tech.fonia.speech.SpeechResampler
import tech.fonia.speech.Transcriber
import tech.fonia.speech.Transcript
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Capture and playback as the speaking screens see them. `:app` implements it on `OboeAudioDirector` /
 * `PlaybackEngine` (FxGate rules apply: nothing plays while the mic is open); [FakeSpeakingAudio] serves previews
 * and tests. Nothing here leaves the device.
 */
interface SpeakingAudio {
    /**
     * Opens the mic and records until [stopRecording], [maxSeconds], or cancellation, then returns the take (mono,
     * capture rate, no noise reduction). [onLevel] gets ~30–100 updates a second from any thread: level 0–1 and
     * whether the VAD hears speech.
     */
    suspend fun record(maxSeconds: Double, onLevel: (level: Float, speaking: Boolean) -> Unit): Clip

    /** Ends a running [record] early (it then returns what it has). Safe to call from any thread, or when idle. */
    fun stopRecording()

    /** Plays [samples] from [fromSeconds] and returns at the end, or when [stopPlayback] is called. False if it did not play. */
    suspend fun play(samples: FloatArray, sampleRate: Double, fromSeconds: Double = 0.0): Boolean

    fun stopPlayback()

    /** Seconds into the buffer being played (what the listener hears now, latency included), or NaN when idle. Polled every frame. */
    fun playbackSeconds(): Double
}

/**
 * The analysis pipeline for one take: optional Whisper transcription (verbatim, so fillers are written out), then
 * [SpeakingAnalyzer]. CPU-bound: call it from a background dispatcher. With no [transcriber], or when it fails or
 * hears nothing, the take is analysed from the sound alone.
 */
class SpeakingPipeline(
    val analyzer: SpeakingAnalyzer = SpeakingAnalyzer(),
    private val transcriber: Transcriber? = null,
    private val language: SpeechLanguage = SpeechLanguage.RO,
) {
    val thresholds: SpeakingThresholds get() = analyzer.thresholds

    suspend fun transcribe(clip: Clip): Transcript? {
        val t = transcriber ?: return null
        return try {
            val x = SpeechResampler.resample(clip.samples, clip.sampleRate)
            t.transcribe(x, language, verbatim = true).takeIf { it.words.isNotEmpty() && it.noSpeechProb < 0.8 }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    suspend fun analyze(clip: Clip): SpeakingAnalysis = analyzer.analyze(clip, transcribe(clip))

    fun clean(clip: Clip, analysis: SpeakingAnalysis, cuts: Set<Int> = analysis.defaultCuts): CleanResult =
        CleanCut.render(clip, analysis, cuts, analyzer.thresholds)
}

/**
 * Plays back a canned take as if it were the microphone, and "plays" by advancing a clock. [realtime] false makes
 * both instant (JVM tests); with kotlinx-coroutines-test virtual time either works.
 */
class FakeSpeakingAudio(
    private val take: Clip,
    private val realtime: Boolean = true,
    private val stepMillis: Long = 20,
) : SpeakingAudio {
    @Volatile private var stopRec = false
    @Volatile private var stopPlay = false
    @Volatile private var position = Double.NaN
    @Volatile var recordings = 0
        private set
    @Volatile var playbacks = 0
        private set

    override suspend fun record(maxSeconds: Double, onLevel: (Float, Boolean) -> Unit): Clip {
        stopRec = false
        recordings++
        val sr = take.sampleRate
        val step = (sr * stepMillis / 1000).toInt().coerceAtLeast(1)
        val limit = min(take.samples.size, (maxSeconds * sr).toInt())
        var n = 0
        while (n < limit && !stopRec) {
            val end = min(limit, n + step)
            var s = 0.0
            for (k in n until end) s += take.samples[k] * take.samples[k]
            val rms = sqrt(s / (end - n))
            val level = ((20 * kotlin.math.log10(rms + 1e-9) + 60) / 50).toFloat().coerceIn(0f, 1f)
            onLevel(level, level > 0.3f)
            n = end
            if (realtime) delay(stepMillis)
        }
        return Clip(take.samples.copyOfRange(0, n), sr, audioSource = "fixture")
    }

    override fun stopRecording() { stopRec = true }

    override suspend fun play(samples: FloatArray, sampleRate: Double, fromSeconds: Double): Boolean {
        stopPlay = false
        playbacks++
        val duration = samples.size / sampleRate
        var t = fromSeconds
        try {
            while (t < duration && !stopPlay) {
                position = t
                if (realtime) delay(stepMillis)
                t += stepMillis / 1000.0
            }
        } finally {
            position = Double.NaN
        }
        return true
    }

    override fun stopPlayback() { stopPlay = true }

    override fun playbackSeconds(): Double = position
}
