package tech.fonia.audio

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs

/** A source whose rate a test can change, as a route change would. */
private class ScriptedSource(rate: Double) : CaptureSource {
    private val lock = Any()
    private var ring: CaptureRing? = null
    private var rate = rate

    override val sampleRate: Double get() = synchronized(lock) { rate }

    override suspend fun start(ring: CaptureRing): Double = synchronized(lock) {
        this.ring = ring
        rate
    }

    override suspend fun stop() {
        synchronized(lock) { ring = null }
    }

    fun deliver(samples: FloatArray) {
        val ring = synchronized(lock) { ring }
        ring?.write(samples)
    }

    fun changeRate(rate: Double) {
        synchronized(lock) { this.rate = rate }
    }
}

private class FailingSource : CaptureSource {
    override val sampleRate: Double get() = 0.0

    override suspend fun start(ring: CaptureRing): Double = throw CaptureError.NoInput()

    override suspend fun stop() = Unit
}

/**
 * Port of CaptureEngineTests.swift ("Capture engine"). Runs on the JVM (Kotlin ring, WAV decoded in Kotlin)
 * and again on a device (native ring). The MP3 and denied-microphone cases need Android and live in
 * CaptureDeviceTests.
 */
class CaptureEngineTests {
    private suspend fun start(engine: CaptureEngine, levels: LiveLevels = LiveLevels(), tally: HopTally) {
        engine.start(levels) { rate ->
            tally.processorMade(rate)
            RMSProcessor(tally)
        }
    }

    private suspend fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            if (System.nanoTime() >= deadline) fail("Timed out waiting for the analysis task")
            delay(5)
        }
    }

    /** soare.wav arrives whole, at its real rate, in 10 ms hops. */
    @Test(timeout = 60_000)
    fun wavFixtureDeliversEverySample() = runBlocking {
        val source = FixtureSource(ClipLocation.File(Fixtures.soareWav), FixtureSource.Pacing.AsFastAsPossible)
        val engine = CaptureEngine(source)
        val tally = HopTally()

        start(engine, tally = tally)
        source.waitUntilFinished()
        val clip = engine.stop()

        assertEquals(Fixtures.SOARE_FRAMES, clip.samples.size)
        assertEquals(Fixtures.SOARE_RATE, clip.sampleRate, 0.0)
        assertEquals(0, clip.dropped)
        assertTrue(abs(clip.duration - 2.361) < 0.001)

        assertEquals(listOf(Fixtures.SOARE_RATE), tally.rates)
        assertEquals(Fixtures.SOARE_FRAMES, tally.totalSamples)
        assertTrue(tally.hopSizes.dropLast(1).all { it == 480 })
        assertEquals(Fixtures.SOARE_FRAMES / 480 + 1, tally.hopSizes.size)
        assertTrue(tally.loudestRMS > 0.01f)
    }

    /** Samples match a direct decode of the file. */
    @Test(timeout = 60_000)
    fun samplesMatchFile() = runBlocking {
        val source = FixtureSource(ClipLocation.File(Fixtures.soareWav), FixtureSource.Pacing.AsFastAsPossible)
        val engine = CaptureEngine(source)

        start(engine, tally = HopTally())
        source.waitUntilFinished()
        val clip = engine.stop()

        val decoded = AudioDecoder.decodeMono(ClipLocation.File(Fixtures.soareWav))
        assertArrayEquals(decoded.samples, clip.samples, 0f)
    }

    /** A file bigger than the ring gets through without drops. */
    @Test(timeout = 60_000)
    fun backpressureNeverDrops() = runBlocking {
        withTemporaryFile("wav") { file ->
            val samples = sine(frequency = 440.0, amplitude = 0.5f, seconds = 4.0, sampleRate = 48_000.0)
            assertTrue(samples.size > 1 shl 17)
            writeWav(samples, 48_000.0, file)

            val source = FixtureSource(ClipLocation.File(file), FixtureSource.Pacing.AsFastAsPossible)
            val engine = CaptureEngine(source)
            start(engine, tally = HopTally())
            source.waitUntilFinished()
            val clip = engine.stop()

            assertEquals(samples.size, clip.samples.size)
            assertEquals(0, clip.dropped)
        }
    }

    /** Real-time pacing takes about as long as the file. */
    @Test(timeout = 60_000)
    fun realTimePacing() = runBlocking {
        withTemporaryFile("wav") { file ->
            writeWav(sine(frequency = 440.0, amplitude = 0.5f, seconds = 0.4, sampleRate = 48_000.0), 48_000.0, file)

            val source = FixtureSource(ClipLocation.File(file), FixtureSource.Pacing.RealTime)
            val engine = CaptureEngine(source)
            start(engine, tally = HopTally())

            val began = System.nanoTime()
            source.waitUntilFinished()
            val elapsedMs = (System.nanoTime() - began) / 1_000_000
            val clip = engine.stop()

            assertEquals(19_200, clip.samples.size)
            assertTrue("elapsed $elapsedMs ms", elapsedMs > 300)
            assertTrue("elapsed $elapsedMs ms", elapsedMs < 2_000)
        }
    }

    /** Stopping early keeps what arrived and stops the feeder. */
    @Test(timeout = 60_000)
    fun stoppingEarly() = runBlocking {
        val source = FixtureSource(ClipLocation.File(Fixtures.soareWav), FixtureSource.Pacing.RealTime)
        val engine = CaptureEngine(source)

        start(engine, tally = HopTally())
        delay(200)
        val clip = engine.stop()

        assertTrue(clip.samples.isNotEmpty())
        assertTrue(clip.samples.size < Fixtures.SOARE_FRAMES)
    }

    /** Levels return to rest when a take ends. */
    @Test(timeout = 60_000)
    fun levelsReset() = runBlocking {
        val source = FixtureSource(ClipLocation.File(Fixtures.soareWav), FixtureSource.Pacing.AsFastAsPossible)
        val engine = CaptureEngine(source)
        val levels = LiveLevels()

        start(engine, levels = levels, tally = HopTally())
        source.waitUntilFinished()
        engine.stop()

        assertEquals(0f, levels.rms, 0f)
    }

    /** Stop without a take returns an empty clip. */
    @Test(timeout = 60_000)
    fun stopWhenIdle() = runBlocking {
        val engine = CaptureEngine(FixtureSource(ClipLocation.File(Fixtures.soareWav)))
        val clip = engine.stop()
        assertTrue(clip.samples.isEmpty())
        assertEquals(0.0, clip.duration, 0.0)
    }

    /** A second start while running is refused, and the engine restarts after stop. */
    @Test(timeout = 60_000)
    fun restart() = runBlocking {
        val source = FixtureSource(ClipLocation.File(Fixtures.soareWav), FixtureSource.Pacing.AsFastAsPossible)
        val engine = CaptureEngine(source)

        start(engine, tally = HopTally())
        try {
            start(engine, tally = HopTally())
            fail("Expected CaptureError.AlreadyRunning")
        } catch (e: CaptureError.AlreadyRunning) {
            // Expected.
        }
        source.waitUntilFinished()
        assertEquals(Fixtures.SOARE_FRAMES, engine.stop().samples.size)

        start(engine, tally = HopTally())
        source.waitUntilFinished()
        assertEquals(Fixtures.SOARE_FRAMES, engine.stop().samples.size)
    }

    /** A source that fails to start leaves the engine usable. */
    @Test(timeout = 60_000)
    fun failedStart() = runBlocking {
        val engine = CaptureEngine(FailingSource())
        try {
            start(engine, tally = HopTally())
            fail("Expected CaptureError.NoInput")
        } catch (e: CaptureError.NoInput) {
            // Expected.
        }
        assertTrue(engine.stop().samples.isEmpty())
    }

    /** A rate change makes a new processor and restarts the take at the new rate. */
    @Test(timeout = 60_000)
    fun rateChangeRestartsTake() = runBlocking {
        val source = ScriptedSource(rate = 48_000.0)
        val engine = CaptureEngine(source)
        val tally = HopTally()

        start(engine, tally = tally)
        source.deliver(FloatArray(4_800) { 0.1f })
        waitUntil { tally.totalSamples == 4_800 }

        source.changeRate(44_100.0)
        waitUntil { tally.rates.size == 2 }
        source.deliver(FloatArray(882) { 0.1f })
        waitUntil { tally.totalSamples == 4_800 + 882 }
        val clip = engine.stop()

        assertEquals(listOf(48_000.0, 44_100.0), tally.rates)
        assertEquals(44_100.0, clip.sampleRate, 0.0)
        assertEquals(882, clip.samples.size)
        assertEquals(4_800, clip.dropped)
    }

    /** A running take silences sound effects. */
    @Test(timeout = 60_000)
    fun captureActivityDuringTake() = runBlocking {
        val source = FixtureSource(ClipLocation.File(Fixtures.soareWav), FixtureSource.Pacing.AsFastAsPossible)
        val engine = CaptureEngine(source)

        start(engine, tally = HopTally())
        assertTrue(CaptureActivity.isActive)
        engine.discard()
    }

    /** Hop size follows the rate. */
    @Test
    fun hopSize() {
        for ((rate, expected) in listOf(48_000.0 to 480, 44_100.0 to 441, 16_000.0 to 160, 22_050.0 to 221)) {
            assertEquals("hopSize($rate)", expected, CaptureEngine.hopSize(rate))
        }
    }
}
