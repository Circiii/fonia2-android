package tech.fonia.dsp

enum class DeliveryLanguage(val rawValue: String) {
    ROMANIAN("romanian"),
    ENGLISH("english"),
}

enum class PaceBand(val rawValue: String) {
    TOO_SLOW("tooSlow"),
    GOOD("good"),
    TOO_FAST("tooFast"),
}

enum class ToneBand(val rawValue: String) {
    MONOTONE("monotone"),
    MODERATE("moderate"),
    LIVELY("lively"),
}

enum class FinalContour(val rawValue: String) {
    RISING("rising"),
    LEVEL("level"),
    FALLING("falling"),
}

/** Speaking report for longer takes: pace, pauses, pitch variation and volume. */
data class DeliveryReport(
    val scores: Scores,
    val wordsPerMinute: Float,
    val syllablesPerSecond: Float,
    val pvq: Float,
    val rangeSt: Float,
    val loudnessSdDb: Float,
    val pausesPerMinute: Float,
    val longPauses: Int,
    val fillersPerMinute: Float,
    val durationSeconds: Double,
    val pace: PaceBand,
    val tone: ToneBand,
    /** Lively by pitch variation or by range, whichever passes. */
    val isLively: Boolean,
    val finalContour: FinalContour,
) {
    data class Scores(
        /** 0...100. */
        val total: Int,
        val pace: Float,
        val tone: Float,
        val volume: Float,
        val pauses: Float,
        val fluency: Float,
    )
}

/**
 * `wordsPerMinute` and `fillersPerMinute` come from a transcript when one exists. Without one, pace
 * is estimated from syllables and fillers from the hesitations the signal shows.
 */
fun makeDeliveryReport(
    summary: ClipSummary,
    language: DeliveryLanguage,
    wordsPerMinute: Float? = null,
    fillersPerMinute: Float? = null,
    thresholds: DSPThresholds = DSPThresholds.v1,
): DeliveryReport {
    val settings = thresholds.delivery
    val speakingMinutes = max(0.1, (summary.speechEnd - summary.speechStart) / 60).toFloat()
    val wordsPerSyllable = if (language == DeliveryLanguage.ROMANIAN) settings.romanianWordsPerSyllable else settings.englishWordsPerSyllable
    val wpm = wordsPerMinute ?: (summary.speechRate * 60f * wordsPerSyllable)
    val fillers = fillersPerMinute ?: (summary.hesitations.size.toFloat() / speakingMinutes)
    val longPauses = summary.pauses.count { it.duration > settings.longPauseSeconds }
    val pausesPerMinute = summary.pauses.size.toFloat() / speakingMinutes

    val overshoot = max(0f, max(settings.minWordsPerMinute - wpm, wpm - settings.maxWordsPerMinute))
    val pace = max(0f, 1f - overshoot / settings.paceFalloffWpm)
    val tone = min(1f, summary.pvq / settings.livelyPvq)
    val spread = summary.loudness.sdDb
    val volume: Float = if (spread > settings.minLoudnessSdDb && spread < settings.maxLoudnessSdDb) 1f else settings.flatLoudnessScore
    val pauses = max(settings.minPauseScore, min(1f, pausesPerMinute / settings.targetPausesPerMinute)) *
        (if (longPauses > 0) settings.longPausePenalty else 1f)
    val fluency = max(settings.minFluencyScore, 1f - max(0f, fillers - settings.freeFillersPerMinute) * settings.fillerPenalty)
    val parts = listOf(
        pace to settings.weightPace, tone to settings.weightTone, volume to settings.weightVolume,
        pauses to settings.weightPauses, fluency to settings.weightFluency,
    )
    var weighted = 0f
    for ((score, weight) in parts) weighted += score * weight
    var weights = 0f
    for ((_, weight) in parts) weights += weight
    val total = weighted / weights

    val paceBand = if (wpm < settings.minWordsPerMinute) {
        PaceBand.TOO_SLOW
    } else if (wpm > settings.maxWordsPerMinute) {
        PaceBand.TOO_FAST
    } else {
        PaceBand.GOOD
    }
    val toneBand = if (summary.pvq < settings.monotonePvq) {
        ToneBand.MONOTONE
    } else if (summary.pvq >= settings.livelyPvq) {
        ToneBand.LIVELY
    } else {
        ToneBand.MODERATE
    }
    val contour = if (summary.finalSlopeSt >= settings.risingSlopeSt) {
        FinalContour.RISING
    } else if (summary.finalSlopeSt <= settings.fallingSlopeSt) {
        FinalContour.FALLING
    } else {
        FinalContour.LEVEL
    }
    return DeliveryReport(
        scores = DeliveryReport.Scores(
            total = (100f * total).rounded().toInt(), pace = pace, tone = tone, volume = volume, pauses = pauses, fluency = fluency,
        ),
        wordsPerMinute = wpm,
        syllablesPerSecond = summary.articulationRate,
        pvq = summary.pvq,
        rangeSt = summary.pitch.rangeSt,
        loudnessSdDb = spread,
        pausesPerMinute = pausesPerMinute,
        longPauses = longPauses,
        fillersPerMinute = fillers,
        durationSeconds = summary.durationSeconds,
        pace = paceBand,
        tone = toneBand,
        isLively = summary.pvq >= settings.monotonePvq || summary.pitch.rangeSt >= settings.livelyRangeSt,
        finalContour = contour,
    )
}
