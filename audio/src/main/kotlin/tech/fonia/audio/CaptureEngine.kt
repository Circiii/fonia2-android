package tech.fonia.audio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/**
 * The hop listener: analyses a take as it arrives. Single-owner: it is made for one analysis task and never
 * leaves it. `:features` wires the live analyser from `:dsp` here, as `AudioHub` does on iOS.
 */
fun interface HopProcessor {
    /**
     * Called with 10 ms of audio at a time, `round(sampleRate / 100)` samples, as `samples[offset until offset + count]`.
     * Only the last hop of a take can be shorter. The array is the take itself: read it, never keep it.
     */
    fun process(samples: FloatArray, offset: Int, count: Int, levels: LiveLevels)
}

/** A finished take. */
class CapturedClip(
    val samples: FloatArray,
    val sampleRate: Double,
    /** Samples lost because the analysis task fell behind the source, or because a route change reset the take. */
    val dropped: Int = 0,
) {
    val duration: Double get() = if (sampleRate > 0) samples.size / sampleRate else 0.0

    // iOS's CapturedClip is a value type, so two clips with the same samples are equal.
    override fun equals(other: Any?): Boolean = other is CapturedClip &&
        other.sampleRate == sampleRate && other.dropped == dropped && other.samples.contentEquals(samples)

    override fun hashCode(): Int = (samples.contentHashCode() * 31 + sampleRate.hashCode()) * 31 + dropped

    override fun toString(): String = "CapturedClip(${samples.size} samples at $sampleRate Hz, dropped $dropped)"
}

/**
 * Runs one take: a source writes into a ring and a polling task drains it every 10 ms, analyses it and keeps it.
 *
 * iOS is an actor; here the phase changes under one lock and the suspension points sit where iOS awaits,
 * so a `stop` that arrives while the source is starting behaves the same.
 */
class CaptureEngine(private val source: CaptureSource) {
    companion object {
        /** About 2.7 s at 48 kHz, far more than the 10 ms polling gap. */
        internal const val RING_CAPACITY = 1 shl 17
        internal const val POLL_INTERVAL_MS = 10L
        internal const val INITIAL_TAKE_SECONDS = 30.0
        internal const val DRAIN_CHUNK = 4096

        /** `max(1, Int((sampleRate / 100).rounded()))`: 480 at 48 kHz, 441 at 44.1 kHz, 221 at 22.05 kHz. */
        fun hopSize(sampleRate: Double): Int = max(1, (sampleRate / 100).roundedAwayFromZero().toInt())
    }

    private class Take(val samples: FloatArray, val sampleRate: Double, val discarded: Int)

    private sealed interface Phase {
        data object Idle : Phase

        data class Starting(val stopRequested: Boolean) : Phase

        class Running(val ring: CaptureRing, val analysis: Job, val result: CompletableDeferred<Take>) : Phase

        data object Stopping : Phase
    }

    private val lock = Any()
    private var phase: Phase = Phase.Idle
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** [makeProcessor] receives the real sample rate. It runs again if a route change alters the rate. */
    @OptIn(DelicateCoroutinesApi::class)
    suspend fun start(levels: LiveLevels, makeProcessor: (Double) -> HopProcessor) {
        synchronized(lock) {
            if (phase != Phase.Idle) throw CaptureError.AlreadyRunning()
            phase = Phase.Starting(stopRequested = false)
        }

        val ring = CaptureRing(RING_CAPACITY)
        val sampleRate = try {
            source.start(ring)
        } catch (e: Throwable) {
            ring.close()
            synchronized(lock) { phase = Phase.Idle }
            throw e
        }

        val stopRequested = synchronized(lock) { (phase as? Phase.Starting)?.stopRequested == true }
        if (stopRequested) {
            withContext(NonCancellable) { source.stop() }
            ring.close()
            synchronized(lock) { phase = Phase.Idle }
            throw CancellationException("The take was stopped while it was starting")
        }

        CaptureActivity.began()
        val result = CompletableDeferred<Take>()
        // ATOMIC: the task runs even if it is cancelled at once, so the result always arrives. A processor that
        // throws (a Swift one cannot) ends the take with its exception instead of leaving `stop` waiting.
        val analysis = scope.launch(start = CoroutineStart.ATOMIC) {
            try {
                result.complete(analyze(ring, source, sampleRate, levels, makeProcessor))
            } catch (e: Throwable) {
                result.completeExceptionally(e)
            }
        }
        synchronized(lock) { phase = Phase.Running(ring, analysis, result) }
    }

    /** Ends the take and returns all of it. Empty when nothing was running. */
    suspend fun stop(): CapturedClip = withContext(NonCancellable) {
        val running: Phase.Running
        synchronized(lock) {
            when (val current = phase) {
                Phase.Idle, Phase.Stopping -> return@withContext CapturedClip(FloatArray(0), 0.0)
                is Phase.Starting -> {
                    phase = Phase.Starting(stopRequested = true)
                    return@withContext CapturedClip(FloatArray(0), 0.0)
                }
                is Phase.Running -> {
                    phase = Phase.Stopping
                    running = current
                }
            }
        }
        // The source stops writing first, so the final drain cannot miss samples.
        source.stop()
        running.analysis.cancel()
        try {
            val take = running.result.await()
            CapturedClip(take.samples, take.sampleRate, dropped = running.ring.dropped + take.discarded)
        } finally {
            CaptureActivity.ended()
            running.ring.close()
            synchronized(lock) { phase = Phase.Idle }
        }
    }

    suspend fun discard() {
        stop()
    }

    private suspend fun analyze(
        ring: CaptureRing,
        source: CaptureSource,
        sampleRate: Double,
        levels: LiveLevels,
        makeProcessor: (Double) -> HopProcessor,
    ): Take {
        var rate = sampleRate
        var processor = makeProcessor(rate)
        val take = TakeBuffer((rate * INITIAL_TAKE_SECONDS).toInt())
        var discarded = 0
        var analysed = 0
        val scratch = FloatArray(DRAIN_CHUNK)

        fun drain() {
            do {
                val read = ring.read(scratch, 0, scratch.size)
                take.append(scratch, read)
            } while (read > 0)
        }

        fun analyseHops(flushing: Boolean) {
            val hop = hopSize(rate)
            while (take.size - analysed >= hop) {
                processor.process(take.data, analysed, hop, levels)
                analysed += hop
            }
            if (flushing && analysed < take.size) {
                processor.process(take.data, analysed, take.size - analysed, levels)
                analysed = take.size
            }
        }

        while (currentCoroutineContext().isActive) {
            val currentRate = source.sampleRate
            if (currentRate > 0 && currentRate != rate) {
                // One clip has one rate, and the analyser is tuned to it, so the take restarts at the new rate.
                discarded += take.size
                take.clear()
                analysed = 0
                rate = currentRate
                processor = makeProcessor(rate)
            }
            drain()
            analyseHops(flushing = false)
            try {
                delay(POLL_INTERVAL_MS)
            } catch (e: CancellationException) {
                break
            }
        }

        drain()
        analyseHops(flushing = true)
        levels.publish()
        return Take(take.toArray(), rate, discarded)
    }

    /** The growing take. Its first 30 s are reserved up front, as iOS's `reserveCapacity`. */
    private class TakeBuffer(initialCapacity: Int) {
        var data = FloatArray(max(1, initialCapacity))
            private set
        var size = 0
            private set

        fun append(source: FloatArray, count: Int) {
            if (count <= 0) return
            if (size + count > data.size) data = data.copyOf(max(size + count, data.size * 2))
            System.arraycopy(source, 0, data, size, count)
            size += count
        }

        fun clear() {
            size = 0
        }

        fun toArray(): FloatArray = data.copyOf(size)
    }
}

/** Whether any take is running in this process. Sound effects stay silent while one is. */
internal object CaptureActivity {
    private val running = AtomicInteger(0)

    val isActive: Boolean get() = running.get() > 0

    fun began() {
        running.incrementAndGet()
    }

    fun ended() {
        running.decrementAndGet()
    }
}
