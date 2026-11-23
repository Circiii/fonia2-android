package tech.fonia.audio

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import tech.fonia.live.BaseAudioDirector
import tech.fonia.live.Clip
import tech.fonia.live.Earcon
import tech.fonia.live.FrameLatencyMeter
import tech.fonia.live.FxGate
import tech.fonia.live.LiveBoard
import tech.fonia.live.LiveCapture
import tech.fonia.live.LiveConfig
import tech.fonia.live.LivePipeline
import tech.fonia.live.LiveStats
import tech.fonia.live.LiveTarget
import tech.fonia.live.NanoClock
import tech.fonia.live.PromptRef
import tech.fonia.live.QuietRule
import tech.fonia.live.SuspendReason
import tech.fonia.live.CaptureInfo as LiveCaptureInfo

/**
 * The real [tech.fonia.live.AudioDirector]: Oboe capture (UNPROCESSED when the phone supports it, else
 * VOICE_RECOGNITION, platform effects off — see [MicrophoneSource]) feeding a [LivePipeline] on its own
 * `fonia-analysis` thread at `THREAD_PRIORITY_AUDIO`, plus [PlaybackEngine] for prompts and [SoundEffects] for
 * earcons. The quiet rule is enforced by the shared state machine and [FxGate].
 *
 * The app owns one instance (process scope) and hooks lifecycle into it: `ProcessLifecycleOwner` ON_STOP →
 * [suspendAll] with [SuspendReason.Background]; audio focus loss → [SuspendReason.FocusLost]; back in front → [resume].
 */
class OboeAudioDirector(
    context: Context,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    config: LiveConfig = LiveConfig(),
    rule: QuietRule = QuietRule(),
    board: LiveBoard = LiveBoard(),
    private val playback: PlaybackEngine = PlaybackEngine(context.applicationContext),
    private val effects: SoundEffects? = null,
    /** The source of each take: the microphone, or a [FixtureSource] in the fixture mode (`fonia.fixture.audio`). */
    private val sourceFor: (LiveTarget) -> CaptureSource = { MicrophoneSource(context.applicationContext) },
    /** Where asset and file prompts live; the default reads app assets and plain files. */
    private val resolve: (PromptRef) -> ClipLocation? = defaultResolver(context.applicationContext),
    /** Receives the playing clip's loudness (`talk`) for a mouth that follows the model voice. */
    val levels: LiveLevels = LiveLevels(),
) : BaseAudioDirector(board, FxGate(rule, NanoClock.System), NanoClock.System, scope) {

    /** The continuous band's writer. Its [LivePipeline.postMouth] takes the face engine's output. */
    val pipeline = LivePipeline(board, config)

    /** Feed it from the frame loop that reads the board (`meter.onFrame(frameNanos, snapshot)`); read it in the overlay. */
    val frameMeter = FrameLatencyMeter()

    private val capture = LiveCapture(pipeline, threadFactory = ::audioPriorityThread)

    private class Take(val ring: CaptureRing, val source: CaptureSource, val pcm: RingPcmSource, val info: LiveCaptureInfo)

    @Volatile private var take: Take? = null

    /** Numbers for the debug latency overlay (docs/v2/02 §4). Allocates; call a few times per second at most. */
    data class LiveMetrics(
        val capture: LiveCaptureInfo?,
        /** Latest input latency Oboe reported (mic → callback, includes the burst), ms. */
        val inputLatencyMillis: Double?,
        /** Analysis time per hop and capture → publish latency. */
        val analysis: LiveStats.Summary,
        /** Age of the snapshot when a frame drew it (`vsync − captureNanos`), from [frameMeter]. */
        val frames: FrameLatencyMeter.Summary,
        val dropped: Int,
    )

    fun metrics(): LiveMetrics {
        val current = take
        return LiveMetrics(
            capture = current?.info ?: captureInfo,
            inputLatencyMillis = current?.pcm?.lastLatencyMillis,
            analysis = pipeline.stats.summary(),
            frames = frameMeter.summary(),
            dropped = current?.ring?.dropped ?: 0,
        )
    }

    override suspend fun startCapture(target: LiveTarget): LiveCaptureInfo {
        val ring = CaptureRing(RING_CAPACITY)
        val source = sourceFor(target)
        val rate = try {
            source.start(ring)
        } catch (t: Throwable) {
            ring.close()
            throw t
        }
        val mic = source as? MicrophoneSource
        val info = mic?.captureInfo?.toLive(mic.inputLatencyMillis())
            ?: LiveCaptureInfo(sampleRate = rate, audioSource = "fixture", requestedSource = "fixture")
        val pcm = RingPcmSource(ring, source, { mic?.inputLatencyMillis() }, info.audioSource)
        pipeline.narrowBand = info.isNarrowBand
        CaptureActivity.began()
        try {
            capture.start(pcm, target)
        } catch (t: Throwable) {
            CaptureActivity.ended()
            withContext(NonCancellable) { source.stop() }
            ring.close()
            throw t
        }
        take = Take(ring, source, pcm, info)
        return info
    }

    override suspend fun stopCapture(): Clip {
        val current = take ?: return Clip.Empty
        take = null
        try {
            // The source stops writing first, so the analysis thread's last drain misses nothing.
            current.source.stop()
            return withContext(Dispatchers.IO) { capture.stop() }
        } finally {
            CaptureActivity.ended()
            current.ring.close()
        }
    }

    override suspend fun playPrompt(prompt: PromptRef): Boolean = when (prompt) {
        is PromptRef.Take -> playback.play(prompt.clip.toCapturedClip(), levels)
        else -> resolve(prompt)?.let { playback.play(it, levels) } ?: false
    }

    override fun playEarcon(earcon: Earcon) {
        effects?.play(
            when (earcon) {
                Earcon.Correct -> SoundEffects.Effect.Correct
                Earcon.Almost -> SoundEffects.Effect.Almost
                Earcon.Pop -> SoundEffects.Effect.Pop
                Earcon.Star -> SoundEffects.Effect.Star
                Earcon.Unlock -> SoundEffects.Effect.Unlock
                Earcon.LevelUp -> SoundEffects.Effect.LevelUp
            },
        )
    }

    override fun applyTarget(target: LiveTarget) = pipeline.setTarget(target)

    /** Closes the mic and frees the player. */
    suspend fun release() {
        closeMic()
        playback.release()
    }

    companion object {
        /** About 2.7 s at 48 kHz: the analysis thread drains every ≤ 2 ms, so this only matters if it stalls. */
        const val RING_CAPACITY = 1 shl 17

        fun defaultResolver(context: Context): (PromptRef) -> ClipLocation? = { prompt ->
            when (prompt) {
                is PromptRef.Asset -> ClipLocation.Asset(context.assets, prompt.path)
                is PromptRef.File -> ClipLocation.File(java.io.File(prompt.path))
                is PromptRef.Take -> null
            }
        }
    }
}
