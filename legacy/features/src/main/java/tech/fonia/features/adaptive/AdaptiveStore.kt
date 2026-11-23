package tech.fonia.features.adaptive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import tech.fonia.domain.adaptive.AdaptiveCodec
import tech.fonia.domain.adaptive.AdaptiveProfile
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * The adaptive pilot's state (docs/PLAN.md §7.4), one small JSON file per profile beside the app's database and outside
 * its iOS schema: `noBackupFilesDir/Fonia/Adaptive/<profile>.json`, as the reading probes do. The folder is never
 * backed up and only the app reads it. [keepOnly] runs whenever the list of profiles changes, so deleting a profile or
 * wiping everything takes its file too. A profile with no file is off: nothing changes for it.
 *
 * Writes keep the order they were asked in: the lock is taken before leaving the caller's thread.
 */
class AdaptiveStore(private val directory: File) {
    private val mutex = Mutex()

    suspend fun load(profileID: UUID): AdaptiveProfile = locked { read(profileID) }

    /** True when the profile has a file: it was in the pilot at some point. */
    suspend fun exists(profileID: UUID): Boolean = locked { file(profileID).isFile }

    /** Reads, changes and writes one profile's file in one step. Returns what was written. */
    suspend fun update(profileID: UUID, change: (AdaptiveProfile) -> AdaptiveProfile): AdaptiveProfile = locked {
        val next = change(read(profileID))
        write(profileID, next)
        next
    }

    /** The summary for the logoped's export, or null for a profile never in the pilot. */
    suspend fun summary(profileID: UUID): JsonObject? = locked {
        if (file(profileID).isFile) AdaptiveCodec.summary(read(profileID)) else null
    }

    /** Deletes the files of the profiles that are gone. */
    suspend fun keepOnly(profileIDs: Set<UUID>) = locked {
        val files = directory.listFiles() ?: return@locked
        for (file in files) {
            val id = runCatching { UUID.fromString(file.name.removeSuffix(".tmp").removeSuffix(".json")) }.getOrNull()
            if (id == null || id !in profileIDs) file.delete()
        }
    }

    private suspend fun <T> locked(block: () -> T): T = mutex.withLock { withContext(Dispatchers.IO) { block() } }

    private fun file(profileID: UUID) = File(directory, "$profileID.json")

    private fun read(profileID: UUID): AdaptiveProfile {
        val file = file(profileID)
        if (!file.isFile) return AdaptiveProfile()
        // A file that can't be read is treated as off; the next write replaces it.
        return runCatching { AdaptiveCodec.decode(file.readText(Charsets.UTF_8)) }.getOrElse { AdaptiveProfile() }
    }

    private fun write(profileID: UUID, profile: AdaptiveProfile) {
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) throw IOException("Could not create the adaptive folder")
        val target = file(profileID)
        val temporary = File(directory, "$profileID.json.tmp")
        temporary.writeText(AdaptiveCodec.encode(profile), Charsets.UTF_8)
        try {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
