package tech.fonia.audio

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.wav.WavExtractor
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Plays model voices and takes. Clips are decoded to PCM ahead of time, so they start at once and the
 * mouth envelope is computed from exactly the samples that play.
 *
 * iOS: `AVAudioPlayerNode` → `AVAudioUnitTimePitch` → mixer. Android: Media3 ExoPlayer with an audio-only
 * renderer, playing each clip as 16-bit PCM WAV from memory; speed changes keep the pitch through
 * `PlaybackParameters(speed, 1f)` (Sonic). It never opens the microphone, and starting a clip ends the one
 * before it. The engine is an actor on its own thread, which is also ExoPlayer's application thread.
 */
@OptIn(UnstableApi::class)
class PlaybackEngine internal constructor(context: Context, private val outputVolume: Float) {
    constructor(context: Context) : this(context, 1f)

    private class Prepared(val wav: ByteArray, val frames: Int, val sampleRate: Double, val envelope: LoudnessEnvelope?)

    private class Graph(val player: ExoPlayer) {
        /** The schedule the player is playing, so a stale end is ignored. */
        var scheduleId = 0
    }

    private class Playing(
        var scheduleId: Int,
        val clip: Prepared,
        val levels: LiveLevels?,
        val continuation: CancellableContinuation<Boolean>,
        /** Where in the clip the player's position 0 sits. Not zero after a rebuild mid-clip. */
        var startFrame: Long,
        var playedFrames: Long,
        var deadlineNanos: Long,
        val ticker: Job,
    ) {
        /** The player reached READY for this clip, so a later error is an end, not a failure to start. */
        var started = false
        var failedToStart = false
    }

    private companion object {
        const val TAG = "Playback"
        const val CACHE_LIMIT = 64
        const val TICK_INTERVAL_MS = 16L

        /** A clip that overruns by this much ends anyway, so a lost completion cannot hang a lesson. */
        const val OVERRUN_ALLOWANCE_NANOS = 2_000_000_000L

        /**
         * iOS subtracts `outputNode.presentationLatency` from the rendered position. Media3's position already
         * is the presented one (it comes from `AudioTrack` timestamps, or the playback head minus the track's
         * latency), so the same correction is in it and nothing more is subtracted.
         */
        const val EXTRA_PRESENTATION_LATENCY = 0.0
    }

    private val appContext = context.applicationContext
    private val thread = HandlerThread("FoniaPlayback").apply { start() }
    private val dispatcher = Handler(thread.looper).asCoroutineDispatcher("FoniaPlayback")
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private var graph: Graph? = null
    private var speed = 1f
    private var observer: AudioGraphObserver? = null

    /** Decoded clips with their envelopes, least recently used first. */
    private val cache = LinkedHashMap<ClipLocation, Prepared>()

    private var playing: Playing? = null
    private var nextScheduleId = 0
    private var latestRequest = 0

    /** Decodes clips ahead of use. Clips that cannot be decoded are skipped here and reported by [play]. */
    suspend fun preload(urls: List<ClipLocation>) = withContext(dispatcher) {
        for (url in urls) if (cache[url] == null) prepared(url)
    }

    /**
     * Suspends until the clip ends or is stopped, publishing its loudness into `levels.talk` in time with playback.
     * Returns false when the clip could not be decoded or the player could not start, so a caller can fall back
     * to speech. Cancelling the caller ends the clip.
     */
    suspend fun play(url: ClipLocation, levels: LiveLevels?): Boolean = withContext(dispatcher) {
        val request = beginRequest()
        val clip = prepared(url) ?: return@withContext false
        if (request != latestRequest) return@withContext true
        run(clip, levels)
    }

    /** Plays a take back so the child can hear themselves. */
    suspend fun play(clip: CapturedClip, levels: LiveLevels?): Boolean = withContext(dispatcher) {
        val request = beginRequest()
        val prepared = prepare(DecodedAudio(clip.samples, clip.sampleRate)) ?: return@withContext false
        if (request != latestRequest) return@withContext true
        run(prepared, levels)
    }

    /** Plays part of a take. Times are clamped to the clip, and an empty range returns at once. */
    suspend fun playRange(clip: CapturedClip, from: Double, to: Double): Boolean = withContext(dispatcher) {
        beginRequest()
        fun frame(time: Double): Int =
            (minOf(maxOf(0.0, time), clip.duration) * clip.sampleRate).roundedAwayFromZero().toInt()
        val lower = frame(from)
        val upper = frame(to)
        if (lower >= upper) return@withContext true
        val wav = PcmWav.mono(clip.samples, lower, upper, clip.sampleRate) ?: return@withContext false
        run(Prepared(wav, upper - lower, clip.sampleRate, envelope = null), levels = null)
    }

    /** How fast the next clips play, 1 being normal. The pitch stays (`PlaybackParameters(speed, 1f)`). */
    suspend fun setSpeed(value: Float) = withContext(dispatcher) {
        speed = value
        graph?.player?.playbackParameters = PlaybackParameters(value, 1f)
    }

    /** Ends the current clip and releases the player. The next [play] makes it again. */
    suspend fun stop() = withContext(dispatcher) {
        beginRequest()
        finishPlaying()
        tearDownGraph()
    }

    /** Stops and ends the engine's thread. Not on iOS, where the actor goes away with its last reference. */
    suspend fun release() {
        withContext(dispatcher) {
            beginRequest()
            finishPlaying()
            tearDownGraph()
            observer?.close()
            observer = null
        }
        scope.cancel()
        thread.quitSafely()
    }

    /** iOS `handle(_:)`, reachable from tests. */
    internal suspend fun handle(event: AudioGraphEvent) = withContext(dispatcher) { handleEvent(event) }

    /** Makes older requests still waiting on decoding give up, so the latest call wins. */
    private fun beginRequest(): Int {
        latestRequest += 1
        return latestRequest
    }

    private suspend fun prepared(url: ClipLocation): Prepared? {
        cache.remove(url)?.let { cached ->
            cache[url] = cached
            return cached
        }
        return try {
            val audio = AudioDecoder.decodeMono(url)
            val clip = prepare(audio) ?: return null
            cache.remove(url)
            cache[url] = clip
            if (cache.size > CACHE_LIMIT) cache.remove(cache.keys.first())
            clip
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Cannot decode ${url.name}: ${e.message}")
            null
        }
    }

    private suspend fun prepare(audio: DecodedAudio): Prepared? {
        val envelope = withContext(Dispatchers.Default) { LoudnessEnvelope(audio.samples, audio.sampleRate) }
        val wav = PcmWav.mono(audio.samples, 0, audio.samples.size, audio.sampleRate) ?: return null
        return Prepared(wav, audio.samples.size, audio.sampleRate, envelope)
    }

    private suspend fun run(clip: Prepared, levels: LiveLevels?): Boolean {
        finishPlaying()
        val player = try {
            readyPlayer()
        } catch (e: Exception) {
            Log.e(TAG, "Playback engine failed to start: ${e.message}")
            return false
        }
        val request = latestRequest
        return suspendCancellableCoroutine { continuation ->
            beginPlaying(clip, player, levels, continuation)
            continuation.invokeOnCancellation { scope.launch { cancelRequest(request) } }
        }
    }

    private fun cancelRequest(request: Int) {
        if (request != latestRequest) return
        finishPlaying()
    }

    private fun beginPlaying(clip: Prepared, player: ExoPlayer, levels: LiveLevels?, continuation: CancellableContinuation<Boolean>) {
        val scheduleId = schedule(clip.wav, player)
        val ticker = scope.launch {
            while (isActive) {
                tick()
                delay(TICK_INTERVAL_MS)
            }
        }
        playing = Playing(
            scheduleId = scheduleId,
            clip = clip,
            levels = levels,
            continuation = continuation,
            startFrame = 0,
            playedFrames = 0,
            deadlineNanos = deadline(clip.frames.toLong(), clip.sampleRate),
            ticker = ticker,
        )
    }

    /** Hands a clip to the player. The id ties the end to this schedule, so a stale one is ignored. */
    private fun schedule(wav: ByteArray, player: ExoPlayer): Int {
        nextScheduleId += 1
        val scheduleId = nextScheduleId
        graph?.scheduleId = scheduleId
        player.setMediaSource(mediaSource(wav))
        player.prepare()
        player.play()
        return scheduleId
    }

    private fun mediaSource(wav: ByteArray): MediaSource = ProgressiveMediaSource.Factory(
        DataSource.Factory { ByteArrayDataSource(wav) },
        ExtractorsFactory { arrayOf<Extractor>(WavExtractor()) },
    ).createMediaSource(MediaItem.fromUri(Uri.parse("fonia://clip")))

    private fun deadline(frames: Long, sampleRate: Double): Long {
        val seconds = frames / sampleRate / speed
        return System.nanoTime() + (seconds * 1e9).toLong() + OVERRUN_ALLOWANCE_NANOS
    }

    private fun clipEnded(scheduleId: Int) {
        if (playing?.scheduleId != scheduleId) return
        finishPlaying()
    }

    private fun finishPlaying() {
        val finished = playing ?: return
        playing = null
        graph?.player?.stop()
        finished.ticker.cancel()
        finished.levels?.publishTalk(0f)
        if (finished.continuation.isActive) finished.continuation.resume(!finished.failedToStart)
    }

    private fun tick() {
        val current = playing ?: return
        val graph = graph ?: return
        val rate = current.clip.sampleRate
        current.playedFrames = current.startFrame + (graph.player.currentPosition / 1000.0 * rate).toLong()
        val levels = current.levels
        val envelope = current.clip.envelope
        if (levels != null && envelope != null) {
            val rendered = current.playedFrames / rate
            levels.publishTalk(envelope.value(rendered - EXTRA_PRESENTATION_LATENCY))
        }
        if (System.nanoTime() > current.deadlineNanos) {
            Log.e(TAG, "A clip overran its duration and was ended")
            finishPlaying()
        }
    }

    /** The player, made if there is none, at the current speed. */
    private fun readyPlayer(): ExoPlayer {
        val current = graph ?: makeGraph().also { graph = it }
        current.player.playbackParameters = PlaybackParameters(speed, 1f)
        return current.player
    }

    private fun makeGraph(): Graph {
        if (observer == null) startObserving()
        val renderers = RenderersFactory { handler, _, audioListener, _, _ ->
            arrayOf<Renderer>(
                MediaCodecAudioRenderer(
                    appContext,
                    MediaCodecSelector.DEFAULT,
                    handler,
                    audioListener,
                    DefaultAudioSink.Builder(appContext).build(),
                ),
            )
        }
        val player = ExoPlayer.Builder(appContext, renderers).setLooper(thread.looper).build()
        // Audio focus belongs to AudioSessionController, as the session does on iOS.
        player.setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
            false,
        )
        player.volume = outputVolume
        val graph = Graph(player)
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (this@PlaybackEngine.graph !== graph) return
                when (playbackState) {
                    Player.STATE_READY -> playing?.takeIf { it.scheduleId == graph.scheduleId }?.started = true
                    Player.STATE_ENDED -> clipEnded(graph.scheduleId)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (this@PlaybackEngine.graph !== graph) return
                Log.e(TAG, "Playback failed: ${error.errorCodeName}", error)
                playing?.let { if (!it.started) it.failedToStart = true }
                // A dead audio track is Android's lost media services: the clip ends, the next play builds anew.
                scope.launch { handleEvent(AudioGraphEvent.MediaServicesLost) }
            }
        })
        return graph
    }

    private fun tearDownGraph() {
        graph?.player?.release()
        graph = null
    }

    private fun startObserving() {
        val observer = AudioGraphObserver(appContext)
        this.observer = observer
        scope.launch {
            for (event in observer.events) handleEvent(event)
        }
    }

    private fun handleEvent(event: AudioGraphEvent) {
        when (event) {
            AudioGraphEvent.ConfigurationChanged, AudioGraphEvent.MediaServicesReset -> rebuildAndContinue()
            // A running player has followed the route by itself; only one that stopped is rebuilt.
            AudioGraphEvent.RouteChanged -> if (graph?.let { isRunning(it.player) } == false) rebuildAndContinue()
            AudioGraphEvent.InterruptionBegan, AudioGraphEvent.MediaServicesLost -> {
                finishPlaying()
                tearDownGraph()
            }
            is AudioGraphEvent.InterruptionEnded -> Unit
        }
    }

    private fun isRunning(player: ExoPlayer): Boolean =
        player.playerError == null && player.playbackState != Player.STATE_IDLE

    /** Replaces the player and carries on from where the clip had reached. */
    private fun rebuildAndContinue() {
        tearDownGraph()
        val current = playing ?: return

        val startFrame = current.playedFrames.coerceIn(0L, current.clip.frames.toLong())
        val tail = PcmWav.tail(current.clip.wav, startFrame.toInt())
        if (tail == null) {
            finishPlaying()
            return
        }
        try {
            val player = readyPlayer()
            current.scheduleId = schedule(tail, player)
        } catch (e: Exception) {
            Log.e(TAG, "Playback rebuild failed: ${e.message}")
            finishPlaying()
            return
        }
        current.startFrame = startFrame
        current.deadlineNanos = deadline(current.clip.frames - startFrame, current.clip.sampleRate)
    }
}
