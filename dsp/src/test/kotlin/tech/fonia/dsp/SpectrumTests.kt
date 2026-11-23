package tech.fonia.dsp

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SpectrumTests {
    @Test
    fun aBinCentredSineGetsItsExpectedPower() {
        val length = 2048
        val bin = 100
        val amplitude = 0.5f
        val signal = FloatArray(length)
        addSine(signal, hz = bin.toFloat() * testRate.toFloat() / length.toFloat(), phase = 0f, amplitude = amplitude, rate = testRate)
        val transform = PowerTransform(length)
        val power = FloatArray(transform.binCount)
        transform.transform(signal, 0, power)
        assertTrue(abs(power[bin] - amplitude * amplitude / 4f) < 1e-4f, "power ${power[bin]}")
        assertEquals(bin, argmax(power))
        assertTrue(power[bin + 5] < power[bin] * 1e-6f, "leak ${power[bin + 5]}")
    }

    @Test
    fun oddSamplesAreNotLostByThePackedTransform() {
        // Energy alone in odd-index samples must still appear: the real transform packs samples in pairs.
        val signal = FloatArray(1024)
        signal[1] = 1f
        val transform = PowerTransform(1024)
        val power = FloatArray(transform.binCount)
        transform.transform(signal, 0, power)
        assertTrue(abs(power[200] - 1f / (1024 * 1024).toFloat()) < 1e-9f, "power ${power[200]}")
    }

    @Test
    fun frameSpectrumPutsToneAtItsFrequency() {
        val spectrum = FrameSpectrum(2048)
        val signal = FloatArray(2048)
        addSine(signal, hz = 3000f, phase = 0.3f, amplitude = 0.5f, rate = testRate)
        spectrum.compute(signal)
        val peak = argmax(spectrum.power)
        assertTrue(abs(peak.toFloat() * testRate.toFloat() / 2048f - 3000f) < 30f, "peak bin $peak")
    }

    @Test
    fun setupsAreSharedPerLength() {
        assertSame(SharedDFTSetup.forLength(4096), SharedDFTSetup.forLength(4096))
        assertNotSame(SharedDFTSetup.forLength(4096), SharedDFTSetup.forLength(2048))
    }

    @Test
    fun noiseFloorLearnsQuicklyThenCreeps() {
        val floor = NoiseFloor(initial = -75f, live = DSPThresholds.v1.live)
        repeat(100) { floor.update(levelDb = -40f, seconds = 0.01f) }
        assertTrue(floor.value > -60f, "floor ${floor.value}")
        val afterLearning = floor.value
        repeat(150) { floor.update(levelDb = -20f, seconds = 0.01f) }
        assertTrue(floor.value - afterLearning < 25f, "floor ${floor.value}")
        repeat(100) { floor.update(levelDb = -70f, seconds = 0.01f) }
        assertTrue(floor.value < -60f, "floor ${floor.value}")
    }

    @Test
    fun voiceActivityHangsOverShortDips() {
        val voice = VoiceActivity(marginDb = 10f, hangSeconds = 0.2)
        val onset = voice.update(levelDb = -30f, floorDb = -60f, now = 0.0)
        val during = voice.update(levelDb = -60f, floorDb = -60f, now = 0.1)
        val after = voice.update(levelDb = -60f, floorDb = -60f, now = 0.3)
        assertTrue(onset)
        assertTrue(during)
        assertFalse(after)
    }
}

class PitchTests {
    private fun estimate(signal: FloatArray, rate: Double = testRate): PitchEstimate {
        val tracker = YINPitchTracker(rate, DSPThresholds.v1.pitch)
        return tracker.estimate(signal, 0)
    }

    @ParameterizedTest
    @ValueSource(floats = [80f, 100f, 150f, 220f, 330f, 440f, 660f, 880f])
    fun readsPureSines(hz: Float) {
        val signal = FloatArray(sampleCount(0.06))
        addSine(signal, hz = hz, phase = 0.4f, amplitude = 0.4f, rate = testRate)
        val result = estimate(signal)
        assertTrue(abs(result.hz - hz) / hz < 0.01f, "read ${result.hz}")
        assertTrue(result.clarity > 0.95f, "clarity ${result.clarity}")
    }

    @ParameterizedTest
    @CsvSource("100, 44100.0", "220, 44100.0", "220, 32000.0", "220, 16000.0", "440, 96000.0")
    fun sampleRateIsAParameter(hz: Float, rate: Double) {
        val signal = FloatArray(sampleCount(0.08, rate))
        addSine(signal, hz = hz, phase = 0f, amplitude = 0.4f, rate = rate)
        val read = estimate(signal, rate).hz
        assertTrue(abs(read - hz) / hz < 0.015f, "read $read")
    }

    @ParameterizedTest
    @ValueSource(floats = [110f, 150f, 200f, 280f])
    fun readsHarmonicSignalsWithAStrongSecondHarmonic(hz: Float) {
        val signal = FloatArray(sampleCount(0.06))
        for (k in 1..8) {
            addSine(signal, hz = hz * k.toFloat(), phase = k.toFloat(), amplitude = if (k == 2) 0.5f else 0.15f, rate = testRate)
        }
        val read = estimate(signal).hz
        assertTrue(abs(read - hz) / hz < 0.015f, "read $read")
    }

    @ParameterizedTest
    @ValueSource(floats = [150f, 200f, 300f])
    fun findsAMissingFundamental(hz: Float) {
        val signal = FloatArray(sampleCount(0.06))
        for (k in 2..8) addSine(signal, hz = hz * k.toFloat(), phase = k.toFloat(), amplitude = 0.2f, rate = testRate)
        val read = estimate(signal).hz
        assertTrue(abs(read - hz) / hz < 0.015f, "read $read")
    }

    @Test
    fun vowelHasHighClarity() {
        val result = estimate(vowel(0.06, f0 = 220f))
        assertTrue(abs(result.hz - 220f) < 3f, "read ${result.hz}")
        assertTrue(result.clarity > 0.9f, "clarity ${result.clarity}")
    }

    @Test
    fun noiseHasLowClarity() {
        val hiss = estimate(sNoise(0.06, amp = 0.2f)).clarity
        assertTrue(hiss < 0.6f, "clarity $hiss")
        val generator = LinearCongruential(seed = 9)
        val white = FloatArray(sampleCount(0.06)) { generator.unit() * 0.4f - 0.2f }
        val clarity = estimate(white).clarity
        assertTrue(clarity < 0.6f, "clarity $clarity")
    }

    @Test
    fun silenceHasNoPitch() {
        assertEquals(PitchEstimate.none, estimate(silence(0.06)))
    }

    @Test
    fun hissOnTopOfAVoiceStillReadsAsVoiced() {
        // The low-pass before YIN is what lets Z and J read as voiced.
        val voice = vowel(0.06, f0 = 180f, amp = 0.1f)
        val hiss = sNoise(0.06, amp = 0.1f)
        val mixed = FloatArray(minOf(voice.size, hiss.size)) { voice[it] + hiss[it] }
        val result = estimate(mixed)
        assertTrue(result.clarity > 0.75f, "clarity ${result.clarity}")
        assertTrue(abs(result.hz - 180f) / 180f < 0.03f, "read ${result.hz}")
    }
}

class TrillTests {
    private fun detect(signal: FloatArray): TrillResult {
        val (values, rate) = envelope(signal)
        val detector = TrillDetector(rate.toDouble(), DSPThresholds.v1.trill)
        return detector.detect(values, values.size - detector.windowCount)
    }

    @Test
    fun findsA25HzTrill() {
        val result = detect(trillSignal(0.4, rate = 25.0))
        assertTrue(result.isTrill, "$result")
        assertTrue(result.rate > 22f && result.rate < 28.5f, "rate ${result.rate}")
        assertTrue(result.strength > 0.5f, "strength ${result.strength}")
    }

    @Test
    fun findsA30HzTrill() {
        assertTrue(detect(trillSignal(0.4, rate = 30.0)).isTrill)
    }

    @Test
    fun ignoresASteadyVowel() {
        assertFalse(detect(withNoise(vowel(0.4))).isTrill)
    }

    @Test
    fun ignoresASlowWobble() {
        assertFalse(detect(wobble(0.4, rate = 5.0)).isTrill)
    }

    @Test
    fun aSingleDipIsNotATrill() {
        assertFalse(detect(withNoise(tapSignal(closureMs = 26.0))).isTrill)
    }

    @Test
    fun clipAnalysisFindsATrillSegment() {
        val summary = analyze(withNoise(silence(0.2) + trillSignal(0.6, rate = 25.0) + silence(0.2)))
        val first = assertNotNull(summary.trills.firstOrNull())
        assertTrue(first.end - first.start > 0.3, "$first")
    }
}

class TapTests {
    private fun candidates(signal: FloatArray): List<TapCandidate> {
        val (values, rate) = envelope(signal)
        return findTapCandidates(values, envelopeRate = rate, thresholds = DSPThresholds.v1.tap)
    }

    @ParameterizedTest
    @ValueSource(doubles = [24.0, 26.0, 29.0])
    fun findsOneTapAtTheRomanianClosure(closureMs: Double) {
        val found = candidates(withNoise(tapSignal(closureMs = closureMs)))
        assertEquals(1, found.size, "$found")
        val tap = assertNotNull(found.firstOrNull())
        assertTrue(tap.score >= 0.5f, "$tap")
    }

    @Test
    fun aClosureInsideThePriorOutscoresOneOutsideIt() {
        val settings = DSPThresholds.v1.tap
        assertEquals(1f, tapScore(widthMs = 26f, thresholds = settings))
        assertTrue(tapScore(widthMs = 15f, thresholds = settings) < 1f)
        assertTrue(tapScore(widthMs = 38f, thresholds = settings) < 1f)
        assertEquals(0f, tapScore(widthMs = 60f, thresholds = settings))
    }

    @Test
    fun findsNoneInAPlainVowel() {
        assertTrue(candidates(withNoise(vowel(0.4))).isEmpty())
    }

    @Test
    fun aLongStopClosureIsNotATap() {
        assertTrue(candidates(withNoise(vowel(0.18) + silence(0.09) + vowel(0.18))).isEmpty())
    }

    @Test
    fun clipAnalysisKeepsTapsWithVoicedFlanks() {
        val summary = analyze(withNoise(silence(0.2) + tapSignal(closureMs = 26.0) + silence(0.2)))
        assertEquals(1, summary.taps.size, "${summary.taps}")
        val tap = assertNotNull(summary.taps.firstOrNull())
        assertTrue(abs(tap.time - 0.393) < 0.03, "$tap")
    }

    @Test
    fun aDipBetweenUnvoicedStretchesIsNotATap() {
        val hiss = shaped(sNoise(0.4)) { t -> if (abs(t - 0.2) < 0.013) 0.08f else 1f }
        assertTrue(analyze(withNoise(silence(0.2) + hiss + silence(0.2))).taps.isEmpty())
    }

    @Test
    fun aDipWithVoicingOnOneSideOnlyIsNotATap() {
        val signal = vowel(0.2) + silence(0.026) + sNoise(0.2)
        assertTrue(analyze(withNoise(silence(0.2) + signal + silence(0.2))).taps.isEmpty())
    }
}
