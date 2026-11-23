package tech.fonia.audio

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** A clip's loudness over time, computed ahead of playback so a mouth can follow the voice. */
internal class LoudnessEnvelope(samples: FloatArray, sampleRate: Double) {
    companion object {
        const val HOP_DURATION = 0.01

        /** RMS below this level closes the mouth. */
        private const val FLOOR_DB = -45f

        /** RMS range from closed to fully open. */
        private const val RANGE_DB = 32f

        /** Keeps 45% of the previous value per 60 Hz frame, expressed per hop so it does not depend on hop size. */
        private val SMOOTHING = 0.45.pow(HOP_DURATION * 60).toFloat()

        private fun mouthOpening(rms: Float): Float {
            val db = 20 * log10(rms + 1e-6f)
            return min(1f, max(0f, (db - FLOOR_DB) / RANGE_DB))
        }

        /** `vDSP.rootMeanSquare`: the square root of the mean of the squares. */
        private fun rootMeanSquare(samples: FloatArray, start: Int, end: Int): Float {
            var sum = 0.0
            for (i in start until end) sum += samples[i].toDouble() * samples[i]
            return sqrt(sum / (end - start)).toFloat()
        }
    }

    val values: FloatArray

    init {
        val hop = max(1, (sampleRate * HOP_DURATION).roundedAwayFromZero().toInt())
        val out = FloatArray(samples.size / hop + 1)
        var count = 0
        var smoothed = 0f
        var start = 0
        while (start < samples.size) {
            val end = min(start + hop, samples.size)
            val rms = rootMeanSquare(samples, start, end)
            smoothed = smoothed * SMOOTHING + mouthOpening(rms) * (1 - SMOOTHING)
            out[count++] = smoothed
            start = end
        }
        values = out.copyOf(count)
    }

    /** The envelope value at a playback position in seconds, or 0 outside the clip. */
    fun value(time: Double): Float {
        if (!(time >= 0)) return 0f
        val index = (time / HOP_DURATION).toInt()
        return if (index < values.size) values[index] else 0f
    }
}
