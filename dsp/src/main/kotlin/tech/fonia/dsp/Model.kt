package tech.fonia.dsp

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sqrt

enum class VoiceGroup(val rawValue: String) {
    CHILD("child"),
    FEMALE("female"),
    MALE("male"),
    ;

    companion object {
        fun fromRawValue(rawValue: String): VoiceGroup? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

/** S family (S, Z, Ț) and Ș family (Ș, J, CE/CI, GE/GI). */
enum class SibilantPlace(val rawValue: String) {
    S("s"),
    SH("sh"),
    ;

    companion object {
        fun fromRawValue(rawValue: String): SibilantPlace? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

data class TimeSpan(val start: Double, val end: Double) {
    val duration: Double get() = end - start
}

internal fun clamp01(x: Float): Float = max(0f, min(1f, x))

internal fun toDb(energy: Float): Float = 10f * log10(energy + 1e-20f)

internal fun sigmoid(x: Float): Float = 1f / (1f + exp(-x))

internal fun semitones(hz: Float, re: Float): Float = 12f * log2(hz / re)

/** Nearest-rank percentile of an already sorted array. */
internal fun percentile(sorted: FloatArray, p: Float): Float {
    if (sorted.isEmpty()) return 0f
    val index = ((sorted.size - 1).toFloat() * p).rounded().toInt()
    return sorted[max(0, min(sorted.size - 1, index))]
}

/** The upper median: `values.sorted()[count / 2]`, no averaging. */
internal fun median(values: FloatArray): Float {
    if (values.isEmpty()) return 0f
    return values.sortedArray()[values.size / 2]
}

internal fun mean(values: FloatArray): Float {
    if (values.isEmpty()) return 0f
    var sum = 0f
    for (value in values) sum += value
    return sum / values.size.toFloat()
}

/** Population standard deviation. */
internal fun standardDeviation(values: FloatArray): Float {
    if (values.isEmpty()) return 0f
    val m = mean(values)
    var sum = 0f
    for (value in values) sum += (value - m) * (value - m)
    return sqrt(sum / values.size.toFloat())
}

/** Analysis frame length: a power of two of at least `seconds`, so 2048 at 44.1 and 48 kHz. */
internal fun powerOfTwoFrame(seconds: Double, sampleRate: Double): Int =
    1 shl ceil(log2(max(2.0, seconds * sampleRate))).toInt()
