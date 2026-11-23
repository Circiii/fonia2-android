package tech.fonia.live

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import tech.fonia.dsp.encodeWAV
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** docs/v2/02 §4: every call order keeps playback and capture apart, with the guard and tail of the quiet rule. */
class AudioDirectorRulesTest {
    private val rule = QuietRule(micOpenDelayMillis = 150, tailMillis = 150, minGapMillis = 120)

    private fun TestScope.director(scope: CoroutineScope = backgroundScope, source: ((LiveTarget) -> PcmSource)? = null) =
        FakeAudioDirector(scope, NanoClock { testScheduler.currentTime * 1_000_000L }, rule, sourceFor = source)

    private fun assertQuietRule(d: FakeAudioDirector) {
        assertEquals(emptyList(), d.overlaps(), "playback and capture overlapped")
        val ms = 1_000_000L
        val mics = d.timeline.filter { it.kind == FakeAudioDirector.Kind.Mic }
        val sounds = d.timeline.filter { it.kind != FakeAudioDirector.Kind.Mic }
        for (mic in mics) {
            for (s in sounds) {
                if (s.endNanos <= mic.startNanos) {
                    assertTrue(mic.startNanos - s.endNanos >= rule.micOpenDelayMillis * ms, "mic opened too soon after $s: $mic")
                }
                if (s.startNanos >= mic.endNanos) {
                    assertTrue(s.startNanos - mic.endNanos >= rule.tailMillis * ms, "$s started inside the tail of $mic")
                }
            }
        }
    }

    @Test
    fun aLessonTurnFollowsTheRule() = runTest {
        val d = director()
        assertTrue(d.play(PromptRef.Asset("voice/s/model.m4a")))
        assertEquals(AudioState.Settling, d.state.value)
        d.openMic(LiveTarget.of(tech.fonia.model.SoundId.S))
        assertEquals(AudioState.Listening, d.state.value)
        assertTrue(d.micGate.isOpen)
        d.earcon(Earcon.Pop)
        delay(1000)
        d.closeMic()
        assertEquals(AudioState.Idle, d.state.value)
        d.earcon(Earcon.Correct)
        delay(200)
        d.earcon(Earcon.Correct)
        d.play(PromptRef.Asset("voice/feedback.m4a"))
        advanceUntilIdle()
        assertQuietRule(d)
        assertEquals(listOf("Prompt", "Mic", "Earcon", "Prompt"), d.timeline.map { it.kind.name }, "the earcon inside the mic was dropped")
    }

    @Test
    fun playClosesAnOpenMicFirst() = runTest {
        val d = director()
        d.openMic(LiveTarget.AnySound)
        delay(500)
        assertTrue(d.play(PromptRef.Asset("a")))
        assertFalse(d.micGate.isOpen)
        assertQuietRule(d)
    }

    @Test
    fun suspendStopsEverythingUntilResume() = runTest {
        val d = director()
        val playing = launch { assertFalse(d.play(PromptRef.Asset("long")), "a stopped prompt did not play to the end") }
        d.promptMillis = 5000
        delay(100)
        d.suspendAll(SuspendReason.Background)
        playing.join()
        assertEquals(AudioState.Suspended(SuspendReason.Background), d.state.value)
        assertFailsWith<AudioSuspendedException> { d.openMic(LiveTarget.AnySound) }
        assertFalse(d.play(PromptRef.Asset("x")))
        d.resume()
        assertEquals(AudioState.Idle, d.state.value)
        d.openMic(LiveTarget.AnySound)
        d.suspendAll(SuspendReason.FocusLost)
        runCurrent()
        assertFalse(d.micGate.isOpen, "suspend closes the mic")
        assertEquals(AudioState.Suspended(SuspendReason.FocusLost), d.state.value)
        assertQuietRule(d)
    }

    @Test
    fun randomCallOrdersNeverOverlap() {
        for (seed in 1..150) {
            runTest {
                val d = director()
                val r = Random(seed)
                val jobs = (0 until 5).map {
                    launch {
                        repeat(8) {
                            delay(r.nextLong(0, 400))
                            when (r.nextInt(7)) {
                                0, 1 -> d.play(PromptRef.Asset("p$seed"))
                                2 -> runCatching { d.openMic(LiveTarget.AnySound) }
                                3 -> d.closeMic()
                                4 -> d.earcon(Earcon.entries[r.nextInt(Earcon.entries.size)])
                                5 -> if (r.nextInt(4) == 0) d.suspendAll(SuspendReason.Other) else d.earcon(Earcon.Pop)
                                else -> d.resume()
                            }
                        }
                    }
                }
                jobs.forEach { it.join() }
                d.closeMic()
                advanceUntilIdle()
                assertQuietRule(d)
                assertFalse(d.micGate.isOpen)
            }
        }
    }

    @Test
    fun theFakeRunsTheRealPipelineFromAWav() = runTest {
        val audio = Signals.concat(Signals.quiet(1.3), Signals.hiss(8500.0, 0.8))
        val wav = encodeWAV(audio, 48_000)
        val d = director(source = { WavPcmSource.fromBytes(wav) })
        val info = d.openMic(LiveTarget.of(tech.fonia.model.SoundId.S))
        assertEquals("fixture", info.audioSource)
        // The analysis thread is real; give it real time to drain the (non-real-time) file.
        Thread.sleep(300)
        val clip = d.closeMic()
        assertEquals(audio.size, clip.samples.size)
        assertTrue(d.board.lastPublishedSeq > 200)
        assertTrue(d.pipeline.stats.summary().hops > 200)
    }
}
