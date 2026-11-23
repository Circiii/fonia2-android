package tech.fonia.dsp

import kotlin.math.ceil
import kotlin.math.sin
import kotlin.math.sqrt

/** A stretch of frication, judged on its middle 50% rather than frame by frame. */
data class FricativeSegment(
    val start: Double,
    val end: Double,
    /** The middle of the frication, where the statistics were measured. */
    val coreStart: Double,
    val coreEnd: Double,
    val place: SibilantPlace,
    val sNess: Float,
    /** Inside the talker's uncertainty band: the place cannot be called either way. */
    val isBorderline: Boolean,
    val voiced: Boolean,
    /** Long enough for `voiced` to mean anything; affricates are not. */
    val voicingMeasurable: Boolean,
    /** Weak and shapeless, TH-like. */
    val diffuse: Boolean,
    /**
     * Two-band rule for a lateral hiss. Means something only for an S-family target, and Ș itself
     * also meets it, so the judge ignores it on clearly Ș-like tokens. Never a verdict.
     */
    val lateralHint: Boolean,
    val centroidHz: Float,
    val peakHz: Float,
    /** 5-11 kHz minus 11-20 kHz. */
    val bandRatioDb: Float,
    val tiltDb: Float,
    val lowBandDb: Float,
    val flatness: Float,
    val aboveFloorDb: Float,
)

/** Multitaper spectrum of a sample range, at the capture rate. Runs after the take, so it may allocate. */
internal class MultitaperAnalyzer(
    private val sampleRate: Double,
    private val bands: DSPThresholds.Bands,
    private val thresholds: DSPThresholds.Fricative,
) {
    private class Tools(val transform: PowerTransform, val features: FeatureExtractor)

    private val extractors = HashMap<Int, Tools>()

    fun features(samples: FloatArray, offset: Int = 0, count: Int = samples.size - offset): FrameFeatures {
        if (count < 16) return FrameFeatures.silent
        val length = max(1024, 1 shl ceil(log2(count.toDouble())).toInt())
        val tools = tools(length)
        val binCount = tools.transform.binCount

        val tapered = FloatBuffer(length)
        val power = FloatBuffer(binCount)
        val accumulated = FloatBuffer(binCount)
        val taper = FloatArray(count)
        val scale = sqrt(2f / (count + 1).toFloat())
        val tapers = max(1, thresholds.tapers)
        for (k in 1..tapers) {
            val step = PI_F * k.toFloat() / (count + 1).toFloat()
            for (n in 0 until count) taper[n] = scale * sin(step * (n + 1).toFloat())
            // vDSP_vmul into the first `count` slots; the rest of the frame stays zero.
            for (n in 0 until count) tapered[n] = samples[offset + n] * taper[n]
            tools.transform.transform(tapered, 0, power)
            // vDSP_vadd
            for (b in 0 until binCount) accumulated[b] = accumulated[b] + power[b]
        }
        // Tapers have unit energy, unlike the Blackman frames, so bring the scale back to samples.
        val normalize = length.toFloat() * length.toFloat() / (tapers.toFloat() * count.toFloat())
        for (b in 0 until binCount) accumulated[b] = accumulated[b] * normalize
        return tools.features.extract(accumulated)
    }

    private fun tools(length: Int): Tools = extractors.getOrPut(length) {
        val transform = PowerTransform(length)
        Tools(transform, FeatureExtractor(transform.binCount, sampleRate.toFloat() / length.toFloat(), bands))
    }
}

/** The share of frication used for measurement, centred and kept within sensible lengths. */
internal fun fricativeCore(start: Double, end: Double, duration: Double, thresholds: DSPThresholds.Fricative): TimeSpan {
    val span = end - start
    val centre = (start + end) / 2
    val length = max(thresholds.minCoreSeconds, min(thresholds.maxCoreSeconds, span * thresholds.coreShare))
    return TimeSpan(start = max(0.0, centre - length / 2), end = min(duration, centre + length / 2))
}
