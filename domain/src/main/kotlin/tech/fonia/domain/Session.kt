package tech.fonia.domain

enum class AgeBand(override val rawValue: String) : StringRawValue {
    FourToFive("fourToFive"),
    SixToSeven("sixToSeven"),
    EightToTen("eightToTen"),
    TeenAdult("teenAdult"),
    ;

    /** Production attempts per session. Starting values from studies on older children. */
    val attemptRange: IntRange
        get() = when (this) {
            FourToFive -> 20..30
            SixToSeven -> 30..50
            EightToTen, TeenAdult -> 50..70
        }

    /** Session sizes inside the range, in whole blocks of six. */
    internal val attemptSteps: List<Int>
        get() = when (this) {
            FourToFive -> listOf(24, 30)
            SixToSeven -> listOf(30, 36, 42, 48)
            EightToTen, TeenAdult -> listOf(54, 60, 66)
        }

    internal val listeningTrials: Int
        get() = when (this) {
            FourToFive -> 5
            SixToSeven -> 6
            EightToTen -> 8
            TeenAdult -> 10
        }

    /** Tongue twisters load confusable sounds together, so they stay an extra for older learners. */
    internal val getsTwisters: Boolean get() = this == EightToTen || this == TeenAdult

    internal val prosodies: List<Prosody>
        get() = if (this == FourToFive) listOf(Prosody.Neutral, Prosody.Loud, Prosody.Slow) else Prosody.entries

    companion object {
        fun fromRawValue(rawValue: String): AgeBand? = entries.byRawValue(rawValue)
    }
}

/** How to say the item in mixed practice, as if picked by a spinner. */
enum class Prosody(override val rawValue: String) : StringRawValue {
    Neutral("neutral"),
    Loud("loud"),
    Slow("slow"),
    Fast("fast"),
    Question("question"),
    Exclamation("exclamation"),
    ;

    companion object {
        fun fromRawValue(rawValue: String): Prosody? = entries.byRawValue(rawValue)
    }
}

/**
 * What the app knows about past practice on this focus. Swift's `var` fields on a value type become `val`s; build a
 * changed summary with `copy()`.
 */
data class AttemptsSummary(
    val sessionsCompleted: Int = 0,
    val attemptsThisWeek: Int = 0,
    /** Texts used in the last session. A new session starts with other items. */
    val recentItems: Set<String> = emptySet(),
) {
    companion object {
        /** Above this the week is on target, so sessions ease off to the short end of the range. */
        val weeklyTarget = 150
    }
}

sealed interface EnglishFocus {
    /** Repeating listening drill on the trained words, in all four voices. */
    data class Drill(val contrast: String) : EnglishFocus

    /** The same drill on held-out words, with no response in the moment. */
    data class Retest(val contrast: String) : EnglishFocus

    data class Speaking(val contrast: String) : EnglishFocus

    /** The contrast's sentences and a tongue twister. */
    data class Sentences(val contrast: String) : EnglishFocus

    data class Melody(val lesson: Int) : EnglishFocus
}

/** The named diction lessons. */
enum class DictionLesson(override val rawValue: String) : StringRawValue {
    Candles("candles"),
    LongSound("longSound"),
    Warmup("warmup"),
    FinalWords("finalWords"),
    Twisters("twisters"),
    VolumeLadder("volumeLadder"),
    Glide("glide"),
    StatementOrQuestion("statementOrQuestion"),
    Emotion("emotion"),
    Pace("pace"),
    Pauses("pauses"),
    Shadow("shadow"),
    ;

    companion object {
        fun fromRawValue(rawValue: String): DictionLesson? = entries.byRawValue(rawValue)
    }
}

/** What a session practises. */
sealed interface SessionFocus {
    data class Sound(val id: SoundID, val rung: Rung) : SessionFocus

    /** Meet the sound's hero, then say whether words hold the sound. */
    data class Meet(val id: SoundID) : SessionFocus

    /** Mouth gymnastics, with an optional long breath at the end. */
    data class Warmups(val ids: List<String>, val breath: Boolean) : SessionFocus

    /** Syllables with the sound at the end or in the middle. */
    data class SyllablesBack(val id: SoundID) : SessionFocus

    /** The hero's story, then its sentences and a tongue twister. */
    data class Story(val id: SoundID) : SessionFocus

    data object Voicing : SessionFocus

    data object VoicingSay : SessionFocus

    data class English(val focus: EnglishFocus) : SessionFocus

    data class Diction(val area: DictionAreaID) : SessionFocus

    // Qualified because the nested class shares the enum's name.
    data class DictionLesson(val lesson: tech.fonia.domain.DictionLesson) : SessionFocus

    val track: Track
        get() = when (this) {
            is Sound, is Meet, is Warmups, is SyllablesBack, is Story, Voicing, VoicingSay -> Track.Sounds
            is English -> Track.English
            is Diction, is DictionLesson -> Track.Diction
        }

    /**
     * The suffix of the sound lesson this focus opens ("meet", "warmup", "isolated", "back"…), which is also the key
     * of the hero's chapter for it. Null for sessions that are not one of a sound's lessons.
     */
    val lessonKey: String?
        get() = when (this) {
            is Meet -> "meet"
            is Warmups -> "warmup"
            is Sound -> rung.rawValue
            is SyllablesBack -> "back"
            is Story, Voicing, VoicingSay, is English, is Diction, is DictionLesson -> null
        }
}

data class SessionRequest(
    val age: AgeBand,
    val focus: SessionFocus,
    val history: AttemptsSummary = AttemptsSummary(),
    val seed: ULong,
    /** Other sounds the child is working on. Word lists leave them out where they can. */
    val avoidingSounds: Set<SoundID> = emptySet(),
    /** Oral warm-ups stay off unless an adult asks for them. */
    val includesOralWarmup: Boolean = false,
)

data class SessionBlock(
    val kind: Kind,
    val index: Int = 0,
) {
    enum class Kind(override val rawValue: String) : StringRawValue {
        /** Meet the sound, the teaching step and any optional warm-up. */
        Opening("opening"),

        /** Quick identification before speaking. */
        Listening("listening"),

        /** The current rung. These blocks drive `Progression`. */
        TargetA("targetA"),

        /** A second target at or below the current rung. */
        TargetB("targetB"),
        Mixed("mixed"),

        /** HVPT training. */
        Drill("drill"),

        /** HVPT on held-out words. */
        Retest("retest"),

        /** One last easy item, so a session ends on something the learner can do. */
        Closing("closing"),

        /** Off the phone. */
        Mission("mission"),
        ;

        companion object {
            fun fromRawValue(rawValue: String): Kind? = entries.byRawValue(rawValue)
        }
    }
}

data class Trial(
    val exercise: Exercise,
    val feedback: FeedbackLevel,
    val block: SessionBlock,
    /** Set in mixed practice. */
    val prosody: Prosody? = null,
) {
    /** True for spoken attempts, and for identifications in a listening drill or retest. */
    val countsAsAttempt: Boolean
        get() = when (block.kind) {
            SessionBlock.Kind.Drill, SessionBlock.Kind.Retest -> exercise.isIdentification
            else -> exercise.isProduction
        }
}

data class Session(
    val trials: List<Trial>,
    val targetAttempts: Int,
    /** The session stops here or at `targetAttempts`, whichever comes first. */
    val timeLimitSeconds: Int = defaultTimeLimitSeconds,
) {
    /** Label of the one button on the end screen. Fixed, so leaving it out of `equals` changes nothing. */
    val endButton: String = "Gata pe azi"

    companion object {
        val defaultTimeLimitSeconds = 600

        /** Foni's goodbye at the end of a child's session. One fixed line, so it can be recorded once. */
        const val goodbye = "Mi-a plăcut să exersăm împreună. Pa-pa!"
    }
}
