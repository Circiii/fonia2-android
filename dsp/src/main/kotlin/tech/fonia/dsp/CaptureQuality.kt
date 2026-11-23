package tech.fonia.dsp

import kotlin.math.abs

/** Whether a take can be judged at all. Bad takes must not read as pronunciation errors. */
data class CaptureQuality(
    val isClipped: Boolean,
    val isTooQuiet: Boolean,
    val isTooShort: Boolean,
    val clippedFraction: Float,
    val peakAmplitude: Float,
    /** RMS of the speaking stretch, dBFS. */
    val speechRmsDb: Float,
    val dynamicRangeDb: Float,
    val speechSeconds: Double,
    val sampleRate: Double,
    /** Where the average spectrum of frication falls off, after any codec or Bluetooth lowpass. */
    val bandwidthHz: Float,
    /** Sample rate and bandwidth are enough to tell S from Ș. */
    val canJudgeSibilants: Boolean,
) {
    val isUsable: Boolean get() = !isClipped && !isTooQuiet && !isTooShort
}

/**
 * Samples inside runs of at least `clipRunSamples` near full scale. Single loud samples are fine;
 * flat tops are what a clipped input looks like.
 */
internal fun clippedSampleCount(
    pcm: FloatArray, thresholds: DSPThresholds.Capture, offset: Int = 0, count: Int = pcm.size - offset,
): Int {
    var clipped = 0
    var run = 0
    for (i in offset until offset + count) {
        if (abs(pcm[i]) >= thresholds.clipLevel) {
            run += 1
        } else {
            if (run >= thresholds.clipRunSamples) clipped += run
            run = 0
        }
    }
    if (run >= thresholds.clipRunSamples) clipped += run
    return clipped
}
