package tech.fonia.speaking

import kotlin.math.sin

/**
 * A ready-made result for previews and UI tests (no audio, no analysis): the sample sentence of the evaluation,
 * „Bună ziua. Ăăă, astăzi vreau să vă povestesc despre, ăăă, proiectul nostru. Deci, practic, …”, with plausible
 * prosody, its flagged spans, a time map and stats.
 */
object SpeakingFixtures {
    class Sample(
        val transcript: ProsodicTranscript,
        val spans: List<FlaggedSpan>,
        val timeMap: TimeMap,
        val stats: CleanStats,
    )

    private val text = listOf(
        "Bună" to 0.0, "ziua." to 0.38, "Ăăă," to 1.0, "astăzi" to 1.62, "vreau" to 1.78, "să" to 2.0, "vă" to 2.12,
        "povestesc" to 2.26, "despre," to 2.74, "ăăă," to 3.42, "proiectul" to 4.14, "nostru." to 4.54, "Deci," to 5.2,
        "practic," to 5.5, "am" to 5.84, "construit" to 5.92, "o" to 6.32, "aplicație" to 6.5, "care," to 7.02, "îîî," to 7.36,
        "ajută" to 8.3, "copiii" to 8.64, "să" to 8.92, "vorbească" to 9.04, "mai" to 9.5, "clar." to 9.68, "Ăăă," to 10.82,
        "și," to 11.5, "cum" to 12.16, "să" to 12.26, "zic," to 12.46, "cam" to 12.86, "asta" to 13.02, "e" to 13.22,
        "tot." to 13.36, "Vă" to 13.92, "mulțumesc." to 14.06,
    )
    private val fillerAt = mapOf(2 to SpanKind.HESITATION, 9 to SpanKind.HESITATION, 12 to SpanKind.MARKER, 13 to SpanKind.MARKER, 19 to SpanKind.HESITATION, 26 to SpanKind.HESITATION)

    fun sample(): Sample {
        val spans = ArrayList<FlaggedSpan>()
        val words = ArrayList<ProsodicWord>()
        for ((i, pair) in text.withIndex()) {
            val (w, start) = pair
            val end = if (i + 1 < text.size) minOf(text[i + 1].second - 0.02, start + 0.5) else start + 0.5
            val kind = fillerAt[i]
            var spanId: Int? = null
            if (kind != null) {
                spanId = spans.size
                spans.add(FlaggedSpan(spans.size, kind, start, end, w.trimEnd(',', '.').lowercase(), if (i == 13) 0.7 else 0.9, SpanSource.BOTH, true, i == 13, listOf(i)))
            }
            if (i in 28..30) {
                if (i == 28) spans.add(FlaggedSpan(spans.size, SpanKind.MARKER, 12.16, 12.66, "cum să zic", 0.85, SpanSource.TRANSCRIPT, true, false, listOf(28, 29, 30)))
                spanId = spans.last().id
            }
            val k = spans.firstOrNull { it.id == spanId }?.kind
            val wave = sin(i * 1.7)
            words.add(
                ProsodicWord(
                    index = i, text = w, start = start, end = end,
                    loudness = if (k != null) 0.3f else (0.45 + 0.4 * sin(i * 0.9)).toFloat().coerceIn(0.1f, 1f),
                    pitchSt = if (k != null) -1f else (2.5 * wave).toFloat(),
                    movement = when { k != null -> PitchMovement.LEVEL; w.endsWith("?") || wave > 0.6 -> PitchMovement.RISE; w.endsWith(".") || wave < -0.6 -> PitchMovement.FALL; else -> PitchMovement.LEVEL },
                    movementSt = if (k != null) 0f else (3 * wave).toFloat(),
                    rate = 4.2f, pauseBefore = if (i == 0) 0.0 else maxOf(0.0, start - (text[i - 1].second + 0.3)),
                    emphasis = w.startsWith("proiectul") || w.startsWith("clar"), spanId = spanId, kind = k, fromTranscript = true,
                ),
            )
        }
        val summary = ProsodySummary(
            durationSeconds = 14.9, speakingSeconds = 13.4, words = 29, wordsPerMinute = 130.0, syllablesPerSecond = 4.4,
            pauseCount = 6, pauseSeconds = 3.1, longestPauseSeconds = 0.9, pauseShare = 0.21, pitchVariationSt = 2.4,
            pitchRangeSt = 8.0, fillerCount = 7, fillersPerMinute = 28.2, repetitionCount = 0, falseStartCount = 0, longPauseCount = 0,
        )
        val transcript = ProsodicTranscript(words, listOf(PauseSpan(0.84, 1.0, false), PauseSpan(9.96, 10.82, false)), summary, true)
        // Time map: every cut span removed.
        val os = ArrayList<Double>()
        val oe = ArrayList<Double>()
        val ns = ArrayList<Double>()
        var prev = 0.0
        var newPos = 0.0
        for (s in spans.sortedBy { it.start }) {
            os.add(prev); oe.add(s.start); ns.add(newPos); newPos += s.start - prev; prev = s.end
        }
        os.add(prev); oe.add(14.9); ns.add(newPos); newPos += 14.9 - prev
        val map = TimeMap(os.toDoubleArray(), oe.toDoubleArray(), ns.toDoubleArray(), 14.9, newPos)
        val stats = CleanStats(14.9, newPos, 14.9 - newPos, 7, 0, 0, 0, 28.2, 0.0)
        return Sample(transcript, spans, map, stats)
    }
}
