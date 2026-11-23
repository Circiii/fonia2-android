package tech.fonia.app.speaking

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.fonia.live.AudioDirector
import tech.fonia.live.AudioState
import tech.fonia.live.Clip
import tech.fonia.live.LiveTarget
import tech.fonia.live.PromptRef
import tech.fonia.speaking.SpeakingAudio

/**
 * The speaking screens' [SpeakingAudio] on the app's one [AudioDirector] (`OboeAudioDirector` on a phone): the same
 * capture (mono at the capture rate, UNPROCESSED / VOICE_RECOGNITION with the platform effects off, so no noise
 * reduction), the same playback (`PlaybackEngine` through `play(PromptRef.Take)`), and the director's quiet rule
 * (FxGate): the mic opens 150 ms after the last sound, nothing plays while it is open, [play] refuses while recording.
 *
 * - [record] opens the mic with [LiveTarget.AnySound] and polls the director's `LiveBoard` every [pollMillis]
 *   (50 updates a second): `onLevel(level, speaking)`. While the source delivers nothing new for [stallMillis] (a
 *   fixture that ran out, a stalled mic) it reports silence, so the screen's 3 s silence rule ends the take. It is the
 *   board's only reader while it runs (the lesson is not on screen).
 * - [playbackSeconds]: `PlaybackEngine` exposes no position, so it is the time since the director entered
 *   `Speaking`, minus [startLatencySeconds] (player start + output latency, an estimate), clamped to the clip; NaN
 *   when nothing plays.
 *
 * [beforeRecord] lets a debug fixture pick its clip (`--es fixture fillers`). Nothing here stores anything.
 */
class DirectorSpeakingAudio(
    private val audio: AudioDirector,
    private val clock: () -> Long = System::nanoTime,
    private val pollMillis: Long = 20,
    private val stallMillis: Long = 250,
    private val startLatencySeconds: Double = START_LATENCY_SECONDS,
    private val beforeRecord: () -> Unit = {},
) : SpeakingAudio {
    @Volatile private var stopRequested = false
    @Volatile var recording = false
        private set

    @Volatile private var playing = false
    @Volatile private var playStartNanos = NOT_STARTED
    @Volatile private var playFrom = 0.0
    @Volatile private var playEnd = 0.0
    @Volatile private var playJob: Job? = null

    /** Level updates delivered by the last [record] (tests, logs). */
    @Volatile var levelUpdates = 0
        private set

    override suspend fun record(maxSeconds: Double, onLevel: (level: Float, speaking: Boolean) -> Unit): Clip {
        stopPlayback()
        stopRequested = false
        levelUpdates = 0
        beforeRecord()
        audio.openMic(LiveTarget.AnySound) // waits for the quiet rule; throws AudioSuspendedException in the background
        recording = true
        try {
            val t0 = clock()
            var lastSeq = Long.MIN_VALUE
            var lastAdvance = t0
            val stallNanos = stallMillis * 1_000_000L
            while (!stopRequested) {
                val now = clock()
                if ((now - t0) / 1e9 >= maxSeconds) break
                if (audio.state.value is AudioState.Suspended) break
                val s = audio.board.latest()
                val seq = s.seq
                if (seq != lastSeq) {
                    lastSeq = seq
                    lastAdvance = now
                    onLevel(s.level.coerceIn(0f, 1f), s.speaking)
                } else if (now - lastAdvance >= stallNanos) {
                    onLevel(0f, false)
                } else {
                    onLevel(s.level.coerceIn(0f, 1f), s.speaking)
                }
                levelUpdates++
                delay(pollMillis)
            }
        } finally {
            recording = false
        }
        return withContext(NonCancellable) { audio.closeMic() }
    }

    override fun stopRecording() {
        stopRequested = true
    }

    override suspend fun play(samples: FloatArray, sampleRate: Double, fromSeconds: Double): Boolean {
        if (recording) return false // FxGate: nothing plays while the mic is open
        stopPlayback()
        val total = samples.size / sampleRate
        val from = fromSeconds.coerceIn(0.0, total)
        val first = (from * sampleRate).toInt()
        if (first >= samples.size) return true
        val slice = if (first == 0) samples else samples.copyOfRange(first, samples.size)
        playFrom = from
        playEnd = total
        playStartNanos = NOT_STARTED
        playing = true
        try {
            return coroutineScope {
                val watcher = launch {
                    audio.state.first { it == AudioState.Speaking }
                    playStartNanos = clock()
                }
                val job = async { audio.play(PromptRef.Take(Clip(slice, sampleRate, audioSource = "speaking"))) }
                playJob = job
                val ok = try {
                    job.await()
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive() // the caller was cancelled: rethrow
                    true // stopPlayback(): it played until it was stopped
                }
                watcher.cancel()
                ok
            }
        } finally {
            playJob = null
            playing = false
            playStartNanos = NOT_STARTED
        }
    }

    override fun stopPlayback() {
        playJob?.cancel()
    }

    override fun playbackSeconds(): Double {
        val start = playStartNanos
        if (!playing || start == NOT_STARTED) return Double.NaN
        val t = playFrom + (clock() - start) / 1e9 - startLatencySeconds
        return t.coerceIn(playFrom, playEnd)
    }

    companion object {
        /** ExoPlayer start on an in-memory WAV plus the output latency, roughly; replace with a measured value. */
        const val START_LATENCY_SECONDS = 0.12
        private const val NOT_STARTED = Long.MIN_VALUE
    }
}
