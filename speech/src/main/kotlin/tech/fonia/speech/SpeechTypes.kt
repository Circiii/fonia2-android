package tech.fonia.speech

import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The value types of FoniaSpeech/SpeechTypes.swift. Swift structs are values, so these are
// immutable data classes: change one with `copy`. The Codable ones are @Serializable with the
// same JSON keys; a key Swift's synthesized Decodable requires stays required here.

@Serializable
enum class SpeechLanguage(val rawValue: String) {
    @SerialName("en")
    EN("en"),

    @SerialName("ro")
    RO("ro"),
}

@Serializable
data class TimeSpan(val start: Double, val end: Double)

@Serializable
data class InferenceCost(val milliseconds: Double, val encoderPasses: Int, val decoderSteps: Int) {
    companion object {
        val ZERO = InferenceCost(milliseconds = 0.0, encoderPasses = 0, decoderSteps = 0)
    }
}

@Serializable
data class TranscriptWord(val word: String, val start: Double, val end: Double, val prob: Double)

@Serializable
data class Transcript(
    val text: String,
    val words: List<TranscriptWord>,
    val noSpeechProb: Double,
    val logp: Double,
    // The init has a default, but the synthesized Decodable still asks for the key.
    @Required val cost: InferenceCost = InferenceCost.ZERO,
)

@Serializable
data class TokenScore(
    val id: Int,
    val text: String,
    val logp: Double,
    val topID: Int,
    val top: String,
    val topLogp: Double,
)

@Serializable
data class WordScore(
    val word: String,
    /** Sum of the word's token log-probabilities, trailing punctuation excluded. */
    val logp: Double,
    val prob: Double,
    val minLogp: Double,
    val top: String,
    val topLogp: Double,
    val start: Double,
    val end: Double,
    val tokens: List<TokenScore>,
)

@Serializable
data class TextScore(
    val text: String,
    val logp: Double,
    val meanLogp: Double,
    val eotLogp: Double,
    val noSpeechProb: Double,
    val tokens: List<TokenScore>,
    val words: List<WordScore>,
)

data class PairScore(
    val target: String,
    val rival: String,
    val language: SpeechLanguage,
    /** Both candidates are the same text (ANimal / aniMAL): the recogniser cannot tell them apart. */
    val same: Boolean,
    val targetLogp: Double,
    val rivalLogp: Double,
    /** targetLogp - rivalLogp, before any calibration. */
    val margin: Double,
    val pTarget: Double,
    val heard: String,
    val heardLogp: Double,
    val noSpeechProb: Double,
    val transcript: Transcript,
    val cost: InferenceCost = InferenceCost.ZERO,
)

data class SentenceScore(
    val target: String,
    val language: SpeechLanguage,
    val transcript: Transcript,
    val score: TextScore,
    val cost: InferenceCost = InferenceCost.ZERO,
)
