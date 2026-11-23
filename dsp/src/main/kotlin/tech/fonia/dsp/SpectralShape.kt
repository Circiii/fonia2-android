package tech.fonia.dsp

import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

// E2 features 1, 2, 3, 5 and 8 (docs/v2/03 §4): the spectral shape of one segment. Additive: nothing here is used by
// the parity-locked detectors, and nothing in them changed. Pure functions, run after the take (they may allocate).

/**
 * Multitaper power spectrum of a sample range (sine tapers, averaged), scaled so the bins sum to the mean square of
 * the range: a band's sum is that band's mean square, so band levels of different segments compare in dB.
 */
class SegmentSpectrum(val power: FloatArray, val binHz: Float, val sampleRate: Double) {
    val nyquistHz: Float get() = (sampleRate / 2).toFloat()

    fun bin(hz: Float): Int = kotlin.math.min(power.size - 1, kotlin.math.max(0, (hz / binHz).toInt()))

    /** Mean square in [lowHz, highHz), or 0 when the band is outside the spectrum. */
    fun bandPower(lowHz: Float, highHz: Float): Double {
        val top = kotlin.math.min(highHz, nyquistHz)
        if (top <= lowHz) return 0.0
        var sum = 0.0
        for (k in bin(lowHz) until kotlin.math.max(bin(lowHz) + 1, bin(top))) sum += power[k]
        return sum
    }

    /** 10·log10 of [bandPower], floored at −160 dB. */
    fun bandLevelDb(lowHz: Float, highHz: Float): Float = (10.0 * log10(bandPower(lowHz, highHz) + 1e-16)).toFloat()

    companion object {
        val Empty = SegmentSpectrum(FloatArray(1), 1f, 2.0)
    }
}

/** [count] samples from [offset]; fewer than 32 samples give [SegmentSpectrum.Empty]. */
fun segmentSpectrum(pcm: FloatArray, sampleRate: Double, offset: Int, count: Int, tapers: Int = 6): SegmentSpectrum {
    val from = kotlin.math.max(0, offset)
    val n = kotlin.math.min(count, pcm.size - from)
    if (n < 32) return SegmentSpectrum.Empty
    val length = kotlin.math.max(1024, 1 shl ceil(kotlin.math.log2(n.toDouble())).toInt())
    val transform = PowerTransform(length)
    val frame = FloatArray(length)
    val power = FloatArray(transform.binCount)
    val sum = DoubleArray(transform.binCount)
    val scale = sqrt(2.0 / (n + 1))
    for (k in 1..kotlin.math.max(1, tapers)) {
        val step = Math.PI * k / (n + 1)
        for (i in 0 until n) frame[i] = (pcm[from + i] * scale * sin(step * (i + 1))).toFloat()
        transform.transform(frame, 0, power)
        for (b in power.indices) sum[b] += power[b].toDouble()
    }
    var meanSquare = 0.0
    for (i in 0 until n) meanSquare += pcm[from + i].toDouble() * pcm[from + i]
    meanSquare /= n
    val total = sum.sum()
    val normalize = if (total > 0) meanSquare / total else 0.0
    val out = FloatArray(sum.size) { (sum[it] * normalize).toFloat() }
    return SegmentSpectrum(out, (sampleRate / length).toFloat(), sampleRate)
}

/** Spectral moments (feature 1). [skewness] and [kurtosis] are the normalised third and fourth moments, kurtosis in excess of 3. */
data class SpectralMoments(
    val m1Hz: Float,
    val m2Hz: Float,
    val skewness: Float,
    val kurtosis: Float,
    /** Least-squares slope of 10·log10 P over the slope band, dB per kHz. */
    val slopeDbPerKhz: Float,
) {
    companion object {
        val None = SpectralMoments(Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN)
    }
}

fun spectralMoments(
    s: SegmentSpectrum,
    lowHz: Float = 2000f,
    highHz: Float = 20000f,
    slopeLowHz: Float = 2000f,
    slopeHighHz: Float = 11000f,
): SpectralMoments {
    val top = kotlin.math.min(highHz, s.nyquistHz)
    if (top <= lowHz || s === SegmentSpectrum.Empty) return SpectralMoments.None
    val first = s.bin(lowHz)
    val last = s.bin(top)
    var p0 = 0.0
    var p1 = 0.0
    for (k in first until last) {
        p0 += s.power[k]
        p1 += s.power[k].toDouble() * k * s.binHz
    }
    if (p0 <= 0) return SpectralMoments.None
    val m1 = p1 / p0
    var c2 = 0.0
    var c3 = 0.0
    var c4 = 0.0
    for (k in first until last) {
        val d = k * s.binHz - m1
        val w = s.power[k] / p0
        c2 += w * d * d
        c3 += w * d * d * d
        c4 += w * d * d * d * d
    }
    val sd = sqrt(c2)
    val skew = if (sd > 0) c3 / (sd * sd * sd) else 0.0
    val kurt = if (sd > 0) c4 / (c2 * c2) - 3.0 else 0.0
    // Slope: regression of dB on kHz.
    val sTop = kotlin.math.min(slopeHighHz, s.nyquistHz)
    var slope = Double.NaN
    if (sTop > slopeLowHz) {
        var n = 0
        var sx = 0.0
        var sy = 0.0
        var sxx = 0.0
        var sxy = 0.0
        for (k in s.bin(slopeLowHz) until s.bin(sTop)) {
            val x = k * s.binHz / 1000.0
            val y = 10.0 * log10(s.power[k] + 1e-20)
            n++
            sx += x
            sy += y
            sxx += x * x
            sxy += x * y
        }
        val den = n * sxx - sx * sx
        if (n > 2 && den > 0) slope = (n * sxy - sx * sy) / den
    }
    return SpectralMoments(m1.toFloat(), sd.toFloat(), skew.toFloat(), kurt.toFloat(), slope.toFloat())
}

/**
 * Spectral flatness over [blockHz] blocks between [lowHz] and [highHz] (feature 2; the existing `FrameFeatures.flatness`
 * is the 2–11 kHz version on Blackman frames): geometric over arithmetic mean of the block powers, 0…1.
 */
fun bandFlatness(s: SegmentSpectrum, lowHz: Float = 5000f, highHz: Float = 11000f, blockHz: Float = 500f): Float {
    val top = kotlin.math.min(highHz, s.nyquistHz)
    if (top - lowHz < blockHz || s === SegmentSpectrum.Empty) return Float.NaN
    val blocks = ArrayList<Double>()
    var edge = lowHz
    while (edge + blockHz <= top + 1e-3f) {
        blocks.add(s.bandPower(edge, edge + blockHz) + 1e-20)
        edge += blockHz
    }
    if (blocks.isEmpty()) return Float.NaN
    val logMean = blocks.sumOf { ln(it) } / blocks.size
    val mean = blocks.average()
    return (kotlin.math.exp(logMean) / mean).toFloat().coerceIn(0f, 1f)
}

/**
 * Feature 3: level of [fric] in 2–15 kHz minus the broadband (80 Hz–15 kHz) level of [reference], usually the
 * neighbouring vowel nucleus; at the isolated level the reference is the floor.
 */
fun relativeLevelDb(fric: SegmentSpectrum, reference: SegmentSpectrum): Float {
    if (fric === SegmentSpectrum.Empty || reference === SegmentSpectrum.Empty) return Float.NaN
    return fric.bandLevelDb(2000f, 15000f) - reference.bandLevelDb(80f, 15000f)
}

/** Feature 5 (without `latMod` and `latIdx`, which need ≥ 150 ms and the child's own good S). */
data class LateralCues(
    /** E(2.5–5 kHz) − E(5–11 kHz): high when the energy sits just below 5 kHz. */
    val latLowDb: Float,
    /** E(5–11 kHz) − E(11–20 kHz): today's `bandRatioDb`; large for Ș and for lateral hisses. */
    val latTopDb: Float,
    /** The peak of 200 Hz blocks in 2.8–3.6 kHz over their median in 2–8 kHz. */
    val latPeakDb: Float,
) {
    companion object {
        val None = LateralCues(Float.NaN, Float.NaN, Float.NaN)
    }
}

fun lateralCues(s: SegmentSpectrum): LateralCues {
    if (s === SegmentSpectrum.Empty) return LateralCues.None
    val low = s.bandLevelDb(2500f, 5000f) - s.bandLevelDb(5000f, 11000f)
    val topBand = if (s.nyquistHz >= 15000f) s.bandLevelDb(5000f, 11000f) - s.bandLevelDb(11000f, 20000f) else Float.NaN
    val blocks = ArrayList<Pair<Float, Float>>()
    var edge = 2000f
    while (edge + 200f <= kotlin.math.min(8000f, s.nyquistHz)) {
        blocks.add(edge to s.bandLevelDb(edge, edge + 200f))
        edge += 200f
    }
    val peak = if (blocks.isEmpty()) Float.NaN else {
        val sorted = blocks.map { it.second }.sorted()
        val median = sorted[sorted.size / 2]
        val inPeak = blocks.filter { it.first >= 2800f && it.first < 3600f }.maxOfOrNull { it.second }
        if (inPeak == null) Float.NaN else inPeak - median
    }
    return LateralCues(low, topBand, peak)
}

/** Feature 8 on band levels: E(0.7–2 kHz) − E(2–5 kHz) and the strongest 100 Hz block in 0.7–1.8 kHz. */
data class UvularCues(val uvNoiseDb: Float, val uvPeakHz: Float)

/**
 * The plan measures the aperiodic part only; this v0 takes the whole band, so a voiced R's harmonics count too.
 * It is evidence for the adult view, never a verdict on its own.
 */
fun uvularCues(s: SegmentSpectrum): UvularCues {
    if (s === SegmentSpectrum.Empty) return UvularCues(Float.NaN, Float.NaN)
    var bestHz = Float.NaN
    var best = -1e9f
    var edge = 700f
    while (edge + 100f <= 1800f) {
        val level = s.bandLevelDb(edge, edge + 100f)
        if (level > best) {
            best = level
            bestHz = edge + 50f
        }
        edge += 100f
    }
    return UvularCues(s.bandLevelDb(700f, 2000f) - s.bandLevelDb(2000f, 5000f), bestHz)
}
