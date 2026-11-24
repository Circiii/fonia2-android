package tech.fonia.features.check

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import tech.fonia.domain.SoundID
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * Where the quick checks are kept: a small file per profile beside the database (no schema change), oldest first.
 * Behind an interface so the JVM tests keep it in memory.
 */
interface CheckStore {
    suspend fun history(profileID: UUID): List<CheckRecord>

    suspend fun add(profileID: UUID, record: CheckRecord)

    suspend fun keepOnly(profileIDs: Set<UUID>) {}
}

class MemoryCheckStore : CheckStore {
    private val all = HashMap<UUID, List<CheckRecord>>()

    override suspend fun history(profileID: UUID): List<CheckRecord> = all[profileID] ?: emptyList()

    override suspend fun add(profileID: UUID, record: CheckRecord) {
        all[profileID] = history(profileID) + record
    }
}

/** `noBackupFilesDir/Fonia/Check/<profile>.json`: `{"checks":[{"date":ms,"results":{"s":{"v":"good","c":0.8}}}]}`. */
class FileCheckStore(private val directory: File) : CheckStore {
    private val mutex = Mutex()

    override suspend fun history(profileID: UUID): List<CheckRecord> = locked { read(profileID) }

    override suspend fun add(profileID: UUID, record: CheckRecord) = locked {
        write(profileID, (read(profileID) + record).takeLast(KEEP))
    }

    override suspend fun keepOnly(profileIDs: Set<UUID>) = locked {
        directory.listFiles()?.forEach { file ->
            val id = runCatching { UUID.fromString(file.nameWithoutExtension) }.getOrNull()
            if (id != null && id !in profileIDs) file.delete()
        }
        Unit
    }

    private suspend fun <T> locked(block: () -> T): T = mutex.withLock { withContext(Dispatchers.IO) { block() } }

    private fun file(profileID: UUID) = File(directory, "$profileID.json")

    private fun read(profileID: UUID): List<CheckRecord> {
        val file = file(profileID)
        if (!file.isFile) return emptyList()
        return runCatching { decode(file.readText()) }.getOrDefault(emptyList())
    }

    private fun write(profileID: UUID, records: List<CheckRecord>) {
        directory.mkdirs()
        val target = file(profileID)
        val temporary = File(directory, "${target.name}.tmp")
        temporary.writeText(encode(records))
        if (!temporary.renameTo(target)) {
            target.delete()
            temporary.renameTo(target)
        }
    }

    companion object {
        private const val KEEP = 50

        fun encode(records: List<CheckRecord>): String = buildJsonObject {
            put(
                "checks",
                buildJsonArray {
                    for (record in records) {
                        add(
                            buildJsonObject {
                                put("date", record.date.toEpochMilli())
                                put(
                                    "results",
                                    buildJsonObject {
                                        for ((sound, check) in record.results) {
                                            put(sound.rawValue, buildJsonObject {
                                                put("v", check.verdict.rawValue)
                                                put("c", check.confidence)
                                            })
                                        }
                                    },
                                )
                            },
                        )
                    }
                },
            )
        }.toString()

        fun decode(text: String): List<CheckRecord> {
            val root = Json.parseToJsonElement(text).jsonObject
            return root["checks"]?.jsonArray?.mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val date = item["date"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                val results = item["results"]?.jsonObject?.mapNotNull { (key, value) ->
                    val sound = SoundID.fromRawValue(key) ?: return@mapNotNull null
                    val body = value.jsonObject
                    val verdict = CheckVerdict.fromRawValue(body["v"]?.jsonPrimitive?.contentOrNull) ?: return@mapNotNull null
                    sound to SoundCheck(verdict, body["c"]?.jsonPrimitive?.floatOrNull ?: 0f)
                }?.toMap() ?: emptyMap()
                CheckRecord(Instant.ofEpochMilli(date), results)
            } ?: emptyList()
        }
    }
}
