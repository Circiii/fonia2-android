package tech.fonia.dsp

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.lang.management.ManagementFactory
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun stream(signal: FloatArray, analyzer: LiveAnalyzer, hop: Int = 480): List<LiveFrame> =
    (0 until signal.size step hop).map { start -> analyzer.process(signal, start, minOf(signal.size, start + hop) - start) }

private fun makeAnalyzer(rate: Double = testRate, group: VoiceGroup = VoiceGroup.FEMALE, calibration: TalkerCalibration? = null): LiveAnalyzer =
    LiveAnalyzer(sampleRate = rate, group = group, calibration = calibration)

/** A second of room tone, so the floor has settled as it would before anyone speaks. */
private fun roomTone(seconds: Double = 1.5): FloatArray = withNoise(silence(seconds), amp = 0.001f)

class LiveAnalyzerTests {
    @Test
    fun roomToneStaysAtTheBottomOfTheMeter() {
        val frames = stream(roomTone(), makeAnalyzer())
        val last = assertNotNull(frames.lastOrNull())
        assertTrue(last.level < 0.15f, "$last")
        assertFalse(last.speaking)
        assertEquals(0f, last.pitchHz)
    }

    @Test
    fun aVoiceLiftsTheLevelAndReadsItsPitch() {
        val frames = stream(roomTone() + withNoise(vowel(0.5, f0 = 220f)), makeAnalyzer())
        val last = assertNotNull(frames.lastOrNull())
        assertTrue(last.level > 0.5f, "$last")
        assertTrue(last.speaking)
        assertTrue(abs(last.pitchHz - 220f) < 4f, "$last")
        assertTrue(last.clarity > 0.85f, "$last")
        assertFalse(last.fricativePresent)
        assertTrue(last.rms > 0.05f, "$last")
        assertTrue(last.floorDb < -40f, "$last")
    }

    @Test
    fun sNessGoesHighForS() {
        val frames = stream(roomTone() + withNoise(sNoise(0.4)), makeAnalyzer())
        val last = assertNotNull(frames.lastOrNull())
        assertTrue(last.fricativePresent, "$last")
        assertTrue(last.sNess > 0.7f, "$last")
        assertTrue(last.fricativeStrength > 0.3f, "$last")
        assertFalse(last.fricativeVoiced)
        assertTrue(last.blowStrength > 0.3f, "$last")
    }

    @Test
    fun sNessGoesLowForSh() {
        val frames = stream(roomTone() + withNoise(shNoise(0.4)), makeAnalyzer())
        val last = assertNotNull(frames.lastOrNull())
        assertTrue(last.fricativePresent, "$last")
        assertTrue(last.sNess < 0.3f, "$last")
    }

    @Test
    fun aTrillRaisesTrillStrength() {
        val frames = stream(roomTone() + withNoise(trillSignal(0.6, rate = 25.0)), makeAnalyzer())
        val late = frames.takeLast(20)
        assertTrue(late.any { it.trillDetected })
        assertTrue(assertNotNull(late.map { it.trillStrength }.maxOrNull()) > 0.5f)
    }

    @Test
    fun aSteadyVowelDoesNotTrill() {
        val frames = stream(roomTone() + withNoise(vowel(0.6)), makeAnalyzer())
        assertTrue(frames.all { !it.trillDetected })
    }

    @Test
    fun airflowReadsAsBlowing() {
        val frames = stream(roomTone() + withNoise(bandNoise(0.4, 300f, 6000f, amp = 0.2f)), makeAnalyzer())
        val last = assertNotNull(frames.lastOrNull())
        assertTrue(last.blowStrength > 0.5f, "$last")
    }

    @Test
    fun aFloorLearnsANoisyRoom() {
        val generator = LinearCongruential(seed = 5)
        val noisy = FloatArray(sampleCount(3.0)) { (generator.unit() * 2f - 1f) * 0.02f }
        val frames = stream(noisy, makeAnalyzer())
        val last = assertNotNull(frames.lastOrNull())
        assertTrue(last.level < 0.3f, "$last")
    }

    @Test
    fun calibrationMovesTheBoundary() {
        val hiss = roomTone() + withNoise(sNoise(0.4))
        val high = TalkerCalibration(
            anchors = (0 until 5).flatMap {
                listOf(
                    SibilantAnchor(place = SibilantPlace.S, centroidHz = 14000f, peakHz = 13000f, bandRatioDb = 2f, tiltDb = 20f),
                    SibilantAnchor(place = SibilantPlace.SH, centroidHz = 11000f, peakHz = 10000f, bandRatioDb = 8f, tiltDb = 10f),
                )
            },
        )
        val plain = assertNotNull(stream(hiss, makeAnalyzer()).lastOrNull())
        val calibrated = assertNotNull(stream(hiss, makeAnalyzer(calibration = high)).lastOrNull())
        assertTrue(calibrated.sNess < plain.sNess, "${calibrated.sNess} vs ${plain.sNess}")
    }

    @Test
    fun aLowSampleRateTurnsSibilantsOff() {
        val rate = 16000.0
        val signal = withNoise(silence(1.0, rate), amp = 0.001f) + withNoise(bandNoise(0.4, 2000f, 7000f, amp = 0.1f, rate = rate))
        val frames = stream(signal, makeAnalyzer(rate = rate), hop = 160)
        assertTrue(frames.all { !it.fricativePresent })
    }

    @ParameterizedTest
    @ValueSource(doubles = [44100.0, 48000.0, 96000.0])
    fun sampleRateIsAParameter(rate: Double) {
        val signal = withNoise(silence(1.0, rate), amp = 0.001f) + vowel(0.5, f0 = 200f, rate = rate)
        val last = assertNotNull(stream(signal, makeAnalyzer(rate = rate), hop = (rate / 100).toInt()).lastOrNull())
        assertTrue(abs(last.pitchHz - 200f) < 4f, "$last")
    }

    @Test
    fun shortHopsReturnTheHeldReadingBetweenAnalyses() {
        val analyzer = makeAnalyzer()
        val signal = roomTone(0.5) + vowel(0.2)
        val frames = stream(signal, analyzer, hop = 64)
        val distinct = frames.zipWithNext().count { (a, b) -> a != b }
        assertTrue(distinct < frames.size / 4, "$distinct of ${frames.size}")
    }

    @Test
    fun aChunkLongerThanTheRingIsHandled() {
        val frames = stream(roomTone(1.0) + vowel(0.5, f0 = 220f), makeAnalyzer(), hop = 12000)
        val last = assertNotNull(frames.lastOrNull())
        assertTrue(abs(last.pitchHz - 220f) < 5f, "$last")
    }

    @Test
    fun anEmptyHopIsHarmless() {
        val analyzer = makeAnalyzer()
        assertEquals(LiveFrame.silent, analyzer.process(FloatArray(0)))
    }

    @Test
    fun resetForgetsEverything() {
        val analyzer = makeAnalyzer()
        val signal = roomTone(0.5) + vowel(0.5, f0 = 220f)
        val first = assertNotNull(stream(signal, analyzer).lastOrNull())
        assertTrue(first.speaking)
        analyzer.reset()
        val second = assertNotNull(stream(signal, analyzer).lastOrNull())
        assertEquals(first, second)
    }

    @Test
    fun processingAVoicedHopIsFarUnderBudget() {
        val signal = roomTone(0.5) + vowel(1.0, f0 = 220f) + sNoise(0.5)
        val hop = 480
        // Swift's build is compiled ahead of time; on the JVM the same run first brings the JIT to
        // steady state, on a separate analyzer, so the measured hops are the ones a phone would run.
        val warmUp = makeAnalyzer()
        val scratch = LiveFrame.silent
        repeat(3) {
            var start = 0
            while (start < signal.size - hop) {
                warmUp.process(signal, start, hop, scratch)
                start += hop
            }
            warmUp.reset()
        }
        val analyzer = makeAnalyzer()
        var elapsed = 0.0
        var worst = 0.0
        var count = 0
        var start = 0
        while (start < signal.size - hop) {
            val began = System.nanoTime()
            analyzer.process(signal, start, hop)
            val seconds = (System.nanoTime() - began).toDouble() / 1e9
            elapsed += seconds
            worst = maxOf(worst, seconds)
            count += 1
            start += hop
        }
        val average = elapsed / count.toDouble()
        println("LiveAnalyzer hop on this JVM: average ${"%.3f".format(average * 1000)} ms, worst ${"%.3f".format(worst * 1000)} ms")
        // Scheduling can stretch a single hop, so only the average is held to the 5 ms budget.
        assertTrue(average < 0.005, "average $average s")
    }
}

class LiveAllocationTests {
    @Test
    fun processDoesNotAllocateOnTheAudioPath() {
        val analyzer = makeAnalyzer()
        val signal = roomTone(0.5) + vowel(0.5, f0 = 220f) + sNoise(0.3) + trillSignal(0.5, rate = 25.0)
        val hop = 480
        val hops = signal.size / hop
        val reading = LiveFrame.silent
        // One full pass first: classes load and caches fill on first use, once.
        fun run() {
            var i = 0
            while (i < hops) {
                analyzer.process(signal, i * hop, hop, reading)
                i += 1
            }
        }
        run()
        analyzer.reset()
        // On the JVM that first-use work (constant and call-site linking) can also land in a later
        // pass, when the JIT deoptimizes into a path it compiled before the path ever ran. It happens
        // once per JVM, so warm up until a whole pass allocates nothing; an allocation in the code
        // itself recurs on every pass and still fails below.
        val warmUp = ArrayList<Long>()
        while (warmUp.size < 10) {
            val bytes = countingAllocatedBytes(::run)
            analyzer.reset()
            warmUp.add(bytes)
            if (bytes == 0L) break
        }
        assertEquals(0L, countingAllocatedBytes(::run), "warm-up passes allocated $warmUp bytes")
    }
}

// MARK: - Counting allocations on this thread

/**
 * The JVM's own per-thread allocation counter, standing in for libmalloc's `malloc_logger` hook.
 * Counts only this thread, since tests may run in parallel. The sanity allocation proves the counter
 * moves on this JVM before trusting a zero.
 */
private fun countingAllocatedBytes(body: () -> Unit): Long {
    val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        ?: error("This JVM cannot count allocations per thread")
    check(bean.isThreadAllocatedMemorySupported) { "This JVM cannot count allocations per thread" }
    bean.isThreadAllocatedMemoryEnabled = true
    val probeStart = bean.currentThreadAllocatedBytes
    sink = ByteArray(4096)
    val probeEnd = bean.currentThreadAllocatedBytes
    check(probeEnd - probeStart >= 4096) { "The allocation counter did not see a 4 KB array" }
    val before = bean.currentThreadAllocatedBytes
    body()
    val after = bean.currentThreadAllocatedBytes
    return after - before
}

/** Keeps the probe allocation observable, so the JIT cannot remove it. */
@Volatile
private var sink: Any? = null

class LiveReadingTests {
    private fun frame(
        present: Boolean = true, sNess: Float = 0.9f, voiced: Boolean = false, clarity: Float = 0.2f, speaking: Boolean = true,
        strength: Float = 0.5f, diffuse: Boolean = false, trill: Boolean = false, trillStrength: Float = 0f, pitch: Float = 0f,
    ): LiveFrame = LiveFrame.silent.copy(
        fricativePresent = present, sNess = sNess, fricativeVoiced = voiced, clarity = clarity, speaking = speaking,
        fricativeStrength = strength, fricativeDiffuse = diffuse, trillDetected = trill, trillStrength = trillStrength, pitchHz = pitch,
    )

    @Test
    fun rightPlaceIsInTheZone() {
        val reading = sibilantReading(frame(sNess = 0.9f), expected = SibilantPlace.S, voiced = false)
        assertTrue(reading.ok)
        assertTrue(assertNotNull(reading.value) >= DSPThresholds.v1.live.meterZone)
    }

    @Test
    fun otherPlaceIsBelowTheZone() {
        val reading = sibilantReading(frame(sNess = 0.9f), expected = SibilantPlace.SH, voiced = false)
        assertFalse(reading.ok)
        assertEquals(LiveFault.OTHER_PLACE, reading.fault)
        assertTrue(assertNotNull(reading.value) < DSPThresholds.v1.live.meterZone)
    }

    @Test
    fun aVowelHasNothingToPlace() {
        val reading = sibilantReading(frame(present = false), expected = SibilantPlace.S, voiced = false)
        assertNull(reading.value)
        assertEquals(LiveFault.NO_SOUND, reading.fault)
    }

    @Test
    fun aDevoicedZIsFlagged() {
        assertEquals(LiveFault.UNVOICED, sibilantReading(frame(sNess = 0.9f), expected = SibilantPlace.S, voiced = true).fault)
        assertTrue(sibilantReading(frame(sNess = 0.9f, voiced = true, clarity = 0.9f), expected = SibilantPlace.S, voiced = true).ok)
    }

    @Test
    fun trillMeterRespondsToStrength() {
        assertTrue(trillReading(frame(trill = true, trillStrength = 0.9f)).ok)
        val weak = trillReading(frame(trillStrength = 0.1f))
        assertFalse(weak.ok)
        assertEquals(LiveFault.NO_TRILL, weak.fault)
        assertNull(trillReading(frame(speaking = false)).value)
    }

    @Test
    fun voiceMeterRespondsToPitch() {
        assertTrue(voiceReading(frame(clarity = 0.9f, pitch = 200f)).ok)
        assertEquals(LiveFault.UNVOICED, voiceReading(frame(pitch = 0f)).fault)
    }

    @Test
    fun thMeterCatchesASharpS() {
        assertEquals(LiveFault.S_LIKE, englishTHReading(frame(sNess = 0.9f, strength = 0.8f)).fault)
        assertTrue(englishTHReading(frame(sNess = 0.9f, strength = 0.2f)).ok)
        assertTrue(englishTHReading(frame(sNess = 0.9f, strength = 0.8f, diffuse = true)).ok)
    }

    @Test
    fun utteranceEndsAfterQuietAndPassesWhenMostlyRight() {
        val tracker = UtteranceTracker()
        repeat(10) { assertNull(tracker.push(LiveReading(value = 0.9f, ok = true), active = true, seconds = 0.01)) }
        var verdict: UtteranceVerdict? = null
        repeat(30) {
            if (verdict == null) verdict = tracker.push(LiveReading(value = null, ok = false), active = false, seconds = 0.01)
        }
        val result = assertNotNull(verdict)
        assertTrue(result.ok)
        assertTrue(abs(result.seconds - 0.1) < 1e-9)
    }

    @Test
    fun utteranceNamesTheMostHeardFault() {
        val tracker = UtteranceTracker()
        repeat(5) { tracker.push(LiveReading(value = null, ok = false, fault = LiveFault.NO_SOUND), active = true, seconds = 0.02) }
        repeat(3) { tracker.push(LiveReading(value = 0.3f, ok = false, fault = LiveFault.OTHER_PLACE), active = true, seconds = 0.02) }
        var verdict: UtteranceVerdict? = null
        repeat(30) {
            if (verdict == null) verdict = tracker.push(LiveReading(value = null, ok = false), active = false, seconds = 0.02)
        }
        val result = assertNotNull(verdict)
        assertFalse(result.ok)
        assertEquals(LiveFault.OTHER_PLACE, result.fault)
    }

    @Test
    fun aClickIsNotAnAttempt() {
        val tracker = UtteranceTracker()
        tracker.push(LiveReading(value = 0.9f, ok = true), active = true, seconds = 0.03)
        var verdict: UtteranceVerdict? = null
        repeat(40) {
            if (verdict == null) verdict = tracker.push(LiveReading(value = null, ok = false), active = false, seconds = 0.01)
        }
        assertNull(verdict)
    }
}
