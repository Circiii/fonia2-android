package tech.fonia.dsp

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sqrt

/** Contours for drawing, on the model's timeline. */
data class ProsodyCurves(
    /** Seconds from the model's speech start. */
    val time: List<Float>,
    /** Model pitch, semitones re its median. `null` when unvoiced. */
    val model: List<Float?>,
    /** Take pitch warped onto the model's timeline. */
    val take: List<Float?>,
)

data class PauseMatch(
    val model: Int,
    val take: Int,
    val matched: Int,
)

enum class ProsodyOutcome {
    NOT_HEARD,
    GOOD,
    FLAT,
    MELODY_DIFFERS,
    TOO_SLOW,
    TOO_FAST,
    PAUSES_DIFFER,
    CLOSE,
}

data class ProsodyComparison(
    /** 0...1: same rises and falls, with a similar range. `null` if the model has no melody to compare. */
    val intonation: Float?,
    /** Correlation of the aligned contours, -1...1. */
    val shape: Float?,
    /** Take range over model range. 1 is equally lively; below 0.6 sounds flat. */
    val rangeRatio: Float?,
    /** 0...1: similar duration and pauses in the same places. */
    val rhythm: Float,
    val durationRatio: Float,
    val pauses: PauseMatch,
    val overall: Float,
    val curves: ProsodyCurves,
    val outcome: ProsodyOutcome,
    val isAccepted: Boolean,
)

/** One step of a DTW path: model frame `i` against take frame `j` (Swift's `(i: Int, j: Int)`). */
internal data class PathStep(val i: Int, val j: Int)

/** DTW with a Sakoe-Chiba band. Returns the path from start to end. */
internal fun dtwPath(a: List<FloatArray>, b: List<FloatArray>, band: Float = DSPThresholds.v1.prosody.dtwBand): List<PathStep> {
    val n = a.size
    val m = b.size
    if (n <= 0 || m <= 0) return emptyList()
    val width = m + 1
    val cost = FloatArray((n + 1) * width) { Float.POSITIVE_INFINITY }
    cost[0] = 0f
    val radius = max(ceil(band * max(n, m).toFloat()).toInt(), abs(n - m) + 2)
    val dimension = a[0].size
    for (i in 1..n) {
        val centre = (i.toFloat() * m.toFloat() / n.toFloat()).rounded().toInt()
        val x = a[i - 1]
        for (j in max(1, centre - radius)..min(m, centre + radius)) {
            val y = b[j - 1]
            var distance = 0f
            for (k in 0 until dimension) distance += abs(x[k] - y[k])
            val best = min(cost[(i - 1) * width + j - 1], min(cost[(i - 1) * width + j], cost[i * width + j - 1]))
            cost[i * width + j] = distance + best
        }
    }
    val path = ArrayList<PathStep>()
    var i = n
    var j = m
    while (i > 0 && j > 0) {
        path.add(PathStep(i - 1, j - 1))
        val diagonal = cost[(i - 1) * width + j - 1]
        val up = cost[(i - 1) * width + j]
        val left = cost[i * width + j - 1]
        if (diagonal <= up && diagonal <= left) {
            i -= 1
            j -= 1
        } else if (up < left) {
            i -= 1
        } else {
            j -= 1
        }
    }
    return path.reversed()
}

private class ProsodySegment(
    val time: List<Double>,
    val semitones: List<Float?>,
    val features: List<FloatArray>,
)

/**
 * The speaking part of a track, decimated to `hop`, with semitones and alignment features. The
 * features use loudness and spectral shape, never pitch, which is the thing being compared.
 */
private fun prepareSegment(summary: ClipSummary, hop: Double, settings: DSPThresholds.Prosody): ProsodySegment {
    val track = summary.track
    val firstTime = track.firstOrNull()?.time ?: return ProsodySegment(emptyList(), emptyList(), emptyList())
    val source = summary.trackHopSeconds
    val step = max(1, (hop / source).rounded().toInt())
    val start = max(0, floor((summary.speechStart - settings.spanMarginSeconds - firstTime) / source).toInt())
    val end = min(track.size, ceil((summary.speechEnd + settings.spanMarginSeconds - firstTime) / source).toInt() + 1)
    if (start >= end) return ProsodySegment(emptyList(), emptyList(), emptyList())
    val indices = (start until end step step).toList()

    val centre = median(indices.map { track[it].melodyHz }.filter { it > 0f }.toFloatArray())
    val raw: List<Float?> = indices.map { index ->
        val hz = track[index].melodyHz
        if (hz > 0f && centre > 0f) semitones(hz, centre) else null
    }
    // Median smoothing drops single-frame pitch errors.
    val smoothed: List<Float?> = raw.indices.map { k ->
        if (raw[k] == null) {
            null
        } else {
            val a = max(0, k - settings.medianFrames)
            val b = min(raw.size - 1, k + settings.medianFrames)
            median(raw.subList(a, b + 1).filterNotNull().toFloatArray())
        }
    }
    val levels = FloatArray(indices.size) { track[indices[it]].db }
    val meanDb = mean(levels)
    val spread = standardDeviation(levels)
    val sdDb = if (spread > 0f) spread else 1f
    val features = ArrayList<FloatArray>(indices.size)
    for ((k, index) in indices.withIndex()) {
        val z = (track[index].db - meanDb) / sdDb
        val previous = if (k > 0) (track[indices[k - 1]].db - meanDb) / sdDb else z
        features.add(floatArrayOf(z, (z - previous) * 2f, track[index].hfShare * 3f, if (track[index].melodyHz > 0f) 1f else 0f))
    }
    return ProsodySegment(time = indices.map { track[it].time }, semitones = smoothed, features = features)
}

private fun pearson(x: FloatArray, y: FloatArray, minCount: Int): Float? {
    if (x.size < minCount) return null
    val meanX = mean(x)
    val meanY = mean(y)
    var product = 0f
    var squareX = 0f
    var squareY = 0f
    for (k in 0 until min(x.size, y.size)) {
        val a = x[k]
        val b = y[k]
        product += (a - meanX) * (b - meanY)
        squareX += (a - meanX) * (a - meanX)
        squareY += (b - meanY) * (b - meanY)
    }
    if (!(squareX >= 1e-6f && squareY >= 1e-6f)) return null
    return product / sqrt(squareX * squareY)
}

/**
 * How close a take is to a model in melody and timing. Contours are in semitones around each
 * speaker's own median, so a child can shadow a grown-up voice.
 */
fun compareProsody(model: ClipSummary, take: ClipSummary, thresholds: DSPThresholds = DSPThresholds.v1): ProsodyComparison {
    val settings = thresholds.prosody
    val modelDuration = max(0.0, model.speechEnd - model.speechStart)
    val takeDuration = max(0.0, take.speechEnd - take.speechStart)
    val pauseCounts = PauseMatch(model = model.pauses.size, take = take.pauses.size, matched = 0)
    if (!(modelDuration >= settings.minSpeakingSeconds && takeDuration >= settings.minSpeakingSeconds)) {
        return ProsodyComparison(
            intonation = null, shape = null, rangeRatio = null, rhythm = 0f, durationRatio = 0f, pauses = pauseCounts, overall = 0f,
            curves = ProsodyCurves(time = emptyList(), model = emptyList(), take = emptyList()),
            outcome = ProsodyOutcome.NOT_HEARD, isAccepted = false,
        )
    }
    // Keeps DTW near a million cells even for long takes.
    val hop = max(
        model.trackHopSeconds, take.trackHopSeconds,
        sqrt(modelDuration * takeDuration / settings.maxDtwCells),
    )
    val a = prepareSegment(model, hop, settings)
    val b = prepareSegment(take, hop, settings)
    val path = dtwPath(a.features, b.features, band = settings.dtwBand)

    // Take contour on the model's timeline: mean of the take frames mapped to each model frame.
    val sum = FloatArray(a.time.size)
    val hits = IntArray(a.time.size)
    val toTake = IntArray(a.time.size) { -1 }
    for (step in path) {
        if (toTake[step.i] < 0) toTake[step.i] = step.j
        val value = b.semitones[step.j]
        if (value != null) {
            sum[step.i] += value
            hits[step.i] += 1
        }
    }
    val warped: List<Float?> = sum.indices.map { if (hits[it] > 0) sum[it] / hits[it].toFloat() else null }
    val modelValues = FloatList()
    val takeValues = FloatList()
    for (k in 0 until min(a.semitones.size, warped.size)) {
        val value = a.semitones[k]
        val warpedValue = warped[k]
        if (value != null && warpedValue != null) {
            modelValues.add(value)
            takeValues.add(warpedValue)
        }
    }
    val modelContour = modelValues.toFloatArray()
    val takeContour = takeValues.toFloatArray()

    val melodic = modelContour.size >= settings.minContourFrames && standardDeviation(modelContour) > settings.minContourSdSt
    val shape: Float? = if (melodic && standardDeviation(takeContour) < settings.monotoneSdSt) {
        0f
    } else {
        pearson(modelContour, takeContour, settings.minContourFrames)
    }
    val rangeRatio: Float? = if (melodic) standardDeviation(takeContour) / standardDeviation(modelContour) else null
    var intonation: Float? = null
    if (shape != null) {
        val shapeScore = clamp01((shape - settings.shapeBase) / settings.shapeRange)
        val rangeScore = rangeRatio?.let { clamp01(1f - abs(log2(max(1e-3f, it)))) } ?: 1f
        intonation = settings.shapeWeight * shapeScore + settings.rangeWeight * rangeScore
    }

    val durationRatio = (takeDuration / modelDuration).toFloat()
    val durationScore = clamp01(1f - abs(ln(durationRatio)) / ln(settings.durationRatioSpan))
    fun mapToTake(modelTime: Double): Double {
        var i = 0
        while (i < a.time.size - 1 && a.time[i + 1] <= modelTime) i += 1
        val j = if (toTake.isEmpty()) -1 else toTake[i]
        return if (j >= 0) b.time[j] else modelTime
    }
    var matched = 0
    for (pause in model.pauses) {
        val middle = mapToTake((pause.start + pause.end) / 2)
        val found = take.pauses.any {
            middle >= it.start - settings.pauseMatchSeconds && middle <= it.end + settings.pauseMatchSeconds
        }
        if (found) matched += 1
    }
    val extra = max(0, take.pauses.size - matched)
    val pauseScore: Float = if (model.pauses.size + extra == 0) 1f else matched.toFloat() / (model.pauses.size + extra).toFloat()
    val rhythm = settings.durationWeight * durationScore + settings.pauseWeight * pauseScore

    val overall = intonation?.let { settings.intonationWeight * it + settings.rhythmWeight * rhythm } ?: rhythm
    val pauses = PauseMatch(model = model.pauses.size, take = take.pauses.size, matched = matched)
    val outcome = prosodyOutcome(
        overall = overall, intonation = intonation, rangeRatio = rangeRatio, durationRatio = durationRatio,
        pauses = pauses, settings = settings,
    )
    val start = a.time.firstOrNull() ?: 0.0
    return ProsodyComparison(
        intonation = intonation, shape = shape, rangeRatio = rangeRatio, rhythm = rhythm, durationRatio = durationRatio,
        pauses = pauses, overall = overall,
        curves = ProsodyCurves(time = a.time.map { (it - start).toFloat() }, model = a.semitones, take = warped),
        outcome = outcome,
        isAccepted = overall >= settings.good ||
            (overall >= settings.accepted && outcome != ProsodyOutcome.FLAT && outcome != ProsodyOutcome.MELODY_DIFFERS),
    )
}

private fun prosodyOutcome(
    overall: Float, intonation: Float?, rangeRatio: Float?, durationRatio: Float, pauses: PauseMatch,
    settings: DSPThresholds.Prosody,
): ProsodyOutcome {
    if (overall >= settings.good) return ProsodyOutcome.GOOD
    if (intonation != null && rangeRatio != null && rangeRatio < settings.flatRangeRatio) return ProsodyOutcome.FLAT
    if (intonation != null && intonation < settings.poorIntonation) return ProsodyOutcome.MELODY_DIFFERS
    if (durationRatio > settings.tooSlowRatio) return ProsodyOutcome.TOO_SLOW
    if (durationRatio < settings.tooFastRatio) return ProsodyOutcome.TOO_FAST
    if (pauses.model + pauses.take > 0 && pauses.matched < pauses.model) return ProsodyOutcome.PAUSES_DIFFER
    return ProsodyOutcome.CLOSE
}
