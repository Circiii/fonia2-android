package tech.fonia.lessons

import java.io.File
import kotlinx.serialization.Serializable

/**
 * spec/content/voice/index.json: voice key -> clip file (relative to the voice folder). Keys are the `voice`,
 * `voiceA`/`voiceB`, `promptVoice`, `lineVoices` values of the content and the keys of foni_lines.json; the value
 * "human-needed" (isolated sounds, syllables, Foni's mixed-up words, Q9) never has a synthetic clip.
 * On Android the folder is bundled as assets: `VoiceIndex.parse(assets.open("content/voice/index.json")…)`, then
 * play `assets.openFd("content/voice/" + index.file(key))`.
 */
class VoiceIndex(val voice: String, val clips: Map<String, VoiceClip>) {

    /** The clip file for a key, or null when there is none (human recording still needed, or not generated). */
    fun file(key: String?): String? = if (key == null || key == HUMAN_NEEDED) null else clips[key]?.takeIf { it.checked }?.file

    fun has(key: String?): Boolean = file(key) != null

    companion object {
        const val HUMAN_NEEDED = "human-needed"

        fun parse(text: String): VoiceIndex {
            val file = LessonContent.json.decodeFromString<VoiceIndexFile>(text)
            return VoiceIndex(file.voice, file.clips)
        }

        fun fromDirectory(directory: File): VoiceIndex = parse(File(directory, "index.json").readText(Charsets.UTF_8))
    }
}

@Serializable
data class VoiceClip(
    val file: String,
    val text: String,
    val kind: String,
    val voice: String,
    val checked: Boolean,
    val seconds: Double? = null,
)

@Serializable
internal data class VoiceIndexFile(
    val schema: Int,
    val voice: String,
    val voiceId: String? = null,
    val model: String? = null,
    val clips: Map<String, VoiceClip>,
)
