package tech.fonia.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sqrt

/**
 * Port-only: the recorded-take mode runs the whole chain with no microphone, as UI tests will. A recorded clip
 * feeds the capture engine in real time, 10 ms hops drive a speech gate, the take ends on silence, and the take is
 * written to M4A and played back.
 */
@RunWith(AndroidJUnit4::class)
class FixtureChainTests {
    @Test(timeout = 60_000)
    fun recordedTakeRunsTheWholeChain() = runBlocking {
        val gate = SpeechGate()
        val levels = LiveLevels()
        val source = FixtureMode.captureSource(
            usesFixtures = true,
            fixtureClip = ClipLocation.File(Fixtures.soareWav),
            fallbackClip = null,
        ) { error("The recorded-take mode never opens the microphone") }
        val engine = CaptureEngine(source)

        // Stands in for :dsp's LiveAnalyzer, which :features wires here.
        engine.start(levels) {
            HopProcessor { samples, offset, count, levels ->
                var sum = 0f
                for (i in offset until offset + count) sum += samples[i] * samples[i]
                val rms = sqrt(sum / count)
                gate.update(isSpeaking = rms > 0.01f)
                levels.publish(rms = rms)
            }
        }
        val waited = elapsedMs { gate.waitForTakeEnd() }
        val clip = engine.stop()

        assertTrue(gate.hasSpoken)
        assertTrue("waited $waited ms", waited < (TakeEnd.DEFAULT_MAXIMUM * 1000).toLong())
        assertEquals(48_000.0, clip.sampleRate, 0.0)
        assertTrue(clip.samples.isNotEmpty())

        withTemporaryFile("m4a") { file ->
            RecordingWriter.writeM4A(clip, file)
            assertTrue(file.length() > 0)
        }
        val playback = PlaybackEngine(targetContext, outputVolume = 0f)
        assertTrue(playback.play(clip, levels))
        assertEquals(0f, levels.talk, 0f)
        playback.release()
    }
}
