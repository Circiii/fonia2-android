package tech.fonia.audio

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow

/** Port of LoudnessEnvelopeTests.swift ("Loudness envelope"). */
class LoudnessEnvelopeTests {
    private fun envelope(samples: FloatArray, rate: Double = 48_000.0) = LoudnessEnvelope(samples, rate)

    /** Silence keeps the mouth closed. */
    @Test
    fun silence() {
        val values = envelope(FloatArray(4_800)).values
        assertEquals(10, values.size)
        assertTrue(values.all { it == 0f })
    }

    /** An empty clip has an empty envelope that reads as closed. */
    @Test
    fun empty() {
        val result = envelope(FloatArray(0))
        assertTrue(result.values.isEmpty())
        assertEquals(0f, result.value(0.0), 0f)
    }

    /** A loud tone opens fully. */
    @Test
    fun loudToneSaturates() {
        val values = envelope(sine(frequency = 440.0, amplitude = 0.9f, seconds = 0.5, sampleRate = 48_000.0)).values
        assertTrue(values.last() > 0.99f)
        assertTrue(values.all { it <= 1f })
    }

    /** A tone below the floor stays closed. */
    @Test
    fun quietToneStaysClosed() {
        val values = envelope(sine(frequency = 440.0, amplitude = 0.001f, seconds = 0.5, sampleRate = 48_000.0)).values
        assertTrue(values.all { it == 0f })
    }

    /** A steady level settles on the mapped value. */
    @Test
    fun steadyLevelMapping() {
        // A constant signal of -29 dBFS sits halfway between the -45 dB floor and the -13 dB top.
        val amplitude = 10.0.pow(-29.0 / 20).toFloat()
        val values = envelope(FloatArray(48_000) { amplitude }).values
        assertTrue(abs(values.last() - 0.5f) < 0.001f)
    }

    /** The envelope rises gradually instead of jumping. */
    @Test
    fun smoothedAttack() {
        val values = envelope(FloatArray(4_800) { 0.5f }).values
        assertTrue(values[0] < values[1])
        assertTrue(values[1] < values[2])
        assertTrue(values[0] < 0.5f)
    }

    /** The envelope follows a clip's shape: a tone between two silences. */
    @Test
    fun followsShape() {
        val silence = FloatArray(9_600)
        val tone = sine(frequency = 440.0, amplitude = 0.5f, seconds = 0.2, sampleRate = 48_000.0)
        val result = envelope(silence + tone + silence)

        assertEquals(0f, result.value(0.1), 0f)
        assertTrue(result.value(0.35) > 0.9f)
        assertTrue(result.value(0.59) < 0.1f)
    }

    /** Hop count and timing do not depend on the sample rate. */
    @Test
    fun rateIndependent() {
        val reference = envelope(sine(frequency = 440.0, amplitude = 0.5f, seconds = 1.0, sampleRate = 48_000.0)).value(0.5)
        for (rate in listOf(16_000.0, 44_100.0, 48_000.0)) {
            val result = envelope(sine(frequency = 440.0, amplitude = 0.5f, seconds = 1.0, sampleRate = rate), rate)
            assertTrue("count at $rate", abs(result.values.size - 100) <= 1)
            assertTrue("value at $rate", abs(result.value(0.5) - reference) < 0.01f)
        }
    }

    /** Positions outside the clip read as closed. */
    @Test
    fun outOfRange() {
        val result = envelope(FloatArray(48_000) { 0.5f })
        for (time in listOf(-1.0, -0.001, 1.0, 100.0)) {
            assertEquals("value($time)", 0f, result.value(time), 0f)
        }
    }

    /** A real voice clip moves the mouth. */
    @Test
    fun realClip() = runBlocking {
        val audio = AudioDecoder.decodeMono(ClipLocation.File(Fixtures.soareWav))
        val values = envelope(audio.samples, audio.sampleRate).values
        assertEquals(237, values.size)
        assertTrue(values.max() > 0.5f)
        assertTrue(values.min() < 0.1f)
    }
}
