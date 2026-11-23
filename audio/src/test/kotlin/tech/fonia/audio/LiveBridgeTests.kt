package tech.fonia.audio

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.live.LiveCapture
import tech.fonia.live.LivePipeline
import tech.fonia.live.LiveTarget

/** The bridges between this module's capture and the continuous band, on the JVM ring. */
class LiveBridgeTests {
    private class FakeSource(override val sampleRate: Double) : CaptureSource {
        override suspend fun start(ring: CaptureRing): Double = sampleRate

        override suspend fun stop() = Unit
    }

    @Test
    fun ringSourceReadsTheRingAndBackdatesByTheInputLatency() {
        var now = 1_000_000_000L
        val ring = CaptureRing(1024)
        val pcm = RingPcmSource(ring, FakeSource(48_000.0), { 12.0 }, "unprocessed") { now }
        ring.write(FloatArray(480) { 0.1f })
        val dst = FloatArray(1024)
        assertEquals(480, pcm.read(dst, 0, dst.size))
        assertEquals(now - 12_000_000L, pcm.newestCaptureNanos())
        assertEquals(12.0, pcm.lastLatencyMillis!!, 0.0)
        assertEquals(0, pcm.read(dst, 0, dst.size))
        now += 5_000_000L
        ring.write(FloatArray(240))
        pcm.read(dst, 0, dst.size)
        assertEquals("the latency is refreshed only every 100 ms", now - 12_000_000L, pcm.newestCaptureNanos())
        assertEquals("unprocessed", pcm.audioSource)
    }

    @Test
    fun theAnalysisThreadRunsOverTheRing() {
        val ring = CaptureRing(1 shl 17)
        val pcm = RingPcmSource(ring, FakeSource(48_000.0), { null }, "fixture")
        val pipeline = LivePipeline()
        val capture = LiveCapture(pipeline)
        capture.start(pcm, LiveTarget.AnySound)
        val tone = sine(220.0, 0.3f, 1.0, 48_000.0)
        var at = 0
        while (at < tone.size) {
            at += ring.write(tone, at, minOf(4800, tone.size - at))
            Thread.sleep(5)
        }
        Thread.sleep(50)
        val clip = capture.stop()
        assertEquals(tone.size, clip.samples.size)
        assertEquals("fixture", clip.audioSource)
        assertTrue(pipeline.board.lastPublishedSeq >= 100)
    }

    @Test
    fun theOldCaptureEngineCanDriveThePipeline() = runBlocking {
        val tone = sine(220.0, 0.3f, 0.5, 48_000.0)
        val source = object : CaptureSource {
            override val sampleRate = 48_000.0

            override suspend fun start(ring: CaptureRing): Double {
                ring.write(tone)
                return sampleRate
            }

            override suspend fun stop() = Unit
        }
        val engine = CaptureEngine(source)
        val pipeline = LivePipeline()
        val levels = LiveLevels()
        engine.start(levels) { rate ->
            pipeline.start(rate, LiveTarget.Voice)
            pipeline.asHopProcessor()
        }
        kotlinx.coroutines.delay(100)
        val clip = engine.stop()
        assertEquals(tone.size, clip.samples.size)
        assertEquals(50L, pipeline.board.lastPublishedSeq)
        assertTrue(pipeline.lastWritten.speaking)
        // CaptureEngine resets the old levels at the end of a take; the mirror itself copies the last frame.
        val mirror = LiveLevels()
        mirror.publishFrom(pipeline)
        assertEquals(pipeline.lastWritten.level, mirror.level, 0f)
        assertEquals(pipeline.lastWritten.pitchHz, mirror.pitchHz, 0f)
    }

    @Test
    fun clipsConvertBothWays() {
        val captured = CapturedClip(floatArrayOf(0.1f, 0.2f), 48_000.0, dropped = 3)
        val clip = captured.toClip("voiceRecognition")
        assertEquals(3, clip.dropped)
        assertEquals("voiceRecognition", clip.audioSource)
        assertEquals(captured, clip.toCapturedClip())
        assertEquals("unprocessed", InputPreset.Unprocessed.rawName)
    }
}
