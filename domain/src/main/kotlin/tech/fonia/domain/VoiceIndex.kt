package tech.fonia.domain

import kotlinx.serialization.Serializable

@Serializable(with = Language.RawSerializer::class)
enum class Language(override val rawValue: String) : StringRawValue {
    Ro("ro"),
    En("en"),
    ;

    companion object {
        fun fromRawValue(rawValue: String): Language? = entries.byRawValue(rawValue)
    }

    internal object RawSerializer : StringRawValueSerializer<Language>("Language", { entries })
}

/** Recorded voices: Foni, a Romanian narrator and four English voices for listening drills. */
enum class Voice(override val rawValue: String) : StringRawValue {
    Foni("foni"),
    Ro("ro"),
    En("en"),
    En2("en2"),
    En3("en3"),
    En4("en4"),
    ;

    companion object {
        val english: List<Voice> = listOf(En, En2, En3, En4)

        fun defaultVoice(language: Language): Voice = if (language == Language.Ro) Ro else En

        /** Default voice first, then the others, so any clip beats speech synthesis. */
        internal fun fallbackOrder(language: Language): List<Voice> = if (language == Language.Ro) listOf(Ro, Foni) else english

        fun fromRawValue(rawValue: String): Voice? = entries.byRawValue(rawValue)
    }
}

/**
 * Finds the recorded clip for a text. Keys in `manifest.json` are `language|voice|normalised text`.
 *
 * [baseUrl] is the folder holding `manifest.json`, as a path relative to the [ContentSource] root (`"voice"`), and
 * [clipUrl] gives the clip's path relative to that same root (`"voice/ro/ro/….mp3"`): Android assets have no file URL,
 * so the app turns it into an asset or file location.
 */
class VoiceIndex(clips: Map<String, String>, val baseUrl: String? = null) {
    sealed class LoadError(message: String) : Exception(message) {
        class ManifestNotFound : LoadError("manifest.json not found") {
            override fun equals(other: Any?): Boolean = other is ManifestNotFound

            override fun hashCode(): Int = javaClass.hashCode()
        }
    }

    // Swift's Dictionary<String, String> finds a key by canonical equivalence; NFC keys give the same lookups.
    private val clips: Map<String, String> = clips.mapKeys { nfc(it.key) }

    /**
     * Path of the clip relative to the folder holding `manifest.json`. Falls back to any voice of the language when
     * `voice` has no clip for this text.
     */
    fun clipPath(text: String, language: Language, voice: Voice? = null): String? {
        val requested = if (voice != null) listOf(voice) else emptyList()
        for (candidate in requested + Voice.fallbackOrder(language)) {
            clips[nfc(key(text, language, candidate))]?.let { return it }
        }
        return null
    }

    /** True only when this exact voice was recorded. */
    fun hasClip(text: String, language: Language, voice: Voice): Boolean = clips[nfc(key(text, language, voice))] != null

    fun clipUrl(text: String, language: Language, voice: Voice? = null): String? {
        val base = baseUrl ?: return null
        val path = clipPath(text, language, voice) ?: return null
        return if (base.isEmpty()) path else "$base/$path"
    }

    companion object {
        /** The voice folder of the `content/` submodule; the iOS app bundle calls it `VoiceClips`. */
        val defaultSubdirectory = "voice"

        /** Loads `manifest.json` from `subdirectory`, or from the top of the source. */
        fun load(source: ContentSource, subdirectory: String = defaultSubdirectory): VoiceIndex {
            val nested = "$subdirectory/manifest.json"
            val (path, bytes) = source.read(nested)?.let { nested to it }
                ?: source.read("manifest.json")?.let { "manifest.json" to it }
                ?: throw LoadError.ManifestNotFound()
            val clips = contentJson.decodeFromString<Map<String, String>>(utf8Text(bytes))
            return VoiceIndex(clips, baseUrl = path.substringBeforeLast('/', missingDelimiterValue = ""))
        }

        /** Same result as the site's `normText`: trimmed, runs of whitespace collapsed, lowercase. */
        fun normalised(text: String): String =
            swiftLowercased(text.splitCharacters { it.isWhitespaceCharacter }.joinToString(" "))

        private fun key(text: String, language: Language, voice: Voice): String =
            "${language.rawValue}|${voice.rawValue}|${normalised(text)}"
    }
}
