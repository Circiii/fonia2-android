package tech.fonia.features.speaking.emphasis

import android.content.res.AssetManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import tech.fonia.domain.Language
import tech.fonia.features.speaking.MarkedText
import tech.fonia.features.speaking.array
import tech.fonia.features.speaking.obj
import tech.fonia.features.speaking.string

// The practice sentences of the emphasis exercise (V2 in fonia-android-plan/03 §1.5), Fonia's own, in
// `assets/drafts/emphasis.json`. Each one comes after a question that makes one word the important one, so the reader
// knows why that word and not another. A draft until a teacher has read it.

data class EmphasisItem(
    val id: String,
    val language: Language,
    /** The question the sentence answers: "Pleacă trenul mâine?". */
    val context: String?,
    val text: MarkedText,
)

data class EmphasisContent(
    val version: String,
    val status: String,
    val approvedBy: String?,
    val notice: String,
    val rule: String,
    val items: List<EmphasisItem>,
) {
    /** True until someone's name is in `approvedBy`. */
    val isDraft: Boolean get() = approvedBy.isNullOrBlank()

    fun items(language: Language): List<EmphasisItem> = items.filter { it.language == language }

    companion object {
        const val ASSET = "drafts/emphasis.json"

        /** Reads the bundled file; null when it is missing or broken, and the exercise then says so. */
        fun load(assets: AssetManager): EmphasisContent? = runCatching {
            parse(assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() })
        }.getOrNull()

        fun parse(json: String): EmphasisContent {
            val root = Json.parseToJsonElement(json).jsonObject
            val items = root.array("sets").flatMap { node ->
                val set = node.jsonObject
                val language = when (set.string("language")) {
                    "ro" -> Language.Ro
                    "en" -> Language.En
                    else -> error("Unknown language ${set.string("language")}")
                }
                set.array("items").map { item ->
                    val obj = item.jsonObject
                    val text = MarkedText.parse(obj.string("text"))
                    require(text.keyIndices.isNotEmpty()) { "${obj.string("id")} has no key word" }
                    EmphasisItem(
                        id = obj.string("id"),
                        language = language,
                        context = (obj["context"] as? JsonPrimitive)?.contentOrNull,
                        text = text,
                    )
                }
            }
            return EmphasisContent(
                version = root.string("version"),
                status = root.string("status"),
                approvedBy = (root["approvedBy"] as? JsonPrimitive)?.contentOrNull,
                notice = root.string("notice"),
                rule = root.obj("sources").string("rule"),
                items = items,
            )
        }
    }
}
