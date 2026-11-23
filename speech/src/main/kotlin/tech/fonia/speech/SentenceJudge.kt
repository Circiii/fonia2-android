package tech.fonia.speech

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.exp

/** Whether the model heard the word, not whether its sounds were well formed. */
@Serializable
enum class WordStatus(val rawValue: String) {
    @SerialName("good")
    GOOD("good"),

    @SerialName("weak")
    WEAK("weak"),

    @SerialName("missed")
    MISSED("missed"),

    @SerialName("extra")
    EXTRA("extra"),
}

@Serializable
data class WordOutcome(
    /** The target word as written, or the extra word that was heard. */
    val word: String,
    val status: WordStatus,
    /** Position in the target sentence; null for extra words. */
    val index: Int?,
    /** What the model heard in its place, when different. */
    val heard: String? = null,
    val prob: Double,
    val start: Double,
    val end: Double,
)

data class SentenceOutcome(
    val result: Result,
    val words: List<WordOutcome>,
    /** The transcript the model wrote by itself. */
    val heard: String,
    val score: Double,
    /** Enough of the sentence was understood. Says nothing about how its sounds were made. */
    val wordsHeard: Boolean,
) {
    @Serializable
    enum class Result(val rawValue: String) {
        @SerialName("notHeard")
        NOT_HEARD("notHeard"),

        @SerialName("allHeard")
        ALL_HEARD("allHeard"),

        @SerialName("someWeak")
        SOME_WEAK("someWeak"),

        @SerialName("oneMissed")
        ONE_MISSED("oneMissed"),

        @SerialName("severalMissed")
        SEVERAL_MISSED("severalMissed"),

        /** Mostly words that were not there at all: probably a different sentence or a cut-off take. */
        @SerialName("unclear")
        UNCLEAR("unclear"),
    }

    val missed: List<WordOutcome>
        get() = words.filter { it.status == WordStatus.MISSED }

    val weak: List<WordOutcome>
        get() = words.filter { it.status == WordStatus.WEAK }
}

fun SpeechJudge.judgeSentence(s: SentenceScore, thresholds: SpeechThresholds = SpeechThresholds.STANDARD): SentenceOutcome {
    val language = s.language
    val limits = thresholds.sentence
    val raw = SpeechText.rawWords(s.target).filter { SpeechText.normalizedWord(it, language).isNotEmpty() }
    val target = raw.map { SpeechText.normalizedWord(it, language) }
    val heardWords = s.transcript.words.filter { SpeechText.normalizedWord(it.word, language).isNotEmpty() }
    val heard = heardWords.map { SpeechText.normalizedWord(it.word, language) }

    if (heard.isEmpty()) {
        val missed = raw.mapIndexed { index, word ->
            WordOutcome(word = SpeechText.trimmedToWord(word), status = WordStatus.MISSED, index = index, prob = 0.0, start = 0.0, end = 0.0)
        }
        return SentenceOutcome(result = SentenceOutcome.Result.NOT_HEARD, words = missed, heard = "", score = 0.0, wordsHeard = false)
    }

    // Teacher-forced words come from the same text, so they line up with the target words one to one.
    val forced = s.score.words
    fun forcedWord(i: Int): WordScore? =
        if (forced.size == target.size) forced[i]
        else forced.firstOrNull { SwiftString.equal(SpeechText.normalizedWord(it.word, language), target[i]) }

    val out = mutableListOf<WordOutcome>()
    for (pair in SpeechText.alignWords(target = target, heard = heard)) {
        val t = pair.target
        if (t == null) {
            val extra = pair.heard?.let { heardWords[it] }
            if (extra != null) {
                out.add(WordOutcome(word = SpeechText.trimmedToWord(extra.word), status = WordStatus.EXTRA, index = null, prob = extra.prob, start = extra.start, end = extra.end))
            }
            continue
        }
        val word = forcedWord(t)
        val logp = word?.logp ?: limits.missingWordLogp
        val h = pair.heard?.let { heardWords[it] }
        val normalizedHeard = pair.heard?.let { heard[it] }
        val distance = normalizedHeard?.let { SpeechText.wordDistance(target[t], it) } ?: 1.0
        val status = if (distance == 0.0) {
            if (logp >= limits.weakLogp) WordStatus.GOOD else WordStatus.WEAK
        } else if (normalizedHeard?.let { SpeechText.nearMiss(target = target[t], heard = it) } == true || logp >= limits.rescueLogp) {
            WordStatus.WEAK
        } else {
            WordStatus.MISSED
        }
        out.add(
            WordOutcome(
                word = SpeechText.trimmedToWord(raw[t]),
                status = status,
                index = t,
                heard = if (distance > 0) h?.let { SpeechText.trimmedToWord(it.word) } else null,
                prob = exp(logp),
                start = word?.start ?: h?.start ?: 0.0,
                end = word?.end ?: h?.end ?: 0.0,
            ),
        )
    }

    val scored = out.filter { it.status != WordStatus.EXTRA }
    val extras = out.size - scored.size
    val total = scored.fold(0.0) { sum, word ->
        when (word.status) {
            WordStatus.GOOD -> sum + 1
            WordStatus.WEAK -> sum + limits.weakWordValue
            WordStatus.MISSED, WordStatus.EXTRA -> sum
        }
    }
    val base = total / maxOf(1, scored.size).toDouble()
    val score = SwiftMath.min(1.0, SwiftMath.max(0.0, base - SwiftMath.min(limits.extraWordPenaltyMax, extras.toDouble() * limits.extraWordPenalty)))
    val missed = scored.filter { it.status == WordStatus.MISSED }
    val weak = scored.filter { it.status == WordStatus.WEAK }
    val transcript = s.transcript.text

    if (missed.isEmpty() && weak.isEmpty()) {
        return SentenceOutcome(SentenceOutcome.Result.ALL_HEARD, out, transcript, SwiftMath.max(limits.allHeardFloor, score), wordsHeard = true)
    }
    if (missed.isEmpty()) {
        return SentenceOutcome(SentenceOutcome.Result.SOME_WEAK, out, transcript, SwiftMath.max(limits.someWeakFloor, score), wordsHeard = score >= limits.someWeakHeardFrom)
    }
    if (missed.size == 1) {
        return SentenceOutcome(SentenceOutcome.Result.ONE_MISSED, out, transcript, SwiftMath.min(score, limits.oneMissedCeiling), wordsHeard = false)
    }
    if (score < limits.unclearBelow && missed.count { it.heard != null } < 2) {
        return SentenceOutcome(SentenceOutcome.Result.UNCLEAR, out, transcript, score, wordsHeard = false)
    }
    return SentenceOutcome(SentenceOutcome.Result.SEVERAL_MISSED, out, transcript, SwiftMath.min(score, limits.severalMissedCeiling), wordsHeard = false)
}
