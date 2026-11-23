package tech.fonia.dsp

import kotlin.math.log10

// E2 feature 4 (docs/v2/03 §4): how much of a segment is voiced, and how harmonic its low band is. Additive; pure.

data class VoicingCues(
    /** Share of 10 ms frames with YIN clarity over [voicedClarity] and voice-band energy over its floor + margin (and
     *  within 20 dB of the take's voiced peak). */
    val voicedFrac: Float,
    /** 10·log10(r/(1−r)), r the best normalised autocorrelation of the signal under 1 kHz, limited to −10…30 dB. */
    val hnrDb: Float,
    val frames: Int,
) {
    /** < 25 % unvoiced, 25–90 % partly voiced, > 90 % voiced (plan 03 §4). */
    val voicingClass: String
        get() = when {
            frames == 0 -> "none"
            voicedFrac < 0.25f -> "unvoiced"
            voicedFrac <= 0.9f -> "partial"
            else -> "voiced"
        }

    companion object {
        val None = VoicingCues(Float.NaN, Float.NaN, 0)
    }
}

/**
 * Voicing of [start, end) seconds of [pcm]. [lowFloorDb] is the floor of the 80–500 Hz band over the whole clip
 * (computed from the clip when null).
 */
fun voicingCues(
    pcm: FloatArray,
    sampleRate: Double,
    start: Double,
    end: Double,
    lowFloorDb: Float? = null,
    voicedClarity: Float = 0.75f,
    marginDb: Float = 6f,
    belowVoicePeakDb: Float = 20f,
    pitch: DSPThresholds.Pitch = DSPThresholds.v1.pitch,
): VoicingCues {
    if (end <= start || pcm.isEmpty()) return VoicingCues.None
    val band = Biquad.lowpass(500.0, sampleRate).run(Biquad.highpass(80.0, sampleRate).run(pcm))
    val hop = kotlin.math.max(1, (0.010 * sampleRate).toInt())
    val win = kotlin.math.max(2, (0.020 * sampleRate).toInt())
    fun levelAt(centre: Int): Float {
        var e = 0.0
        var n = 0
        for (k in centre - win / 2 until centre + win / 2) {
            if (k < 0 || k >= band.size) continue
            e += band[k].toDouble() * band[k]
            n++
        }
        return if (n == 0) -120f else kotlin.math.max(-120.0, 10 * log10(e / n + 1e-14)).toFloat()
    }
    val levels = FloatArray(pcm.size / hop) { levelAt(it * hop + hop / 2) }.also { it.sort() }
    val floor = lowFloorDb ?: if (levels.isEmpty()) -120f else levels[(levels.size - 1) / 10]
    // Floor + margin (plan 03 §4), and no more than [belowVoicePeakDb] under the take's voiced peak: on clean or coded
    // audio the floor is near digital silence, and the vowels' low band leaking into an S would pass it.
    val voicePeak = if (levels.isEmpty()) -120f else levels[(levels.size - 1) * 95 / 100]
    val limit = kotlin.math.max(floor + marginDb, voicePeak - belowVoicePeakDb)
    val tracker = YINPitchTracker(sampleRate, pitch)
    val need = tracker.requiredInputCount
    var frames = 0
    var voiced = 0
    var t = start
    while (t < end - 1e-9) {
        val centre = ((t + 0.005) * sampleRate).toInt()
        val offset = (centre - need / 2).coerceIn(0, kotlin.math.max(0, pcm.size - need))
        if (pcm.size >= need) {
            val estimate = tracker.estimate(pcm, offset)
            frames++
            if (estimate.clarity > voicedClarity && levelAt(centre) > limit) voiced++
        }
        t += 0.010
    }
    if (frames == 0) return VoicingCues.None
    return VoicingCues(voiced.toFloat() / frames, harmonicity(pcm, sampleRate, start, end), frames)
}

/** HNR of the segment under 1 kHz, from the mean of per-frame autocorrelation peaks (F0 70–600 Hz). */
internal fun harmonicity(pcm: FloatArray, sampleRate: Double, start: Double, end: Double): Float {
    val lp = Biquad.lowpass(1000.0, sampleRate).run(Biquad.lowpass(1000.0, sampleRate).run(pcm))
    val factor = kotlin.math.max(1, (sampleRate / 12000.0).toInt())
    val rate = sampleRate / factor
    val x = FloatArray(lp.size / factor) { lp[it * factor] }
    val frame = (0.040 * rate).toInt()
    val tauMin = (rate / 600.0).toInt()
    val tauMax = (rate / 70.0).toInt()
    var sum = 0.0
    var count = 0
    var t = start
    while (t < end) {
        val a = ((t + 0.005) * rate).toInt() - frame / 2
        t += 0.010
        if (a < 0 || a + frame + tauMax >= x.size) continue
        var best = 0.0
        var e0 = 0.0
        for (i in 0 until frame) e0 += x[a + i].toDouble() * x[a + i]
        for (tau in tauMin..tauMax) {
            var c = 0.0
            var e1 = 0.0
            for (i in 0 until frame) {
                c += x[a + i].toDouble() * x[a + i + tau]
                e1 += x[a + i + tau].toDouble() * x[a + i + tau]
            }
            val r = if (e0 > 0 && e1 > 0) c / kotlin.math.sqrt(e0 * e1) else 0.0
            if (r > best) best = r
        }
        sum += best
        count++
    }
    if (count == 0) return Float.NaN
    val r = (sum / count).coerceIn(1e-6, 1 - 1e-6)
    return (10 * log10(r / (1 - r))).toFloat().coerceIn(-10f, 30f)
}
