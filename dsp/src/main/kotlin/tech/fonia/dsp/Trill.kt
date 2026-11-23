package tech.fonia.dsp

import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.sqrt

/** Mutable so the live path can reuse one instance; see `FrameFeatures`. */
internal data class TrillResult(
    var isTrill: Boolean,
    var rate: Float,
    var periodicity: Float,
    var depthDb: Float,
    var strength: Float,
) {
    fun set(isTrill: Boolean, rate: Float, periodicity: Float, depthDb: Float, strength: Float): TrillResult {
        this.isTrill = isTrill
        this.rate = rate
        this.periodicity = periodicity
        this.depthDb = depthDb
        this.strength = strength
        return this
    }

    fun setNone(): TrillResult = set(isTrill = false, rate = 0f, periodicity = 0f, depthDb = 0f, strength = 0f)

    companion object {
        val none: TrillResult get() = TrillResult(isTrill = false, rate = 0f, periodicity = 0f, depthDb = 0f, strength = 0f)
    }
}

/**
 * Alveolar trill from the envelope: each tongue-tip contact briefly closes the tract, so a trill
 * shows as a strong autocorrelation peak of the detrended log-envelope near 18 to 38 Hz.
 */
internal class TrillDetector(envelopeRate: Double, private val thresholds: DSPThresholds.Trill) {
    val windowCount: Int = (thresholds.windowSeconds * envelopeRate).rounded().toInt()
    private val envelopeRate: Float = envelopeRate.toFloat()
    private val minLag: Int = max(2, (envelopeRate.toFloat() / thresholds.maxRateHz).toInt())
    private val maxLag: Int = min(windowCount - 5, ceil(envelopeRate.toFloat() / thresholds.minRateHz).toInt())
    private val detrendHalf: Int = max(2, (thresholds.detrendHalfSeconds * envelopeRate.toFloat()).rounded().toInt())
    private val logEnvelope = FloatBuffer(maxOf(0, windowCount))
    private val prefix = FloatBuffer(maxOf(0, windowCount) + 1)
    private val detrended = FloatBuffer(maxOf(0, windowCount))
    private val correlation = FloatBuffer(max(1, maxLag + 2))

    /** `envelope` holds the newest `windowCount` values from `offset`, oldest first. */
    fun detect(envelope: FloatArray, offset: Int): TrillResult = detect(envelope, offset, TrillResult.none)

    /** Fills `into` and returns it; allocates nothing. */
    fun detect(envelope: FloatArray, offset: Int, into: TrillResult): TrillResult {
        if (windowCount < 16 || maxLag <= minLag) return into.setNone()
        val n = windowCount
        prefix[0] = 0f
        var i = 0
        while (i < n) {
            logEnvelope[i] = 20f * log10(envelope[offset + i] + 1e-9f)
            prefix[i + 1] = prefix[i] + logEnvelope[i]
            i += 1
        }
        i = 0
        while (i < n) {
            val a = max(0, i - detrendHalf)
            val b = min(n - 1, i + detrendHalf)
            detrended[i] = logEnvelope[i] - (prefix[b + 1] - prefix[a]) / (b - a + 1).toFloat()
            i += 1
        }
        val energy = svesq(detrended, 0, n)
        val depthDb = sqrt(energy / n.toFloat())
        if (!(energy > 1e-9f)) return into.setNone()

        val ac = correlation
        var lag = 1
        while (lag <= maxLag + 1) {
            ac[lag] = normalizedCorrelation(lag)
            lag += 1
        }

        var best = 0f
        var bestLag = 0
        lag = minLag
        while (lag <= maxLag) {
            // Only interior maxima count: a slow contour keeps the correlation rising to the band
            // edge, which is not a period.
            if (ac[lag] > best && ac[lag] > ac[lag - 1] && ac[lag] >= ac[lag + 1]) {
                best = ac[lag]
                bestLag = lag
            }
            lag += 1
        }
        if (bestLag <= 0) return into.set(isTrill = false, rate = 0f, periodicity = 0f, depthDb = depthDb, strength = 0f)

        // A real period has a trough before it, anti-correlated around half the period.
        var trough = 1f
        lag = 1
        while (lag < bestLag) {
            trough = min(trough, ac[lag])
            lag += 1
        }
        val atHalf = ac[max(1, (bestLag.toFloat() / 2f).rounded().toInt())]
        val rate = envelopeRate / bestLag.toFloat()
        val prominent = best - trough > thresholds.minProminence && atHalf < thresholds.maxHalfLagCorrelation
        val secondPeriod = if (bestLag * 2 < n - 4) normalizedCorrelation(bestLag * 2) else 0f
        val repeats = secondPeriod >= thresholds.minSecondPeriodCorrelation &&
            contactCount(bestLag, depthDb) >= thresholds.minContacts
        val isTrill = prominent &&
            repeats &&
            best > thresholds.minPeriodicity &&
            depthDb > thresholds.minDepthDb &&
            rate >= thresholds.minRateHz &&
            rate <= thresholds.maxRateHz
        val strength = if (prominent) {
            clamp01(
                ((best - thresholds.strengthPeriodicityBase) / thresholds.strengthPeriodicityRange) *
                    min(1f, depthDb / thresholds.strengthDepthDb),
            )
        } else {
            0f
        }
        return into.set(isTrill = isTrill, rate = rate, periodicity = best, depthDb = depthDb, strength = strength)
    }

    private fun normalizedCorrelation(lag: Int): Float {
        val span = windowCount - lag
        val product = dotpr(detrended, 0, detrended, lag, span)
        val head = svesq(detrended, 0, span)
        val tail = svesq(detrended, lag, span)
        return product / sqrt(head * tail + 1e-12f)
    }

    /** Dips below the contour, at least most of a period apart. */
    private fun contactCount(lag: Int, rms: Float): Int {
        val limit = -thresholds.contactDepthShare * rms
        val spacing = max(1, lag * 2 / 3)
        var count = 0
        var last = -spacing
        var i = 1
        while (i < windowCount - 1) {
            val value = detrended[i]
            if (value < limit && value <= detrended[i - 1] && value < detrended[i + 1] && i - last >= spacing) {
                count += 1
                last = i
            }
            i += 1
        }
        return count
    }
}

internal data class TapCandidate(
    val index: Int,
    val widthMs: Float,
    val depthDb: Float,
    val score: Float,
)

/**
 * How well a half-depth width fits the Romanian closure prior: 1 inside it, falling to 0 at the
 * limits of what still counts.
 */
internal fun tapScore(widthMs: Float, thresholds: DSPThresholds.Tap): Float {
    if (widthMs < thresholds.minWidthMs || widthMs > thresholds.maxWidthMs) return 0f
    if (widthMs < thresholds.closurePriorLowMs) {
        return clamp01((widthMs - thresholds.minWidthMs) / (thresholds.closurePriorLowMs - thresholds.minWidthMs))
    }
    if (widthMs > thresholds.closurePriorHighMs) {
        return clamp01((thresholds.maxWidthMs - widthMs) / (thresholds.maxWidthMs - thresholds.closurePriorHighMs))
    }
    return 1f
}

/**
 * Short, deep dips of the envelope flanked by louder stretches: the single-contact R that is the
 * usual Romanian realisation (Savu: 86.66% of tokens in #rV, Cr and rC). Voicing of the flanks is
 * checked by the caller, which has the pitch track.
 */
internal fun findTapCandidates(envelope: FloatArray, envelopeRate: Float, thresholds: DSPThresholds.Tap): List<TapCandidate> {
    val n = envelope.size
    if (n < 8) return emptyList()
    val db = FloatArray(n) { 20f * log10(envelope[it] + 1e-9f) }
    val half = max(1, (thresholds.smoothHalfSeconds * envelopeRate).rounded().toInt())
    val smooth = FloatArray(n)
    for (i in 0 until n) {
        val a = max(0, i - half)
        val b = min(n - 1, i + half)
        var sum = 0f
        for (k in a..b) sum += db[k]
        smooth[i] = sum / (b - a + 1).toFloat()
    }
    val top = sliceMax(smooth) ?: -200f
    val look = (thresholds.flankLookSeconds * envelopeRate).rounded().toInt()
    val minSpacing = thresholds.minSpacingSeconds * envelopeRate
    val result = ArrayList<TapCandidate>()
    for (i in 1 until n - 1) {
        if (!(smooth[i] <= smooth[i - 1] && smooth[i] < smooth[i + 1])) continue
        val left = sliceMax(smooth, max(0, i - look), i) ?: continue
        val right = sliceMax(smooth, i + 1, min(n - 1, i + look) + 1) ?: continue
        val flank = min(left, right)
        val depth = flank - smooth[i]
        if (depth < thresholds.minDepthDb || flank < top - thresholds.maxFlankBelowSpeechDb) continue
        val halfLevel = smooth[i] + depth / 2f
        var a = i
        var b = i
        while (a > 0 && smooth[a] < halfLevel) a -= 1
        while (b < n - 1 && smooth[b] < halfLevel) b += 1
        val widthMs = (b - a).toFloat() / envelopeRate * 1000f
        val score = tapScore(widthMs, thresholds)
        if (score < thresholds.minScore) continue
        val last = result.lastOrNull()
        if (last != null && (i - last.index).toFloat() < minSpacing) continue
        result.add(TapCandidate(index = i, widthMs = widthMs, depthDb = depth, score = score))
    }
    return result
}

/** Swift's `slice.max()`: the first largest value of `values[from ..< until]`, or null when empty. */
internal fun sliceMax(values: FloatArray, from: Int = 0, until: Int = values.size): Float? {
    if (from >= until) return null
    var best = values[from]
    for (i in from + 1 until until) {
        if (best < values[i]) best = values[i]
    }
    return best
}
