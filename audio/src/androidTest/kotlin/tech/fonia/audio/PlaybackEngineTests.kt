package tech.fonia.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.max

/**
 * Port of PlaybackEngineTests.swift ("Playback engine"). These drive the device's real output, with the player's
 * volume at zero so they stay silent. Each engine is released at the end, which iOS does by dropping it.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackEngineTests {
    private fun silentEngine() = PlaybackEngine(targetContext, outputVolume = 0f)

    private fun toneClip(seconds: Double, rate: Double = 48_000.0) =
        CapturedClip(sine(frequency = 440.0, amplitude = 0.5f, seconds = seconds, sampleRate = rate), rate)

    /** A clip suspends until it has played. */
    @Test(timeout = 60_000)
    fun playsToTheEnd() = runBlocking {
        val engine = silentEngine()
        val time = elapsedMs { assertTrue(engine.play(toneClip(0.5), levels = null)) }
        engine.release()
        assertTrue("$time ms", time >= 450)
    }

    /** Stop ends a clip early. */
    @Test(timeout = 60_000)
    fun stopEndsEarly() = runBlocking {
        val engine = silentEngine()
        val clip = toneClip(10.0)
        val time = elapsedMs {
            val playing = async { engine.play(clip, levels = null) }
            delay(200)
            engine.stop()
            playing.await()
        }
        engine.release()
        assertTrue("$time ms", time < 5_000)
    }

    /** Cancelling the caller ends the clip. */
    @Test(timeout = 60_000)
    fun cancellationEnds() = runBlocking {
        val engine = silentEngine()
        val clip = toneClip(10.0)
        val time = elapsedMs {
            val task = launch { engine.play(clip, levels = null) }
            delay(200)
            task.cancel()
            task.join()
        }
        engine.stop()
        engine.release()
        assertTrue("$time ms", time < 5_000)
    }

    /** A new clip ends the one before it. */
    @Test(timeout = 60_000)
    fun latestClipWins() = runBlocking {
        val engine = silentEngine()
        val long = toneClip(10.0)
        val short = toneClip(0.1)
        val time = elapsedMs {
            val first = async { engine.play(long, levels = null) }
            delay(200)
            assertTrue(engine.play(short, levels = null))
            first.await()
        }
        engine.release()
        assertTrue("$time ms", time < 5_000)
    }

    /** A range plays only that part. */
    @Test(timeout = 60_000)
    fun rangeDuration() = runBlocking {
        val engine = silentEngine()
        val clip = toneClip(5.0)
        val time = elapsedMs { assertTrue(engine.playRange(clip, from = 1.0, to = 1.3)) }
        engine.release()
        assertTrue("$time ms", time >= 250)
        assertTrue("$time ms", time < 4_000)
    }

    /** An empty or inverted range returns at once. */
    @Test(timeout = 60_000)
    fun emptyRange() = runBlocking {
        val engine = silentEngine()
        val clip = toneClip(1.0)
        val time = elapsedMs {
            assertTrue(engine.playRange(clip, from = 0.5, to = 0.5))
            assertTrue(engine.playRange(clip, from = 0.8, to = 0.2))
            assertTrue(engine.playRange(clip, from = 5.0, to = 6.0))
        }
        engine.release()
        assertTrue("$time ms", time < 500)
    }

    /** Range times past the clip are clamped. */
    @Test(timeout = 60_000)
    fun rangeClamps() = runBlocking {
        val engine = silentEngine()
        val time = elapsedMs { assertTrue(engine.playRange(toneClip(0.2), from = -3.0, to = Double.POSITIVE_INFINITY)) }
        engine.release()
        assertTrue("$time ms", time >= 150)
    }

    /** A preloaded file plays, publishes talk while it plays, and closes the mouth at the end. */
    @Test(timeout = 60_000)
    fun talkFollowsClip() = runBlocking {
        val engine = silentEngine()
        val url = ClipLocation.File(Fixtures.soareWav)
        engine.preload(listOf(url))

        val levels = LiveLevels()
        var peak = 0f
        val watcher = launch(Dispatchers.Default) {
            while (isActive) {
                peak = max(peak, levels.talk)
                delay(10)
            }
        }

        val time = elapsedMs { assertTrue(engine.play(url, levels)) }
        watcher.cancelAndJoin()
        engine.release()

        assertTrue("$time ms", time >= 2_000)
        assertTrue("peak $peak", peak > 0.3f)
        assertEquals(0f, levels.talk, 0f)
    }

    /** A file that cannot be decoded reports false. */
    @Test(timeout = 60_000)
    fun missingFile() = runBlocking {
        val engine = silentEngine()
        val missing = ClipLocation.File(File(System.getProperty("java.io.tmpdir"), "no-such-clip.mp3"))
        assertFalse(engine.play(missing, levels = null))
        engine.release()
    }

    /** Clips of different rates play one after another. */
    @Test(timeout = 60_000)
    fun differentRates() = runBlocking {
        val engine = silentEngine()
        assertTrue(engine.play(toneClip(0.1), levels = null))
        assertTrue(engine.play(toneClip(0.1, rate = 16_000.0), levels = null))
        assertTrue(engine.play(ClipLocation.File(Fixtures.popMp3), levels = null))
        engine.release()
    }

    /** A configuration change mid-clip rebuilds the graph and carries on from there. */
    @Test(timeout = 60_000)
    fun rebuildContinues() = runBlocking {
        val engine = silentEngine()
        val clip = toneClip(4.0)
        val time = elapsedMs {
            val playing = async { engine.play(clip, levels = null) }
            delay(3_000)
            engine.handle(AudioGraphEvent.ConfigurationChanged)
            assertTrue(playing.await())
        }
        engine.release()
        // Starting the clip over would take about 7 s.
        assertTrue("$time ms", time >= 3_900)
        assertTrue("$time ms", time < 6_500)
    }

    /** A route change that leaves the engine running does not disturb the clip. */
    @Test(timeout = 60_000)
    fun routeChangeLeavesClip() = runBlocking {
        val engine = silentEngine()
        val clip = toneClip(1.0)
        val time = elapsedMs {
            val playing = async { engine.play(clip, levels = null) }
            delay(300)
            engine.handle(AudioGraphEvent.RouteChanged)
            assertTrue(playing.await())
        }
        engine.release()
        assertTrue("$time ms", time >= 900)
    }

    /** An interruption ends the clip, and the next clip plays on a new graph. */
    @Test(timeout = 60_000)
    fun interruptionEnds() = runBlocking {
        val engine = silentEngine()
        val long = toneClip(10.0)
        val time = elapsedMs {
            val playing = async { engine.play(long, levels = null) }
            delay(200)
            engine.handle(AudioGraphEvent.InterruptionBegan)
            playing.await()
        }
        assertTrue("$time ms", time < 5_000)
        assertTrue(engine.play(toneClip(0.1), levels = null))
        engine.release()
    }
}
