package tech.fonia.speaking

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The objective checks of one join. Ratios compare the join with the two original edges it was made from. */
data class JoinCheck(
    val edit: CutEdit,
    /** Original times of the removed stretch and the join's place in the cleaned take, seconds. */
    val oldStart: Double,
    val oldEnd: Double,
    val newAt: Double,
    /** Largest sample step across the join / largest step near the original edges (a click is a step). */
    val jumpRatio: Double,
    /** Peak energy of the first difference (5 ms windows) across the join / the same near the original edges. */
    val clickRatio: Double,
    /** Level just before the removed stretch and just after it, dB relative to the take's speech median. */
    val edgeBeforeDb: Double,
    val edgeAfterDb: Double,
    val noClick: Boolean,
    /** Both edges are well below the speech level: no word was cut into. */
    val quietEdges: Boolean,
)

data class CutQualityReport(
    val joins: List<JoinCheck>,
    val expectedSamples: Int,
    val actualSamples: Int,
) {
    val lengthConsistent: Boolean get() = expectedSamples == actualSamples
    val clicks: Int get() = joins.count { !it.noClick }
    val loudEdges: Int get() = joins.count { !it.quietEdges }
    val allClean: Boolean get() = lengthConsistent && clicks == 0
}

/**
 * Checks a [CleanResult] objectively (G11 "fiecare tăietură afișată se aude tăiată, fără pocnet"): no click at a join
 * (sample step and first-difference energy no larger than at the original edges), cut edges quiet (no word truncated),
 * and the length arithmetic (`cleaned = original − Σ removed`). Pure; usable in tests and in a debug overlay.
 */
object CutQuality {
    fun check(original: FloatArray, result: CleanResult, speechMedianDb: Float, thresholds: SpeakingThresholds = SpeakingThresholds.v1): CutQualityReport {
        val q = thresholds.quality
        val sr = result.sampleRate
        val y = result.samples
        val win = max(4, (q.clickWindowMs / 1000 * sr).roundToInt())
        val pad = max(2, (0.001 * sr).roundToInt())
        val edge = max(4, (0.01 * sr).roundToInt())
        val joins = result.edits.map { e ->
            val h = e.half
            val inner = e.start > 0 && e.end < original.size
            var jump = 0.0
            var click = 0.0
            if (inner) {
                val outStep = maxStep(y, e.joinAt - h - pad, e.joinAt + h + pad)
                val refStep = max(maxStep(original, e.start - h - pad, e.start + h + pad), maxStep(original, e.end - h - pad, e.end + h + pad))
                jump = outStep / max(refStep, 1e-6)
                val outClick = peakDiffEnergy(y, e.joinAt - h, e.joinAt + h, win)
                val refClick = max(peakDiffEnergy(original, e.start - h - win, e.start + h + win, win), peakDiffEnergy(original, e.end - h - win, e.end + h + win, win))
                click = outClick / max(refClick, 1e-12)
            }
            val before = if (e.start > 0) db(CleanCut.rms(original, e.start - edge, edge)) - speechMedianDb else -99.0
            val after = if (e.end < original.size) db(CleanCut.rms(original, e.end, edge)) - speechMedianDb else -99.0
            JoinCheck(
                edit = e, oldStart = e.start / sr, oldEnd = e.end / sr, newAt = e.joinAt / sr,
                jumpRatio = jump, clickRatio = click, edgeBeforeDb = before, edgeAfterDb = after,
                noClick = !inner || (jump <= q.maxJumpRatio && click <= q.maxClickRatio),
                quietEdges = before <= -q.edgeQuietDb && after <= -q.edgeQuietDb,
            )
        }
        val expected = original.size - result.edits.sumOf { it.end - it.start }
        return CutQualityReport(joins, expected, y.size)
    }

    private fun maxStep(x: FloatArray, from: Int, to: Int): Double {
        var m = 0.0
        for (k in max(1, from) until min(x.size, to)) m = max(m, kotlin.math.abs((x[k] - x[k - 1]).toDouble()))
        return m
    }

    private fun peakDiffEnergy(x: FloatArray, from: Int, to: Int, win: Int): Double {
        var best = 0.0
        val step = max(1, win / 4)
        var c = from
        while (c <= to) {
            var s = 0.0
            var n = 0
            for (k in max(1, c - win / 2) until min(x.size, c + win / 2)) {
                val d = (x[k] - x[k - 1]).toDouble()
                s += d * d
                n++
            }
            if (n > 0) best = max(best, s / n)
            c += step
        }
        return best
    }

    private fun db(rms: Double): Double = 20 * log10(rms + 1e-9)
}
