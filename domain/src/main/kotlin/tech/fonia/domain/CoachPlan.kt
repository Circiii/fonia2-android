package tech.fonia.domain

import kotlinx.serialization.Serializable

/** Placement steps for one sound, in order: lips and jaw, then tongue, then the sound itself. */
@Serializable
data class CoachPlan(
    val key: String,
    val language: Language,
    val letter: String,
    val steps: List<CoachStep>,
    val syllables: CoachSyllables? = null,
    val word: CoachWord? = null,
    val faults: List<CoachFaultText>,
    /** Extra help after several misses on the sound step. */
    val rescue: String? = null,
) {
    val id: String get() = key
}

@Serializable(with = CoachFocus.RawSerializer::class)
enum class CoachFocus(override val rawValue: String) : StringRawValue {
    Lips("Buzele"),
    Teeth("Dinții"),
    Tongue("Limba"),
    Air("Aerul"),
    Voice("Vocea"),
    Mouth("Gura"),
    ;

    companion object {
        fun fromRawValue(rawValue: String): CoachFocus? = entries.byRawValue(rawValue)
    }

    internal object RawSerializer : StringRawValueSerializer<CoachFocus>("CoachFocus", { entries })
}

@Serializable(with = CoachCheck.RawSerializer::class)
enum class CoachCheck(override val rawValue: String) : StringRawValue {
    Face("face"),
    Mic("mic"),
    Self("self"),
    ;

    companion object {
        fun fromRawValue(rawValue: String): CoachCheck? = entries.byRawValue(rawValue)
    }

    internal object RawSerializer : StringRawValueSerializer<CoachCheck>("CoachCheck", { entries })
}

@Serializable(with = CoachFaceJudge.RawSerializer::class)
enum class CoachFaceJudge(override val rawValue: String) : StringRawValue {
    Smile("smile"),
    Teeth("teeth"),
    Pucker("pucker"),
    RoundClose("roundClose"),
    RoundLight("roundLight"),
    Open("open"),
    TongueOut("tongueOut"),
    RoundW("roundW"),
    LipBite("lipBite"),
    Relaxed("relaxed"),
    WideOpen("wideOpen"),
    ;

    companion object {
        fun fromRawValue(rawValue: String): CoachFaceJudge? = entries.byRawValue(rawValue)
    }

    internal object RawSerializer : StringRawValueSerializer<CoachFaceJudge>("CoachFaceJudge", { entries })
}

@Serializable(with = CoachMicJudge.RawSerializer::class)
enum class CoachMicJudge(override val rawValue: String) : StringRawValue {
    /** Where the hiss sits, plus voicing for Z, J and GE. */
    Sibilant("sibilant"),

    /** R: a clean tap passes and so does a trill. */
    Rhotic("rhotic"),
    Voice("voice"),

    /** English TH: weak, diffuse friction. */
    Th("th"),
    ;

    companion object {
        fun fromRawValue(rawValue: String): CoachMicJudge? = entries.byRawValue(rawValue)
    }

    internal object RawSerializer : StringRawValueSerializer<CoachMicJudge>("CoachMicJudge", { entries })
}

@Serializable(with = CoachFault.RawSerializer::class)
enum class CoachFault(override val rawValue: String) : StringRawValue {
    OtherPlace("other-place"),
    Unvoiced("unvoiced"),
    Lateral("lateral"),
    Interdental("interdental"),
    NoTap("no-tap"),
    SLike("s-like"),
    NoSound("no-sound"),
    ;

    companion object {
        fun fromRawValue(rawValue: String): CoachFault? = entries.byRawValue(rawValue)
    }

    internal object RawSerializer : StringRawValueSerializer<CoachFault>("CoachFault", { entries })
}

@Serializable
data class CoachDiagram(
    val view: View,
    val pose: Viseme? = null,
    val from: Viseme? = null,
    val sound: String? = null,
) {
    @Serializable(with = View.RawSerializer::class)
    enum class View(override val rawValue: String) : StringRawValue {
        Front("front"),
        Side("side"),
        ;

        companion object {
            fun fromRawValue(rawValue: String): View? = entries.byRawValue(rawValue)
        }

        internal object RawSerializer : StringRawValueSerializer<View>("CoachDiagram.View", { entries })
    }
}

@Serializable
data class CoachStep(
    val id: String,
    val label: String,
    val say: String,
    val sub: String? = null,
    val focus: CoachFocus,
    val diagram: CoachDiagram,
    val check: CoachCheck,
    val face: CoachFaceJudge? = null,
    val mic: CoachMicJudge? = null,
    /** Meter end labels: the wrong side, then the target. */
    val ends: List<String>? = null,
    /** Short sounds: count this many good ones instead of holding. */
    val bursts: Int? = null,
    val holdMs: Int,
    /** A helper the learner may skip. It never blocks the next step. */
    val optional: Boolean? = null,
) {
    val isOptional: Boolean get() = optional ?: false
}

@Serializable
data class CoachSyllables(
    val list: List<String>,
    val judge: CoachMicJudge,
    /** Holds `{syllable}` where the syllable goes. */
    val say: String,
) {
    fun prompt(syllable: String): String = say.replace("{syllable}", syllable)
}

@Serializable
data class CoachWord(
    val word: String,
    val rival: String,
    val cue: String,
)

@Serializable
data class CoachFaultText(
    val fault: CoachFault,
    val title: String,
    val tip: String,
)
