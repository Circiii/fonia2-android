package tech.fonia.speaking

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Finds sustained hesitation sounds ("ăăă", "îîî", "mmm") from the sound alone, so NO-UM works without Whisper.
 *
 * A hesitation is a voiced stretch whose spectrum hardly changes (one vowel or a hum held still), whose pitch is flat
 * (spread around its own trend under ~1 semitone, no glide), whose level is steady, and which stands apart: a pause on
 * at least one side. Ordinary speech changes its spectrum every 50–100 ms, so steady runs longer than ~0.2 s are rare
 * outside hesitations and drawn-out word endings. A run glued to the word before it is a lengthening: only the
 * stretched part beyond [SpeakingThresholds.Hesitation.keepLengthenedSeconds] is offered, at low confidence.
 *
 * Compared with the dsp's `ClipSummary.hesitations` (steady pitch next to a pause, 0.25–1 s), this adds spectral
 * steadiness, level steadiness, a trend-removed pitch spread, both-side boundaries, lengthenings and a confidence.
 */
object HesitationDetector {
    /** Debug hook: why each long-enough steady run was kept or dropped. */
    @Volatile internal var trace: ((String) -> Unit)? = null

    fun detect(f: SpeechFrames, thresholds: SpeakingThresholds = SpeakingThresholds.v1): List<AcousticHesitation> {
        val h = thresholds.hesitation
        val a = thresholds.analysis
        val n = f.count
        val steady = BooleanArray(n) {
            f.speech[it] && f.change[it] < h.maxSpectralChangeDb && f.db[it] > f.floorDb + a.minSpeechDbAboveFloor
        }
        val runs = ArrayList<IntRange>()
        var i = 0
        while (i < n) {
            if (!steady[i]) { i++; continue }
            var j = i
            var last = i
            while (j < n) {
                if (steady[j]) last = j
                else if (j - last > h.gapFrames) break
                j++
            }
            // A run chained through many unsteady frames is speech, not one held sound.
            val steadyShare = (i..last).count { steady[it] }.toDouble() / (last - i + 1)
            if (steadyShare >= h.minSteadyShare) runs.add(i..last)
            i = last + 1
        }

        val out = ArrayList<AcousticHesitation>()
        val hangFrames = (a.speechHangSeconds / f.hop).roundToInt()
        val reach = h.boundaryReachSeconds + hangFrames * f.hop
        for (run in runs) {
            val dur = (run.last - run.first + 1) * f.hop
            if (dur < h.minSeconds || dur > h.maxSeconds) continue
            val start = f.time(run.first)
            val end = f.time(run.last + 1)

            // Pitch: flat around its own trend, no glide.
            val times = ArrayList<Double>()
            val st = ArrayList<Double>()
            for (k in run) if (f.hz[k] > 0f) { times.add(f.time(k)); st.add(12 * ln(f.hz[k].toDouble()) / ln(2.0)) }
            val voicedShare = st.size.toDouble() / (run.last - run.first + 1)
            val tr = trace
            fun drop(why: String) { tr?.invoke("drop ${"%.2f".format(start)}-${"%.2f".format(end)}: $why") }
            if (voicedShare < h.minVoicedShare || st.size < 4) { drop("voiced ${"%.2f".format(voicedShare)}"); continue }
            val med = st.sorted()[st.size / 2]
            val keep = st.indices.filter { abs(st[it] - med) < 6.0 } // octave slips of the tracker
            if (keep.size < 4) { drop("pitch outliers"); continue }
            val fit = linearFit(keep.map { times[it] }, keep.map { st[it] })
            if (fit.residualSd > h.maxPitchSdSt || abs(fit.slope) > h.maxPitchSlopeStPerSecond) {
                drop("pitch sd ${"%.2f".format(fit.residualSd)} slope ${"%.1f".format(fit.slope)}"); continue
            }

            // Level steady over the inner 80 %; the envelope change judged by its median (one glitch is not a word).
            val inner = (run.first + (run.last - run.first) / 10)..(run.last - (run.last - run.first) / 10)
            val levels = inner.map { f.db[it].toDouble() }
            val spread = robustSd(levels)
            if (spread > h.maxLevelSdDb) { drop("level sd ${"%.2f".format(spread)}"); continue }
            val changes = run.map { f.change[it].toDouble() }.sorted()
            val meanChange = changes[changes.size / 2]
            if (meanChange > h.maxMeanSpectralChangeDb) { drop("change ${"%.2f".format(meanChange)}"); continue }

            val silenceStart = f.silenceBefore(start, reach)
            val silenceEnd = f.silenceAfter(end, reach)
            // (A stop closure inside a word, "co-p-iii", is silent too and can pass for a pause; tested on the voices
            // here, its release burst could not be told from a breathy onset, so no rule is built on it.)
            val before = (silenceStart != null && silenceStart.endInclusive - silenceStart.start >= h.boundaryPauseSeconds - 1e-9) ||
                noSpeechBefore(f, run.first, reach)
            val after = (silenceEnd != null && silenceEnd.endInclusive - silenceEnd.start >= h.boundaryPauseSeconds - 1e-9) ||
                noSpeechAfter(f, run.last, reach)

            var confidence: Double
            var lengthening = false
            var cutStart = start
            var cutEnd = end
            when {
                before && after -> confidence = 0.78
                before -> confidence = 0.66
                after && dur >= h.longAloneSeconds -> {
                    confidence = 0.62
                    cutStart = start + h.keepLengthenedSeconds / 2
                }
                after -> {
                    confidence = 0.5
                    lengthening = true
                    cutStart = start + h.keepLengthenedSeconds
                }
                dur >= h.longAloneSeconds * 1.4 -> confidence = 0.52
                else -> { drop("not bounded"); continue }
            }
            if (dur >= 0.4) confidence += 0.08
            if (fit.residualSd < 0.5) confidence += 0.05
            if (meanChange < 0.7 * h.maxMeanSpectralChangeDb) confidence += 0.05
            if (lengthening) confidence = min(confidence, 0.55)
            confidence = min(confidence, 0.97)

            // A free side reaches into the pause (never across it), so the cut lands in room tone, not on the onset.
            val hang = a.speechHangSeconds
            if (before && silenceStart != null) cutStart = max(silenceStart.start, silenceStart.endInclusive - hang)
            if (after && silenceEnd != null) cutEnd = min(silenceEnd.endInclusive, silenceEnd.start + hang)
            // A side glued to the next word reaches to the level dip between them (the vowel's own decay goes too).
            val runLevel = levels.average()
            if (!after) valleyAfter(f, run.last, runLevel, h)?.let { cutEnd = max(cutEnd, it) }
            if (!before && !lengthening) valleyBefore(f, run.first, runLevel, h)?.let { cutStart = min(cutStart, it) }
            if (cutEnd - cutStart < h.minSeconds / 2) continue

            val hl = run.map { f.highLowDb[it].toDouble() }.average()
            val f1 = run.map { f.f1Hz[it].toDouble() }.sorted()[(run.last - run.first + 1) / 2]
            val label = when {
                hl < h.nasalHighBandDb -> "mmm"
                f1 < h.closeVowelF1Hz -> "îîî"
                else -> "ăăă"
            }
            out.add(AcousticHesitation(cutStart, cutEnd, confidence, label, before, after, lengthening, meanChange, fit.residualSd))
        }
        out.addAll(sonorantIslands(f, h, out))
        return out.sortedBy { it.start }
    }

    /** The second cue (see [SpeakingThresholds.Hesitation.islandMinSeconds]). */
    private fun sonorantIslands(f: SpeechFrames, h: SpeakingThresholds.Hesitation, found: List<AcousticHesitation>): List<AcousticHesitation> {
        val out = ArrayList<AcousticHesitation>()
        val edge = max(1, (h.islandEdgeSilenceSeconds / f.hop).roundToInt())
        var i = 0
        while (i < f.count) {
            if (!f.active[i]) { i++; continue }
            var j = i
            while (j < f.count && f.active[j]) j++
            // Split the island at deep dips (a stop closure between two words can stay above the gate).
            val level = (i until j).map { f.db[it] }.sorted()[(j - i) / 2]
            var a = i
            var k = i
            while (k <= j) {
                val dip = k == j || f.db[k] < level - h.islandDipDb
                if (dip) {
                    if (k > a) island(f, h, a, k - 1, edge, found)?.let { out.add(it) }
                    a = k + 1
                }
                k++
            }
            i = j
        }
        return out
    }

    private fun island(f: SpeechFrames, h: SpeakingThresholds.Hesitation, a: Int, b: Int, edge: Int, found: List<AcousticHesitation>): AcousticHesitation? {
        val start = f.time(a)
        val end = f.time(b + 1)
        val dur = end - start
        if (dur < h.islandMinSeconds || dur > h.maxSeconds) return null
        if (found.any { it.start < end && it.end > start }) return null
        val tr = trace
        fun drop(why: String): AcousticHesitation? { tr?.invoke("island ${"%.2f".format(start)}-${"%.2f".format(end)}: $why"); return null }
        // Quiet on both sides: inactive frames, or the dip that split the island, for at least `edge` frames.
        fun quiet(k: Int) = k < 0 || k >= f.count || !f.active[k] || f.db[k] < f.db[(a + b) / 2] - h.islandDipDb
        if (!(1..edge).all { quiet(a - it) }) return drop("no pause before")
        if (!(1..edge).all { quiet(b + it) }) return drop("no pause after")
        val inner = (a + 3)..(b - 3)
        if (inner.isEmpty()) return null
        val hissy = inner.count { f.highLowDb[it] >= h.islandMaxHighLowDb }
        if (hissy > 0) return drop("frication in $hissy frames")
        val voiced = inner.count { f.hz[it] > 0f }.toDouble() / (inner.last - inner.first + 1)
        if (voiced < h.islandMinVoicedShare) return drop("voiced ${"%.2f".format(voiced)}")
        val changes = inner.map { f.change[it].toDouble() }.sorted()
        val median = changes[changes.size / 2]
        val p90 = changes[(changes.size * 0.9).toInt().coerceAtMost(changes.size - 1)]
        if (median > h.islandMedianChangeDb || p90 > h.islandP90ChangeDb) return drop("change ${"%.2f".format(median)}/${"%.2f".format(p90)}")
        val ts = ArrayList<Double>()
        val st = ArrayList<Double>()
        for (k in inner) if (f.hz[k] > 0f) { ts.add(f.time(k)); st.add(12 * ln(f.hz[k].toDouble()) / ln(2.0)) }
        val med = st.sorted()[st.size / 2]
        val keep = st.indices.filter { abs(st[it] - med) < 6.0 }
        if (keep.size < 4) return drop("pitch")
        val fit = linearFit(keep.map { ts[it] }, keep.map { st[it] })
        if (fit.residualSd > h.islandMaxPitchSdSt) return drop("pitch sd ${"%.2f".format(fit.residualSd)}")
        val confidence = min(0.75, h.islandConfidence + if (dur >= 0.45) 0.05 else 0.0)
        val hl = inner.map { f.highLowDb[it].toDouble() }.average()
        val f1 = inner.map { f.f1Hz[it].toDouble() }.sorted()[(inner.last - inner.first + 1) / 2]
        val label = when {
            hl < h.nasalHighBandDb -> "mmm"
            f1 < h.closeVowelF1Hz -> "îîî"
            else -> "ăăă"
        }
        // Take in the onset and decay frames the dip split off (up to 30 ms each side), so they are not left behind.
        var s0 = a
        while (s0 > 0 && a - s0 < 3 && f.active[s0 - 1]) s0--
        var e0 = b
        while (e0 < f.count - 1 && e0 - b < 3 && f.active[e0 + 1]) e0++
        return AcousticHesitation(f.time(s0), f.time(e0 + 1), confidence, label, true, true, false, median, fit.residualSd)
    }

    private fun valleyAfter(f: SpeechFrames, last: Int, level: Double, h: SpeakingThresholds.Hesitation): Double? {
        val reach = (h.valleySearchSeconds / f.hop).roundToInt()
        var best = -1
        for (k in last + 1..min(f.count - 1, last + reach)) {
            if (f.highLowDb[k] > h.valleyMaxHighLowDb) break // a fricative: the next word has begun
            if (best < 0 || f.db[k] < f.db[best]) best = k
        }
        return if (best >= 0 && f.db[best] <= level - h.valleyDepthDb) f.time(best) else null
    }

    private fun valleyBefore(f: SpeechFrames, first: Int, level: Double, h: SpeakingThresholds.Hesitation): Double? {
        val reach = (h.valleySearchSeconds / f.hop).roundToInt()
        var best = -1
        for (k in first - 1 downTo max(0, first - reach)) {
            if (f.highLowDb[k] > h.valleyMaxHighLowDb) break
            if (best < 0 || f.db[k] < f.db[best]) best = k
        }
        return if (best >= 0 && f.db[best] <= level - h.valleyDepthDb) f.time(best) else null
    }

    /** The take starts (speech from its first frame) within [reach] seconds before frame [i]. */
    private fun noSpeechBefore(f: SpeechFrames, i: Int, reach: Double): Boolean {
        val k = f.index(speechEdgeBefore(f, i))
        return k == 0 && (i - k) * f.hop <= reach
    }

    /** The take ends (speech to its last frame) within [reach] seconds after frame [i]. */
    private fun noSpeechAfter(f: SpeechFrames, i: Int, reach: Double): Boolean {
        val end = speechEdgeAfter(f, i)
        return f.index(end) >= f.count - 1 && end - f.time(i + 1) <= reach
    }

    /** Where the speech island containing frame [i] begins (start of the frame after the last silent one). */
    private fun speechEdgeBefore(f: SpeechFrames, i: Int): Double {
        var k = i
        while (k > 0 && f.speech[k - 1]) k--
        return f.time(k)
    }

    private fun speechEdgeAfter(f: SpeechFrames, i: Int): Double {
        var k = i
        while (k < f.count - 1 && f.speech[k + 1]) k++
        return f.time(k + 1)
    }

    internal class Fit(val slope: Double, val intercept: Double, val residualSd: Double)

    internal fun linearFit(x: List<Double>, y: List<Double>): Fit {
        val n = x.size
        val mx = x.average()
        val my = y.average()
        var sxx = 0.0
        var sxy = 0.0
        for (k in 0 until n) { sxx += (x[k] - mx) * (x[k] - mx); sxy += (x[k] - mx) * (y[k] - my) }
        val slope = if (sxx > 0) sxy / sxx else 0.0
        val intercept = my - slope * mx
        var ss = 0.0
        for (k in 0 until n) { val r = y[k] - (intercept + slope * x[k]); ss += r * r }
        return Fit(slope, intercept, sqrt(ss / max(1, n)))
    }

    /** Spread from the 10th–90th percentile range (a Gaussian's SD), so onset and decay frames do not count. */
    internal fun robustSd(v: List<Double>): Double {
        if (v.size < 3) return 0.0
        val s = v.sorted()
        return (s[(s.size * 0.9).toInt().coerceAtMost(s.size - 1)] - s[(s.size * 0.1).toInt()]) / 2.56
    }

    internal fun sd(v: List<Double>): Double {
        if (v.size < 2) return 0.0
        val m = v.average()
        return sqrt(v.sumOf { (it - m) * (it - m) } / v.size)
    }
}
