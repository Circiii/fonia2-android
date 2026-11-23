package tech.fonia.dsp

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Not ported from Swift: checks of the conventions the port reproduces instead of calling vDSP and
 * the Swift standard library. Values from the inventory (`01_inventar_ios.md`, §4.4 and §4.19).
 */
class NumericsParityTests {
    @Test
    fun floatPiIsSwiftsRoundedTowardZero() {
        assertEquals(0x40490FDA, PI_F.toRawBits())
        assertTrue(PI_F.toDouble() < Math.PI)
    }

    @Test
    fun roundedTiesGoAwayFromZero() {
        assertEquals(11.0, 10.5.rounded())
        assertEquals(-3.0, (-2.5).rounded())
        assertEquals(368.0, (0.025 * 14700.0).rounded())
        assertEquals(0.0, 0.49999999999999994.rounded())
        assertEquals(11f, (0.028f * 375f).rounded())
        assertEquals(8.0, (0.02 * 375.0).rounded())
        assertEquals(23f, (0.06f * 375f).rounded())
    }

    @Test
    fun log2IsExactOnPowersOfTwo() {
        for (exponent in 0..20) assertEquals(exponent.toDouble(), log2(Math.scalb(1.0, exponent)))
        assertEquals(1024, powerOfTwoFrame(seconds = 1024.0 / 25600.0, sampleRate = 25600.0))
    }

    @Test
    fun packedTransformMatchesADirectDft() {
        val n = 256
        val generator = LinearCongruential(seed = 4)
        val signal = FloatArray(n) { generator.unit() * 2f - 1f }
        val power = FloatArray(n / 2)
        PowerTransform(n).transform(signal, 0, power)
        for (k in 0 until n / 2) {
            var re = 0.0
            var im = 0.0
            for (t in 0 until n) {
                val angle = 2.0 * Math.PI * k * t / n
                re += signal[t] * cos(angle)
                im -= signal[t] * sin(angle)
            }
            val expected = (re * re + im * im) / (n.toDouble() * n)
            assertTrue(abs(power[k] - expected) <= 1e-6 * expected + 1e-12, "bin $k: ${power[k]} vs $expected")
        }
    }

    @ParameterizedTest
    @CsvSource(
        // rate, frame, YIN input samples, envelope hop, trill window
        "16000, 1024, 675, 43, 89",
        "32000, 2048, 1350, 85, 90",
        "44100, 2048, 1861, 118, 90",
        "48000, 2048, 2024, 128, 90",
        "96000, 4096, 4048, 256, 90",
    )
    fun derivedSizesMatchTheInventory(rate: Double, frame: Int, yinInput: Int, envelopeHop: Int, trillWindow: Int) {
        val thresholds = DSPThresholds.v1
        assertEquals(frame, powerOfTwoFrame(thresholds.analysis.frameSeconds, rate))
        assertEquals(yinInput, YINPitchTracker(rate, thresholds.pitch).requiredInputCount)
        val envelope = EnvelopeTracker(rate, thresholds.envelope)
        assertEquals(envelopeHop, envelope.hop)
        assertEquals(trillWindow, TrillDetector(envelope.rate, thresholds.trill).windowCount)
    }

    @Test
    fun percentileAndMedianAreNearestRankAndUpper() {
        assertEquals(3f, median(floatArrayOf(4f, 1f, 3f, 2f)))
        // Rank (4 - 1) · 0.5 = 1.5 rounds away from zero, to the third value.
        assertEquals(3f, percentile(floatArrayOf(1f, 2f, 3f, 4f), 0.5f))
        assertEquals(1f, percentile(floatArrayOf(1f, 2f, 3f, 4f), 0.08f))
        assertEquals(4f, percentile(floatArrayOf(1f, 2f, 3f, 4f), 0.98f))
    }
}
