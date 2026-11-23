package tech.fonia.features.app

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tech.fonia.design.FoniaSfx
import tech.fonia.design.Sfx
import tech.fonia.design.SfxCue
import tech.fonia.design.SfxGate
import tech.fonia.design.SfxSound
import java.io.File
import kotlin.random.Random

/** The quiet rule (plan 05 §9.3), the anti-stacking rule and the asset set, proven through the real AudioHub. */
@OptIn(ExperimentalCoroutinesApi::class)
class SfxQuietRuleTests {
    private data class Started(val at: Long, val sound: SfxSound)

    private val started = mutableListOf<Started>()
    private val log = mutableListOf<String>()

    @Before
    fun setUp() {
        FoniaSfx.gate.reset()
        started.clear()
        log.clear()
    }

    @After
    fun tearDown() {
        FoniaSfx.gate.reset()
        FoniaSfx.gate.clock = { System.nanoTime() / 1_000_000 }
        FoniaSfx.sink = Sfx { _, _, _ -> }
        FoniaSfx.scheduler = null
        FoniaSfx.log = {}
    }

    private fun TestScope.install() {
        FoniaSfx.gate.clock = { testScheduler.currentTime }
        FoniaSfx.sink = Sfx { sound, _, _ -> started += Started(testScheduler.currentTime, sound) }
        FoniaSfx.log = { log += it }
        FoniaSfx.scheduler = { ms, run -> launch { delay(ms); run() } }
    }

    /** NoAudioBackend with a capture that starts and stops (a microphone that hears nothing). */
    private class QuietMicBackend(base: AudioBackend = NoAudioBackend(null)) : AudioBackend by base {
        override fun captureEngine(source: TakeSource): CaptureEnginePort = object : CaptureEnginePort {
            override suspend fun start(levels: tech.fonia.audio.LiveLevels, makeProcessor: (Double) -> tech.fonia.audio.HopProcessor) {
                Unit // hears nothing: no processor needed
            }

            override suspend fun stop() = tech.fonia.audio.CapturedClip(FloatArray(0), 16_000.0)

            override suspend fun discard() = Unit

            override val captureInfo: tech.fonia.audio.CaptureInfo? = null
        }
    }

    private fun hub() = AudioHub(voices = null, backend = QuietMicBackend(), usesFixtures = true)

    @Test
    fun noEffectStartsWhileCapturingOrWithin150msAroundIt_1000RandomSessions() = runTest {
        install()
        val hub = hub()
        val cues = SfxCue.entries
        val random = Random(2026_11_21)
        // Each capture: when it was asked for, when the microphone really opened, when it closed.
        data class Window(val asked: Long, val opened: Long, val closed: Long)
        val windows = mutableListOf<Window>()
        repeat(1000) {
            repeat(random.nextInt(0, 4)) {
                FoniaSfx.play(cues[random.nextInt(cues.size)], random.nextInt(6))
                delay(random.nextLong(0, 200))
            }
            // A take: "your turn", then the microphone, cues fired while it listens (they must all drop).
            if (random.nextBoolean()) FoniaSfx.play(SfxCue.TakeStart)
            val asked = testScheduler.currentTime // "your turn" may start at this same instant, just before the request
            hub.startListening(tech.fonia.dsp.VoiceGroup.entries.first(), null)
            val opened = testScheduler.currentTime
            repeat(random.nextInt(0, 5)) {
                delay(random.nextLong(1, 400))
                FoniaSfx.play(cues[random.nextInt(cues.size)])
            }
            delay(random.nextLong(1, 300))
            if (random.nextBoolean()) hub.stopListening() else hub.cancelListening()
            val closed = testScheduler.currentTime
            windows += Window(asked, opened, closed)
            // The outcome arrives right after the microphone closes (within the 150 ms tail, often).
            delay(random.nextLong(0, 250))
            FoniaSfx.play(listOf(SfxCue.TakeGood, SfxCue.TakeAlmost, SfxCue.Retake, SfxCue.Pop, SfxCue.Tap)[random.nextInt(5)])
            delay(random.nextLong(0, 300))
        }
        advanceUntilIdle()
        assertTrue("effects did play", started.size > 500)
        for (w in windows) {
            for (s in started) {
                assertTrue("${s.sound} started at ${s.at} inside the capture ${w}", s.at <= w.asked || s.at >= w.closed + SfxGate.QUIET_MS)
                if (s.at <= w.asked) {
                    val end = s.at + s.sound.durationMs + SfxGate.LATENCY_MS
                    assertTrue("${s.sound} (ends $end) was still sounding 150 ms before the mic opened at ${w.opened}", end + SfxGate.QUIET_MS <= w.opened)
                }
            }
        }
        // And the log says the same thing, line by line: no "start" between "capture open-request" and "capture closed".
        var capturing = false
        for (line in log) {
            if (line.startsWith("capture open-request")) capturing = true
            if (line.startsWith("capture closed")) capturing = false
            if (line.startsWith("start ")) assertTrue("log: $line while capturing", !capturing)
        }
    }

    @Test
    fun theMicrophoneWaitsForYourTurnToEnd() = runTest {
        install()
        FoniaSfx.play(SfxCue.TakeStart)
        hub().startListening(tech.fonia.dsp.VoiceGroup.entries.first(), null)
        assertEquals(SfxSound.MicOpen.durationMs + SfxGate.LATENCY_MS + SfxGate.QUIET_MS, testScheduler.currentTime)
    }

    @Test
    fun anOutcomeAskedForJustAfterTheTakeIsDeferredNotLost() = runTest {
        install()
        val hub = hub()
        hub.startListening(tech.fonia.dsp.VoiceGroup.entries.first(), null)
        FoniaSfx.play(SfxCue.Star)
        hub.stopListening()
        val closed = testScheduler.currentTime
        FoniaSfx.play(SfxCue.TakeGood)
        FoniaSfx.play(SfxCue.Tap)
        advanceUntilIdle()
        assertEquals(listOf(Started(closed + SfxGate.QUIET_MS, SfxSound.Good)), started)
    }

    @Test
    fun closeEffectsMergeByPriority() = runTest {
        install()
        FoniaSfx.play(SfxCue.Star)
        FoniaSfx.play(SfxCue.SessionEnd) // replaces the star (same frame, higher priority)
        FoniaSfx.play(SfxCue.Tap) // drops under it
        delay(50)
        FoniaSfx.play(SfxCue.SessionEnd) // duplicate
        delay(200)
        FoniaSfx.play(SfxCue.Tap) // far enough: plays
        assertEquals(listOf("Star", "SessionEnd", "Tap"), started.map { it.sound.name.trimEnd('1', '2', '3') })
    }

    @Test
    fun tapsTakeTurnsSoTwoNeverSoundTheSame() = runTest {
        install()
        repeat(4) {
            FoniaSfx.play(SfxCue.Tap)
            delay(200)
        }
        val taps = started.map { it.sound }
        assertEquals(4, taps.size)
        assertTrue("consecutive taps differ: $taps", taps.zipWithNext().all { (a, b) -> a != b })
    }

    @Test
    fun theSwitchThePhoneAndTheScreenReaderAreHonoured() = runTest {
        install()
        FoniaSfx.muted = true
        FoniaSfx.play(SfxCue.TakeGood)
        FoniaSfx.muted = false
        FoniaSfx.gate.environment = SfxGate.Environment { SfxGate.Environment.State(silent = true) }
        delay(500)
        FoniaSfx.play(SfxCue.TakeGood)
        FoniaSfx.gate.environment = SfxGate.Environment { SfxGate.Environment.State(screenReader = true) }
        delay(500)
        FoniaSfx.play(SfxCue.Tap)
        delay(500)
        FoniaSfx.play(SfxCue.TakeGood)
        assertEquals(listOf(SfxSound.Good), started.map { it.sound })
    }

    @Test
    fun hisEffectsGoThroughTheSameGate() = runTest {
        install()
        val hub = hub()
        hub.startListening(tech.fonia.dsp.VoiceGroup.entries.first(), null)
        hub.effect(SoundEffect.Correct)
        hub.cancelListening()
        delay(500)
        hub.effect(SoundEffect.LevelUp)
        assertEquals(listOf(SfxSound.LevelUp), started.map { it.sound })
    }

    @Test
    fun everySoundHasItsFileAndItsLengthMatchesTheMeasuredOne() {
        val dir = File("../design/src/main/assets/sfx")
        val manifest = File("../../tools/sfx/manifest.json").readText()
        val measured = Regex("\"name\": \"(\\w+)\"[\\s\\S]*?\"duration_s\": ([\\d.]+)").findAll(manifest)
            .associate { it.groupValues[1] to it.groupValues[2].toDouble() }
        for (sound in SfxSound.entries) {
            assertTrue("${sound.fileName} missing", File(dir, sound.fileName).isFile)
            val name = sound.fileName.removeSuffix(".ogg")
            val seconds = measured[name] ?: error("$name not in manifest.json")
            assertTrue("$name lasts ${seconds}s, declared ${sound.durationMs} ms", sound.durationMs >= seconds * 1000 - 0.5)
            assertTrue("$name declared ${sound.durationMs} ms, measured ${seconds}s", sound.durationMs <= seconds * 1000 + 15)
        }
        val total = dir.listFiles()!!.sumOf { it.length() }
        assertTrue("set is $total bytes", total <= 600_000)
    }
}
