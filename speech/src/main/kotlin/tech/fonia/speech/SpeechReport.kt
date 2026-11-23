package tech.fonia.speech

/** Filler words for one language. Chosen by the caller, since the lists are content. */
data class FillerLexicon(
    /** How a hesitation sound is counted, whatever its spelling ("um", "ăă"). */
    val hesitationLabel: String,
    val fillers: List<String>,
    /** Fillers only when set apart ("...about, like, why..."); "I like pizza" stays a verb. */
    val ambiguous: List<String>,
)

data class FillerHit(
    val word: String,
    val start: Double,
    val end: Double,
    val kind: Kind,
    val source: Source,
) {
    enum class Kind(val rawValue: String) {
        /** um, uh, ăă: the only kind that can lower a score. */
        HESITATION("hesitation"),

        /** deci, practic, so, well: reported as information, never as an error. */
        DISCOURSE_MARKER("discourseMarker"),
    }

    enum class Source(val rawValue: String) {
        TRANSCRIPT("transcript"),

        /** A hesitation the recogniser cleaned up, found by the clip analysis. */
        ACOUSTIC("acoustic"),
    }
}

data class RepeatedWords(val words: String, val start: Double, val end: Double)

data class UnclearWord(val word: String, val prob: Double, val start: Double, val end: Double)

data class SpeechReport(
    /** Words that carry meaning (sounds like "um" excluded). */
    val words: Int,
    /** From the first to the last word, without pauses longer than a second. */
    val speakingSeconds: Double,
    val wordsPerMinute: Double,
    val pace: Pace,
    val fillers: List<FillerHit>,
    val fillerCounts: Map<String, Int>,
    val fillersPerMinute: Double,
    val hesitationsPer100Words: Double,
    val repeats: List<RepeatedWords>,
    val unclear: List<UnclearWord>,
    val pauses: List<TimeSpan>,
    val score: Double,
    val focus: Focus,
) {
    enum class Pace(val rawValue: String) {
        SLOW("slow"),
        GOOD("good"),
        FAST("fast"),
        UNKNOWN("unknown"),
    }

    /** The single thing worth raising about this take, so the report stays on one action. */
    sealed interface Focus {
        data object NotHeard : Focus

        data class ManyHesitations(val count: Int) : Focus

        data object Fast : Focus

        data object Slow : Focus

        data object Clean : Focus
    }
}

/** `hesitations` are acoustic ones from the clip analysis, for those the transcript left out. */
fun SpeechJudge.speechReport(
    t: Transcript,
    language: SpeechLanguage,
    lexicon: FillerLexicon,
    hesitations: List<TimeSpan> = emptyList(),
    thresholds: SpeechThresholds = SpeechThresholds.STANDARD,
): SpeechReport {
    val limits = thresholds.freeSpeech
    val words = t.words.filter { SpeechText.normalizedWord(it.word, language).isNotEmpty() }
    val norm = words.map { SpeechText.normalizedWord(it.word, language) }
    // Longest first, so "de genul" wins over "gen". Both sorts are stable.
    val list = lexicon.fillers
        .map { filler -> splitOnWhitespace(SwiftString.lowercased(filler)).map { SpeechText.normalizedWord(it, language) } }
        .sortedWith { a, b -> if (a.size > b.size) -1 else if (b.size > a.size) 1 else 0 }
    val ambiguous = lexicon.ambiguous.map { SwiftString.nfc(SpeechText.words(it, language).joinToString(" ")) }.toSet()

    val fillers = mutableListOf<FillerHit>()
    val isFiller = BooleanArray(words.size)
    var i = 0
    // Swift's `defer { i += 1 }` runs on every way out of an iteration; here each way adds it.
    while (i < words.size) {
        if (SpeechText.isHesitationSound(norm[i])) {
            fillers.add(FillerHit(lexicon.hesitationLabel, words[i].start, words[i].end, FillerHit.Kind.HESITATION, FillerHit.Source.TRANSCRIPT))
            isFiller[i] = true
            i += 1
            continue
        }
        for (filler in list) {
            if (filler.size == 1 && SpeechText.isHesitationSound(filler[0])) continue
            if (!(i + filler.size <= words.size && SwiftString.equal(norm.subList(i, i + filler.size), filler))) continue
            val phrase = filler.joinToString(" ")
            if (SwiftString.nfc(phrase) in ambiguous) {
                val setApart = i == 0 || endsClause(words[i - 1].word) || isFiller[i - 1] || endsWithComma(words[i + filler.size - 1].word)
                if (!setApart) continue
            }
            fillers.add(FillerHit(phrase, words[i].start, words[i + filler.size - 1].end, FillerHit.Kind.DISCOURSE_MARKER, FillerHit.Source.TRANSCRIPT))
            for (k in 0 until filler.size) isFiller[i + k] = true
            i += filler.size - 1
            break
        }
        i += 1
    }
    for (h in hesitations) {
        val covered = words.any { SwiftMath.min(it.end, h.end) - SwiftMath.max(it.start, h.start) > 0.5 * (h.end - h.start) }
        if (!covered) {
            fillers.add(FillerHit(lexicon.hesitationLabel, h.start, h.end, FillerHit.Kind.HESITATION, FillerHit.Source.ACOUSTIC))
        }
    }
    fillers.sortWith { a, b -> if (a.start < b.start) -1 else if (b.start < a.start) 1 else 0 }
    val fillerCounts = countByCanonicalKey(fillers.map { it.word })

    val repeats = mutableListOf<RepeatedWords>()
    i = 0
    while (i < words.size) {
        for (n in intArrayOf(2, 1)) {
            if (i + 2 * n > words.size) continue
            val same = (0 until n).all { SwiftString.equal(norm[i + it], norm[i + n + it]) && !isFiller[i + it] }
            if (!same) continue
            repeats.add(RepeatedWords(norm.subList(i, i + n).joinToString(" "), words[i].start, words[i + 2 * n - 1].end))
            i += 2 * n - 1
            break
        }
        i += 1
    }

    val unclear = words.indices
        .filter { !isFiller[it] && words[it].prob < limits.unclearProbability }
        .map { UnclearWord(SpeechText.trimmedToWord(words[it].word), words[it].prob, words[it].start, words[it].end) }

    val pauses = mutableListOf<TimeSpan>()
    var longGaps = 0.0
    for (k in 1 until words.size) {
        val gap = words[k].start - words[k - 1].end
        if (gap >= limits.pauseSeconds) pauses.add(TimeSpan(start = words[k - 1].end, end = words[k].start))
        if (gap > limits.longPauseSeconds) longGaps += gap
    }

    val lexical = norm.count { !SpeechText.isHesitationSound(it) }
    val span = (words.lastOrNull()?.end ?: 0.0) - (words.firstOrNull()?.start ?: 0.0)
    val speakingSeconds = SwiftMath.max(0.0, span - longGaps)
    val wordsPerMinute = if (speakingSeconds > 0) lexical.toDouble() / speakingSeconds * 60 else 0.0
    val band = limits.pace(language)
    val pace = when {
        speakingSeconds < limits.minSpeakingSeconds || lexical < limits.minWordsForPace -> SpeechReport.Pace.UNKNOWN
        wordsPerMinute < band.slowBelow -> SpeechReport.Pace.SLOW
        wordsPerMinute > band.fastAbove -> SpeechReport.Pace.FAST
        else -> SpeechReport.Pace.GOOD
    }

    val hesitationHits = fillers.filter { it.kind == FillerHit.Kind.HESITATION }
    val hesitationsPer100Words = if (lexical > 0) hesitationHits.size.toDouble() / lexical.toDouble() * 100 else 0.0
    val overNorm = hesitationHits.size >= limits.minHesitationsToFlag && hesitationsPer100Words > limits.hesitationFreePer100Words

    var score = 1.0
    if (pace == SpeechReport.Pace.SLOW || pace == SpeechReport.Pace.FAST) score -= limits.pacePenalty
    if (overNorm) {
        score -= SwiftMath.min(limits.hesitationPenaltyMax, (hesitationsPer100Words - limits.hesitationFreePer100Words) * limits.hesitationPenaltySlope)
    }
    score -= SwiftMath.min(limits.repeatPenaltyMax, repeats.size.toDouble() * limits.repeatPenalty)

    val focus = when {
        !heardSomething(t) -> SpeechReport.Focus.NotHeard
        overNorm -> SpeechReport.Focus.ManyHesitations(count = hesitationHits.size)
        pace == SpeechReport.Pace.FAST -> SpeechReport.Focus.Fast
        pace == SpeechReport.Pace.SLOW -> SpeechReport.Focus.Slow
        else -> SpeechReport.Focus.Clean
    }

    return SpeechReport(
        words = lexical,
        speakingSeconds = speakingSeconds,
        wordsPerMinute = wordsPerMinute,
        pace = pace,
        fillers = fillers.toList(),
        fillerCounts = fillerCounts,
        fillersPerMinute = fillers.size.toDouble() / (SwiftMath.max(speakingSeconds, 1.0) / 60),
        hesitationsPer100Words = hesitationsPer100Words,
        repeats = repeats.toList(),
        unclear = unclear,
        pauses = pauses.toList(),
        score = SwiftMath.min(1.0, SwiftMath.max(0.0, score)),
        focus = focus,
    )
}

private val clauseEnds = listOf(".", ",", "!", "?", ";", ":", "…")
private val commaEnds = listOf(",", ";", "…")

private fun endsClause(word: String): Boolean =
    SwiftString.characters(SwiftString.trimmingWhitespaces(word)).lastOrNull()?.let { last -> clauseEnds.any { SwiftString.equal(last, it) } } ?: false

private fun endsWithComma(word: String): Boolean =
    SwiftString.characters(SwiftString.trimmingWhitespaces(word)).lastOrNull()?.let { last -> commaEnds.any { SwiftString.equal(last, it) } } ?: false

/** `split(whereSeparator: \.isWhitespace)`, Character by Character, empty pieces dropped. */
private fun splitOnWhitespace(text: String): List<String> {
    val out = mutableListOf<String>()
    val current = StringBuilder()
    for (character in SwiftString.characters(text)) {
        if (SwiftString.isWhitespace(character)) {
            if (current.isNotEmpty()) {
                out.add(current.toString())
                current.setLength(0)
            }
        } else {
            current.append(character)
        }
    }
    if (current.isNotEmpty()) out.add(current.toString())
    return out
}

/** `reduce(into: [String: Int]())`: canonically equivalent keys count together under the first spelling seen. */
private fun countByCanonicalKey(keys: List<String>): Map<String, Int> {
    val first = LinkedHashMap<String, String>()
    val counts = LinkedHashMap<String, Int>()
    for (key in keys) {
        val canonical = SwiftString.nfc(key)
        val spelling = first.getOrPut(canonical) { key }
        counts[spelling] = (counts[spelling] ?: 0) + 1
    }
    return counts
}
