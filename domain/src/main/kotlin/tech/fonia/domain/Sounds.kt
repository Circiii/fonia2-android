package tech.fonia.domain

import kotlinx.serialization.Required
import kotlinx.serialization.Serializable

/** The nine Romanian sounds Fonia can practise. Raw values match the JSON resources. */
@Serializable(with = SoundID.RawSerializer::class)
enum class SoundID(override val rawValue: String) : StringRawValue {
    S("s"),
    Sh("ș"),
    Z("z"),
    J("j"),
    Ts("ț"),
    Ce("ce"),
    Ge("ge"),
    R("r"),
    L("l"),
    ;

    val family: SoundFamily
        get() = when (this) {
            S, Z, Ts -> SoundFamily.ColdAir
            Sh, J, Ce, Ge -> SoundFamily.WarmAir
            R, L -> SoundFamily.TongueTip
        }

    companion object {
        /**
         * Order for someone with no logoped to ask: L early, R last. A parent or logoped can pick any other order, and
         * nothing in the app picks a target on its own.
         */
        val defaultOrder: List<SoundID> = listOf(L, S, Z, Sh, J, Ts, Ce, Ge, R)

        fun fromRawValue(rawValue: String): SoundID? = entries.byRawValue(rawValue)
    }

    internal object RawSerializer : StringRawValueSerializer<SoundID>("SoundID", { entries })
}

/** Colour and teaching family from `docs/design.md`. */
enum class SoundFamily(override val rawValue: String) : StringRawValue {
    ColdAir("coldAir"),
    WarmAir("warmAir"),
    TongueTip("tongueTip"),
    ;

    companion object {
        fun fromRawValue(rawValue: String): SoundFamily? = entries.byRawValue(rawValue)
    }
}

@Serializable(with = SoundDetector.RawSerializer::class)
enum class SoundDetector(override val rawValue: String) : StringRawValue {
    Sibilant("sibilant"),
    Rhotic("rhotic"),
    None("none"),
    ;

    companion object {
        fun fromRawValue(rawValue: String): SoundDetector? = entries.byRawValue(rawValue)
    }

    internal object RawSerializer : StringRawValueSerializer<SoundDetector>("SoundDetector", { entries })
}

/**
 * One practice word. A word's tags are relative to the sound whose list holds it.
 *
 * The constructor defaults are Swift's memberwise ones; in JSON every key except `picture` is still required, as with
 * Swift's synthesized decoding (hence `@Required`).
 */
@Serializable
data class WordItem(
    val text: String,
    /** Name of a picture in `images/pictures` (`Pictures.xcassets` on iOS). */
    val picture: String? = null,
    /** The other Fonia target sounds in this word, so a list can be filtered for a child's profile. */
    @Required val otherSounds: List<SoundID> = emptyList(),
    /** Runs of two or more consonants, such as "st" or "ndv". */
    @Required val clusters: List<String> = emptyList(),
    /** False when the picture would not call up this word, so it is repeated after a model instead. */
    @Required val namable: Boolean = false,
    /** True when `manifest.json` holds a clip for the exact text. */
    @Required val recorded: Boolean = true,
)

@Serializable
data class SoundHero(
    val name: String,
    val picture: String,
    val says: String,
    val story: String,
    /**
     * One or two sentences of the hero's story per lesson, keyed by the lesson id's suffix ("meet", "isolated",
     * "back"…). Optional: content written before the chapters still loads.
     */
    val chapters: Map<String, String>? = null,
) {
    /** The chapter for a lesson key, or null when there is none or it is blank. */
    fun chapter(key: String): String? = chapters?.get(key)?.trim()?.takeIf { it.isNotEmpty() }
}

@Serializable
data class SoundFix(
    val `when`: String,
    val tip: String,
)

/** An optional helper for a hard sound. It never decides whether the learner moves on. */
@Serializable
data class Facilitation(
    val say: String,
    val seconds: Double,
)

@Serializable
data class SyllableSet(
    val direct: List<String>,
    val inverse: List<String>,
    val between: List<String>,
    /** Triples such as "ra-ra-ra", for sounds practised in repeated syllables. */
    val repeated: List<String>,
)

@Serializable
data class WordLists(
    val initial: List<WordItem>,
    val medial: List<WordItem>,
    val `final`: List<WordItem>,
    val clusters: List<WordItem>,
) {
    val positional: List<WordItem> get() = initial + medial + `final`
}

/** A true minimal pair: `a` holds the owner sound and `b` holds the sound it is confused with. */
@Serializable
data class WordPair(
    val a: WordItem,
    val b: WordItem,
    /** Text of the clip that says both words, when one was recorded. */
    val spoken: String? = null,
    /** New pair proposed for logoped review. */
    val suggested: Boolean? = null,
) {
    val isSuggested: Boolean get() = suggested ?: false
    val isRecorded: Boolean get() = a.recorded && b.recorded
}

@Serializable
data class SoundContrast(
    val with: SoundID,
    val pairs: List<WordPair>,
)

@Serializable
data class Twister(
    val text: String,
    val folk: Boolean,
)

@Serializable
data class RetellPrompt(
    val title: String,
    /** Played one after another. Each line is looked up on its own in `VoiceIndex`. */
    val lines: List<String>,
    val pictures: List<String>,
    val prompt: String,
)

@Serializable
data class DescribePrompt(
    /** For the adult who listens. The learner sees only the pictures. */
    val words: List<String>,
    val pictures: List<String>,
    val prompt: String,
)

@Serializable
data class MissionPrompt(
    val text: String,
) {
    companion object {
        val parentNote = "Ascultă fără să corectezi pe loc. Seara, notează cum a mers."
    }
}

@Serializable
data class SoundDef(
    val id: SoundID,
    val letter: String,
    val graphemes: List<String>,
    val hero: SoundHero,
    val viseme: Viseme,
    val detector: SoundDetector,
    val voiced: Boolean,
    /** Ț, CE and GE are short: they are counted in bursts, not held. */
    val isAffricate: Boolean,
    /**
     * Age by which many children say this sound, from studies on other languages. Shown to parents as orientation only
     * and never used to judge a child.
     */
    val typicalByAge: Int,
    /** What the sound should sound like, for the adult. */
    val parentNote: String,
    val how: List<String>,
    val fixes: List<SoundFix>,
    val facilitation: Facilitation? = null,
    /** Optional oral warm-ups. They never gate anything. */
    val warmups: List<String>,
    val syllables: SyllableSet,
    val words: WordLists,
    val sentences: List<String>,
    val twisters: List<Twister>,
    val contrasts: List<SoundContrast>,
    val retell: List<RetellPrompt>,
    val describe: List<DescribePrompt>,
    val missions: List<MissionPrompt>,
) {
    val family: SoundFamily get() = id.family

    val allWords: List<WordItem> get() = words.positional + words.clusters

    /**
     * Words at a position, without any that hold a sound the child is also working on. Returns the whole list when
     * filtering would leave too few words to practise. Swift's `words(in: \.initial, …)` key path is a property
     * reference here: `words(WordLists::initial, …)`.
     */
    fun words(
        list: (WordLists) -> List<WordItem>,
        avoiding: Set<SoundID>,
        minimumCount: Int = 3,
    ): List<WordItem> {
        val all = list(words)
        val kept = all.filter { word -> word.otherSounds.none { it in avoiding } }
        return if (kept.size >= minimumCount) kept else all
    }

    /**
     * The hero's chapter for one of this sound's lessons, by the lesson's id ("r-isolated") or its suffix
     * ("isolated"). Null when the content has none, and the lesson opens as it always has.
     */
    fun chapter(lessonID: String): String? {
        val prefix = "${id.rawValue}-"
        return hero.chapter(if (lessonID.startsWith(prefix)) lessonID.removePrefix(prefix) else lessonID)
    }

    /** The chapter for the lesson a session opens, when it is one of this sound's lessons. */
    fun chapter(focus: SessionFocus): String? = focus.lessonKey?.let { hero.chapter(it) }

    /** For Ț, CE and GE: the short unit said again and again at the isolated rung, "ț", "ci" or "gi". */
    val burstUnit: String? get() = if (isAffricate) graphemes.lastOrNull() else null

    /** "ț-ț-ț", "ci-ci-ci", "gi-gi-gi": the model said and shown at the isolated rung of an affricate. */
    val burstPrompt: String? get() = burstUnit?.let { unit -> List(burstsPerPrompt) { unit }.joinToString("-") }

    /**
     * What the isolated rung practises. R and L are said in repeated syllables (R is a tap in speech, never a held
     * trill); the affricates in short bursts; the other sounds are held for two, three and four seconds.
     */
    val isolatedPractice: List<Exercise>
        get() {
            if (family == SoundFamily.TongueTip) {
                return syllables.repeated.map { Exercise.Repeat(RepeatPrompt(text = it, language = Language.Ro, sound = id, level = RepeatPrompt.Level.Syllable)) }
            }
            burstPrompt?.let { return listOf(Exercise.Repeat(RepeatPrompt(text = it, language = Language.Ro, sound = id, level = RepeatPrompt.Level.Syllable))) }
            return listOf(2.0, 3.0, 4.0).map { Exercise.Sustain(target = SustainTarget.Sound(id), seconds = it, role = SustainRole.Practice) }
        }

    companion object {
        /** Three bursts, as the mouth coach asks for ("Spune scurt, de 3 ori"). */
        const val burstsPerPrompt = 3
    }
}

/** Words and pairs for the optional listening check and the voicing practice. */
@Serializable
data class ListeningContent(
    val check: List<WordPair>,
    val voicingPairs: List<WordPair>,
)
