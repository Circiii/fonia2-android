package tech.fonia.dsp

import kotlin.math.abs

data class ExpectedFricative(
    /** `null` for F, V and H, which are not sibilants but still produce frication. */
    val place: SibilantPlace?,
    val voiced: Boolean,
    val grapheme: String,
    /** Where the letter falls in the text, 0...1. */
    val position: Float,
    /** Romanian voiced sibilants devoice at the end of a word, so voicing is not judged there. */
    val isWordFinal: Boolean,
)

enum class UnclearReason(val rawValue: String) {
    /** The spectrum sits inside the talker's uncertainty band. */
    BORDERLINE("borderline"),

    /** Sample rate or bandwidth cannot show S against Ș. */
    LOW_BANDWIDTH("lowBandwidth"),

    /** The text contains none of the sounds being judged. */
    NO_TARGET("noTarget"),

    /**
     * An R outside a vowel-vowel context produced no event. The tap cue needs voiced flanks, which
     * word edges and consonant clusters may lack, so its absence proves nothing there.
     */
    RHOTIC_CONTEXT("rhoticContext"),
}

/** Structured result of judging one sound. Copy is written elsewhere. */
sealed class SoundOutcome {
    data object NotHeard : SoundOutcome()
    data object TooShort : SoundOutcome()
    data object Clipped : SoundOutcome()
    data class Unclear(val reason: UnclearReason) : SoundOutcome()
    data object Good : SoundOutcome()
    data class WrongPlace(val heard: SibilantPlace, val count: Int, val of: Int) : SoundOutcome()
    data object Missing : SoundOutcome()
    data class Partial(val heard: Int, val of: Int) : SoundOutcome()
    data object Weak : SoundOutcome()
    data object Unvoiced : SoundOutcome()

    /** The spectrum resembles a lateral hiss. No verdict either way: a hint that an adult should listen. */
    data object LateralHint : SoundOutcome()

    /** A single-contact R, the usual correct Romanian realisation in words. */
    data object Tap : SoundOutcome()

    /** A rolled R. Accepted in words, though only facilitation exercises ask for it. */
    data object Trill : SoundOutcome()

    /** The learner should hear this as a pass. */
    fun isAccepted(thresholds: DSPThresholds = DSPThresholds.v1): Boolean = when (this) {
        Good, Tap, Trill -> true
        is Partial -> of > 0 && heard.toFloat() / of.toFloat() >= thresholds.judge.scorePartialFloor
        else -> false
    }

    /** The take could not be judged, which says nothing about pronunciation. */
    val isAbstention: Boolean
        get() = when (this) {
            NotHeard, TooShort, Clipped, is Unclear, LateralHint -> true
            else -> false
        }

    fun score(thresholds: DSPThresholds = DSPThresholds.v1): Float {
        val judge = thresholds.judge
        return when (this) {
            NotHeard, Clipped -> 0f
            TooShort, is Unclear, LateralHint -> judge.scoreUnclear
            Good -> judge.scoreGood
            Tap, Trill -> judge.scoreRhoticAccepted
            is WrongPlace -> min(judge.scoreWrongPlaceCap, if (of > 0) 1f - count.toFloat() / of.toFloat() else 0f)
            Missing -> judge.scoreMissing
            is Partial -> max(judge.scorePartialFloor, if (of > 0) heard.toFloat() / of.toFloat() else 0f)
            Weak -> judge.scoreWeak
            Unvoiced -> judge.scoreUnvoiced
        }
    }
}

/** Fricatives and affricates a Romanian text should contain, in order. */
fun expectedFricatives(text: String): List<ExpectedFricative> {
    val letters = characters(normalizedRomanian(text))
    val span = max(1, letters.size - 1).toFloat()
    val result = ArrayList<ExpectedFricative>()
    fun add(place: SibilantPlace?, voiced: Boolean, grapheme: String, index: Int) {
        val after = index + characterCount(grapheme)
        result.add(
            ExpectedFricative(
                place = place, voiced = voiced, grapheme = grapheme, position = index.toFloat() / span,
                isWordFinal = after >= letters.size || !isLetter(letters[after]),
            ),
        )
    }
    for ((i, letter) in letters.withIndex()) {
        val next = if (i + 1 < letters.size) letters[i + 1] else " "
        val previous = if (i > 0) letters[i - 1] else " "
        // A prolongation written "ssssss" is one sound.
        if (letter == previous) continue
        when {
            letter == "c" && (next == "e" || next == "i") && previous != "s" ->
                add(SibilantPlace.SH, voiced = false, grapheme = "c$next", index = i)
            letter == "g" && (next == "e" || next == "i") ->
                add(SibilantPlace.SH, voiced = true, grapheme = "g$next", index = i)
            letter == "s" || letter == "ț" || letter == "x" ->
                add(SibilantPlace.S, voiced = false, grapheme = letter, index = i)
            letter == "z" -> add(SibilantPlace.S, voiced = true, grapheme = "z", index = i)
            letter == "ș" -> add(SibilantPlace.SH, voiced = false, grapheme = "ș", index = i)
            letter == "j" -> add(SibilantPlace.SH, voiced = true, grapheme = "j", index = i)
            letter == "f" -> add(null, voiced = false, grapheme = "f", index = i)
            letter == "v" -> add(null, voiced = true, grapheme = "v", index = i)
            letter == "h" && previous != "c" && previous != "g" -> add(null, voiced = false, grapheme = "h", index = i)
            else -> Unit
        }
    }
    return result
}

/** Lowercase, composed, with the legacy cedilla letters mapped to the correct comma-below ones. */
internal fun normalizedRomanian(text: String): String =
    nfc(text.lowercase()).replace("ş", "ș").replace("ţ", "ț")

data class FricativeMatch(
    val expected: ExpectedFricative,
    /** `null` when nothing was heard at this position. */
    val detected: FricativeSegment?,
)

/**
 * Edit-distance alignment of what the text expects against what the take contains. Extra detections
 * are cheap because stop bursts and aspiration produce short frication the text does not predict.
 */
fun alignFricatives(text: String, summary: ClipSummary, thresholds: DSPThresholds = DSPThresholds.v1): List<FricativeMatch> {
    val settings = thresholds.judge
    val expected = expectedFricatives(text)
    val detected = summary.fricatives
    val n = expected.size
    val m = detected.size
    val speechSpan = max(0.001, summary.speechEnd - summary.speechStart)
    fun match(i: Int, j: Int): Float {
        val middle = (detected[j].start + detected[j].end) / 2
        val position = ((middle - summary.speechStart) / speechSpan).toFloat()
        val distance = settings.alignPositionCost * abs(expected[i].position - position)
        val place = expected[i].place ?: return settings.alignOtherMatch + distance
        return (if (place == detected[j].place) 0f else settings.alignWrongPlace) + distance
    }
    fun missing(i: Int): Float = if (expected[i].place == null) settings.alignMissingOther else settings.alignMissingSibilant
    fun at(i: Int, j: Int): Int = i * (m + 1) + j
    val cost = FloatArray((n + 1) * (m + 1))
    for (i in 0 until n) cost[at(i + 1, 0)] = cost[at(i, 0)] + missing(i)
    for (j in 0 until m) cost[at(0, j + 1)] = cost[at(0, j)] + settings.alignExtraDetection
    for (i in 0 until n) {
        for (j in 0 until m) {
            cost[at(i + 1, j + 1)] = min(
                cost[at(i, j)] + match(i, j),
                cost[at(i, j + 1)] + missing(i),
                cost[at(i + 1, j)] + settings.alignExtraDetection,
            )
        }
    }
    val matches = ArrayList<FricativeMatch>()
    var i = n
    var j = m
    while (i > 0 || j > 0) {
        if (i > 0 && j > 0 && abs(cost[at(i, j)] - cost[at(i - 1, j - 1)] - match(i - 1, j - 1)) < 1e-4f) {
            matches.add(FricativeMatch(expected = expected[i - 1], detected = detected[j - 1]))
            i -= 1
            j -= 1
        } else if (i > 0 && abs(cost[at(i, j)] - cost[at(i - 1, j)] - missing(i - 1)) < 1e-4f) {
            matches.add(FricativeMatch(expected = expected[i - 1], detected = null))
            i -= 1
        } else if (j > 0) {
            j -= 1
        } else {
            i -= 1
        }
    }
    return matches.reversed()
}

/** Seconds of the take that are loud and pitched: a voice. */
fun voicedSeconds(summary: ClipSummary, thresholds: DSPThresholds = DSPThresholds.v1): Double {
    if (summary.track.size < 2) return 0.0
    val judge = thresholds.judge
    val count = summary.track.count {
        it.hz > 0f &&
            it.db > summary.floorDb + judge.voicedMinAboveFloorDb &&
            it.db > summary.peakDb - judge.voicedMaxBelowPeakDb
    }
    return count.toDouble() * summary.trackHopSeconds
}

fun heardSpeech(summary: ClipSummary, thresholds: DSPThresholds = DSPThresholds.v1): Boolean {
    // A voice-activity model is robust to room noise but sometimes finds nothing in a clear, loud
    // word; a quarter second of loud pitched voice is speech too, so nobody who spoke up hears otherwise.
    if (summary.usedSpeechModel) {
        return summary.speechSeconds > thresholds.capture.minSpeechSeconds ||
            voicedSeconds(summary, thresholds) > thresholds.judge.voicedSeconds
    }
    return summary.speechEnd - summary.speechStart > thresholds.capture.minSpeechSeconds &&
        summary.peakDb - summary.floorDb > thresholds.capture.minDynamicRangeDb
}

/** Capture problems outrank everything else: a bad take is not a pronunciation error. */
private fun captureOutcome(summary: ClipSummary, thresholds: DSPThresholds): SoundOutcome? {
    val quality = summary.quality
    if (quality.isClipped) return SoundOutcome.Clipped
    if (quality.isTooQuiet) return SoundOutcome.NotHeard
    if (quality.isTooShort) {
        // A short burst of real signal is too short; a clip with nothing in it was not heard.
        return if (summary.peakDb - summary.floorDb > thresholds.capture.minDynamicRangeDb) SoundOutcome.TooShort else SoundOutcome.NotHeard
    }
    return if (heardSpeech(summary, thresholds)) null else SoundOutcome.NotHeard
}

/**
 * Judges one sibilant sound in a word or sentence. `graphemes` names the sound: `["s"]`, `["ș"]`,
 * `["ce", "ci"]` and so on.
 */
fun judgeSibilant(
    text: String, graphemes: List<String>, summary: ClipSummary, thresholds: DSPThresholds = DSPThresholds.v1,
): SoundOutcome {
    captureOutcome(summary, thresholds)?.let { return it }
    val wanted = graphemes.map { normalizedRomanian(it) }.toSet()
    val matches = alignFricatives(text, summary, thresholds)
    val targets = matches.filter { it.expected.grapheme in wanted }
    if (targets.isEmpty()) return SoundOutcome.Unclear(UnclearReason.NO_TARGET)
    if (!summary.quality.canJudgeSibilants) return SoundOutcome.Unclear(UnclearReason.LOW_BANDWIDTH)

    var good = 0
    var wrongPlace = 0
    var wrongHeard: SibilantPlace? = null
    var missing = 0
    var unvoiced = 0
    var diffuse = 0
    var borderline = 0
    var lateral = 0
    for (target in targets) {
        val detected = target.detected
        if (detected == null) {
            missing += 1
            continue
        }
        // A clearly Ș-like token is reported as such: the two-band rule alone cannot tell them apart.
        val clearlySh = detected.place == SibilantPlace.SH && !detected.isBorderline
        if (detected.lateralHint && target.expected.place == SibilantPlace.S && !clearlySh) lateral += 1
        if (detected.isBorderline) borderline += 1
        val place = target.expected.place
        if (place != null && place != detected.place) {
            wrongPlace += 1
            wrongHeard = detected.place
        } else {
            good += 1
            if (target.expected.voiced && !target.expected.isWordFinal && detected.voicingMeasurable && !detected.voiced) {
                unvoiced += 1
            }
            if (detected.diffuse) diffuse += 1
        }
    }

    if (lateral > 0) return SoundOutcome.LateralHint
    if (borderline > 0) return SoundOutcome.Unclear(UnclearReason.BORDERLINE)
    if (missing == targets.size && summary.speechEnd - summary.speechStart < thresholds.judge.shortTakeSeconds) {
        return SoundOutcome.TooShort
    }
    if (wrongPlace > 0 && wrongHeard != null) return SoundOutcome.WrongPlace(heard = wrongHeard, count = wrongPlace, of = targets.size)
    if (missing == targets.size) return SoundOutcome.Missing
    if (diffuse > 0) return SoundOutcome.Weak
    if (unvoiced > 0) return SoundOutcome.Unvoiced
    if (missing > 0) return SoundOutcome.Partial(heard = good, of = targets.size)
    return SoundOutcome.Good
}

/**
 * Judges R from envelope events. Taps are correct in words, and a trill is accepted too; a word
 * never has to be rolled.
 */
fun judgeRhotic(text: String, summary: ClipSummary, thresholds: DSPThresholds = DSPThresholds.v1): SoundOutcome {
    captureOutcome(summary, thresholds)?.let { return it }
    val positions = rhoticPositions(text)
    if (positions.isEmpty()) return SoundOutcome.Unclear(UnclearReason.NO_TARGET)
    val events = summary.trills.size + summary.taps.size
    if (events >= positions.size) return if (summary.trills.isEmpty()) SoundOutcome.Tap else SoundOutcome.Trill
    val observable = positions.count { it.isIntervocalic }
    if (events > 0) {
        // Heard some. Only count the shortfall against the learner where the cue is observable.
        val accountedFor = events + (positions.size - observable)
        return if (accountedFor >= positions.size) {
            if (summary.trills.isEmpty()) SoundOutcome.Tap else SoundOutcome.Trill
        } else {
            SoundOutcome.Partial(heard = events, of = positions.size)
        }
    }
    return if (observable > 0) SoundOutcome.Missing else SoundOutcome.Unclear(UnclearReason.RHOTIC_CONTEXT)
}

internal data class RhoticPosition(val isIntervocalic: Boolean)

private val romanianVowels = setOf("a", "ă", "â", "e", "i", "î", "o", "u")

internal fun rhoticPositions(text: String): List<RhoticPosition> {
    val letters = characters(normalizedRomanian(text))
    val result = ArrayList<RhoticPosition>()
    for ((i, letter) in letters.withIndex()) {
        if (!(letter == "r" && (i == 0 || letters[i - 1] != "r"))) continue
        val before = i > 0 && letters[i - 1] in romanianVowels
        val after = i + 1 < letters.size && letters[i + 1] in romanianVowels
        result.add(RhoticPosition(isIntervocalic = before && after))
    }
    return result
}

/** English TH: weak, diffuse friction. A strong, sharp hiss at the start means it became S. */
fun judgeEnglishTH(summary: ClipSummary, thresholds: DSPThresholds = DSPThresholds.v1): SoundOutcome {
    captureOutcome(summary, thresholds)?.let { return it }
    val first = summary.fricatives.firstOrNull()
    if (first != null && !first.diffuse && first.place == SibilantPlace.S &&
        first.start - summary.speechStart < thresholds.judge.thLeadSeconds
    ) {
        return SoundOutcome.WrongPlace(heard = SibilantPlace.S, count = 1, of = 1)
    }
    return SoundOutcome.Good
}
