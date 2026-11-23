package tech.fonia.audio

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** The capture cases that need Android: MP3 decoding, the microphone, and the native callback. */
@RunWith(AndroidJUnit4::class)
class CaptureDeviceTests {
    private suspend fun start(engine: CaptureEngine, tally: HopTally, levels: LiveLevels = LiveLevels()) {
        engine.start(levels) { rate ->
            tally.processorMade(rate)
            RMSProcessor(tally)
        }
    }

    /** CaptureEngineTests.swift: an MP3 is decoded and delivered at its own rate. */
    @Test(timeout = 60_000)
    fun mp3FixtureDecodes() = runBlocking {
        val source = FixtureSource(ClipLocation.File(Fixtures.popMp3), FixtureSource.Pacing.AsFastAsPossible)
        val engine = CaptureEngine(source)
        val tally = HopTally()

        start(engine, tally)
        source.waitUntilFinished()
        val clip = engine.stop()

        assertEquals(44_100.0, clip.sampleRate, 0.0)
        assertEquals(listOf(44_100.0), tally.rates)
        assertTrue("duration ${clip.duration}", clip.duration > 0.4 && clip.duration < 0.7)
        assertTrue(tally.hopSizes.dropLast(1).all { it == 441 })
        assertEquals(clip.samples.size, tally.totalSamples)
        assertEquals(0, clip.dropped)
    }

    /**
     * CaptureEngineTests.swift: a denied microphone fails the start and leaves the engine usable. iOS runs it only
     * on a machine where the permission is denied; revoking a permission kills the test process on Android, so the
     * source is given a denied permission instead.
     */
    @Test(timeout = 60_000)
    fun deniedMicrophone() = runBlocking {
        val engine = CaptureEngine(MicrophoneSource(targetContext) { false })
        try {
            start(engine, HopTally())
            fail("Expected CaptureError.MicrophoneDenied")
        } catch (e: CaptureError.MicrophoneDenied) {
            // Expected.
        }
        assertTrue(engine.stop().samples.isEmpty())
    }

    /**
     * CaptureRingTests.swift: a sink node can be made and attached without opening the microphone. Android's
     * counterpart is the Oboe callback object: it exists without a stream, and its delivery writes channel 0 of
     * the input into the ring.
     */
    @Test
    fun sinkNode() {
        CaptureRing(1 shl 10).use { ring ->
            NativeCapture(ring as NativeCaptureRing) {}.use { capture ->
                assertEquals("no stream is open", OBOE_STATE_CLOSED, capture.state)
                val stereo = floatArrayOf(1f, -1f, 2f, -2f, 3f, -3f)
                assertEquals(3, capture.deliver(stereo, frames = 3, channels = 2))
                val out = FloatArray(8)
                assertEquals(3, ring.read(out))
                assertArrayEquals(floatArrayOf(1f, 2f, 3f), out.copyOf(3), 0f)
            }
        }
    }

    /** Port-only: the real microphone through Oboe, at 48 kHz, with the chosen preset and 10 ms hops. */
    @Test(timeout = 60_000)
    fun microphoneCapturesWithThePreferredPreset() = runBlocking {
        grantMicrophone()
        val source = MicrophoneSource(targetContext)
        val engine = CaptureEngine(source)
        val tally = HopTally()

        start(engine, tally)
        assertTrue(CaptureActivity.isActive)
        delay(600)
        val clip = engine.stop()

        val info = assertNotNullAndGet(source.captureInfo)
        Log.i("CaptureDeviceTests", "Microphone: $info, ${clip.samples.size} samples, dropped ${clip.dropped}")
        assertEquals(48_000.0, clip.sampleRate, 0.0)
        assertEquals(info.requestedPreset, info.inputPreset)
        assertTrue(info.inputPreset == InputPreset.Unprocessed || info.inputPreset == InputPreset.VoiceRecognition)
        assertFalse(info.isNarrowBand)
        assertTrue("samples ${clip.samples.size}", clip.samples.size > 9_600)
        assertTrue(tally.hopSizes.dropLast(1).all { it == 480 })
        assertFalse(CaptureActivity.isActive)
    }

    private fun <T> assertNotNullAndGet(value: T?): T {
        assertNotNull(value)
        return value!!
    }

    private companion object {
        const val OBOE_STATE_CLOSED = 12
    }
}
