package tech.fonia.phones

import org.junit.jupiter.api.Test
import tech.fonia.speech.SpeechResampler
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Decimator3Test {
    private fun tone(hz: Double, seconds: Double = 0.5) = FloatArray((48_000 * seconds).toInt()) { (0.5 * sin(2 * PI * hz * it / 48_000.0)).toFloat() }

    private fun rms(x: FloatArray, from: Int = 400, to: Int = x.size - 400): Double {
        var e = 0.0
        for (i in from until to) e += x[i].toDouble() * x[i]
        return sqrt(e / (to - from))
    }

    @Test
    fun theLengthAndTimingFollowSpeechResampler() {
        assertEquals(16_000, Decimator3.decimate(FloatArray(48_000)).size)
        assertEquals(3, Decimator3.decimate(FloatArray(7)).size)
        // A 1 kHz tone: same samples as the generic resampler, within its own passband tolerance.
        val x = tone(1_000.0)
        val a = Decimator3.decimate(x)
        val b = SpeechResampler.resample(x, 48_000.0, 16_000.0)
        var maxDiff = 0f
        for (i in 300 until a.size - 300) maxDiff = maxOf(maxDiff, kotlin.math.abs(a[i] - b[i]))
        assertTrue(maxDiff < 2e-3f, "max diff $maxDiff")
    }

    @Test
    fun passbandIsFlatAndAliasesAreGone() {
        for (hz in listOf(200.0, 3_000.0, 6_000.0, 7_000.0)) {
            val gainDb = 20 * log10(rms(Decimator3.decimate(tone(hz))) / rms(tone(hz)))
            assertTrue(kotlin.math.abs(gainDb) < 0.1, "$hz Hz: $gainDb dB")
        }
        for (hz in listOf(8_400.0, 10_000.0, 15_000.0, 20_000.0)) {
            val gainDb = 20 * log10(rms(Decimator3.decimate(tone(hz))) / rms(tone(hz)))
            assertTrue(gainDb < -70, "$hz Hz: $gainDb dB")
        }
    }
}
