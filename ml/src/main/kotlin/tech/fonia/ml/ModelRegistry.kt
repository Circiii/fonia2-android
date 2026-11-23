package tech.fonia.ml

import android.content.res.AssetManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** One model of `spec/models/manifest.json` (copied into the APK as `assets/models/manifest.json`). */
data class ModelEntry(
    val id: String,
    val name: String,
    val version: String,
    val runtime: String,
    /** Path inside the APK's assets. */
    val asset: String,
    val bytes: Long,
    val sha256: String,
    val license: String,
    val attribution: String,
) {
    val modelVersion: ModelVersion get() = ModelVersion(id, version, sha256, runtime)
}

/** The models the APK carries, from the manifest the build copied next to them. */
class ModelRegistry(val entries: List<ModelEntry>) {
    fun entry(id: String): ModelEntry = entries.firstOrNull { it.id == id } ?: error("no model '$id' in models/manifest.json")

    /** `zipa@dfe87c976626;…`: saved in `attempt.modelVersions` with the thresholds and fusion versions. */
    fun versions(): String = entries.joinToString(";") { it.modelVersion.tag }

    companion object {
        const val MANIFEST_ASSET = "models/manifest.json"
        const val ZIPA = "zipa"

        fun parse(text: String): ModelRegistry {
            val root = Json.parseToJsonElement(text).jsonObject
            val models = root.getValue("models").jsonArray.map { it.jsonObject }
            return ModelRegistry(models.map { m ->
                fun s(key: String) = m.getValue(key).jsonPrimitive.content
                ModelEntry(
                    id = s("id"), name = s("name"), version = s("version"), runtime = s("runtime"), asset = s("asset"),
                    bytes = m.getValue("bytes").jsonPrimitive.long, sha256 = s("sha256"), license = s("license"),
                    attribution = (m["attribution"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "",
                )
            })
        }

        fun load(assets: AssetManager): ModelRegistry = parse(assets.open(MANIFEST_ASSET).use { it.readBytes().toString(Charsets.UTF_8) })

        @Suppress("unused")
        private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content
    }
}
