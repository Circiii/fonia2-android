package tech.fonia.live

import kotlin.math.max
import kotlin.math.min

/**
 * The latency overlay's reader side: the age of the snapshot when a frame drew it (`vsync − captureNanos`, docs/v2/02
 * §4, budget p95 ≤ 55 ms). Call [onFrame] from the same `withFrameNanos` loop that calls [LiveBoard.latest]; it does
 * not allocate. [summary] allocates (debug overlay only). Single thread.
 */
class FrameLatencyMeter(window: Int = 300) {
    private val ages = LongArray(max(1, window))
    private var count = 0L
    private var slowFrames = 0L
    private var lastFrameNanos = 0L

    /** [frameNanos] from `withFrameNanos` (CLOCK_MONOTONIC on Android), [snapshot] the frame drawn. */
    fun onFrame(frameNanos: Long, snapshot: LiveSnapshot) {
        if (lastFrameNanos != 0L && frameNanos - lastFrameNanos > SLOW_FRAME_NANOS) slowFrames += 1
        lastFrameNanos = frameNanos
        if (snapshot.captureNanos == 0L) return
        ages[(count % ages.size).toInt()] = frameNanos - snapshot.captureNanos
        count += 1
    }

    data class Summary(val frames: Long, val ageP50Ms: Double, val ageP95Ms: Double, val ageMaxMs: Double, val framesOver32Ms: Long)

    fun summary(): Summary {
        val n = min(count, ages.size.toLong()).toInt()
        if (n == 0) return Summary(0, 0.0, 0.0, 0.0, slowFrames)
        val sorted = ages.copyOf(n).also { it.sort() }
        fun p(q: Double) = sorted[min(n - 1, (q * n).toInt())] / 1e6
        return Summary(count, p(0.5), p(0.95), sorted.last() / 1e6, slowFrames)
    }

    fun reset() {
        count = 0
        slowFrames = 0
        lastFrameNanos = 0
    }

    private companion object {
        const val SLOW_FRAME_NANOS = 32_000_000L
    }
}
