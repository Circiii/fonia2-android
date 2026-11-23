package tech.fonia.domain

import java.io.File
import kotlinx.serialization.json.Json

/**
 * Where the shared content comes from: a function from a path relative to the content root to the file's bytes, or
 * null when there is no such file. Paths follow the `content/` submodule (`json/sounds.json`, `voice/manifest.json`),
 * which is also how the app lays out its assets. Stands in for `Bundle` on iOS, so loading does not depend on Android.
 *
 * In the app: `ContentSource { path -> try { assets.open(path).use { it.readBytes() } } catch (_: FileNotFoundException) { null } }`.
 */
fun interface ContentSource {
    fun read(path: String): ByteArray?

    companion object {
        /** A folder on disk laid out like the `content/` submodule, as the JVM tests use it. */
        fun directory(root: File): ContentSource = ContentSource { path ->
            File(root, path).takeIf { it.isFile }?.readBytes()
        }
    }
}

/** Everything the JSON resources hold. These files are the source of truth for the curriculum. */
class FoniaContent private constructor(
    /** In `SoundID.defaultOrder`. */
    val sounds: List<SoundDef>,
    val english: EnglishContent,
    val diction: DictionContent,
    val warmups: List<Warmup>,
    val coachPlans: List<CoachPlan>,
    val listening: ListeningContent,
    private val soundsByID: Map<SoundID, SoundDef>,
) {
    sealed class LoadError(message: String) : Exception(message) {
        data class MissingResource(val name: String) : LoadError("$name.json is missing")

        data class MissingSound(val sound: SoundID) : LoadError("sounds.json has no sound ${sound.rawValue}")
    }

    fun sound(id: SoundID): SoundDef =
        soundsByID[id] ?: throw IllegalStateException("load(from:) checks that every sound is present")

    fun warmup(id: String): Warmup? = warmups.firstOrNull { canonicallyEqual(it.id, id) }

    /** Key of a plan: the sound's raw value, or `en-th` and the like for English. */
    fun coachPlan(key: String): CoachPlan? = coachPlans.firstOrNull { canonicallyEqual(it.key, key) }

    companion object {
        /** Folder of the six JSON files under the content root. */
        val jsonFolder = "json"

        /**
         * Loads the six JSON files. Swift's `load()` read them from the module's bundle; here the caller passes the
         * source (the app's assets, or the submodule folder in tests). Decoding errors surface as
         * `kotlinx.serialization.SerializationException`, the counterpart of `DecodingError`.
         */
        fun load(source: ContentSource): FoniaContent {
            val sounds: List<SoundDef> = decode("sounds", source)
            // Dictionary(uniqueKeysWithValues:) traps on a repeated key.
            val byID = LinkedHashMap<SoundID, SoundDef>()
            for (sound in sounds) {
                check(byID.put(sound.id, sound) == null) { "Duplicate values for key: '${sound.id.rawValue}'" }
            }
            SoundID.entries.firstOrNull { byID[it] == null }?.let { throw LoadError.MissingSound(it) }
            // Arguments are evaluated in this order, so the first missing file is reported, as on iOS.
            return FoniaContent(
                sounds = SoundID.defaultOrder.mapNotNull { byID[it] },
                english = decode("english", source),
                diction = decode("diction", source),
                warmups = decode("exercises", source),
                coachPlans = decode("coach_plans", source),
                listening = decode("listening", source),
                soundsByID = byID,
            )
        }

        private inline fun <reified Value> decode(name: String, source: ContentSource): Value {
            val bytes = source.read("$jsonFolder/$name.json") ?: throw LoadError.MissingResource(name)
            return contentJson.decodeFromString<Value>(utf8Text(bytes))
        }
    }
}

/** `JSONDecoder()` with its defaults: keys are the property names, unknown keys are ignored, required keys must be there. */
internal val contentJson = Json { ignoreUnknownKeys = true }

/** UTF-8 text of a JSON file, without the byte-order mark Foundation's decoder also accepts. */
internal fun utf8Text(bytes: ByteArray): String = bytes.decodeToString().removePrefix("﻿")
