package tech.fonia.collector.core

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * The collector's own energy gate. It only decides when a take ends, never what is kept: the WAV is the whole
 * take. Values are dBFS of 10 ms hops.
 */
object Levels {
    const val SILENT_DB = -120f

    /** Above this floor the adult is asked to find a quieter place (03 §8, „Camera”). */
    const val NOISY_FLOOR_DB = -45f

    /** Speech is this much over the room floor, but never below [MIN_SPEECH_DB] nor above [MAX_THRESHOLD_DB]. */
    const val SPEECH_MARGIN_DB = 12f
    const val MIN_SPEECH_DB = -50f
    const val MAX_THRESHOLD_DB = -32f

    fun dbfs(samples: FloatArray, offset: Int, count: Int): Float {
        if (count <= 0) return SILENT_DB
        var sum = 0.0
        for (i in offset until offset + count) {
            val v = samples[i].toDouble()
            sum += v * v
        }
        val rms = sqrt(sum / count)
        return if (rms <= 1e-6) SILENT_DB else (20 * log10(rms)).toFloat()
    }

    /** 0…1 for a meter: −60 dBFS and below is empty, 0 dBFS is full. */
    fun meter(db: Float): Float = ((db + 60f) / 60f).coerceIn(0f, 1f)

    fun speechThreshold(floorDb: Float?): Float =
        ((floorDb ?: -70f) + SPEECH_MARGIN_DB).coerceIn(MIN_SPEECH_DB, MAX_THRESHOLD_DB)

    /** The room floor: the 20th percentile of the hop levels, so a cough or a word does not raise it. */
    fun floor(hopDbs: List<Float>): Float {
        if (hopDbs.isEmpty()) return SILENT_DB
        val sorted = hopDbs.sorted()
        return sorted[((sorted.size - 1) * 0.2).toInt()]
    }

    fun isNoisy(floorDb: Float): Boolean = floorDb > NOISY_FLOOR_DB
}

/** Where the speech of a take starts and ends, counted in samples from the start of the take. */
class HopStats(private val threshold: Float) {
    @Volatile var total = 0
        private set

    @Volatile var firstSpeech = -1
        private set

    @Volatile var lastSpeechEnd = -1
        private set

    /** Returns whether this hop is speech. */
    fun add(db: Float, count: Int): Boolean {
        val speech = db > threshold
        if (speech) {
            if (firstSpeech < 0) firstSpeech = total
            lastSpeechEnd = total + count
        }
        total += count
        return speech
    }
}
