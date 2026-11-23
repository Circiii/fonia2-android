package tech.fonia.audio

import android.os.Process
import tech.fonia.live.Clip
import tech.fonia.live.LivePipeline
import tech.fonia.live.NanoClock
import tech.fonia.live.PcmSource
import java.util.concurrent.locks.LockSupport
import tech.fonia.live.CaptureInfo as LiveCaptureInfo

// Bridges between this module's capture types and the pure continuous band in `:engine:live`. Everything here is
// additive: the old API (`CaptureEngine`, `HopProcessor`, `LiveLevels`, `CapturedClip`) is unchanged.

/** The pure twin of a take. Shares the sample array. */
fun CapturedClip.toClip(audioSource: String = ""): Clip = Clip(samples, sampleRate, dropped, audioSource = audioSource)

/** Back to the old type, for `PlaybackEngine.play(clip, …)` and `RecordingWriter`. Shares the sample array. */
fun Clip.toCapturedClip(): CapturedClip = CapturedClip(samples, sampleRate, dropped)

/** The raw name saved with each attempt: `unprocessed`, `voiceRecognition`, or `other`. */
val InputPreset?.rawName: String
    get() = when (this) {
        InputPreset.Unprocessed -> "unprocessed"
        InputPreset.VoiceRecognition -> "voiceRecognition"
        null -> "other"
    }

fun CaptureInfo.toLive(inputLatencyMillis: Double? = null): LiveCaptureInfo = LiveCaptureInfo(
    sampleRate = sampleRate,
    audioSource = inputPreset.rawName,
    requestedSource = requestedPreset.rawName,
    deviceType = deviceType,
    isLowLatency = isLowLatency,
    framesPerBurst = framesPerBurst,
    isNarrowBand = isNarrowBand,
    inputLatencyMillis = inputLatencyMillis,
    platformEffectsSwitchedOff = platformEffectsSwitchedOff,
)

/**
 * Lets the old [CaptureEngine] drive a [LivePipeline] (and keeps mirroring into [LiveLevels] for legacy screens).
 * The capture time is the drain time, because `CaptureEngine` polls every 10 ms; prefer [OboeAudioDirector], whose
 * analysis thread knows the real capture clock. Call [LivePipeline.start] from the `makeProcessor` lambda.
 */
fun LivePipeline.asHopProcessor(clock: NanoClock = NanoClock.System): HopProcessor = HopProcessor { samples, offset, count, levels ->
    process(samples, offset, count, clock.nanos())
    levels.publishFrom(this)
}

/** Copies the newest published values into the old volatile holder. Writer thread only. */
internal fun LiveLevels.publishFrom(pipeline: LivePipeline) {
    val s = pipeline.lastWritten
    publish(
        level = s.level,
        rms = s.rms,
        pitchHz = s.pitchHz,
        clarity = s.voicing,
        sNess = s.sNess,
        fricativeStrength = s.sibilance,
        trillStrength = s.trill,
        blowStrength = s.blow,
        isFricative = s.fricative,
    )
}

/**
 * The live [PcmSource] over a capture ring: the Oboe callback writes into [ring] (C++, no JNI on the audio thread);
 * the analysis thread reads here. The capture clock is `now − Oboe's input latency`, refreshed every 100 ms
 * (docs/v2/02 §4), or the drain time when the stream cannot tell (fixture sources, emulators without timestamps).
 */
internal class RingPcmSource(
    private val ring: CaptureRing,
    private val source: CaptureSource,
    private val latencyMillis: () -> Double?,
    override val audioSource: String,
    private val clock: NanoClock = NanoClock.System,
) : PcmSource {
    private var newest = 0L
    private var latencyNanos = 0L
    private var latencyCheckedAt = Long.MIN_VALUE

    @Volatile var lastLatencyMillis: Double? = null
        private set

    override val sampleRate: Double get() = source.sampleRate

    override fun read(dst: FloatArray, offset: Int, count: Int): Int {
        val n = ring.read(dst, offset, count)
        if (n > 0) {
            val now = clock.nanos()
            if (latencyCheckedAt == Long.MIN_VALUE || now - latencyCheckedAt >= LATENCY_REFRESH_NANOS) {
                latencyCheckedAt = now
                val ms = latencyMillis()
                lastLatencyMillis = ms
                latencyNanos = if (ms != null && ms >= 0.0) (ms * 1e6).toLong() else 0L
            }
            newest = now - latencyNanos
        }
        return n
    }

    override fun newestCaptureNanos(): Long = newest

    override val dropped: Int get() = ring.dropped

    override fun awaitData(maxNanos: Long) {
        LockSupport.parkNanos(maxNanos)
    }

    private companion object {
        const val LATENCY_REFRESH_NANOS = 100_000_000L
    }
}

/** Threads for the analysis loop at `THREAD_PRIORITY_AUDIO`, out of the way of models and the UI. */
internal fun audioPriorityThread(runnable: Runnable): Thread = Thread({
    runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
    runnable.run()
}, tech.fonia.live.LiveCapture.THREAD_NAME).apply { isDaemon = true }
