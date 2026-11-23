package tech.fonia.speech

import kotlin.math.exp
import kotlin.math.ln

/**
 * Which word the take sounded like. Never a statement about the sound itself: Whisper's text
 * decoder can only express real words and repairs a lateral S into "sun", so [Target] means
 * "word heard" and must not overrule a DSP verdict on the target sound.
 */
sealed interface PairOutcome {
    data object NotHeard : PairOutcome

    data object SameText : PairOutcome

    data class OtherWord(val heard: String) : PairOutcome

    data class Target(val pTarget: Double) : PairOutcome

    data class Rival(val pTarget: Double) : PairOutcome

    data object Unclear : PairOutcome

    val wordHeard: Boolean
        get() = this is Target

    val score: Double
        get() = when (this) {
            NotHeard -> 0.0
            SameText -> 0.75
            is OtherWord -> 0.2
            is Target -> SwiftMath.max(0.8, pTarget)
            is Rival -> SwiftMath.min(0.4, pTarget)
            Unclear -> 0.5
        }
}

/**
 * The judges. Swift spreads `enum SpeechJudge` over PairJudge, SentenceJudge and SpeechReport with
 * extensions; here the other two files add extension functions, called the same way:
 * `SpeechJudge.judgeSentence(...)`.
 */
object SpeechJudge {
    fun combinePair(
        target: String,
        rival: String,
        language: SpeechLanguage,
        transcript: Transcript,
        targetScores: List<TextScore>,
        rivalScores: List<TextScore>,
    ): PairScore {
        val same = targetScores.isEmpty() || rivalScores.isEmpty()
        val targetLogp = logSumExp(targetScores.map { it.logp })
        val rivalLogp = logSumExp(rivalScores.map { it.logp })
        val margin = if (same) 0.0 else targetLogp - rivalLogp
        return PairScore(
            target = target,
            rival = rival,
            language = language,
            same = same,
            targetLogp = targetLogp,
            rivalLogp = rivalLogp,
            margin = margin,
            pTarget = if (same) 0.5 else probability(margin),
            heard = transcript.text,
            heardLogp = transcript.logp,
            noSpeechProb = transcript.noSpeechProb,
            transcript = transcript,
        )
    }

    fun judgePair(score: PairScore, contrast: String? = null, thresholds: SpeechThresholds = SpeechThresholds.STANDARD): PairOutcome {
        if (!heardSomething(score.transcript)) return PairOutcome.NotHeard
        if (score.same) return PairOutcome.SameText
        val pair = thresholds.pair
        val language = score.language
        val targetWords = SpeechText.words(score.target, language)
        val heard = SpeechText.words(score.heard, language)
        val slack = pair.otherWordSlack + pair.otherWordSlackPerWord * targetWords.size.toDouble()
        if (SwiftMath.max(score.targetLogp, score.rivalLogp) < score.heardLogp - slack &&
            !SwiftString.equal(heard, targetWords) &&
            !SwiftString.equal(heard, SpeechText.words(score.rival, language))
        ) {
            return PairOutcome.OtherWord(heard = withoutTrailingStop(score.heard))
        }
        val margin = score.margin - pair.bias(target = score.target, rival = score.rival)
        val band = pair.band(forContrast = contrast)
        if (margin >= band.okMargin) return PairOutcome.Target(pTarget = probability(margin))
        if (margin <= band.wrongMargin) return PairOutcome.Rival(pTarget = probability(margin))
        return PairOutcome.Unclear
    }

    internal fun heardSomething(transcript: Transcript): Boolean =
        transcript.words.any { SpeechText.hasWordCharacter(it.word) }

    private fun probability(margin: Double): Double = 1 / (1 + exp(-margin))

    private fun logSumExp(values: List<Double>): Double {
        val top = SwiftMath.maxOrNull(values) ?: return Double.NEGATIVE_INFINITY
        var sum = 0.0
        for (value in values) sum += exp(value - top)
        return top + ln(sum)
    }

    private fun withoutTrailingStop(text: String): String {
        val characters = SwiftString.characters(text)
        var end = characters.size
        while (end > 0 && stops.any { SwiftString.equal(characters[end - 1], it) }) end -= 1
        return characters.subList(0, end).joinToString("")
    }

    private val stops = listOf(".", "!", "?")
}
