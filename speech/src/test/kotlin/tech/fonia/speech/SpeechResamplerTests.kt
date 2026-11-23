package tech.fonia.speech

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/** FoniaSpeechTests/SpeechResamplerTests.swift, suite "Resampling". */
@DisplayName("Resampling")
class SpeechResamplerTests {
    private fun sine(hertz: Double, rate: Double, seconds: Double): FloatArray =
        FloatArray((rate * seconds).toInt()) { (0.5 * sin(2 * PI * hertz * it.toDouble() / rate)).toFloat() }

    private fun zeroCrossingRate(samples: FloatArray, rate: Double): Double {
        var crossings = 0
        for (k in 1 until samples.size) if ((samples[k - 1] < 0) != (samples[k] < 0)) crossings += 1
        return crossings.toDouble() / 2 / (samples.size.toDouble() / rate)
    }

    @Test
    @DisplayName("returns 16 kHz audio untouched")
    fun passThrough() {
        val samples = floatArrayOf(0.1f, 0.2f, 0.3f)
        assertContentEquals(samples, SpeechResampler.resample(samples, from = 16_000.0))
    }

    @Test
    @DisplayName("returns an empty clip as it is")
    fun empty() {
        assertTrue(SpeechResampler.resample(FloatArray(0), from = 48_000.0).isEmpty())
    }

    @ParameterizedTest
    @ValueSource(doubles = [48_000.0, 44_100.0, 24_000.0, 8_000.0])
    @DisplayName("keeps length, pitch and level")
    fun preservesSignal(rate: Double) {
        val out = SpeechResampler.resample(sine(hertz = 1000.0, rate = rate, seconds = 2.0), from = rate)
        assertTrue(abs(out.size - 32_000) <= 64)
        val middle = out.copyOfRange(4000, 28_000)
        assertTrue(abs(zeroCrossingRate(middle, rate = 16_000.0) - 1000) < 2)
        var energy = 0f
        for (x in middle) energy += x * x
        val rms = sqrt(energy / middle.size.toFloat())
        assertTrue(abs(rms - 0.5f / sqrt(2.0).toFloat()) < 0.02f)
    }

    @Test
    @DisplayName("keeps events where they were, so word timings stay true")
    fun keepsTiming() {
        val samples = FloatArray(48_000 * 2)
        for (i in 48_000 until 48_048) samples[i] = 0.8f
        val out = SpeechResampler.resample(samples, from = 48_000.0)
        // out.indices.max { abs(out[$0]) < abs(out[$1]) }: the first of the largest.
        val peak = out.indices.maxWith { a, b -> abs(out[a]).compareTo(abs(out[b])) }
        assertTrue(abs(peak.toDouble() / 16_000 - 1.0015) < 0.005)
    }

    @Test
    @DisplayName("works from the main actor without blocking it")
    fun fromMainActor() {
        // Swift hops off the main actor itself; here the function is blocking and the caller picks the
        // thread, so the port checks that it runs on another thread.
        var out = FloatArray(0)
        thread { out = SpeechResampler.resample(sine(hertz = 440.0, rate = 48_000.0, seconds = 1.0), from = 48_000.0) }.join()
        assertTrue(out.isNotEmpty())
    }
}
