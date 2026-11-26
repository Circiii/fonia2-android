package tech.fonia.features.probe

import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import tech.fonia.domain.NewWordsRecord
import tech.fonia.domain.ProbeItem
import tech.fonia.domain.ProbeLevel
import tech.fonia.domain.ProbeMark

/**
 * The „Cuvinte noi” probes of every profile, oldest first, and the sounds whose probes moved on to sentences: one
 * small JSON file per profile beside the database and outside its schema, as the quick checks are:
 * `noBackupFilesDir/Fonia/Probes/<profile>.json`, `{"probes":[…],"sentences":["s"]}`, keys sorted and dates in
 * milliseconds as iOS writes them. Never backed up (`noBackupFilesDir`, and the app allows no backup). `keepOnly` runs
 * whenever the list of profiles changes, and "Șterge tot" calls `removeAll`.
 */
class ProbeStore(private val directory: File) {
    data class Stored(
        val probes: List<NewWordsRecord> = emptyList(),
        /** Sounds whose probes are sentences now, because the adult said yes to them. */
        val sentences: List<String> = emptyList(),
    )

    /** One writer at a time, as the iOS actor. */
    private val mutex = Mutex()

    suspend fun stored(profileID: UUID): Stored = locked { read(profileID) }

    suspend fun records(profileID: UUID): List<NewWordsRecord> = stored(profileID).probes

    /** Adds a probe, or replaces the one with the same id (the adult's marks come after it was first kept). */
    suspend fun save(record: NewWordsRecord, profileID: UUID) = locked {
        val all = read(profileID)
        val index = all.probes.indexOfFirst { it.id == record.id }
        val probes = if (index >= 0) {
            all.probes.toMutableList().also { it[index] = record }
        } else {
            (all.probes + record).takeLast(kept)
        }
        write(all.copy(probes = probes), profileID)
    }

    /** The adult said yes to sentences for [sound]: its next probes ask sentences. */
    suspend fun moveToSentences(sound: String, profileID: UUID) = locked {
        val all = read(profileID)
        if (sound in all.sentences) return@locked
        write(all.copy(sentences = all.sentences + sound), profileID)
    }

    /** Deletes the files of the profiles that are gone. */
    suspend fun keepOnly(profileIDs: Set<UUID>) = locked {
        directory.listFiles()?.forEach { file ->
            val id = runCatching { UUID.fromString(file.nameWithoutExtension) }.getOrNull()
            if (id == null || id !in profileIDs) file.delete()
        }
        Unit
    }

    suspend fun removeAll() = keepOnly(emptySet())

    private suspend fun <T> locked(block: () -> T): T = mutex.withLock { withContext(Dispatchers.IO) { block() } }

    private fun file(profileID: UUID) = File(directory, "$profileID.json")

    private fun read(profileID: UUID): Stored {
        val file = file(profileID)
        if (!file.isFile) return Stored()
        return runCatching { decode(file.readText()) }.getOrDefault(Stored())
    }

    private fun write(stored: Stored, profileID: UUID) {
        directory.mkdirs()
        val target = file(profileID)
        val temporary = File(directory, "${target.name}.tmp")
        temporary.writeText(encode(stored))
        if (!temporary.renameTo(target)) {
            target.delete()
            temporary.renameTo(target)
        }
    }

    companion object {
        /** The last probes kept per profile. */
        const val kept = 120

        /**
         * `JSONEncoder` with `.sortedKeys`, `.withoutEscapingSlashes` and `.millisecondsSince1970`, as iOS writes the
         * file: keys in order, an unmarked or unsaid item without the key, ids in capitals.
         */
        fun encode(stored: Stored): String = buildJsonObject {
            put(
                "probes",
                buildJsonArray {
                    for (record in stored.probes) {
                        add(
                            buildJsonObject {
                                put("date", record.date.toEpochMilli())
                                put("finished", record.finished)
                                put("id", record.id.toString().uppercase(Locale.ROOT))
                                put(
                                    "items",
                                    buildJsonArray {
                                        for (item in record.items) {
                                            add(
                                                buildJsonObject {
                                                    item.adult?.let { put("adult", it.rawValue) }
                                                    item.phone?.let { put("phone", it) }
                                                    put("text", item.text)
                                                },
                                            )
                                        }
                                    },
                                )
                                put("level", record.level.rawValue)
                                put("sound", record.sound)
                            },
                        )
                    }
                },
            )
            put("sentences", buildJsonArray { stored.sentences.forEach { add(JsonPrimitive(it)) } })
        }.toString()

        /** Fails on a file that isn't one, as `JSONDecoder` does; the store then starts empty. */
        fun decode(text: String): Stored {
            val root = Json.parseToJsonElement(text).jsonObject
            val probes = root.getValue("probes").jsonArray.map { element ->
                val record = element.jsonObject
                NewWordsRecord(
                    id = UUID.fromString(record.getValue("id").jsonPrimitive.content),
                    date = Instant.ofEpochMilli(record.getValue("date").jsonPrimitive.long),
                    sound = record.getValue("sound").jsonPrimitive.content,
                    level = ProbeLevel.fromRawValue(record.getValue("level").jsonPrimitive.content) ?: error("level"),
                    items = record.getValue("items").jsonArray.map { decodeItem(it.jsonObject) },
                    finished = record.getValue("finished").jsonPrimitive.boolean,
                )
            }
            val sentences = root.getValue("sentences").jsonArray.map { it.jsonPrimitive.content }
            return Stored(probes, sentences)
        }

        private fun decodeItem(item: JsonObject): ProbeItem = ProbeItem(
            text = item.getValue("text").jsonPrimitive.content,
            phone = item["phone"]?.jsonPrimitive?.contentOrNull,
            adult = item["adult"]?.jsonPrimitive?.contentOrNull?.let { ProbeMark.fromRawValue(it) ?: error("adult") },
        )
    }
}
