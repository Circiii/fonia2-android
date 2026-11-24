package tech.fonia.features.diction

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import tech.fonia.domain.SoundID
import java.io.File
import java.util.UUID

/** The best share of good takes per drill of each sound (a small file per profile beside the database). */
interface DrillStore {
    suspend fun best(profileID: UUID, sound: SoundID): Map<Int, Double>

    suspend fun record(profileID: UUID, sound: SoundID, index: Int, share: Double)
}

class MemoryDrillStore : DrillStore {
    private val all = HashMap<Pair<UUID, SoundID>, Map<Int, Double>>()

    override suspend fun best(profileID: UUID, sound: SoundID): Map<Int, Double> = all[profileID to sound] ?: emptyMap()

    override suspend fun record(profileID: UUID, sound: SoundID, index: Int, share: Double) {
        val now = best(profileID, sound)
        all[profileID to sound] = now + (index to maxOf(share, now[index] ?: 0.0))
    }
}

/** `noBackupFilesDir/Fonia/Diction/<profile>.json`: `{"s":{"0":1.0,"1":0.67}}`. */
class FileDrillStore(private val directory: File) : DrillStore {
    private val mutex = Mutex()

    private suspend fun <T> locked(block: () -> T): T = mutex.withLock { withContext(Dispatchers.IO) { block() } }

    private fun file(profileID: UUID) = File(directory, "$profileID.json")

    private fun read(profileID: UUID): Map<String, Map<Int, Double>> = runCatching {
        Json.parseToJsonElement(file(profileID).readText()).jsonObject.mapValues { (_, value) ->
            value.jsonObject.mapNotNull { (k, v) -> k.toIntOrNull()?.let { i -> v.jsonPrimitive.doubleOrNull?.let { i to it } } }.toMap()
        }
    }.getOrDefault(emptyMap())

    override suspend fun best(profileID: UUID, sound: SoundID): Map<Int, Double> = locked { read(profileID)[sound.rawValue] ?: emptyMap() }

    override suspend fun record(profileID: UUID, sound: SoundID, index: Int, share: Double) = locked {
        val all = read(profileID).toMutableMap()
        val now = all[sound.rawValue] ?: emptyMap()
        all[sound.rawValue] = now + (index to maxOf(share, now[index] ?: 0.0))
        directory.mkdirs()
        val text = buildJsonObject {
            for ((key, drills) in all) put(key, buildJsonObject { for ((i, v) in drills) put("$i", v) })
        }.toString()
        val temporary = File(directory, "$profileID.json.tmp")
        temporary.writeText(text)
        if (!temporary.renameTo(file(profileID))) {
            file(profileID).delete()
            temporary.renameTo(file(profileID))
        }
        Unit
    }
}
