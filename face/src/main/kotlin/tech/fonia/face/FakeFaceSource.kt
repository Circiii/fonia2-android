package tech.fonia.face

import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Plays a script of face values, for previews, tests and an emulator without a usable camera.
 *
 * Swift plays it in a Task on the main actor, where `stop()` also runs, so no frame can follow a
 * stop. Here a daemon thread plays it, and a lock shared with [stop] gives the same guarantee.
 */
class FakeFaceSource(
    private val script: List<Step> = SPEAKING_SCRIPT,
    private val loops: Boolean = true,
) : FaceSource {
    data class Step(val frame: FaceFrame, val hold: Duration)

    override val kind = FaceSourceKind.FAKE
    override val isSupported = true
    override val canDetectTongue = true
    override val canEstimateEyeGaze = true

    private class Playback {
        @Volatile var cancelled = false
        var thread: Thread? = null
    }

    private val lock = Any()
    private var levels: FaceLevels? = null
    private var playback: Playback? = null

    /** Publishes the first step before returning, then plays the rest in the background. */
    override suspend fun start(levels: FaceLevels) {
        stop()
        synchronized(lock) { this.levels = levels }
        val first = script.firstOrNull() ?: return
        levels.publish(first.frame)

        val run = Playback()
        val script = script
        val loops = loops
        synchronized(lock) { playback = run }
        run.thread = thread(isDaemon = true, name = "tech.fonia.face.fake") {
            var index = 0
            while (true) {
                // `try? await Task.sleep(for:)`: a cancelled sleep simply ends early.
                sleep(script[index].hold)
                if (run.cancelled) return@thread
                val next = index + 1
                if (next == script.size && !loops) return@thread
                index = next % script.size
                synchronized(lock) {
                    if (run.cancelled) return@thread
                    levels.publish(script[index].frame)
                }
            }
        }
    }

    override fun stop() {
        synchronized(lock) {
            playback?.let {
                it.cancelled = true
                it.thread?.interrupt()
            }
            playback = null
            levels?.publish(FaceFrame.ABSENT)
            levels = null
        }
    }

    private fun sleep(duration: Duration) {
        val nanos = duration.inWholeNanoseconds
        try {
            Thread.sleep(nanos / 1_000_000, (nanos % 1_000_000).toInt())
        } catch (e: InterruptedException) {
            // Woken by stop(); the cancelled flag ends the loop.
        }
    }

    companion object {
        /** A mouth opening, rounding and spreading, again and again. */
        val SPEAKING_SCRIPT: List<Step> = listOf(
            Step(frame = FaceFrame(jawOpen = 0.1f), hold = 200.milliseconds),
            Step(frame = FaceFrame(jawOpen = 0.6f), hold = 200.milliseconds),
            Step(frame = FaceFrame(jawOpen = 0.3f, pucker = 0.8f), hold = 200.milliseconds),
            Step(frame = FaceFrame(jawOpen = 0.1f, smile = 0.7f), hold = 200.milliseconds),
        )
    }
}
