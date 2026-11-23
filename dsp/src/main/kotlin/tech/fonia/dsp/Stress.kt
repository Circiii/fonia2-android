package tech.fonia.dsp

import kotlin.math.abs

data class StressSyllable(
    val time: Double,
    val start: Double,
    val end: Double,
    val db: Float,
    /** Highest pitch in the syllable, semitones re the word's median. `null` if unvoiced. */
    val semitones: Float?,
    val duration: Float,
    val prominence: Float,
)

data class StressAnalysis(
    val syllables: List<StressSyllable>,
    /** Index of the most prominent syllable, -1 when nothing was found. */
    val stressed: Int,
    /** How clearly it beats the runner-up, 0...1. */
    val confidence: Float,
)

data class StressPattern(
    val word: String,
    /** Written syllables (vowel groups), an estimate for English spelling. */
    val syllables: Int,
    val stressed: Int,
    /** The capitalised part, for example "AN" in ANimal. */
    val marked: String,
)

sealed class StressOutcome {
    data object NotHeard : StressOutcome()

    /** The word has no usable stress mark. */
    data object NotJudgeable : StressOutcome()
    data class SyllablesMissing(val heard: Int, val expected: Int) : StressOutcome()

    /** All syllables sounded alike. */
    data object Unclear : StressOutcome()
    data class Correct(val syllable: Int) : StressOutcome()
    data class Wrong(val heard: Int, val expected: Int) : StressOutcome()

    val isAccepted: Boolean get() = this is Correct
}

private class RawSyllable(
    val time: Double,
    val start: Double,
    val end: Double,
    val db: Float,
    val st: Float?,
    val duration: Float,
)

/**
 * Which syllable stands out: the stressed syllable of a word is louder, higher and longer (Fry
 * 1955, 1958). Each nucleus gets a prominence from those cues, measured against the word's own
 * average so voices and microphones do not matter.
 */
fun analyzeStress(summary: ClipSummary, thresholds: DSPThresholds = DSPThresholds.v1): StressAnalysis {
    val settings = thresholds.stress
    val track = summary.track
    val firstTime = track.firstOrNull()?.time ?: return StressAnalysis(syllables = emptyList(), stressed = -1, confidence = 0f)
    val nuclei = ArrayList<Int>()
    for (time in summary.syllables) {
        val index = max(0, min(track.size - 1, ((time - firstTime) / summary.trackHopSeconds).rounded().toInt()))
        if (nuclei.lastOrNull() != index) nuclei.add(index)
    }
    if (nuclei.isEmpty()) return StressAnalysis(syllables = emptyList(), stressed = -1, confidence = 0f)

    // Boundaries: the vowel-band minimum between neighbouring nuclei.
    val firstIndex = track.indexOfFirst { it.time >= summary.speechStart }
    val first = max(0, if (firstIndex >= 0) firstIndex else 0)
    var last = track.size - 1
    while (last > 0 && track[last].time > summary.speechEnd) last -= 1
    val bounds = ArrayList<Int>()
    bounds.add(min(first, nuclei[0]))
    for ((previous, next) in nuclei.zipWithNext()) {
        var lowest = previous
        for (k in previous..next) if (track[k].vowelDb < track[lowest].vowelDb) lowest = k
        bounds.add(lowest)
    }
    bounds.add(max(last, nuclei[nuclei.size - 1]))

    val voiced = (bounds[0]..bounds[bounds.size - 1]).map { track[it].hz }.filter { it > 0f }.toFloatArray()
    val centre = median(voiced)

    val raw = nuclei.mapIndexed { i, nucleus ->
        val start = bounds[i]
        val end = bounds[i + 1]
        var db = Float.NEGATIVE_INFINITY
        for (j in max(start, nucleus - settings.nucleusLookFrames)..min(end, nucleus + settings.nucleusLookFrames)) {
            db = max(db, track[j].vowelDb)
        }
        val highest = sliceMax(FloatArray(end - start + 1) { track[start + it].hz }) ?: 0f
        var a = nucleus
        var b = nucleus
        while (a > start && track[a - 1].vowelDb >= db - settings.vowelDropDb) a -= 1
        while (b < end && track[b + 1].vowelDb >= db - settings.vowelDropDb) b += 1
        val lengthening = if (i == nuclei.size - 1 && nuclei.size > 1) settings.finalLengthening else 1f
        RawSyllable(
            time = track[nucleus].time, start = track[start].time, end = track[end].time, db = db,
            st = if (highest > 0f && centre > 0f) semitones(highest, centre) else null,
            duration = (b - a + 1).toFloat() * summary.trackHopSeconds.toFloat() * lengthening,
        )
    }
    val meanDb = mean(FloatArray(raw.size) { raw[it].db })
    val meanSt = mean(raw.mapNotNull { it.st }.toFloatArray())
    val meanDuration = mean(FloatArray(raw.size) { raw[it].duration })
    val syllables = raw.map { entry ->
        val pitchCue = entry.st?.let { (it - meanSt) / settings.semitoneUnit } ?: 0f
        StressSyllable(
            time = entry.time, start = entry.start, end = entry.end, db = entry.db, semitones = entry.st,
            duration = entry.duration,
            prominence = (entry.db - meanDb) / settings.dbUnit + pitchCue +
                settings.durationWeight * (entry.duration - meanDuration) / settings.durationUnit,
        )
    }
    // A stable sort with Swift's `>` as the order: equal prominences keep syllable order.
    val ranked = syllables.indices.sortedWith { x, y ->
        val p = syllables[x].prominence
        val q = syllables[y].prominence
        if (p > q) -1 else if (q > p) 1 else 0
    }
    val margin = if (ranked.size > 1) syllables[ranked[0]].prominence - syllables[ranked[1]].prominence else 0f
    return StressAnalysis(
        syllables = syllables, stressed = ranked[0], confidence = clamp01(margin / settings.confidenceMargin),
    )
}

private val stressCapitals = "ABCDEFGHIJKLMNOPQRSTUVWXYZĂÂÎȘȚ".map { it.toString() }.toSet()
private val stressLowercase = "abcdefghijklmnopqrstuvwxyzăâîșț".map { it.toString() }.toSet()
private val stressCapitalVowels = "AEIOUYĂÂÎ".map { it.toString() }.toSet()
private val stressVowels = "aeiouyăâî".map { it.toString() }.toSet()

/** Swift `Set<Character>.contains`: characters compare by canonical equivalence. */
private fun Set<String>.containsCharacter(character: String): Boolean = contains(nfc(character))

/** Reads a stress mark written in capitals: "ANimal" is syllable 0 of 3, "aniMAL" syllable 2 of 3. */
fun parseStress(marked: String): StressPattern? {
    val trimmed = characters(trimmingWhitespacesAndNewlines(marked))

    var capsStart = -1
    var capsEnd = 0
    var i = 0
    while (i < trimmed.size) {
        if (!stressCapitals.containsCharacter(trimmed[i])) {
            i += 1
            continue
        }
        var j = i
        while (j < trimmed.size && stressCapitals.containsCharacter(trimmed[j])) j += 1
        val isRun = j - i >= 2
        val standsAlone = j == trimmed.size || !stressLowercase.containsCharacter(trimmed[j])
        if (isRun || standsAlone) {
            capsStart = i
            capsEnd = j
            break
        }
        i = j
    }
    if (capsStart < 0) return null

    val word = characters(trimmed.joinToString("").lowercase())
    val groups = ArrayList<Pair<Int, Int>>()
    var k = 0
    while (k < word.size) {
        if (!stressVowels.containsCharacter(word[k])) {
            k += 1
            continue
        }
        var end = k
        while (end < word.size && stressVowels.containsCharacter(word[end])) end += 1
        groups.add(k to end - k)
        k = end
    }
    var count = groups.size
    // A silent final e (chocolate, late) is a vowel group in spelling but not a syllable.
    if (count > 1 && word.size >= 2 && nfc(word[word.size - 1]) == "e" &&
        !stressVowels.containsCharacter(word[word.size - 2]) && nfc(word[word.size - 2]) != "l"
    ) {
        count -= 1
    }
    val markedCharacters = trimmed.subList(capsStart, capsEnd)
    val firstVowel = markedCharacters.indexOfFirst { stressCapitalVowels.containsCharacter(it) }
    val vowelOffset = if (firstVowel >= 0) firstVowel else 0
    val capVowel = capsStart + vowelOffset
    val firstGroup = groups.indexOfFirst { (start, length) -> start + length > capVowel }
    var stressed = if (firstGroup >= 0) firstGroup else count - 1
    stressed = min(stressed, max(0, count - 1))
    return StressPattern(
        word = word.joinToString(""), syllables = max(1, count), stressed = stressed, marked = markedCharacters.joinToString(""),
    )
}

private fun samePlace(observed: Int, found: Int, pattern: StressPattern, tolerance: Float): Boolean {
    if (pattern.stressed == 0) return observed == 0
    if (pattern.stressed == pattern.syllables - 1) return observed == found - 1
    if (observed == 0) return false
    // Middle syllables: compare relative positions, since a fast take may merge a weak syllable.
    val heard = observed.toFloat() / max(1, found - 1).toFloat()
    val wanted = pattern.stressed.toFloat() / max(1, pattern.syllables - 1).toFloat()
    return abs(heard - wanted) <= tolerance
}

/** Verdict for a word whose stress is marked in capitals. */
fun judgeStress(analysis: StressAnalysis, marked: String, thresholds: DSPThresholds = DSPThresholds.v1): StressOutcome {
    if (analysis.syllables.isEmpty()) return StressOutcome.NotHeard
    val pattern = parseStress(marked)
    if (pattern == null || pattern.syllables < 2) return StressOutcome.NotJudgeable
    if (analysis.syllables.size < 2) {
        return StressOutcome.SyllablesMissing(heard = analysis.syllables.size, expected = pattern.syllables)
    }
    if (analysis.confidence < thresholds.stress.minConfidence) return StressOutcome.Unclear
    val right = samePlace(
        observed = analysis.stressed, found = analysis.syllables.size, pattern = pattern,
        tolerance = thresholds.stress.middleTolerance,
    )
    return if (right) StressOutcome.Correct(syllable = analysis.stressed) else StressOutcome.Wrong(heard = analysis.stressed, expected = pattern.stressed)
}
