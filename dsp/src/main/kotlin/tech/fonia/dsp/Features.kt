package tech.fonia.dsp

import kotlin.math.exp
import kotlin.math.ln

/**
 * Spectral description of one frame or one segment. All band energies are on the same dB scale.
 *
 * Mutable, unlike the Swift struct: the live path fills one instance in place, because a JVM object
 * per frame would allocate on the audio path. Everything else creates a new instance and never
 * changes it afterwards.
 */
data class FrameFeatures(
    var levelDb: Float,
    var hfDb: Float,
    /** Share of energy above 2.5 kHz. Fricatives are high, vowels low. */
    var hfShare: Float,
    var lfShare: Float,
    var vowelDb: Float,
    var centroidHz: Float,
    var peakHz: Float,
    /** 5.5-11 kHz minus 2-5.5 kHz. Positive leans S, negative leans Ș. */
    var tiltDb: Float,
    /** 5-11 kHz minus 11-20 kHz. Small for S, large for Ș and for lateral hisses. */
    var bandRatioDb: Float,
    /** 2-5 kHz minus 5-11 kHz. High when energy sits below 5 kHz. */
    var lowBandDb: Float,
    var flatness: Float,
) {
    companion object {
        /** A new instance each time, so no caller can change the shared value. */
        internal val silent: FrameFeatures
            get() = FrameFeatures(
                levelDb = -200f, hfDb = -200f, hfShare = 0f, lfShare = 0f, vowelDb = -200f, centroidHz = 0f, peakHz = 0f,
                tiltDb = 0f, bandRatioDb = 0f, lowBandDb = 0f, flatness = 0f,
            )
    }
}

/** Band edges resolved to bins once, so per-frame extraction only sums and allocates nothing. */
internal class FeatureExtractor(private val binCount: Int, private val binHz: Float, bands: DSPThresholds.Bands) {
    private class BinRange(lowHz: Float, highHz: Float, binHz: Float, binCount: Int) {
        val first: Int
        val count: Int

        init {
            fun bin(hz: Float): Int = max(0, min(binCount - 1, (hz / binHz).rounded().toInt()))
            first = bin(lowHz)
            count = max(0, bin(highHz) - first + 1)
        }
    }

    private val level: BinRange
    private val hf: BinRange
    private val lf: BinRange
    private val vowel: BinRange
    private val centroidBand: BinRange
    private val tiltLow: BinRange
    private val tiltHigh: BinRange
    private val ratioMid: BinRange
    private val ratioTop: BinRange?
    private val lowBand: BinRange
    private val flatnessBlocks: Array<BinRange>
    private val frequencies = FloatBuffer(binCount)
    private val peakHalf: Int

    init {
        val nyquistBin = binHz * (binCount - 1).toFloat()
        fun range(low: Float, high: Float): BinRange = BinRange(low, min(high, nyquistBin), binHz, binCount)
        level = range(bands.levelLowHz, bands.levelHighHz)
        hf = range(bands.hfLowHz, bands.levelHighHz)
        lf = range(bands.lfLowHz, bands.lfHighHz)
        vowel = range(bands.vowelLowHz, bands.vowelHighHz)
        centroidBand = range(bands.centroidLowHz, bands.centroidHighHz)
        tiltLow = range(bands.tiltLowHz, bands.tiltMidHz)
        tiltHigh = range(bands.tiltMidHz, bands.ratioMidHz)
        ratioMid = range(bands.ratioLowHz, bands.ratioMidHz)
        ratioTop = if (nyquistBin > bands.ratioMidHz + 500f) range(bands.ratioMidHz, bands.ratioHighHz) else null
        lowBand = range(bands.tiltLowHz, bands.ratioLowHz)
        val blocks = ArrayList<BinRange>()
        var low = bands.flatnessLowHz
        val flatnessTop = min(bands.flatnessHighHz, nyquistBin)
        while (low < flatnessTop) {
            blocks.add(range(low, min(low + bands.flatnessBlockHz, flatnessTop)))
            low += bands.flatnessBlockHz
        }
        flatnessBlocks = blocks.toTypedArray()
        for (k in 0 until binCount) frequencies[k] = k.toFloat() * binHz
        peakHalf = max(1, (bands.peakSmoothingHz / binHz / 2f).rounded().toInt())
    }

    fun extract(power: FloatArray): FrameFeatures = extract(power, FrameFeatures.silent)

    /** Fills `into` and returns it; allocates nothing. */
    fun extract(power: FloatArray, into: FrameFeatures): FrameFeatures {
        val total = sum(power, level)
        val hfEnergy = sum(power, hf)
        val lfEnergy = sum(power, lf)
        val midEnergy = sum(power, ratioMid)
        into.levelDb = toDb(total)
        into.hfDb = toDb(hfEnergy)
        into.hfShare = if (total > 0f) hfEnergy / total else 0f
        into.lfShare = if (total > 0f) lfEnergy / (total + lfEnergy) else 0f
        into.vowelDb = toDb(sum(power, vowel))
        into.centroidHz = centroid(power)
        into.peakHz = peak(power)
        into.tiltDb = toDb(sum(power, tiltHigh)) - toDb(sum(power, tiltLow))
        into.bandRatioDb = if (ratioTop != null) toDb(midEnergy) - toDb(sum(power, ratioTop)) else 0f
        into.lowBandDb = toDb(sum(power, lowBand)) - toDb(midEnergy)
        into.flatness = flatness(power)
        return into
    }

    private fun sum(power: FloatArray, range: BinRange): Float = sve(power, range.first, range.count)

    private fun centroid(power: FloatArray): Float {
        val weighted = dotpr(power, centroidBand.first, frequencies, centroidBand.first, centroidBand.count)
        val energy = sum(power, centroidBand)
        return if (energy > 0f) weighted / energy else 0f
    }

    /** Largest peak after smoothing over `peakSmoothingHz`, computed as a running sum. */
    private fun peak(power: FloatArray): Float {
        if (centroidBand.count <= 0) return 0f
        val lastBin = centroidBand.first + centroidBand.count - 1
        val windowStart = max(0, centroidBand.first - peakHalf)
        val windowEnd = min(binCount - 1, centroidBand.first + peakHalf)
        var running = sve(power, windowStart, windowEnd - windowStart + 1)
        var best = running / (windowEnd - windowStart + 1).toFloat()
        var bestBin = centroidBand.first
        var k = centroidBand.first + 1
        while (k <= lastBin) {
            val enter = k + peakHalf
            val leave = k - peakHalf - 1
            if (enter < binCount) running += power[enter]
            if (leave >= 0) running -= power[leave]
            val width = min(binCount - 1, enter) - max(0, leave + 1) + 1
            val value = running / width.toFloat()
            if (value > best) {
                best = value
                bestBin = k
            }
            k += 1
        }
        return bestBin.toFloat() * binHz
    }

    private fun flatness(power: FloatArray): Float {
        if (flatnessBlocks.isEmpty()) return 0f
        var logSum = 0f
        var total = 0f
        var index = 0
        while (index < flatnessBlocks.size) {
            val block = flatnessBlocks[index]
            val energy = sum(power, block) / max(1, block.count).toFloat() + 1e-20f
            logSum += ln(energy)
            total += energy
            index += 1
        }
        val count = flatnessBlocks.size.toFloat()
        return if (total > 0f) exp(logSum / count) / (total / count) else 0f
    }
}
