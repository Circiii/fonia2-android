package tech.fonia.dsp

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor

/**
 * Mutable so the live path can reuse one instance; see `FrameFeatures`.
 */
internal data class PitchEstimate(
    var hz: Float,
    /** 1 minus the YIN dip: near 1 for a clean period, low for noise. */
    var clarity: Float,
) {
    fun setNone(): PitchEstimate {
        hz = 0f
        clarity = 0f
        return this
    }

    companion object {
        val none: PitchEstimate get() = PitchEstimate(hz = 0f, clarity = 0f)
    }
}

/**
 * YIN (de Cheveigné & Kawahara 2002) on a decimated, low-passed frame. All scratch is allocated
 * once, so `estimate` allocates nothing per frame.
 */
internal class YINPitchTracker(sampleRate: Double, thresholds: DSPThresholds.Pitch) {
    /** Samples at the input rate that `estimate` reads. */
    val requiredInputCount: Int

    private val decimation: Int = max(1, (sampleRate / thresholds.decimatedRateHz).toInt())
    private val decimatedRate: Float
    private val windowCount: Int
    private val tauMin: Int
    private val tauMax: Int
    private val threshold: Float = thresholds.yinThreshold
    private val octaveMargin: Float = thresholds.octaveCorrectionMargin
    private val fallbackMargin: Float = thresholds.fallbackDipMargin
    private val filter: FloatBuffer
    private val filterCount: Int
    private val decimated: FloatBuffer
    private val normalized: FloatBuffer

    init {
        val rate = sampleRate / decimation.toDouble()
        decimatedRate = rate.toFloat()
        windowCount = (thresholds.windowSeconds * rate).rounded().toInt()
        tauMin = max(2, floor(rate / thresholds.maxHz.toDouble()).toInt())
        tauMax = ceil(rate / thresholds.minHz.toDouble()).toInt()

        // A Hann FIR whose first null sits at `lowpassHz`: cheap, with sidelobes well under the voice.
        filterCount = max(1, (2 * sampleRate / thresholds.lowpassHz).rounded().toInt())
        filter = FloatBuffer(filterCount)
        var sum = 0f
        for (i in 0 until filterCount) {
            val value = 0.5f - 0.5f * cos(2f * PI_F * (i.toFloat() + 0.5f) / filterCount.toFloat())
            filter[i] = value
            sum += value
        }
        for (i in 0 until filterCount) filter[i] /= sum

        val decimatedCount = windowCount + tauMax + 1
        requiredInputCount = (decimatedCount - 1) * decimation + filterCount
        decimated = FloatBuffer(decimatedCount)
        normalized = FloatBuffer(tauMax + 2)
    }

    /** Reads `requiredInputCount` samples from `offset`, oldest first. */
    fun estimate(input: FloatArray, offset: Int): PitchEstimate = estimate(input, offset, PitchEstimate.none)

    /** Fills `into` and returns it; allocates nothing. */
    fun estimate(input: FloatArray, offset: Int, into: PitchEstimate): PitchEstimate {
        desamp(input, offset, decimation, filter, decimated, decimated.size, filterCount)
        val x = decimated
        val window = windowCount
        val energy = svesq(x, 0, window)
        if (!(energy > 1e-10f)) return into.setNone()

        val dips = normalized
        dips[0] = 1f
        var shiftedEnergy = energy
        var running = 0f
        var lag = 1
        while (lag <= tauMax) {
            val entering = x[lag + windowCount - 1]
            val leaving = x[lag - 1]
            shiftedEnergy += entering * entering - leaving * leaving
            val correlation = dotpr(x, 0, x, lag, window)
            val difference = max(0f, energy + shiftedEnergy - 2f * correlation)
            running += difference
            dips[lag] = if (running > 0f) difference * lag.toFloat() / running else 1f
            lag += 1
        }

        var chosen = firstDip(dips).let { if (it >= 0) it else earliestNearDeepestDip(dips) }
        chosen = settle(chosen, dips)
        val doubled = chosen * 2
        if (doubled + 1 < tauMax) {
            val octaveDown = settle(doubled - 1, dips)
            if (dips[octaveDown] < dips[chosen] - octaveMargin && abs(octaveDown - doubled) <= 2) chosen = octaveDown
        }

        var period = chosen.toFloat()
        if (chosen > 1 && chosen < tauMax) {
            val left = dips[chosen - 1]
            val centre = dips[chosen]
            val right = dips[chosen + 1]
            val curvature = left - 2f * centre + right
            if (curvature > 1e-9f) {
                period += max(-1f, min(1f, 0.5f * (left - right) / curvature))
            }
        }
        into.hz = decimatedRate / period
        into.clarity = clamp01(1f - dips[chosen])
        return into
    }

    /** The first τ with a dip under the threshold, or -1 (Swift's `nil`). */
    private fun firstDip(dips: FloatArray): Int {
        var tau = tauMin
        while (tau <= tauMax) {
            if (dips[tau] < threshold) return tau
            tau += 1
        }
        return -1
    }

    private fun earliestNearDeepestDip(dips: FloatArray): Int {
        var deepest = dips[tauMin]
        var tau = tauMin + 1
        while (tau <= tauMax) {
            deepest = min(deepest, dips[tau])
            tau += 1
        }
        tau = tauMin
        while (tau < tauMax) {
            if (dips[tau] <= deepest + fallbackMargin) return tau
            tau += 1
        }
        return tauMax
    }

    /** Walks down to the bottom of the dip that `tau` is on. */
    private fun settle(tau: Int, dips: FloatArray): Int {
        var position = tau
        while (position < tauMax && dips[position + 1] < dips[position]) position += 1
        return position
    }
}
