package tech.fonia.domain

// Swift enums with associated values become sealed interfaces of data classes (and data objects for cases without
// values), which keep Swift's synthesized structural equality and hashing. Unlabelled associated values get a name.

/** What a sustain exercise holds: a sound, or plain air for breathing work. */
sealed interface SustainTarget {
    data class Sound(val id: SoundID) : SustainTarget

    data object Breath : SustainTarget
}

enum class SustainRole(override val rawValue: String) : StringRawValue {
    Practice("practice"),

    /** A helper the learner may skip. It is not an attempt and never decides progress. */
    Facilitation("facilitation"),
    ;

    companion object {
        fun fromRawValue(rawValue: String): SustainRole? = entries.byRawValue(rawValue)
    }
}

sealed interface HowTarget {
    data class Sound(val id: SoundID) : HowTarget

    data class English(val contrast: String) : HowTarget
}

data class RepeatPrompt(
    val text: String,
    val language: Language,
    val sound: SoundID? = null,
    val picture: String? = null,
    val level: Level,
    val cue: String? = null,
    val englishContrast: String? = null,
    /** English minimal pair: the word the attempt must not sound like. */
    val rival: String? = null,
) {
    enum class Level(override val rawValue: String) : StringRawValue {
        Syllable("syllable"),
        Word("word"),
        Sentence("sentence"),
        ;

        companion object {
            fun fromRawValue(rawValue: String): Level? = entries.byRawValue(rawValue)
        }
    }
}

data class ChoosePrompt(
    val answer: WordItem,
    val other: WordItem,
    val language: Language,
    /** Which recorded voice says the word, for listening drills. */
    val voice: Voice? = null,
    val cue: String? = null,
)

enum class SortSide(override val rawValue: String) : StringRawValue {
    A("a"),
    B("b"),
    ;

    companion object {
        fun fromRawValue(rawValue: String): SortSide? = entries.byRawValue(rawValue)
    }
}

enum class IntonationAsk(override val rawValue: String) : StringRawValue {
    Statement("statement"),
    Question("question"),
    ;

    companion object {
        fun fromRawValue(rawValue: String): IntonationAsk? = entries.byRawValue(rawValue)
    }
}

/** One thing the learner does. */
sealed interface Exercise {
    /** Meet the sound's hero. */
    data class Intro(val sound: SoundID) : Exercise

    /** Short teaching step before the first production. */
    data class How(val target: HowTarget) : Exercise

    /** Does this word hold the sound? */
    data class Hear(val sound: SoundID, val item: WordItem, val has: Boolean) : Exercise

    data class Warmup(val id: String) : Exercise

    data class Sustain(val target: SustainTarget, val seconds: Double, val role: SustainRole) : Exercise

    data class Repeat(val prompt: RepeatPrompt) : Exercise

    /** Say the word from its picture, without a model first. */
    data class Name(val sound: SoundID, val item: WordItem) : Exercise

    /** Which of the two did you hear? */
    data class Choose(val prompt: ChoosePrompt) : Exercise

    data class Sort(val a: SoundID, val b: SoundID, val item: WordItem, val answer: SortSide) : Exercise

    data class Twister(val text: String, val language: Language, val sound: SoundID?) : Exercise

    data class Stress(val word: String, val wrong: String) : Exercise

    data object Volume : Exercise

    data object Glide : Exercise

    data class Intonation(val statement: String, val question: String, val ask: IntonationAsk) : Exercise

    /** Reading with a moving voice. Judged on pitch range only, with no claim about feeling. */
    data class Expression(val text: String, val cue: String) : Exercise

    data class Pace(val text: String, val targetSyllablesPerSecond: Double) : Exercise

    data class Pauses(val text: String, val spoken: String) : Exercise

    /** Judged on rhythm and melody only, never as evidence for individual sounds. */
    data class Shadow(val text: String, val language: Language) : Exercise

    /** Hear a short story, then tell it in your own words. An adult listens. */
    data class Retell(val prompt: RetellPrompt) : Exercise

    /** Make a sentence from pictures. An adult listens. */
    data class Describe(val prompt: DescribePrompt) : Exercise

    /** A real-life task off the phone. The parent watches, and nothing is judged in the moment. */
    data class Mission(val prompt: MissionPrompt) : Exercise

    enum class Kind(override val rawValue: String) : StringRawValue {
        Intro("intro"),
        How("how"),
        Hear("hear"),
        Warmup("warmup"),
        Sustain("sustain"),
        Repeat("repeat"),
        Name("name"),
        Choose("choose"),
        Sort("sort"),
        Twister("twister"),
        Stress("stress"),
        Volume("volume"),
        Glide("glide"),
        Intonation("intonation"),
        Expression("expression"),
        Pace("pace"),
        Pauses("pauses"),
        Shadow("shadow"),
        Retell("retell"),
        Describe("describe"),
        Mission("mission"),
        ;

        companion object {
            fun fromRawValue(rawValue: String): Kind? = entries.byRawValue(rawValue)
        }
    }

    val kind: Kind
        get() = when (this) {
            is Intro -> Kind.Intro
            is How -> Kind.How
            is Hear -> Kind.Hear
            is Warmup -> Kind.Warmup
            is Sustain -> Kind.Sustain
            is Repeat -> Kind.Repeat
            is Name -> Kind.Name
            is Choose -> Kind.Choose
            is Sort -> Kind.Sort
            is Twister -> Kind.Twister
            is Stress -> Kind.Stress
            Volume -> Kind.Volume
            Glide -> Kind.Glide
            is Intonation -> Kind.Intonation
            is Expression -> Kind.Expression
            is Pace -> Kind.Pace
            is Pauses -> Kind.Pauses
            is Shadow -> Kind.Shadow
            is Retell -> Kind.Retell
            is Describe -> Kind.Describe
            is Mission -> Kind.Mission
        }

    /** True when the learner speaks on the phone and the attempt counts towards the dose. */
    val isProduction: Boolean
        get() = when (this) {
            is Sustain -> role == SustainRole.Practice
            is Repeat, is Name, is Twister, Volume, Glide, is Intonation, is Expression, is Pace, is Pauses, is Shadow,
            is Retell, is Describe,
            -> true
            is Intro, is How, is Hear, is Warmup, is Choose, is Sort, is Stress, is Mission -> false
        }

    /** True for the two-choice and yes-or-no listening exercises. */
    val isIdentification: Boolean
        get() = when (this) {
            is Hear, is Choose, is Sort, is Stress -> true
            else -> false
        }

    /** The practice item, for `AttemptsSummary.recentItems`. */
    val itemText: String?
        get() = when (this) {
            is Hear -> item.text
            is Name -> item.text
            is Sort -> item.text
            is Repeat -> prompt.text
            is Choose -> prompt.answer.text
            is Twister -> text
            is Expression -> text
            is Pace -> text
            is Pauses -> text
            is Shadow -> text
            is Stress -> word
            is Intonation -> statement
            is Retell -> prompt.title
            is Describe -> prompt.words.joinToString(" ")
            is Mission -> prompt.text
            is Intro, is How, is Warmup, is Sustain, Volume, Glide -> null
        }
}
