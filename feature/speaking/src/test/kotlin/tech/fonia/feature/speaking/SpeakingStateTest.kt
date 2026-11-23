package tech.fonia.feature.speaking

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.live.Clip
import tech.fonia.speaking.FakeSpeakingAudio
import tech.fonia.speaking.SyntheticVoice
import tech.fonia.speaking.SyntheticVoice.Kind
import tech.fonia.speaking.SyntheticVoice.Part

@OptIn(ExperimentalCoroutinesApi::class)
class SpeakingStateTest {
    private val voice = SyntheticVoice.render(
        listOf(
            Part(Kind.PAUSE, 0.4), Part(Kind.SPEECH, 1.6), Part(Kind.PAUSE, 0.3), Part(Kind.HESITATION, 0.6),
            Part(Kind.PAUSE, 0.3), Part(Kind.SPEECH, 1.8), Part(Kind.PAUSE, 1.6), Part(Kind.SPEECH, 1.2), Part(Kind.PAUSE, 0.4),
        ),
    )
    private val clip = Clip(voice.samples, voice.sampleRate)

    @Test
    fun noUmGoesThroughEveryStep() = runTest {
        val audio = FakeSpeakingAudio(clip)
        var kept = 0
        val state = NoUmState(
            this, audio, minSpeakingSeconds = 2.0, worker = StandardTestDispatcher(testScheduler),
            onKeep = { _, _ -> kept++ },
        )
        assertEquals(NoUmStep.Prepare, state.ui.value.step)
        state.start()
        runCurrent()
        assertEquals(NoUmStep.Recording, state.ui.value.step)
        advanceUntilIdle()
        val take = state.ui.value.take
        assertEquals(NoUmStep.Transcript, state.ui.value.step)
        assertNotNull(take)
        take!!
        assertEquals(1, take.fillersCut)
        assertTrue(take.headline, take.headline.startsWith("1 umplutură"))
        assertTrue(take.cleanedLine, take.cleanedLine.startsWith("Ai scos 1 umplutură."))
        assertTrue(take.stats.secondsRemoved > 0.5)

        // Keep the filler: nothing but the long pause is removed any more.
        val id = take.cuts.single()
        state.toggleCut(id)
        advanceUntilIdle()
        val kept1 = state.ui.value.take!!
        assertFalse(id in kept1.cuts)
        assertEquals(0, kept1.stats.fillersRemoved)
        state.toggleCut(id)
        advanceUntilIdle()
        assertTrue(id in state.ui.value.take!!.cuts)

        // Listen, switch A/B mid-way, let it end.
        state.listen()
        runCurrent()
        assertEquals(Version.Clean, state.ui.value.playing)
        advanceTimeBy(1000)
        val p = state.position()
        assertNotNull(p)
        assertEquals(Version.Clean, p!!.version)
        state.setVersion(Version.Original)
        runCurrent()
        assertEquals(Version.Original, state.ui.value.playing)
        assertEquals(Version.Original, state.ui.value.version)
        advanceUntilIdle()
        assertNull(state.ui.value.playing)
        assertNull(state.position())

        state.sayItAgain()
        advanceUntilIdle()
        val ui = state.ui.value
        assertEquals(NoUmStep.Result, ui.step)
        assertTrue(ui.comparison!!, ui.comparison!!.startsWith("Umpluturi: 1 → 1"))
        assertTrue(state.canKeep)
        state.keep()
        assertEquals(1, kept)
        state.close()
    }

    @Test
    fun aShortTakeAsksForMore() = runTest {
        val state = NoUmState(this, FakeSpeakingAudio(clip), minSpeakingSeconds = 100.0, worker = StandardTestDispatcher(testScheduler))
        state.start()
        advanceUntilIdle()
        assertEquals(NoUmStep.Prepare, state.ui.value.step)
        assertEquals(SpeakingStrings.SAY_MORE, state.ui.value.message)
    }

    @Test
    fun gataStopsTheRecording() = runTest {
        val audio = FakeSpeakingAudio(clip)
        val state = NoUmState(this, audio, minSpeakingSeconds = 0.5, worker = StandardTestDispatcher(testScheduler))
        state.start()
        advanceTimeBy(2500)
        state.finishRecording()
        advanceUntilIdle()
        assertEquals(NoUmStep.Transcript, state.ui.value.step)
        assertTrue(state.ui.value.take!!.transcript.summary.durationSeconds < 3.0)
        assertTrue(state.levels.written > 50)
    }

    @Test
    fun minuteGoesThroughEveryStep() = runTest {
        var seen: SelfView? = null
        val state = MinuteState(
            this, FakeSpeakingAudio(clip), minSpeakingSeconds = 2.0, worker = StandardTestDispatcher(testScheduler),
            onSelfView = { _, v -> seen = v },
        )
        state.moveRung(-1)
        assertEquals(0, state.ui.value.rung)
        state.moveRung(2)
        assertEquals(2, state.ui.value.rung)
        assertEquals(SpeakingStrings.TOPICS[2], state.ui.value.topic)
        state.start()
        advanceUntilIdle()
        assertEquals(MinuteStep.SelfCheck, state.ui.value.step)
        state.answer(SelfView.SoSo)
        assertEquals(SelfView.SoSo, seen)
        val ui = state.ui.value
        assertEquals(MinuteStep.Result, ui.step)
        assertNotNull(ui.transcript)
        assertTrue(ui.lines.isNotEmpty())
        assertNotNull(ui.worked)
        assertNotNull(ui.toTry)
        assertTrue(ui.spans.isNotEmpty())
        state.listen()
        runCurrent()
        assertTrue(state.ui.value.playing)
        advanceUntilIdle()
        assertFalse(state.ui.value.playing)
        state.close()
    }

    @Test
    fun levelHistoryKeepsTheNewestFirst() {
        val h = LevelHistory(4)
        h.reset()
        for (i in 1..6) h.push(i / 10f, i % 2 == 0, 0.02)
        assertEquals(0.6f, h.level(0), 1e-6f)
        assertEquals(0.3f, h.level(3), 1e-6f)
        assertEquals(0f, h.level(4), 0f)
        assertEquals(0.06, h.speakingSeconds, 1e-9)
    }
}
