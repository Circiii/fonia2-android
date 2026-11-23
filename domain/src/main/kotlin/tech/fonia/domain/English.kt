package tech.fonia.domain

import kotlinx.serialization.Serializable

/**
 * One contrast for Romanian speakers of English. The order of `EnglishContent.contrasts` is the teaching order: stress
 * first, TH last.
 */
@Serializable
data class EnglishContrast(
    val id: String,
    val kind: Kind,
    val title: String,
    /** The articulatory cue, in Romanian. */
    val cue: String,
    val picture: String,
    val pose: Viseme? = null,
    val camera: String? = null,
    val acoustic: String? = null,
    val pairs: List<EnglishPair>,
    /** Words for `Kind.ReducedVowel`. */
    val words: List<String>? = null,
    val sentences: List<String>,
    /** True when no pair has clips yet. Sessions skip these contrasts until they are recorded. */
    val needsRecording: Boolean,
) {
    @Serializable(with = Kind.RawSerializer::class)
    enum class Kind(override val rawValue: String) : StringRawValue {
        MinimalPairs("minimalPairs"),
        Stress("stress"),

        /** Practised by repeating the listed words. There is no two-choice listening drill. */
        ReducedVowel("reducedVowel"),
        ;

        companion object {
            fun fromRawValue(rawValue: String): Kind? = entries.byRawValue(rawValue)
        }

        internal object RawSerializer : StringRawValueSerializer<Kind>("EnglishContrast.Kind", { entries })
    }

    val trainingPairs: List<EnglishPair> get() = pairs.filter { it.role == EnglishPair.Role.Train && it.recorded }

    val retestPairs: List<EnglishPair> get() = pairs.filter { it.role == EnglishPair.Role.Retest && it.recorded }
}

@Serializable
data class EnglishPair(
    val a: String,
    val b: String,
    /** British IPA for each word. Stress pairs differ in stress only and have none. */
    val ipa: List<String>? = null,
    val role: Role,
    /** True when every voice the drill needs has a clip for both words. */
    val recorded: Boolean,
) {
    @Serializable(with = Role.RawSerializer::class)
    enum class Role(override val rawValue: String) : StringRawValue {
        Train("train"),

        /** Held out of training, so the retest measures new words. */
        Retest("retest"),
        ;

        companion object {
            fun fromRawValue(rawValue: String): Role? = entries.byRawValue(rawValue)
        }

        internal object RawSerializer : StringRawValueSerializer<Role>("EnglishPair.Role", { entries })
    }
}

@Serializable
data class MelodyLesson(
    val title: String,
    val picture: String,
    val lines: List<String>,
)

@Serializable
data class EnglishMelody(
    val title: String,
    val subtitle: String,
    val picture: String,
    val lessons: List<MelodyLesson>,
)

@Serializable
data class EnglishContent(
    val contrasts: List<EnglishContrast>,
    /** Optional extra for older learners. */
    val twisters: List<String>,
    val melody: EnglishMelody,
) {
    fun contrast(id: String): EnglishContrast? = contrasts.firstOrNull { canonicallyEqual(it.id, id) }
}
