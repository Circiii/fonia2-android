package tech.fonia.lessons

import java.io.File
import kotlinx.serialization.json.Json
import tech.fonia.model.Rung
import tech.fonia.model.SoundId

/**
 * Reads one file of the content by its path relative to `spec/content/v2` ("sounds.json", "sounds/s.json"…).
 * On Android: `ContentSource { path -> assets.open("content/v2/$path").bufferedReader().readText() }`.
 */
fun interface ContentSource {
    fun read(path: String): String
}

/** Thrown when the content does not load or does not fit the schema the builder needs. */
class ContentException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** All of the v2 content, loaded and checked once; the lesson builder only reads it. */
class LessonContent internal constructor(
    val index: SoundsIndex,
    val sounds: Map<SoundId, SoundContent>,
    val twisters: List<Twister>,
    val lines: List<FoniLine>,
    val parent: ParentTexts,
    val lesson: LessonSpec,
) {
    val contentVersion: String get() = index.contentVersion

    private val lineByKey: Map<String, FoniLine> = lines.associateBy { it.key }
    private val itemsBySoundRung: Map<Pair<SoundId, Rung>, List<ContentItem>> =
        sounds.flatMap { (sound, data) -> data.items.map { (sound to rungOf(it.rung)) to it } }
            .groupBy({ it.first }, { it.second })
    private val itemById: Map<String, ContentItem> = sounds.values.flatMap { it.items }.associateBy { it.id }

    fun sound(sound: SoundId): SoundContent = sounds.getValue(sound)

    /** The items of one rung, in content order (word lists are already ordered by rising difficulty). */
    fun items(sound: SoundId, rung: Rung): List<ContentItem> = itemsBySoundRung[sound to rung].orEmpty()

    fun item(id: String): ContentItem? = itemById[id]

    /** Twisters that practise [sound], easiest first, content order inside a difficulty. */
    fun twisters(sound: SoundId): List<Twister> =
        twisters.filter { sound.raw in it.sounds }.sortedBy { it.difficulty }

    fun line(key: String): FoniLine? = lineByKey[key]

    fun chapterKey(sound: SoundId, rung: Rung): String = sound(sound).hero.chapters.getValue(rung.raw).key

    /** The parent card for a rung (each rung belongs to exactly one of the six). */
    fun parentCard(rung: Rung): ParentCard = parent.cards.first { rung.raw in it.rungs }

    companion object {
        internal val json = Json { ignoreUnknownKeys = true }

        fun load(source: ContentSource): LessonContent {
            val index = decode<SoundsIndex>(source, "sounds.json")
            if (index.schema != 2) throw ContentException("sounds.json: schema ${index.schema}, expected 2")
            val sounds = index.sounds.associate { entry ->
                val sound = SoundId.of(entry.id) ?: throw ContentException("sounds.json: unknown sound '${entry.id}'")
                sound to decode<SoundContent>(source, entry.file)
            }
            val missing = SoundId.entries.filter { it !in sounds }
            if (missing.isNotEmpty()) throw ContentException("sounds.json lacks ${missing.map { it.raw }}")
            val content = LessonContent(
                index = index,
                sounds = sounds,
                twisters = decode<TwistersFile>(source, "twisters.json").twisters,
                lines = decode<FoniLinesFile>(source, "foni_lines.json").lines,
                parent = decode<ParentTexts>(source, "parent.json"),
                lesson = decode<LessonSpec>(source, "lesson.json"),
            )
            content.validate()
            return content
        }

        fun fromDirectory(directory: File): LessonContent =
            load { path ->
                val file = File(directory, path)
                if (!file.isFile) throw ContentException("missing content file ${file.path}")
                file.readText(Charsets.UTF_8)
            }

        fun fromStrings(files: Map<String, String>): LessonContent =
            load { path -> files[path] ?: throw ContentException("missing content file $path") }

        private inline fun <reified T> decode(source: ContentSource, path: String): T =
            try {
                json.decodeFromString<T>(source.read(path))
            } catch (error: ContentException) {
                throw error
            } catch (error: Exception) {
                throw ContentException("$path: ${error.message}", error)
            }

        internal fun rungOf(raw: String): Rung = Rung.of(raw) ?: throw ContentException("unknown rung '$raw'")
    }

    private fun validate() {
        for ((sound, data) in sounds) {
            if (data.sound != sound.raw) throw ContentException("${data.slug}: sound '${data.sound}' != '${sound.raw}'")
            val chapters = data.hero.chapters.keys
            val noChapter = Rung.entries.filter { it.raw !in chapters }
            if (noChapter.isNotEmpty()) throw ContentException("${data.slug}: no chapter line for ${noChapter.map { it.raw }}")
            data.items.forEach { rungOf(it.rung) }
            if (data.holdMode !in setOf("sustain", "burst")) throw ContentException("${data.slug}: holdMode ${data.holdMode}")
        }
        val missingBlocks = Rung.entries.filter { it.raw !in lesson.blocks }
        if (missingBlocks.isNotEmpty()) throw ContentException("lesson.json: no blocks for ${missingBlocks.map { it.raw }}")
        for (level in AdaptLevel.entries) {
            if (level.raw !in lesson.adaptation.levels) throw ContentException("lesson.json: no adaptation level ${level.raw}")
        }
        val ids = sounds.values.flatMap { d -> d.items.map { it.id } } + twisters.map { it.id }
        val duplicate = ids.groupBy { it }.filterValues { it.size > 1 }.keys
        if (duplicate.isNotEmpty()) throw ContentException("duplicate item ids $duplicate")
    }
}
