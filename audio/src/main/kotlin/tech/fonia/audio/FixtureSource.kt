package tech.fonia.audio

import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.min

/** Feeds a WAV or MP3 file into the ring, standing in for the microphone in tests and in the recorded-take mode. */
class FixtureSource(private val url: ClipLocation, private val pacing: Pacing = Pacing.RealTime) : CaptureSource {
    enum class Pacing {
        /** Delivers samples as a microphone would, so the take lasts as long as the file. */
        RealTime,

        /** Delivers samples as fast as the reader drains them. Never drops. */
        AsFastAsPossible,
    }

    private companion object {
        const val FEED_INTERVAL_MS = 5L
    }

    private val lock = Any()
    private var rate = 0.0
    private var feeder: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val sampleRate: Double get() = synchronized(lock) { rate }

    override suspend fun start(ring: CaptureRing): Double {
        val audio = AudioDecoder.decodeMono(url)
        val pacing = pacing
        val feeder = scope.launch { feed(audio, pacing, ring) }
        synchronized(lock) {
            rate = audio.sampleRate
            this.feeder = feeder
        }
        return audio.sampleRate
    }

    /** Suspends until the whole file has been written into the ring. */
    suspend fun waitUntilFinished() {
        val feeder = synchronized(lock) { feeder }
        feeder?.join()
    }

    override suspend fun stop() {
        val feeder = synchronized(lock) { feeder.also { feeder = null } }
        feeder?.cancelAndJoin()
    }

    private suspend fun CoroutineScope.feed(audio: DecodedAudio, pacing: Pacing, ring: CaptureRing) {
        val started = System.nanoTime()
        var written = 0
        val total = audio.samples.size

        while (written < total && isActive) {
            val due = when (pacing) {
                Pacing.RealTime -> {
                    val elapsed = (System.nanoTime() - started) / 1e9
                    min(total.toDouble(), elapsed * audio.sampleRate).toInt()
                }
                Pacing.AsFastAsPossible -> total
            }
            val count = min(due - written, ring.free)
            if (count > 0) {
                ring.write(audio.samples, written, count)
                written += count
            }
            if (written < total) delay(FEED_INTERVAL_MS)
        }
    }
}

/**
 * The no-microphone mode, where a take comes from the recorded clip of the text being practised (iOS §6.10).
 * iOS turns it on in the simulator and with `-foniaFixtures`; the Android emulator has a microphone, so here
 * it is only ever switched on explicitly, with the launch extra [LAUNCH_ARGUMENT].
 *
 * In this mode `AudioHub` configures the session with `playbackOnly = true` and answers the microphone
 * request with `true` without asking, so no permission prompt appears; everything after the source is the
 * same chain (10 ms hops, end of take, judging, saving).
 */
object FixtureMode {
    /** The boolean intent extra that switches the mode on, the equivalent of iOS's `-foniaFixtures`. */
    const val LAUNCH_ARGUMENT = "foniaFixtures"

    /** The word whose clip stands in when the text being practised has none. */
    const val FALLBACK_TEXT = "soare"

    fun isRequested(extras: Bundle?): Boolean = extras?.getBoolean(LAUNCH_ARGUMENT, false) ?: false

    /**
     * `AudioHub.startListening`'s choice of source: the clip of the fixture text, else the clip of
     * [FALLBACK_TEXT], else the microphone. Fixture takes run in real time, as a voice would.
     */
    fun captureSource(
        usesFixtures: Boolean,
        fixtureClip: ClipLocation?,
        fallbackClip: ClipLocation?,
        microphone: () -> CaptureSource,
    ): CaptureSource = when {
        usesFixtures && fixtureClip != null -> FixtureSource(fixtureClip, FixtureSource.Pacing.RealTime)
        usesFixtures && fallbackClip != null -> FixtureSource(fallbackClip, FixtureSource.Pacing.RealTime)
        else -> microphone()
    }
}
