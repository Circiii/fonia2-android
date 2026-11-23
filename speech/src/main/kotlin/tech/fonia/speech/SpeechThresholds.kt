package tech.fonia.speech

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Every number the judges use, as data, so the harness can refit them without a code change.
 * Keys left out of a JSON file keep their defaults, so `{}` is a valid `calibration.json`.
 *
 * Swift's custom `init(from:)` (decodeIfPresent with a default) is a default value here; [json]
 * also ignores unknown keys and treats `null` as absent, as JSONDecoder does.
 */
@Serializable
data class SpeechThresholds(
    val pair: PairThresholds = PairThresholds(),
    val sentence: SentenceThresholds = SentenceThresholds(),
    val freeSpeech: FreeSpeechThresholds = FreeSpeechThresholds(),
    val silence: SilenceThresholds = SilenceThresholds(),
) {
    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        val STANDARD = SpeechThresholds()

        /** JSONDecoder's rules. Encoding writes every key, as JSONEncoder does. */
        val json: Json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            encodeDefaults = true
        }

        fun decode(text: String): SpeechThresholds = json.decodeFromString(serializer(), text)

        /** `init(contentsOf:)`. */
        fun contentsOf(file: File): SpeechThresholds = decode(file.readText(Charsets.UTF_8))
    }
}

/** The explicit band between a clear target and a clear rival; anything inside it is `unclear`. */
@Serializable
data class MarginBand(val okMargin: Double, val wrongMargin: Double)

@Serializable
data class PairThresholds(
    val band: MarginBand = MarginBand(okMargin = 0.5, wrongMargin = -1.5),
    /** Overrides of `band` per contrast id, chosen by the caller ("think-sink"). */
    val contrasts: Map<String, MarginBand> = emptyMap(),
    /**
     * Keyed by [biasKey]: how far the model leans to the first word of the pair on balanced
     * correct takes, since a margin mixes acoustics with a word-frequency prior.
     */
    val pairBias: Map<String, Double> = emptyMap(),
    /** Neither candidate fits nearly as well as what the model wrote by itself: a different word. */
    val otherWordSlack: Double = 6.0,
    val otherWordSlackPerWord: Double = 1.5,
) {
    fun band(forContrast: String?): MarginBand =
        forContrast?.let { SwiftString.lookup(contrasts, it) } ?: band

    fun bias(target: String, rival: String): Double {
        val value = SwiftString.lookup(pairBias, biasKey(target, rival)) ?: return 0.0
        val targetIsFirst = SwiftString.compare(SpeechText.words(target).joinToString(" "), SpeechText.words(rival).joinToString(" ")) <= 0
        return if (targetIsFirst) value else -value
    }

    companion object {
        /** The two normalised texts in sorted order, so one entry serves both directions of a pair. */
        fun biasKey(a: String, b: String): String {
            val x = SpeechText.words(a).joinToString(" ")
            val y = SpeechText.words(b).joinToString(" ")
            return if (SwiftString.compare(x, y) <= 0) "$x|$y" else "$y|$x"
        }
    }
}

@Serializable
data class SentenceThresholds(
    /** A word the model also wrote down, but only just. */
    val weakLogp: Double = -1.9,
    /** Written differently (homophone, spelling) but clearly what the model expects. */
    val rescueLogp: Double = -0.4,
    val missingWordLogp: Double = -10.0,
    val weakWordValue: Double = 0.6,
    val extraWordPenalty: Double = 0.05,
    val extraWordPenaltyMax: Double = 0.2,
    val allHeardFloor: Double = 0.9,
    val someWeakFloor: Double = 0.6,
    val someWeakHeardFrom: Double = 0.75,
    val oneMissedCeiling: Double = 0.7,
    val severalMissedCeiling: Double = 0.6,
    val unclearBelow: Double = 0.4,
)

@Serializable
data class PaceBand(val slowBelow: Double, val fastAbove: Double)

@Serializable
data class FreeSpeechThresholds(
    val paceEnglish: PaceBand = PaceBand(slowBelow = 110.0, fastAbove = 170.0),
    /** No published Romanian norm exists; carried over from the web version until recordings of good Romanian speakers replace it. */
    val paceRomanian: PaceBand = PaceBand(slowBelow = 100.0, fastAbove = 160.0),
    val minSpeakingSeconds: Double = 3.0,
    val minWordsForPace: Int = 5,
    val unclearProbability: Double = 0.35,
    val pauseSeconds: Double = 0.5,
    val longPauseSeconds: Double = 1.0,
    val pacePenalty: Double = 0.2,
    /**
     * Conversational speech runs 2 to 6 hesitations per 100 words, so nothing is penalised at or
     * below this rate. Only um/uh/ăă count; discourse markers are reported, never penalised.
     */
    val hesitationFreePer100Words: Double = 3.0,
    /** Below this count the rate is noise: a short take with one "um" reads as a high rate. */
    val minHesitationsToFlag: Int = 3,
    val hesitationPenaltySlope: Double = 0.1,
    val hesitationPenaltyMax: Double = 0.4,
    val repeatPenalty: Double = 0.05,
    val repeatPenaltyMax: Double = 0.15,
) {
    /** `pace(for:)`. */
    fun pace(language: SpeechLanguage): PaceBand = if (language == SpeechLanguage.RO) paceRomanian else paceEnglish
}

/**
 * On silence the model still writes something ("you", "Thank you."): a likely no-speech window
 * with an unlikely transcript is silence. Single real words stay under the soft limit.
 */
@Serializable
data class SilenceThresholds(
    val noSpeechHard: Double = 0.9,
    val noSpeechSoft: Double = 0.6,
    val softMeanLogp: Double = -1.0,
) {
    fun isSilent(noSpeechProb: Double, meanLogp: Double): Boolean =
        noSpeechProb > noSpeechHard || (noSpeechProb > noSpeechSoft && meanLogp < softMeanLogp)
}
