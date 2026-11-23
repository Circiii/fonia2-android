package tech.fonia.live

import java.util.concurrent.locks.LockSupport
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Runs one take on its own analysis thread: drains a [PcmSource], keeps every sample for the [Clip], cuts 10 ms hops
 * and hands them to [pipeline] with their capture time. Replaces the old `delay(10)` polling on
 * `Dispatchers.Default`: the thread waits at most [pollNanos] (2 ms) for new data, or less if the source's
 * [PcmSource.awaitData] wakes it.
 *
 * [threadFactory] lets `:platform:audio` raise the thread to `THREAD_PRIORITY_AUDIO`.
 */
class LiveCapture(
    val pipeline: LivePipeline,
    private val clock: NanoClock = NanoClock.System,
    private val threadFactory: (Runnable) -> Thread = { Thread(it, THREAD_NAME).apply { isDaemon = true } },
    private val pollNanos: Long = 2_000_000L,
) {
    private class Running(val runner: TakeRunner, val thread: Thread) {
        @Volatile var stopRequested = false
        @Volatile var clip: Clip? = null
        @Volatile var failure: Throwable? = null
    }

    private val lock = Any()
    private var running: Running? = null

    val isRunning: Boolean get() = synchronized(lock) { running != null }

    /** Starts the analysis thread. Throws if a take is already running. */
    fun start(source: PcmSource, target: LiveTarget) {
        synchronized(lock) {
            check(running == null) { "A take is already running" }
            val runner = TakeRunner(source, pipeline, target, clock)
            lateinit var state: Running
            val thread = threadFactory {
                try {
                    runner.begin()
                    while (!state.stopRequested) {
                        val read = runner.step()
                        if (read == 0 && !state.stopRequested) source.awaitData(pollNanos)
                        if (read < 0 && !state.stopRequested) LockSupport.parkNanos(pollNanos)
                    }
                    state.clip = runner.finish()
                } catch (t: Throwable) {
                    state.failure = t
                }
            }
            state = Running(runner, thread)
            running = state
            thread.start()
        }
    }

    /**
     * Stops the thread after a last drain and returns the whole take. Call only after the source stopped writing, so
     * no sample is missed. Returns [Clip.Empty] when nothing was running.
     */
    fun stop(): Clip {
        val state = synchronized(lock) { running.also { running = null } } ?: return Clip.Empty
        state.stopRequested = true
        LockSupport.unpark(state.thread)
        state.thread.join()
        pipeline.stop()
        state.failure?.let { throw it }
        return state.clip ?: Clip.Empty
    }

    companion object {
        const val THREAD_NAME = "fonia-analysis"

        /** `max(1, round(rate / 100))`: 480 at 48 kHz, as `CaptureEngine.hopSize`. */
        fun hopSize(sampleRate: Double): Int = max(1, (sampleRate / 100).roundToLong().toInt())

        /**
         * Runs a whole finite source through [pipeline] on the calling thread, until the source ends. For JVM tests
         * and offline checks of `spec/` fixtures. A [LiveBoard] reader may run concurrently.
         */
        fun runToEnd(source: PcmSource, pipeline: LivePipeline, target: LiveTarget, clock: NanoClock = NanoClock.System): Clip {
            val runner = TakeRunner(source, pipeline, target, clock)
            runner.begin()
            while (true) {
                val read = runner.step()
                if (read < 0) break
                if (read == 0) source.awaitData(1_000_000L)
            }
            val clip = runner.finish()
            pipeline.stop()
            return clip
        }
    }
}

/** One take's state, owned by the analysis thread. */
internal class TakeRunner(
    private val source: PcmSource,
    private val pipeline: LivePipeline,
    private val target: LiveTarget,
    private val clock: NanoClock,
) {
    private var rate = 0.0
    private var take = FloatArray(1)
    private var size = 0
    private var analysed = 0
    private var discarded = 0
    private var startNanos = 0L
    private val scratch = FloatArray(4096)

    fun begin() {
        rate = source.sampleRate
        take = FloatArray(max(1, (rate * INITIAL_SECONDS).toInt()))
        pipeline.start(rate, target)
    }

    /** Drains what is ready and analyses whole hops. Returns samples read, or -1 when the source ended. */
    fun step(): Int {
        val currentRate = source.sampleRate
        if (currentRate > 0 && currentRate != rate) {
            // One clip has one rate, and the analyser is tuned to it, so the take restarts at the new rate.
            discarded += size
            size = 0
            analysed = 0
            rate = currentRate
            pipeline.start(rate, target)
        }
        var total = 0
        var ended = false
        while (true) {
            val n = source.read(scratch, 0, scratch.size)
            if (n < 0) {
                ended = true
                break
            }
            if (n == 0) break
            append(n)
            total += n
        }
        if (total > 0) {
            val newest = source.newestCaptureNanos().takeIf { it != 0L } ?: clock.nanos()
            if (startNanos == 0L) startNanos = newest - nanosFor(size)
            pipeline.dropped = source.dropped + discarded
            val hop = LiveCapture.hopSize(rate)
            while (size - analysed >= hop) {
                val end = analysed + hop
                pipeline.process(take, analysed, hop, newest - nanosFor(size - end))
                analysed = end
            }
        }
        return if (ended && total == 0) -1 else total
    }

    /** Flushes the last partial hop and returns the take. */
    fun finish(): Clip {
        step()
        if (analysed < size) {
            pipeline.process(take, analysed, size - analysed, 0L)
            analysed = size
        }
        return Clip(take.copyOf(size), rate, source.dropped + discarded, startNanos, source.audioSource)
    }

    private fun nanosFor(samples: Int): Long = (samples / rate * 1e9).toLong()

    private fun append(n: Int) {
        if (size + n > take.size) take = take.copyOf(max(size + n, take.size * 2))
        System.arraycopy(scratch, 0, take, size, n)
        size += n
    }

    private companion object {
        const val INITIAL_SECONDS = 30.0
    }
}
