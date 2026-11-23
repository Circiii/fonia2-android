package tech.fonia.dsp

// Word emphasis: which words of a take stand out from the words around them. New, not on iOS (V2 in
// fonia-android-plan/03 §1.5, F2 in Fonia_Research_Hub/rapoarte/H_paraverbal_public_speaking.md §1.8 and §8).
//
// The recogniser says where each word is; everything measured comes from the clip's own track, so a word's cues
// are acoustic: how long its loudest vowel lasts, how loud that vowel is, and how high the voice goes on the word.
// Nothing here names a state of the speaker (AI Act art. 5(1)(f)): the result is three numbers per word and which
// of them cleared their bar.

/** The three cues of emphasis, in the order the research weighs them. */
enum class EmphasisCue(val rawValue: String) {
    DURATION("duration"),
    LOUDNESS("loudness"),
    PITCH("pitch"),
}

/**
 * Starting values from the hub ([S]: proposed, to be calibrated on labelled adult takes, none exist yet):
 * a word is stressed when at least 2 of 3 cues clear their bar against its neighbours, +30 % duration, +3 dB,
 * +2 semitones (raport 08, H F2). Duration and loudness weigh 1.5 times as much as pitch (Kochanski et al. 2005:
 * F0 contributes little to perceived prominence): with three cues and a 2-of-3 rule the weights cannot change who
 * is stressed, so they set the continuous [WordEmphasis.prominence], which ranks words and says how close a word came.
 */
data class EmphasisThresholds(
    val durationGain: Float = 0.30f,
    val loudnessGainDb: Float = 3f,
    val pitchGainSt: Float = 2f,
    /** How many cues must clear their bar. */
    val minCues: Int = 2,
    val durationWeight: Float = 1.5f,
    val loudnessWeight: Float = 1.5f,
    val pitchWeight: Float = 1f,
    /** Words compared with on each side; other key words are left out of the comparison when there are others. */
    val neighboursPerSide: Int = 2,
    /** A practice set meets its aim when this share of its key words was stressed (H F2: ≥ 80 %). */
    val keyWordShare: Float = 0.8f,
    /** "Everything is stressed" past this share of words (H F2: > 40 %); the optimum is not known. */
    val everythingShare: Float = 0.4f,
    /** The share above means little on a short sentence, whose two key words alone can be half of it. */
    val minWordsForEverything: Int = 8,
    /** How far outside a word's span a vowel nucleus may lie and still belong to it (word edges come from DTW, 20 ms). */
    val edgeToleranceSeconds: Double = 0.03,
    /** A word needs this many voiced frames before its pitch counts. */
    val minVoicedFrames: Int = 3,
    /** The word's pitch is this percentile of its voiced frames: the top, without a stray frame. */
    val pitchPercentile: Float = 0.9f,
    /** A pause this long after a word ends a phrase, and the word's last vowel is lengthened anyway. */
    val phrasePauseSeconds: Double = 0.25,
) {
    companion object {
        val v1 = EmphasisThresholds()
    }
}

/** What was measured on one word. */
data class WordCues(
    /** Time of the vowel nucleus the cues come from: the loudest one of the word. */
    val nucleusTime: Double,
    /** Peak vowel-band level of that nucleus, dB. */
    val loudnessDb: Float,
    /** How long that vowel stays within 6 dB of its peak, seconds, after the phrase-final correction. */
    val durationSeconds: Float,
    /** The same, before the correction. */
    val rawDurationSeconds: Float,
    /** Top of the word's pitch, semitones re the take's median; null when the word has too few voiced frames. */
    val pitchSt: Float?,
    /** The word ends a phrase (a pause or the end follows), so its last vowel counts [DSPThresholds.Stress.finalLengthening]. */
    val phraseFinal: Boolean,
    /** The DSP found a syllable nucleus inside the word; false when the loudest frame of the span stood in. */
    val fromNucleus: Boolean,
)

enum class EmphasisState(val rawValue: String) {
    /** At least two cues cleared their bar. */
    STRESSED("stressed"),

    /** Exactly one did. */
    CLOSE("close"),

    /** None did. */
    LEVEL("level"),

    /** No span, no voice in the span, or no neighbour to compare with. */
    NOT_MEASURED("notMeasured"),
}

data class WordEmphasis(
    val index: Int,
    val span: TimeSpan?,
    val isKey: Boolean,
    val cues: WordCues?,
    /** Indices of the words this one was compared with. */
    val neighbours: List<Int>,
    /** Duration over the neighbours' mean, minus one: 0.3 is 30 % longer. */
    val durationGain: Float?,
    /** Level above the neighbours' mean, dB. */
    val loudnessGainDb: Float?,
    /** Pitch above the neighbours' mean, semitones; null when either side has no pitch. */
    val pitchGainSt: Float?,
    val passed: Set<EmphasisCue>,
    /**
     * Each available cue over its bar, weighted 1.5 / 1.5 / 1 and averaged over the weights present: 1 means every
     * cue exactly at its bar. Null when the word was not measured.
     */
    val prominence: Float?,
    val state: EmphasisState,
) {
    val isStressed: Boolean get() = state == EmphasisState.STRESSED
    val isMeasured: Boolean get() = state != EmphasisState.NOT_MEASURED
}

data class EmphasisAnalysis(
    val words: List<WordEmphasis>,
    val thresholds: EmphasisThresholds,
) {
    val keyWords: List<WordEmphasis> get() = words.filter { it.isKey }
    val measuredKeyWords: List<WordEmphasis> get() = keyWords.filter { it.isMeasured }
    val stressedKeyWords: List<WordEmphasis> get() = keyWords.filter { it.isStressed }

    /** Stressed words nobody marked. */
    val extraStressed: List<WordEmphasis> get() = words.filter { !it.isKey && it.isStressed }

    val tally: EmphasisTally
        get() = EmphasisTally(
            keyWords = keyWords.size,
            measuredKeyWords = measuredKeyWords.size,
            stressedKeyWords = stressedKeyWords.size,
            measuredWords = words.count { it.isMeasured },
            stressedWords = words.count { it.isStressed },
            thresholds = thresholds,
        )
}

/** Counts over one take or many (a practice set, a whole text), and the two rules that read them. */
data class EmphasisTally(
    val keyWords: Int,
    val measuredKeyWords: Int,
    val stressedKeyWords: Int,
    val measuredWords: Int,
    val stressedWords: Int,
    val thresholds: EmphasisThresholds = EmphasisThresholds.v1,
) {
    /** Stressed key words over measured key words; null when no key word could be measured. */
    val keyShare: Float? get() = if (measuredKeyWords > 0) stressedKeyWords.toFloat() / measuredKeyWords.toFloat() else null

    /** Stressed words over measured words. */
    val stressedShare: Float? get() = if (measuredWords > 0) stressedWords.toFloat() / measuredWords.toFloat() else null

    val meetsKeyTarget: Boolean get() = keyShare?.let { it >= thresholds.keyWordShare } ?: false

    /** More than 40 % of the words stood out, over enough words for the share to mean something. */
    val everythingStressed: Boolean
        get() = measuredWords >= thresholds.minWordsForEverything && (stressedShare ?: 0f) > thresholds.everythingShare

    operator fun plus(other: EmphasisTally) = EmphasisTally(
        keyWords = keyWords + other.keyWords,
        measuredKeyWords = measuredKeyWords + other.measuredKeyWords,
        stressedKeyWords = stressedKeyWords + other.stressedKeyWords,
        measuredWords = measuredWords + other.measuredWords,
        stressedWords = stressedWords + other.stressedWords,
        thresholds = thresholds,
    )

    companion object {
        val EMPTY = EmphasisTally(0, 0, 0, 0, 0)
    }
}

/**
 * Measures each word of a take and compares it with its neighbours.
 *
 * [spans] holds one entry per word of the text, in reading order: where the recogniser found it, or null when the
 * word was not read (it is then neither measured nor anyone's neighbour). [keyWords] are indices into [spans].
 * Syllable nuclei and their boundaries come from the clip analysis, as in [analyzeStress]; a nucleus belongs to the
 * word whose span holds it, and the word's cues come from its loudest nucleus, so a long word is not "longer" than a
 * short one for having more sounds.
 */
fun analyzeEmphasis(
    summary: ClipSummary,
    spans: List<TimeSpan?>,
    keyWords: Set<Int> = emptySet(),
    thresholds: EmphasisThresholds = EmphasisThresholds.v1,
    dsp: DSPThresholds = DSPThresholds.v1,
): EmphasisAnalysis {
    val cues = measureWords(summary, spans, thresholds, dsp)
    val words = spans.indices.map { index ->
        compare(index, spans[index], index in keyWords, cues, keyWords, thresholds)
    }
    return EmphasisAnalysis(words = words, thresholds = thresholds)
}

private fun compare(
    index: Int,
    span: TimeSpan?,
    isKey: Boolean,
    cues: List<WordCues?>,
    keyWords: Set<Int>,
    thresholds: EmphasisThresholds,
): WordEmphasis {
    val own = cues[index]
    var neighbours = neighbourIndices(index, cues, keyWords, thresholds.neighboursPerSide, skipKeys = true)
    if (neighbours.isEmpty()) neighbours = neighbourIndices(index, cues, keyWords, thresholds.neighboursPerSide, skipKeys = false)
    if (own == null || neighbours.isEmpty()) {
        return WordEmphasis(
            index = index, span = span, isKey = isKey, cues = own, neighbours = neighbours,
            durationGain = null, loudnessGainDb = null, pitchGainSt = null, passed = emptySet(), prominence = null,
            state = EmphasisState.NOT_MEASURED,
        )
    }
    val others = neighbours.map { cues[it]!! }
    val meanDuration = mean(FloatArray(others.size) { others[it].durationSeconds })
    val meanLoudness = mean(FloatArray(others.size) { others[it].loudnessDb })
    val pitched = others.mapNotNull { it.pitchSt }
    val durationGain = own.durationSeconds / meanDuration - 1f
    val loudnessGain = own.loudnessDb - meanLoudness
    val pitchGain = if (own.pitchSt != null && pitched.isNotEmpty()) own.pitchSt - mean(pitched.toFloatArray()) else null

    val passed = LinkedHashSet<EmphasisCue>()
    if (durationGain >= thresholds.durationGain) passed.add(EmphasisCue.DURATION)
    if (loudnessGain >= thresholds.loudnessGainDb) passed.add(EmphasisCue.LOUDNESS)
    if (pitchGain != null && pitchGain >= thresholds.pitchGainSt) passed.add(EmphasisCue.PITCH)

    var weighted = thresholds.durationWeight * durationGain / thresholds.durationGain +
        thresholds.loudnessWeight * loudnessGain / thresholds.loudnessGainDb
    var weights = thresholds.durationWeight + thresholds.loudnessWeight
    if (pitchGain != null) {
        weighted += thresholds.pitchWeight * pitchGain / thresholds.pitchGainSt
        weights += thresholds.pitchWeight
    }
    val state = when {
        passed.size >= thresholds.minCues -> EmphasisState.STRESSED
        passed.size == 1 -> EmphasisState.CLOSE
        else -> EmphasisState.LEVEL
    }
    return WordEmphasis(
        index = index, span = span, isKey = isKey, cues = own, neighbours = neighbours,
        durationGain = durationGain, loudnessGainDb = loudnessGain, pitchGainSt = pitchGain,
        passed = passed, prominence = weighted / weights, state = state,
    )
}

/** Up to [perSide] measured words on each side, nearest first, left side first. */
private fun neighbourIndices(index: Int, cues: List<WordCues?>, keyWords: Set<Int>, perSide: Int, skipKeys: Boolean): List<Int> {
    val result = ArrayList<Int>()
    for (direction in intArrayOf(-1, 1)) {
        var found = 0
        var i = index + direction
        while (i in cues.indices && found < perSide) {
            if (cues[i] != null && !(skipKeys && i in keyWords)) {
                result.add(i)
                found += 1
            }
            i += direction
        }
    }
    return result
}

private fun measureWords(summary: ClipSummary, spans: List<TimeSpan?>, thresholds: EmphasisThresholds, dsp: DSPThresholds): List<WordCues?> {
    val track = summary.track
    val firstTime = track.firstOrNull()?.time ?: return spans.map { null }
    val hop = summary.trackHopSeconds
    fun indexOf(time: Double): Int = max(0, min(track.size - 1, ((time - firstTime) / hop).rounded().toInt()))

    // Syllable nuclei and the vowel-band dips between them, as analyzeStress finds them.
    val nuclei = ArrayList<Int>()
    for (time in summary.syllables) {
        val index = indexOf(time)
        if (nuclei.lastOrNull() != index) nuclei.add(index)
    }
    val bounds = ArrayList<Int>()
    if (nuclei.isNotEmpty()) {
        bounds.add(0)
        for ((previous, next) in nuclei.zipWithNext()) {
            var lowest = previous
            for (k in previous..next) if (track[k].vowelDb < track[lowest].vowelDb) lowest = k
            bounds.add(lowest)
        }
        bounds.add(track.size - 1)
    }

    // Each nucleus goes to the word whose span holds it, or to the nearest one within the tolerance.
    val tolerance = thresholds.edgeToleranceSeconds
    val owned = Array(spans.size) { ArrayList<Int>() }
    for ((k, nucleus) in nuclei.withIndex()) {
        val time = track[nucleus].time
        var best = -1
        var bestDistance = Double.POSITIVE_INFINITY
        for ((w, span) in spans.withIndex()) {
            if (span == null) continue
            val outside = max(0.0, max(span.start - time, time - span.end))
            // Inside two overlapping spans: the one whose middle is nearer.
            val distance = outside * 1000.0 + kotlin.math.abs(time - (span.start + span.end) / 2)
            if (outside <= tolerance && distance < bestDistance) {
                best = w
                bestDistance = distance
            }
        }
        if (best >= 0) owned[best].add(k)
    }

    val located = spans.indices.filter { spans[it] != null }
    val centre = summary.pitch.medianHz
    val stress = dsp.stress
    return spans.mapIndexed { w, span ->
        if (span == null) return@mapIndexed null
        val from = indexOf(span.start - tolerance)
        val to = max(from, indexOf(span.end + tolerance))
        var nucleus: Int
        var lo: Int
        var hi: Int
        var lastOfWord: Boolean
        val fromNucleus = owned[w].isNotEmpty()
        if (fromNucleus) {
            // The loudest syllable of the word: in a stressed word, the one that carries it.
            var chosen = owned[w][0]
            for (k in owned[w]) if (peakNear(track, nuclei[k], stress.nucleusLookFrames, bounds[k], bounds[k + 1]) > peakNear(track, nuclei[chosen], stress.nucleusLookFrames, bounds[chosen], bounds[chosen + 1])) chosen = k
            nucleus = nuclei[chosen]
            lo = max(bounds[chosen], from)
            hi = min(bounds[chosen + 1], to)
            lastOfWord = chosen == owned[w].last()
        } else {
            // No nucleus inside (a short function word, or the DSP merged it with a neighbour): the span's loudest
            // speaking frame stands in.
            var loudest = -1
            for (j in from..to) if (track[j].speaking && (loudest < 0 || track[j].vowelDb > track[loudest].vowelDb)) loudest = j
            if (loudest < 0) return@mapIndexed null
            nucleus = loudest
            lo = from
            hi = to
            lastOfWord = true
        }
        if (hi < lo) {
            lo = from
            hi = to
        }
        nucleus = max(lo, min(hi, nucleus))
        val peak = peakNear(track, nucleus, stress.nucleusLookFrames, lo, hi)
        var a = nucleus
        var b = nucleus
        while (a > lo && track[a - 1].vowelDb >= peak - stress.vowelDropDb) a -= 1
        while (b < hi && track[b + 1].vowelDb >= peak - stress.vowelDropDb) b += 1
        val raw = (b - a + 1).toFloat() * hop.toFloat()

        val next = located.firstOrNull { it > w }?.let { spans[it] }
        val phraseFinal = next == null || next.start - span.end >= thresholds.phrasePauseSeconds ||
            summary.pauses.any { it.duration >= thresholds.phrasePauseSeconds && it.start >= span.end - 0.1 && it.start <= next.start }
        val lengthening = if (phraseFinal && lastOfWord) stress.finalLengthening else 1f

        val voiced = FloatList(to - from + 1)
        for (j in from..to) if (track[j].hz > 0f) voiced.add(track[j].hz)
        val pitch = if (voiced.size >= thresholds.minVoicedFrames && centre > 0f) {
            semitones(percentile(voiced.toFloatArray().also { it.sort() }, thresholds.pitchPercentile), centre)
        } else {
            null
        }
        WordCues(
            nucleusTime = track[nucleus].time,
            loudnessDb = peak,
            durationSeconds = raw * lengthening,
            rawDurationSeconds = raw,
            pitchSt = pitch,
            phraseFinal = phraseFinal,
            fromNucleus = fromNucleus,
        )
    }
}

/** Highest vowel-band level within [look] frames of [nucleus], inside [lo]...[hi]. */
private fun peakNear(track: List<TrackPoint>, nucleus: Int, look: Int, lo: Int, hi: Int): Float {
    var peak = Float.NEGATIVE_INFINITY
    val from = max(lo, nucleus - look)
    val to = min(hi, nucleus + look)
    if (from > to) return track[max(lo, min(hi, nucleus))].vowelDb
    for (j in from..to) peak = max(peak, track[j].vowelDb)
    return peak
}
