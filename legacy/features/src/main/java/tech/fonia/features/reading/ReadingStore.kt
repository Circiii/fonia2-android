package tech.fonia.features.reading

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * How the words are set (C9): Standard, then two looser settings. Letter spacing only ever grows together with word
 * spacing, since wider letters alone slow reading down (A §6.1 [55]); lines stay short, at most 5 to 8 words. The
 * app has Andika only, so "Lat" spreads the letters the way Lexend's wider cuts do, instead of changing the face.
 */
enum class ReadingPreset(
    val rawValue: String,
    val title: String,
    val detail: String,
    /** Extra space between letters, in em. */
    val letterSpacing: Float,
    /** Space between words, in em. */
    val wordGap: Float,
    /** Distance from one line to the next, as a multiple of the type size. */
    val lineHeight: Float,
    val maxWordsPerLine: Int,
) {
    Standard("standard", "Standard", "Ca în carte.", letterSpacing = 0.01f, wordGap = 0.45f, lineHeight = 1.45f, maxWordsPerLine = 8),
    Airy("aerisit", "Aerisit", "Mai mult loc între litere, cuvinte și rânduri.", letterSpacing = 0.09f, wordGap = 0.95f, lineHeight = 1.95f, maxWordsPerLine = 6),
    Wide("lat", "Lat", "Literele mult mai rare, rânduri scurte.", letterSpacing = 0.18f, wordGap = 1.2f, lineHeight = 1.6f, maxWordsPerLine = 5),
    ;

    companion object {
        fun fromRawValue(rawValue: String?): ReadingPreset? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

/** What started the clock: the adult's tap, or the first voiced sound the microphone heard. */
enum class StartSource(val rawValue: String) {
    Tap("tap"),
    Voice("voice"),
    ;

    companion object {
        fun fromRawValue(rawValue: String?): StartSource? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

/**
 * One probe as it is kept: no audio and no name, only what the numbers are made from (03 §4.1, §4.3), so a later
 * version of the formulas can recompute them. [errorIndices] are the words marked as read differently.
 */
data class ProbeRecord(
    val id: UUID = UUID.randomUUID(),
    val takenAt: Instant,
    /** The calendar day it was taken, `YYYY-MM-DD`, as the store's sessions keep it. */
    val localDay: String,
    val probeID: String,
    val kind: ProbeKind,
    val set: String,
    val contentVersion: String,
    val preset: ReadingPreset,
    val startedBy: StartSource,
    val seconds: Double,
    val totalWords: Int,
    val wordsRead: Int,
    val errorIndices: List<Int>,
    val syllablesRead: Int,
    val syllablesCorrect: Int,
) {
    val score: ProbeScore get() = ProbeScore(wordsRead, errorIndices.size, seconds, syllablesRead, syllablesCorrect)

    val day: LocalDate get() = LocalDate.parse(localDay)
}

/** The school year the report names (Anexa 1 asks for it). */
enum class SchoolGrade(val rawValue: String, val short: String, val title: String) {
    Preparatory("pregatitoare", "Preg.", "clasa pregătitoare"),
    First("1", "I", "clasa I"),
    Second("2", "a II-a", "clasa a II-a"),
    Third("3", "a III-a", "clasa a III-a"),
    Fourth("4", "a IV-a", "clasa a IV-a"),
    ;

    companion object {
        fun fromRawValue(rawValue: String?): SchoolGrade? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

/** What the adult typed for the report last time, so it is there the next time. The child's name is never in it. */
data class ReportDetails(val pseudonym: String = "", val age: String = "", val grade: SchoolGrade? = null)

/** Everything the reading pages keep for one child. */
data class ReadingProfileData(
    val preset: ReadingPreset = ReadingPreset.Standard,
    val report: ReportDetails = ReportDetails(),
    val probes: List<ProbeRecord> = emptyList(),
) {
    /** Oldest first, as a curve reads. */
    fun probes(kind: ProbeKind): List<ProbeRecord> = probes.filter { it.kind == kind }.sortedBy { it.takenAt }

    val latest: ProbeRecord? get() = probes.maxByOrNull { it.takenAt }
}

/**
 * The reading probes of every child, one small JSON file per profile, beside the app's database and outside its iOS
 * schema: `noBackupFilesDir/Fonia/Reading/<profile>.json`. That folder is never backed up and is readable only by
 * the app. [keepOnly] runs whenever the list of profiles changes, so deleting a profile or wiping everything takes
 * its probes too.
 */
class ReadingStore(private val directory: File) {
    private val mutex = Mutex()

    suspend fun load(profileID: UUID): ReadingProfileData = io { read(profileID) }

    /** Reads, changes and writes one profile's file in one step. Returns what was written. */
    suspend fun update(profileID: UUID, change: (ReadingProfileData) -> ReadingProfileData): ReadingProfileData = io {
        val next = change(read(profileID))
        write(profileID, next)
        next
    }

    suspend fun add(profileID: UUID, record: ProbeRecord): ReadingProfileData =
        update(profileID) { it.copy(probes = it.probes + record) }

    suspend fun remove(profileID: UUID, recordID: UUID): ReadingProfileData =
        update(profileID) { data -> data.copy(probes = data.probes.filterNot { it.id == recordID }) }

    /** Deletes the files of the profiles that are gone. */
    suspend fun keepOnly(profileIDs: Set<UUID>) = io {
        val files = directory.listFiles() ?: return@io
        for (file in files) {
            val id = runCatching { UUID.fromString(file.name.removeSuffix(".json").removeSuffix(".tmp")) }.getOrNull()
            if (id == null || id !in profileIDs) file.delete()
        }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { mutex.withLock { block() } }

    private fun file(profileID: UUID) = File(directory, "$profileID.json")

    private fun read(profileID: UUID): ReadingProfileData {
        val file = file(profileID)
        if (!file.isFile) return ReadingProfileData()
        // A file that can't be read is treated as empty; the next write replaces it.
        return runCatching { decode(file.readText(Charsets.UTF_8)) }.getOrElse { ReadingProfileData() }
    }

    private fun write(profileID: UUID, data: ReadingProfileData) {
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Could not create the reading folder")
        }
        val target = file(profileID)
        val temporary = File(directory, "$profileID.json.tmp")
        temporary.writeText(encode(data), Charsets.UTF_8)
        try {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        private const val FORMAT = 1

        fun encode(data: ReadingProfileData): String = buildJsonObject {
            put("format", FORMAT)
            put("preset", data.preset.rawValue)
            put(
                "report",
                buildJsonObject {
                    put("pseudonym", data.report.pseudonym)
                    put("age", data.report.age)
                    data.report.grade?.let { put("grade", it.rawValue) }
                },
            )
            put("probes", buildJsonArray { data.probes.forEach { add(encode(it)) } })
        }.toString()

        private fun encode(record: ProbeRecord): JsonObject = buildJsonObject {
            put("id", record.id.toString())
            put("takenAt", record.takenAt.toString())
            put("localDay", record.localDay)
            put("probeID", record.probeID)
            put("kind", record.kind.rawValue)
            put("set", record.set)
            put("contentVersion", record.contentVersion)
            put("preset", record.preset.rawValue)
            put("startedBy", record.startedBy.rawValue)
            put("seconds", record.seconds)
            put("totalWords", record.totalWords)
            put("wordsRead", record.wordsRead)
            put("errorIndices", buildJsonArray { record.errorIndices.forEach { add(JsonPrimitive(it)) } })
            put("syllablesRead", record.syllablesRead)
            put("syllablesCorrect", record.syllablesCorrect)
        }

        fun decode(text: String): ReadingProfileData {
            val root = Json.parseToJsonElement(text).jsonObject
            val report = root["report"] as? JsonObject
            return ReadingProfileData(
                preset = ReadingPreset.fromRawValue(root.text("preset")) ?: ReadingPreset.Standard,
                report = ReportDetails(
                    pseudonym = report?.text("pseudonym") ?: "",
                    age = report?.text("age") ?: "",
                    grade = SchoolGrade.fromRawValue(report?.text("grade")),
                ),
                // One record that can't be read is skipped, not the whole history.
                probes = (root["probes"] as? JsonArray)?.mapNotNull { runCatching { decodeRecord(it.jsonObject) }.getOrNull() } ?: emptyList(),
            )
        }

        private fun decodeRecord(node: JsonObject): ProbeRecord = ProbeRecord(
            id = UUID.fromString(node.text("id") ?: error("id")),
            takenAt = Instant.parse(node.text("takenAt") ?: error("takenAt")),
            localDay = node.text("localDay") ?: error("localDay"),
            probeID = node.text("probeID") ?: error("probeID"),
            kind = ProbeKind.fromRawValue(node.text("kind") ?: "") ?: error("kind"),
            set = node.text("set") ?: error("set"),
            contentVersion = node.text("contentVersion") ?: "",
            preset = ReadingPreset.fromRawValue(node.text("preset")) ?: ReadingPreset.Standard,
            startedBy = StartSource.fromRawValue(node.text("startedBy")) ?: StartSource.Tap,
            seconds = node.number("seconds"),
            totalWords = node.int("totalWords"),
            wordsRead = node.int("wordsRead"),
            errorIndices = node["errorIndices"]?.jsonArray?.map { it.jsonPrimitive.intOrNull ?: error("errorIndices") } ?: emptyList(),
            syllablesRead = node.int("syllablesRead"),
            syllablesCorrect = node.int("syllablesCorrect"),
        )

        private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

        private fun JsonObject.number(key: String): Double = (this[key] as? JsonPrimitive)?.doubleOrNull ?: error(key)

        private fun JsonObject.int(key: String): Int = (this[key] as? JsonPrimitive)?.intOrNull ?: error(key)
    }
}
