package tech.fonia.domain

import kotlinx.serialization.Serializable

@Serializable(with = DictionAreaID.RawSerializer::class)
enum class DictionAreaID(override val rawValue: String) : StringRawValue {
    Breathing("breathing"),
    Articulation("articulation"),
    Volume("volume"),
    Pitch("pitch"),
    Rhythm("rhythm"),
    ;

    companion object {
        fun fromRawValue(rawValue: String): DictionAreaID? = entries.byRawValue(rawValue)
    }

    internal object RawSerializer : StringRawValueSerializer<DictionAreaID>("DictionAreaID", { entries })
}

@Serializable
data class DictionArea(
    val id: DictionAreaID,
    val title: String,
    val subtitle: String,
)

@Serializable
data class IntonationPair(
    val statement: String,
    val question: String,
)

@Serializable
data class ExpressionLine(
    val text: String,
    val cue: String,
)

/** Wording for the expression exercise, which is judged on pitch range only. */
@Serializable
data class ExpressionVerdicts(
    val moved: String,
    val flat: String,
    val flatTip: String,
    val pitchRangeSemitones: Double,
)

@Serializable
data class PaceText(
    val text: String,
    val targetSyllablesPerSecond: Double,
)

@Serializable
data class PauseText(
    /** Marks each pause with `|`. */
    val text: String,
    /** The text the clip was recorded from. */
    val spoken: String,
)

@Serializable
data class SpeechPrompt(
    val title: String,
    val prompt: String,
    val seconds: Int,
)

/** Hesitations are counted, while discourse markers are shown as information and never as errors. */
@Serializable
data class FillerList(
    val hesitations: List<String>,
    val discourseMarkers: List<String>,
)

@Serializable
data class FillerLists(
    val ro: FillerList,
    val en: FillerList,
)

@Serializable
data class PauseClasses(
    val breath: Double,
    val phrase: Double,
    val dramatic: Double,
    val lost: Double,
)

/** Soft guides for a report. A talk outside a range is not wrong. */
@Serializable
data class RateGuide(
    val english: English,
    val romanian: Romanian,
) {
    @Serializable
    data class English(
        val wordsPerMinuteMin: Double,
        val wordsPerMinuteMax: Double,
    )

    @Serializable
    data class Romanian(
        val syllablesPerSecondMin: Double,
        val syllablesPerSecondMax: Double,
        /** False until the range is checked on recordings of good Romanian speakers. */
        val calibrated: Boolean,
    )
}

@Serializable
data class PhonationNorms(
    val child4: Double,
    val child7: Double,
    val child10: Double,
    val adult: Double,
) {
    fun seconds(age: AgeBand): Double = when (age) {
        AgeBand.FourToFive, AgeBand.SixToSeven -> child4
        AgeBand.EightToTen -> child7
        AgeBand.TeenAdult -> adult
    }
}

@Serializable
data class DictionContent(
    val areas: List<DictionArea>,
    val phonationNormSeconds: PhonationNorms,
    val finalIWords: List<String>,
    val intonationPairs: List<IntonationPair>,
    val expressionLines: List<ExpressionLine>,
    val expressionVerdicts: ExpressionVerdicts,
    val paceTexts: List<PaceText>,
    val pauseTexts: List<PauseText>,
    val speechPrompts: List<SpeechPrompt>,
    val fillers: FillerLists,
    /** Shown next to any filler count, with a way to turn the count off. */
    val fillerNote: String,
    /** Free speech below this rate gets no comment on hesitations. */
    val hesitationsPer100WordsBeforeComment: Double,
    val pauseClasses: PauseClasses,
    val rateGuide: RateGuide,
)
