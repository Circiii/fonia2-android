package tech.fonia.app.speaking

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.live.FakeAudioDirector
import tech.fonia.live.LiveBoard

/** The `SpeakingAudio` adapter's bookkeeping on a fake director, in virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class DirectorSpeakingAudioTest {
    private class Rig(scope: TestScope) {
        val nanos = { scope.testScheduler.currentTime * 1_000_000L }
        val board = LiveBoard()
        val director = FakeAudioDirector(scope.backgroundScope, clock = { nanos() }, board = board, promptMillis = 2_000)
        val audio = DirectorSpeakingAudio(director, clock = nanos, startLatencySeconds = 0.1)
        private var seq = 0L

        /** One analysis hop on the board, as the real pipeline would publish it. */
        fun hop(level: Float, speaking: Boolean) {
            val s = board.back()
            s.seq = ++seq
            s.level = level
            s.speaking = speaking
            board.publish()
        }
    }

    @Test fun recordReportsLevelsAboutFiftyTimesASecondAndStopsOnRequest() = runTest {
        val rig = Rig(this)
        val levels = ArrayList<Pair<Float, Boolean>>()
        val feeder = launch { repeat(100) { rig.hop(0.6f, true); delay(10) } } // 1 s of speech, 10 ms hops
        val take = async { rig.audio.record(60.0) { l, s -> levels += l to s } }
        advanceTimeBy(1_000)
        assertTrue(rig.audio.recording)
        rig.audio.stopRecording()
        advanceTimeBy(100)
        runCurrent()
        assertTrue(take.isCompleted)
        assertFalse(rig.audio.recording)
        feeder.cancel()
        // ~1 s at a 20 ms poll after the 150 ms quiet-rule wait: 30–100 updates a second
        assertTrue("updates ${levels.size}", levels.size in 30..60)
        assertTrue(levels.all { it.first in 0f..1f })
        assertTrue(levels.count { it.second } > levels.size / 2)
        assertTrue(rig.director.overlaps().isEmpty())
    }

    @Test fun aStalledSourceReadsAsSilenceSoTheScreenCanEndTheTake() = runTest {
        val rig = Rig(this)
        val levels = ArrayList<Pair<Float, Boolean>>()
        rig.hop(0.8f, true) // one frame, then nothing (a fixture that ran out)
        val take = async { rig.audio.record(60.0) { l, s -> levels += l to s } }
        advanceTimeBy(1_200)
        rig.audio.stopRecording()
        advanceTimeBy(100)
        take.await()
        assertTrue(levels.first().second)
        assertEquals(0f to false, levels.last())
    }

    @Test fun recordEndsAtMaxSeconds() = runTest {
        val rig = Rig(this)
        val take = async { rig.audio.record(0.5) { _, _ -> } }
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(take.isCompleted)
    }

    @Test fun playbackSecondsIsNaNWhenIdleAndFollowsPlaybackWithLatency() = runTest {
        val rig = Rig(this)
        assertTrue(rig.audio.playbackSeconds().isNaN())
        val samples = FloatArray(48_000 * 3) // 3 s at 48 kHz; the fake plays any prompt for 2 s
        val play = async { rig.audio.play(samples, 48_000.0, fromSeconds = 0.5) }
        runCurrent()
        advanceTimeBy(600)
        runCurrent()
        // 0.6 s since the director started speaking, minus 0.1 s latency, from 0.5 s
        assertEquals(1.0, rig.audio.playbackSeconds(), 0.03)
        advanceTimeBy(5_000)
        assertTrue(play.await())
        assertTrue(rig.audio.playbackSeconds().isNaN())
    }

    @Test fun stopPlaybackEndsPlayEarlyAndNothingPlaysWhileRecording() = runTest {
        val rig = Rig(this)
        val play = async { rig.audio.play(FloatArray(48_000 * 10), 48_000.0) }
        advanceTimeBy(300)
        rig.audio.stopPlayback()
        runCurrent()
        assertTrue(play.isCompleted)
        assertTrue(play.await())
        assertTrue(rig.audio.playbackSeconds().isNaN())

        val take = async { rig.audio.record(60.0) { _, _ -> } }
        advanceTimeBy(500)
        assertTrue(rig.audio.recording)
        assertFalse("FxGate: no playback while the mic is open", rig.audio.play(FloatArray(4800), 48_000.0))
        rig.audio.stopRecording()
        advanceTimeBy(100)
        take.await()
        assertTrue(rig.director.overlaps().isEmpty())
    }
}
