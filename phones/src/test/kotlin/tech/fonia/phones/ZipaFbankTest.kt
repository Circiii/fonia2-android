package tech.fonia.phones

import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Kotlin fbank against lhotse's own numbers (`tools/fbank_reference.py`, lhotse 1.33.0 `Wav2LogFilterBank` with
 * ZIPA's `FbankConfig`). The reference runs in float32 and this port in Double, so they differ by float32 rounding,
 * whose size follows the loudest band of the frame, not the band itself: each band must match within 2e-4 of its
 * own energy plus 1e-6 of the frame's loudest band. The filter edges and the window are rounded as lhotse rounds them
 * in float32, so what is left is the reference's float32 FFT and the odd ulp of numpy's float32 log in the mel
 * positions: 1e-5 in the log domain within 20 dB of a frame's peak, 1.3e-4 for a pure tone sitting on the far edge of
 * the top filter, where the weights are small.
 */
class ZipaFbankTest {
    private fun floats(name: String): FloatArray {
        val bytes = javaClass.getResourceAsStream("/fbank/$name")!!.use { it.readBytes() }
        val out = FloatArray(bytes.size / 4)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
        return out
    }

    private fun compare(name: String) {
        val signal = floats("$name.f32")
        val expected = floats("$name.fbank.f32")
        val actual = ZipaFbank().compute(signal)
        assertEquals(expected.size, actual.size, "$name: frame count")
        assertEquals(ZipaFbank.frameCount(signal.size) * ZipaFbank.NUM_BINS, actual.size)
        var worstNearPeak = 0.0
        var worstAt = ""
        for (frame in 0 until expected.size / 80) {
            val row = frame * 80
            val peak = (0 until 80).maxOf { exp(expected[row + it].toDouble()) }
            for (bin in 0 until 80) {
                val reference = exp(expected[row + bin].toDouble())
                val port = exp(actual[row + bin].toDouble())
                assertTrue(
                    abs(port - reference) <= 2e-4 * reference + 1e-6 * peak,
                    "$name: frame $frame, bin $bin is ${actual[row + bin]}, lhotse ${expected[row + bin]}",
                )
                // Bands within 20 dB of the frame's peak.
                val difference = abs(actual[row + bin] - expected[row + bin]).toDouble()
                if (reference >= peak / 100 && difference > worstNearPeak) {
                    worstNearPeak = difference
                    worstAt = "frame $frame, bin $bin: ${actual[row + bin]} against ${expected[row + bin]}, peak ${ln(peak)}"
                }
            }
        }
        println("fbank $name: ${expected.size / 80} frames, max |Δ ln E| within 20 dB of each frame's peak $worstNearPeak ($worstAt)")
        assertTrue(worstNearPeak < 2e-4, "$name: $worstNearPeak in the log domain within 20 dB of the peak, $worstAt")
    }

    @Test
    fun tonesChirpNoiseClickAndDcStepMatchLhotse() = compare("mix")

    @Test
    fun aQuarterSecondMatchesLhotseAtBothEdges() = compare("short")

    @Test
    fun aVoicedSoundThenAHissMatchLhotse() = compare("speechlike")

    @Test
    fun theShortestInputLhotseHandlesMatches() = compare("tiny")

    @Test
    fun digitalSilenceSitsOnTheFloor() {
        val out = ZipaFbank().compute(FloatArray(8000))
        assertEquals(50 * 80, out.size)
        // ln(FLT_EPSILON), the value lhotse prints for silence.
        assertEquals(-15.942385f, ZipaFbank.LOG_FLOOR, 1e-6f)
        assertTrue(out.all { it == ZipaFbank.LOG_FLOOR })
    }

    @Test
    fun frameCountFollowsLhotse() {
        // compute_num_frames_from_samples: (n + 80) // 160.
        assertEquals(0, ZipaFbank.frameCount(79))
        assertEquals(1, ZipaFbank.frameCount(80))
        assertEquals(100, ZipaFbank.frameCount(16_000))
        assertEquals(130, ZipaFbank.frameCount(20_837))
    }

    @Test
    fun mirroringMatchesTorchFlip() {
        // Left: position −1 is sample 0, −120 is sample 119. Right: position n is sample n − 1, and so on.
        assertEquals(0, ZipaFbank.mirror(-1, 1000))
        assertEquals(119, ZipaFbank.mirror(-120, 1000))
        assertEquals(999, ZipaFbank.mirror(1000, 1000))
        assertEquals(800, ZipaFbank.mirror(1199, 1000))
        assertEquals(5, ZipaFbank.mirror(5, 1000))
    }

    @Test
    fun aToneLightsTheFilterAroundItsFrequency() {
        val tone = FloatArray(16_000) { (0.5 * sin(2 * PI * 1000.0 * it / 16_000)).toFloat() }
        val out = ZipaFbank().compute(tone)
        val frame = 50 * 80
        val loudest = (0 until 80).maxBy { out[frame + it] }
        // Filter centres in Hz; 1 kHz must land on the filter whose centre is nearest.
        val melLow = ZipaFbank.mel(20.0)
        val delta = (ZipaFbank.mel(7600.0) - melLow) / 81
        val centres = (0 until 80).map { 700.0 * (Math.exp((melLow + (it + 1) * delta) / 1127.0) - 1.0) }
        assertEquals(centres.indices.minBy { abs(centres[it] - 1000.0) }, loudest)
    }

    @Test
    fun computeAllocatesNothingOnceWarm() {
        val fbank = ZipaFbank()
        val signal = floats("mix.f32")
        val out = FloatArray(ZipaFbank.frameCount(signal.size) * 80)
        assertEquals(0L, allocationAfterWarmUp { fbank.compute(signal, out = out) }, "bytes allocated by a warm call")
    }
}
