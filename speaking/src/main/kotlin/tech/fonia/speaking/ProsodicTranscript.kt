package tech.fonia.speaking

/** How the pitch moved across a word. */
enum class PitchMovement { RISE, FALL, LEVEL, UNVOICED }

/**
 * One word of the prosodic transcript (or, without a transcript, one chunk of speech between pauses, shown as dots,
 * or a hesitation shown by its sound). Everything the screen draws, nothing it has to compute.
 */
data class ProsodicWord(
    val index: Int,
    /** Display text: the recognised word with its punctuation, "•••" for an unrecognised chunk, "ăăă" for a hesitation. */
    val text: String,
    val start: Double,
    val end: Double,
    /** Relative loudness 0–1 within this take (10th → 95th percentile of the speech level). Drawn as size and weight. */
    val loudness: Float,
    /** Pitch level against the speaker's median, semitones; null when unvoiced. Drawn as the baseline offset. */
    val pitchSt: Float?,
    val movement: PitchMovement,
    /** Pitch change across the word, semitones (positive = up). Drawn as a slight tilt. */
    val movementSt: Float,
    /** Syllables per second around the word (pauses excluded). */
    val rate: Float,
    /** Silence before the word, seconds. Drawn as a gap. */
    val pauseBefore: Double,
    /** The dsp emphasis analysis marked the word as stressed against its neighbours. */
    val emphasis: Boolean,
    /** The flagged span this word belongs to, if any. */
    val spanId: Int?,
    val kind: SpanKind?,
    /** False for chunks and hesitations found only by the sound. */
    val fromTranscript: Boolean,
) {
    val duration: Double get() = end - start
}

/**
 * The take's numbers, phrased neutrally by [SpeakingText]: no grades, no emotion (AI Act; plan 01 §10 "Fără scor").
 * [wordsPerMinute] is null without a transcript; [syllablesPerSecond] always exists.
 */
data class ProsodySummary(
    val durationSeconds: Double,
    /** From the first to the last speech, pauses longer than a second excluded. */
    val speakingSeconds: Double,
    val words: Int,
    val wordsPerMinute: Double?,
    val syllablesPerSecond: Double,
    val pauseCount: Int,
    val pauseSeconds: Double,
    val longestPauseSeconds: Double,
    /** Share of the speaking span spent in pauses (0–1). */
    val pauseShare: Double,
    /** Spread of the pitch over the take, semitones (SD). */
    val pitchVariationSt: Double,
    /** 5th to 95th percentile of the pitch, semitones. */
    val pitchRangeSt: Double,
    val fillerCount: Int,
    val fillersPerMinute: Double,
    val repetitionCount: Int,
    val falseStartCount: Int,
    val longPauseCount: Int,
)

class ProsodicTranscript(
    val words: List<ProsodicWord>,
    val pauses: List<PauseSpan>,
    val summary: ProsodySummary,
    /** The words came from the recogniser (else: chunks and sounds). */
    val hasTranscript: Boolean,
) {
    /** The word under [t] seconds of the original take, or the last one before it; -1 before the first. */
    fun wordAt(t: Double): Int {
        var lo = 0
        var hi = words.size - 1
        var best = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (words[mid].start <= t) { best = mid; lo = mid + 1 } else hi = mid - 1
        }
        return best
    }

    /** Plain text for the screen reader. */
    val plainText: String get() = words.filter { it.fromTranscript }.joinToString(" ") { it.text }
}
