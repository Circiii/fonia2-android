package tech.fonia.speaking

/** What a flagged stretch of the take is. Only these are ever cut; ordinary words never are. */
enum class SpanKind {
    /** "ăăă", "îîî", "mmm": a sustained hesitation sound. */
    HESITATION,

    /** A discourse marker used as a filler: "deci", "practic", "cum să zic"… */
    MARKER,

    /** The first of two identical words or word pairs ("că că", "în care în care"). */
    REPETITION,

    /** A word broken off and restarted ("pro- proiectul"). */
    FALSE_START,

    /** The drawn-out end of a word ("șiii"); only the stretched part is offered, the word itself stays. */
    LENGTHENING,
}

enum class SpanSource { ACOUSTIC, TRANSCRIPT, BOTH }

/**
 * One stretch the engine proposes to cut. [cut] is the default decision (confidence ≥ `cut.cutConfidence`), [unsure]
 * means the screen draws it dotted (confidence < `cut.sureConfidence`); a tap flips [cut] (plan 01 §10, step 3).
 */
data class FlaggedSpan(
    val id: Int,
    val kind: SpanKind,
    val start: Double,
    val end: Double,
    /** What to show: the recognised text ("deci", "ăăă") or, without a transcript, a label from the sound. */
    val label: String,
    val confidence: Double,
    val source: SpanSource,
    val cut: Boolean,
    val unsure: Boolean,
    /** Indices into the transcript words this span covers (empty for acoustic-only spans). */
    val wordIndices: List<Int> = emptyList(),
    /** Why it was flagged, for logs and the evaluation report (not shown to the speaker). */
    val reason: String = "",
) {
    val duration: Double get() = end - start
    val isFiller: Boolean get() = kind == SpanKind.HESITATION || kind == SpanKind.MARKER
}

/** A silence between two stretches of speech. [long] ones are shortened in the cleaned version. */
data class PauseSpan(val start: Double, val end: Double, val long: Boolean) {
    val duration: Double get() = end - start
}

/** An acoustic hesitation candidate before it is merged with the transcript. */
data class AcousticHesitation(
    val start: Double,
    val end: Double,
    val confidence: Double,
    val label: String,
    val boundedBefore: Boolean,
    val boundedAfter: Boolean,
    /** Attached to a preceding word with no pause: a lengthened ending, not a free-standing "ăăă". */
    val lengthening: Boolean,
    val meanChangeDb: Double,
    val pitchSdSt: Double,
)
