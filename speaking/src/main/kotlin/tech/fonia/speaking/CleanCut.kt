package tech.fonia.speaking

import tech.fonia.live.Clip
import tech.fonia.speech.TranscriptWord
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

enum class EditReason { HESITATION, MARKER, REPETITION, FALSE_START, LENGTHENING, LONG_PAUSE }

/**
 * One removal from the original take, in samples: `[start, end)` is dropped and the two sides are joined with an
 * equal-power crossfade of `2 * half` samples centred on the join ([start] - [half] … [start] + [half] fading out,
 * [end] - [half] … [end] + [half] fading in). The output is exactly `end - start` samples shorter.
 */
data class CutEdit(
    val start: Int,
    val end: Int,
    val half: Int,
    val reason: EditReason,
    val spanIds: List<Int>,
    /** Where the join lands in the cleaned samples. */
    val joinAt: Int,
)

/**
 * Old time ↔ new time for a cleaned take: piecewise identity over the kept segments. A time inside a removed stretch
 * maps to its join. Used to make the transcript follow the cleaned playback.
 */
class TimeMap internal constructor(
    /** Kept segments in seconds: original start, original end, new start. */
    private val oldStart: DoubleArray,
    private val oldEnd: DoubleArray,
    private val newStart: DoubleArray,
    val oldDuration: Double,
    val newDuration: Double,
) {
    val segments: Int get() = oldStart.size

    fun toNew(old: Double): Double {
        if (oldStart.isEmpty()) return 0.0
        val i = lastAtOrBefore(oldStart, old)
        if (i < 0) return newStart[0]
        val into = old - oldStart[i]
        val len = oldEnd[i] - oldStart[i]
        return newStart[i] + into.coerceAtMost(len)
    }

    fun toOld(new: Double): Double {
        if (newStart.isEmpty()) return 0.0
        val i = lastAtOrBefore(newStart, new).coerceAtLeast(0)
        return min(oldEnd[i], oldStart[i] + (new - newStart[i]).coerceAtLeast(0.0))
    }

    /** True when [old] lies in a removed stretch. */
    fun isRemoved(old: Double): Boolean {
        val i = lastAtOrBefore(oldStart, old)
        return i < 0 || old >= oldEnd[i]
    }

    private fun lastAtOrBefore(a: DoubleArray, v: Double): Int {
        var lo = 0
        var hi = a.size - 1
        var best = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (a[mid] <= v) { best = mid; lo = mid + 1 } else hi = mid - 1
        }
        return best
    }

    companion object {
        val Identity = TimeMap(doubleArrayOf(0.0), doubleArrayOf(Double.MAX_VALUE), doubleArrayOf(0.0), 0.0, 0.0)
    }
}

data class CleanStats(
    val originalSeconds: Double,
    val cleanSeconds: Double,
    val secondsRemoved: Double,
    /** Hesitations and markers actually removed. */
    val fillersRemoved: Int,
    val repetitionsRemoved: Int,
    val falseStartsRemoved: Int,
    val pausesShortened: Int,
    /** Fillers the analysis counted, per minute of the original. */
    val fillersPerMinuteBefore: Double,
    /** Fillers left (not cut), per minute of the cleaned version. */
    val fillersPerMinuteAfter: Double,
)

class CleanResult(
    val samples: FloatArray,
    val sampleRate: Double,
    val edits: List<CutEdit>,
    val timeMap: TimeMap,
    val stats: CleanStats,
    /** The span ids that were asked for and produced a removal. */
    val cutIds: Set<Int>,
) {
    val clip: Clip get() = Clip(samples, sampleRate, audioSource = "clean")
}

/**
 * "You, without the ums": renders the cleaned take from a [SpeakingAnalysis] and the spans to cut.
 *
 * Rules (plan 01 §10, NO-UM step 4): cuts never enter a kept word (guard margins from the word timings, wider when the
 * recogniser was unsure, from the speech mask without a transcript); each edge moves to the quietest nearby point and
 * then to a zero crossing; joins are 10–20 ms equal-power crossfades; pauses over 1.2 s become ~0.6 s by removing
 * their middle, so the room tone stays (no dead-silent gaps); when a cut joins two silences the rest is capped.
 * Works on the original samples and rate.
 */
object CleanCut {
    fun render(clip: Clip, analysis: SpeakingAnalysis, cutIds: Set<Int> = analysis.defaultCuts, thresholds: SpeakingThresholds = SpeakingThresholds.v1): CleanResult =
        render(clip.samples, clip.sampleRate, analysis, cutIds, thresholds)

    fun render(samples: FloatArray, sampleRate: Double, analysis: SpeakingAnalysis, cutIds: Set<Int> = analysis.defaultCuts, thresholds: SpeakingThresholds = SpeakingThresholds.v1): CleanResult {
        val edits = plan(samples, sampleRate, analysis, cutIds, thresholds)
        return apply(samples, sampleRate, edits, analysis, cutIds)
    }

    private class Removal(var a: Double, var b: Double, val reason: EditReason, val ids: MutableList<Int>)

    /** The edit list, sorted, non-overlapping, snapped. */
    fun plan(samples: FloatArray, sampleRate: Double, analysis: SpeakingAnalysis, cutIds: Set<Int>, t: SpeakingThresholds = SpeakingThresholds.v1): List<CutEdit> {
        val c = t.cut
        val f = analysis.frames
        val duration = samples.size / sampleRate
        val removals = ArrayList<Removal>()
        for (s in analysis.spans) {
            if (s.id !in cutIds) continue
            removals.add(Removal(max(0.0, s.start), min(duration, s.end), reasonOf(s.kind), mutableListOf(s.id)))
        }

        // Protected stretches: every kept word (or kept speech, without a transcript), widened by its guard.
        val guards = protectedStretches(analysis, cutIds, t)
        val shrunk = ArrayList<Removal>()
        for (r in removals) shrunk.addAll(shrink(r, guards))
        removals.clear()
        removals.addAll(shrunk.filter { it.b - it.a >= c.minCutMs / 1000 })
        // Two cuts with no kept word between them ("Deci, practic,") become one: nothing worth keeping lies between.
        removals.sortBy { it.a }
        var k = 0
        while (k < removals.size - 1) {
            val x = removals[k]
            val y = removals[k + 1]
            if (guards.none { it.start < y.a && it.endInclusive > x.b }) {
                x.b = max(x.b, y.b)
                x.ids.addAll(y.ids)
                removals.removeAt(k + 1)
            } else k++
        }

        // A removed filler between two silences leaves one long silence: cap what is left of it.
        for (r in removals) {
            val left = silenceExtentLeft(f, r.a)
            val right = silenceExtentRight(f, r.b, duration)
            if (left + right > t.pause.joinedPauseMaxSeconds) {
                val target = t.pause.joinedPauseTargetSeconds
                var keepLeft = min(left, target / 2)
                val keepRight = min(right, target - keepLeft)
                keepLeft = min(left, target - keepRight)
                r.a -= left - keepLeft
                r.b += right - keepRight
            }
        }

        // Long pauses lose their middle; edge silences keep half the target on the speech side.
        for (p in analysis.pauses) {
            if (!p.long) continue
            if (removals.any { it.a <= p.end + 0.03 && it.b >= p.start - 0.03 }) continue
            val keep = t.pause.shortenToSeconds
            val (a, b) = when {
                p.start <= f.hop -> 0.0 to p.end - keep / 2
                p.end >= duration - f.hop -> p.start + keep / 2 to duration
                else -> p.start + keep / 2 to p.end - keep / 2
            }
            if (b - a >= c.minCutMs / 1000) removals.add(Removal(a, b, EditReason.LONG_PAUSE, mutableListOf()))
        }

        // Merge what touches.
        removals.sortBy { it.a }
        val merged = ArrayList<Removal>()
        for (r in removals) {
            val last = merged.lastOrNull()
            if (last != null && r.a <= last.b + c.mergeGapMs / 1000) {
                last.b = max(last.b, r.b)
                last.ids.addAll(r.ids)
            } else merged.add(r)
        }

        // Snap, size the crossfade, convert to samples.
        val sr = sampleRate
        val energyWin = max(8, (c.energyWindowMs / 1000 * sr).roundToInt())
        val out = ArrayList<CutEdit>()
        var prevEnd = 0
        var removedSoFar = 0
        for (r in merged) {
            val silentA = f.silentShare(r.a - 0.02, r.a) > 0.5
            val silentB = f.silentShare(r.b, r.b + 0.02) > 0.5
            var half = (((if (silentA && silentB) c.crossfadeSilenceMs else c.crossfadeMs) / 1000 * sr) / 2).roundToInt()
            val limitLo = max(guardEndBefore(guards, r.a), prevEnd / sr)
            val limitHi = min(guardStartAfter(guards, r.b), duration)
            val snap = c.snapSearchMs / 1000
            val atStart = r.a <= 1e-6
            val atEnd = r.b >= duration - 1e-6
            var a = if (atStart) 0 else snapEdge(samples, sr, r.a, max(limitLo, r.a - snap), min(r.a + snap, (r.a + r.b) / 2), energyWin, c.zeroCrossSearchMs)
            var b = if (atEnd) samples.size else snapEdge(samples, sr, r.b, max((r.a + r.b) / 2, r.b - snap), min(limitHi, r.b + snap), energyWin, c.zeroCrossSearchMs)
            // Room for the fades on both sides.
            half = minOf(half, if (atStart) half else a - prevEnd, if (atEnd) half else samples.size - b, max(1, (b - a) / 2))
            if (!atStart && a - half < prevEnd) a = prevEnd + half
            if (b - a < (c.minCutMs / 1000 * sr / 2).toInt() || half < 1) continue
            val joinAt = a - removedSoFar
            out.add(CutEdit(a, b, half, r.reason, r.ids.distinct(), joinAt))
            removedSoFar += b - a
            prevEnd = b + if (atEnd) 0 else half
        }
        return out
    }

    /** Renders [edits] (from [plan]) and builds the time map and the stats. */
    fun apply(samples: FloatArray, sampleRate: Double, edits: List<CutEdit>, analysis: SpeakingAnalysis, cutIds: Set<Int>): CleanResult {
        val removed = edits.sumOf { it.end - it.start }
        val out = FloatArray(samples.size - removed)
        var cursor = 0
        var w = 0
        for (e in edits) {
            val h = e.half
            if (e.start == 0) {
                // Start of the take: fade the first kept samples in.
                for (k in 0 until h) out[w++] = samples[e.end + k] * sin(PI / 2 * (k + 0.5) / h).toFloat()
                cursor = e.end + h
                continue
            }
            val copyEnd = e.start - h
            System.arraycopy(samples, cursor, out, w, copyEnd - cursor)
            w += copyEnd - cursor
            if (e.end >= samples.size) {
                for (k in 0 until h) out[w++] = samples[copyEnd + k] * cos(PI / 2 * (k + 0.5) / h).toFloat()
                cursor = samples.size
                continue
            }
            for (k in 0 until 2 * h) {
                val x = PI / 2 * (k + 0.5) / (2 * h)
                out[w++] = (samples[e.start - h + k] * cos(x) + samples[e.end - h + k] * sin(x)).toFloat()
            }
            cursor = e.end + h
        }
        if (cursor < samples.size) {
            System.arraycopy(samples, cursor, out, w, samples.size - cursor)
            w += samples.size - cursor
        }
        check(w == out.size) { "CleanCut wrote $w samples, expected ${out.size}" }

        val sr = sampleRate
        val os = ArrayList<Double>()
        val oe = ArrayList<Double>()
        val ns = ArrayList<Double>()
        var prev = 0
        var newPos = 0
        for (e in edits) {
            if (e.start > prev) { os.add(prev / sr); oe.add(e.start / sr); ns.add(newPos / sr); newPos += e.start - prev }
            prev = e.end
        }
        if (prev < samples.size) { os.add(prev / sr); oe.add(samples.size / sr); ns.add(newPos / sr) }
        val map = TimeMap(os.toDoubleArray(), oe.toDoubleArray(), ns.toDoubleArray(), samples.size / sr, out.size / sr)

        val done = edits.flatMap { it.spanIds }.toSet()
        val spans = analysis.spans.filter { it.id in done }
        val fillersBefore = analysis.spans.count { it.isFiller && (it.cut || it.id in cutIds) }
        val fillersRemoved = spans.count { it.isFiller }
        val stats = CleanStats(
            originalSeconds = samples.size / sr,
            cleanSeconds = out.size / sr,
            secondsRemoved = removed / sr,
            fillersRemoved = fillersRemoved,
            repetitionsRemoved = spans.count { it.kind == SpanKind.REPETITION },
            falseStartsRemoved = spans.count { it.kind == SpanKind.FALSE_START },
            pausesShortened = edits.count { it.reason == EditReason.LONG_PAUSE } + edits.count { it.reason != EditReason.LONG_PAUSE && it.spanIds.isEmpty() },
            fillersPerMinuteBefore = fillersBefore / max(1.0 / 60, samples.size / sr / 60),
            fillersPerMinuteAfter = (fillersBefore - fillersRemoved).coerceAtLeast(0) / max(1.0 / 60, out.size / sr / 60),
        )
        return CleanResult(out, sampleRate, edits, map, stats, done)
    }

    private fun reasonOf(kind: SpanKind) = when (kind) {
        SpanKind.HESITATION -> EditReason.HESITATION
        SpanKind.MARKER -> EditReason.MARKER
        SpanKind.REPETITION -> EditReason.REPETITION
        SpanKind.FALSE_START -> EditReason.FALSE_START
        SpanKind.LENGTHENING -> EditReason.LENGTHENING
    }

    /** Kept stretches that no cut may enter, sorted, with their guards already added. */
    internal fun protectedStretches(analysis: SpeakingAnalysis, cutIds: Set<Int>, t: SpeakingThresholds): List<ClosedFloatingPointRange<Double>> {
        val c = t.cut
        val out = ArrayList<ClosedFloatingPointRange<Double>>()
        val cutWords = analysis.spans.filter { it.id in cutIds }.flatMap { it.wordIndices }.toSet()
        if (analysis.words.isNotEmpty()) {
            for ((i, w) in analysis.words.withIndex()) {
                if (i in cutWords) continue
                val g = (analysis.wordGuardMs.getOrNull(i) ?: guardFor(w, c)) / 1000
                out.add((w.start - g)..(w.end + g))
            }
            // Acoustic spans that are not cut are protected as they are (they may be a word the recogniser missed).
            for (s in analysis.spans) if (s.id !in cutIds && s.wordIndices.isEmpty()) out.add(s.start..s.end)
        } else {
            // Without a transcript: every active frame (no hangover) outside the cut spans, gaps of <= 20 ms bridged.
            val f = analysis.frames
            val cut = analysis.spans.filter { it.id in cutIds }
            fun kept(k: Int) = f.active[k] && cut.none { f.time(k) >= it.start && f.time(k) < it.end }
            val g = c.guardMs / 1000
            var i = 0
            while (i < f.count) {
                if (!kept(i)) { i++; continue }
                var j = i
                var gap = 0
                var last = i
                while (j < f.count) {
                    if (kept(j)) { last = j; gap = 0 } else if (++gap > 2) break
                    j++
                }
                // A stretch shorter than `minSpeechRunSeconds` is an onset or a decay left by a cut, not a word.
                if ((last + 1 - i) * f.hop >= t.analysis.minSpeechRunSeconds) out.add((f.time(i) - g)..(f.time(last + 1) + g))
                i = last + 1
            }
        }
        return out.sortedBy { it.start }
    }

    private fun guardFor(w: TranscriptWord, c: SpeakingThresholds.Cut): Double =
        if (w.prob < c.uncertainProbability) c.guardUncertainMs else c.guardMs

    private fun shrink(r: Removal, guards: List<ClosedFloatingPointRange<Double>>): List<Removal> {
        var pieces = listOf(r)
        for (g in guards) {
            if (g.start >= r.b || g.endInclusive <= r.a) continue
            val next = ArrayList<Removal>()
            for (p in pieces) {
                if (g.start >= p.b || g.endInclusive <= p.a) { next.add(p); continue }
                if (g.start > p.a) next.add(Removal(p.a, g.start, p.reason, p.ids.toMutableList()))
                if (g.endInclusive < p.b) next.add(Removal(g.endInclusive, p.b, p.reason, p.ids.toMutableList()))
            }
            pieces = next
        }
        // Several pieces of one span: keep only the longest (a span split by a kept word is not trusted further).
        return if (pieces.size <= 1) pieces else listOf(pieces.maxBy { it.b - it.a })
    }

    private fun guardEndBefore(guards: List<ClosedFloatingPointRange<Double>>, t: Double): Double =
        guards.filter { it.endInclusive <= t + 1e-9 }.maxOfOrNull { it.endInclusive } ?: 0.0

    private fun guardStartAfter(guards: List<ClosedFloatingPointRange<Double>>, t: Double): Double =
        guards.filter { it.start >= t - 1e-9 }.minOfOrNull { it.start } ?: Double.MAX_VALUE

    /** Silence (non-speech frames) running back from [t], seconds. */
    private fun silenceExtentLeft(f: SpeechFrames, t: Double): Double {
        var i = f.index(t) - 1
        var n = 0
        while (i >= 0 && !f.speech[i]) { n++; i-- }
        return n * f.hop
    }

    private fun silenceExtentRight(f: SpeechFrames, t: Double, duration: Double): Double {
        var i = f.index(t)
        var n = 0
        while (i < f.count && !f.speech[i]) { n++; i++ }
        return min(n * f.hop, duration - t)
    }

    /**
     * The quietest point (RMS over [energyWin] samples) between [lo] and [hi] seconds, preferring [target] on ties,
     * then the nearest zero crossing within [zeroMs].
     */
    private fun snapEdge(x: FloatArray, sr: Double, target: Double, lo: Double, hi: Double, energyWin: Int, zeroMs: Double): Int {
        val t0 = (target * sr).roundToInt().coerceIn(0, x.size)
        val a = (lo * sr).roundToInt().coerceIn(0, x.size)
        val b = (hi * sr).roundToInt().coerceIn(0, x.size)
        if (b <= a) return t0.coerceIn(min(a, b), max(a, b))
        val step = max(1, energyWin / 4)
        var best = t0.coerceIn(a, b)
        var bestE = Double.MAX_VALUE
        var p = a
        while (p <= b) {
            val e = rms(x, p - energyWin / 2, energyWin)
            // A small pull towards the target, so equal quiet keeps the planned place.
            val score = e * (1 + 0.15 * abs(p - t0) / max(1, b - a))
            if (score < bestE) { bestE = score; best = p }
            p += step
        }
        val z = (zeroMs / 1000 * sr).roundToInt()
        var zBest = best
        var zDist = Int.MAX_VALUE
        for (k in max(1, best - z)..min(x.size - 1, best + z)) {
            if ((x[k - 1] <= 0f && x[k] > 0f) || (x[k - 1] >= 0f && x[k] < 0f)) {
                val d = abs(k - best)
                if (d < zDist) { zDist = d; zBest = k }
            }
        }
        return zBest.coerceIn(a, b)
    }

    internal fun rms(x: FloatArray, start: Int, n: Int): Double {
        var s = 0.0
        var c = 0
        for (k in max(0, start) until min(x.size, start + n)) { s += x[k] * x[k]; c++ }
        return if (c == 0) 0.0 else sqrt(s / c)
    }
}
