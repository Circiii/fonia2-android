package tech.fonia.facecamera

import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Per-frame time on this device, by stage: a frame where the detector runs (no face tracked yet) and a tracked frame
 * (the common case). The camera delivers at most 30 frames a second, so a frame has 33 ms.
 */
@RunWith(AndroidJUnit4::class)
class LatencyTests {
    private fun median(values: LongArray): Double = values.sorted()[values.size / 2] / 1e6

    private fun percentile90(values: LongArray): Double = values.sorted()[(values.size * 9) / 10] / 1e6

    @Test
    fun perFrame() {
        val pipeline = TestPipeline.pipeline
        val frame = AndroidFixtures.frame("smile")
        repeat(5) { pipeline.process(frame, 0, stream = false) } // warm-up: XNNPACK packs weights on first runs
        val runs = 40
        val detected = Array(5) { LongArray(runs) }
        for (k in 0 until runs) {
            pipeline.process(frame, 0, stream = false)
            for (s in 0 until 5) detected[s][k] = pipeline.timings[s]
        }
        pipeline.reset()
        val tracked = Array(5) { LongArray(runs) }
        for (k in 0 until runs + 1) {
            pipeline.process(frame, 1_000_000L + k * 33_333L, stream = true)
            if (k == 0) continue
            for (s in 0 until 5) tracked[s][k - 1] = pipeline.timings[s]
        }
        val names = listOf("detector", "landmarks", "blendshapes", "pose+gaze", "total")
        report("with detection (ms, median / p90): " + names.indices.joinToString { "${names[it]} %.2f / %.2f".format(median(detected[it]), percentile90(detected[it])) })
        report("tracked (ms, median / p90): " + names.indices.joinToString { "${names[it]} %.2f / %.2f".format(median(tracked[it]), percentile90(tracked[it])) })
        // A loose bound against regressions; the numbers themselves go to PARITY.md.
        assertTrue(median(tracked[4]) < 100.0)
    }

    /**
     * The hot path allocates nothing of its own: buffers are made once. What remains per frame is inside LiteRT's
     * Java wrapper (the iterator over its output map) and nothing that grows with the image.
     */
    @Suppress("DEPRECATION")
    @Test
    fun trackedFramesDoNotAllocate() {
        val pipeline = TestPipeline.pipeline
        val frame = AndroidFixtures.frame("smile")
        pipeline.reset()
        repeat(3) { pipeline.process(frame, 1_000_000L + it * 33_333L, stream = true) }
        val frames = 20
        Debug.resetThreadAllocCount()
        Debug.resetThreadAllocSize()
        Debug.startAllocCounting()
        for (k in 0 until frames) pipeline.process(frame, 2_000_000L + k * 33_333L, stream = true)
        Debug.stopAllocCounting()
        val count = Debug.getThreadAllocCount()
        val bytes = Debug.getThreadAllocSize()
        report("allocations over $frames tracked frames: $count objects, $bytes bytes")
        assertTrue("$count objects", count <= frames * 4)
        assertTrue("$bytes bytes", bytes <= frames * 256)
    }
}
