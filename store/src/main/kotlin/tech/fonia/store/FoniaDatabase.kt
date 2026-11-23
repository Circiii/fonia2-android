package tech.fonia.store

import java.io.File
import java.io.IOException
import java.nio.file.NoSuchFileException
import java.util.UUID
import java.util.logging.Logger

/** `Logger(subsystem: "tech.fonia", category: "store")`; java.util.logging reaches logcat on Android. */
internal val storeLog: Logger = Logger.getLogger("tech.fonia.store")

/**
 * The on-device database and the folder that holds the audio files.
 *
 * Swift closes the connections when the last copy goes away; here [close] does it.
 */
class FoniaDatabase internal constructor(
    internal val writer: DatabaseWriter,
    internal val files: RecordingFiles,
) : AutoCloseable {

    override fun close() {
        writer.close()
    }

    companion object {
        /**
         * The real store, in `Fonia` inside the folder the app passes: `Context.getNoBackupFilesDir()`. That folder is
         * in credential-encrypted storage, readable after the first unlock (iOS:
         * `completeUntilFirstUserAuthentication`), and Android never backs it up (iOS: `isExcludedFromBackup`).
         */
        fun live(noBackupFilesDir: File): FoniaDatabase = open(File(noBackupFilesDir, "Fonia"))

        /** Opens or creates a store inside [directory] and runs any pending migrations. */
        fun open(directory: File): FoniaDatabase {
            val databaseFolder = File(directory, "Database")
            val recordingsFolder = File(directory, "Recordings")
            ProtectedStorage.prepareDirectory(databaseFolder)
            ProtectedStorage.prepareDirectory(recordingsFolder)

            val pool = DatabasePool(File(databaseFolder, "fonia.sqlite").path, configuration())
            try {
                Schema.migrator.migrate(pool)
            } catch (error: Throwable) {
                pool.close()
                throw error
            }
            return FoniaDatabase(pool, RecordingFiles(recordingsFolder))
        }

        /** For previews and tests: the rows live in memory, the audio files in [recordingsDirectory]. */
        fun inMemory(recordingsDirectory: File): FoniaDatabase {
            ProtectedStorage.prepareDirectory(recordingsDirectory)
            val queue = DatabaseQueue(configuration = configuration())
            try {
                Schema.migrator.migrate(queue)
            } catch (error: Throwable) {
                queue.close()
                throw error
            }
            return FoniaDatabase(queue, RecordingFiles(recordingsDirectory))
        }

        private fun configuration(): Configuration = Configuration().apply {
            prepareDatabase { db ->
                // A deleted child's rows are overwritten with zeros rather than left in free pages.
                db.execute("PRAGMA secure_delete = ON")
            }
        }
    }
}

/**
 * Every folder and audio file Fonia creates: readable after the first unlock, so WAL writes survive
 * a device lock, and never in a backup.
 *
 * Android has no per-file protection class or backup flag: both come from the folder the store lives in
 * (see [FoniaDatabase.live]). What stays from iOS's `setAttributes` is that protecting a missing item fails,
 * which is how `addRecording` refuses a take whose file was never written.
 */
internal object ProtectedStorage {
    fun prepareDirectory(directory: File) {
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Could not create the folder ${directory.path}")
        }
        protect(directory)
    }

    fun protect(file: File) {
        if (!file.exists()) throw NoSuchFileException(file.path)
    }
}

/** The `.m4a` files under `Recordings/<profile>/<recording>.m4a`. */
internal class RecordingFiles(val root: File) {

    fun url(fileName: String): File = File(root, fileName)

    fun prepareFolder(recording: Recording): File {
        val file = url(recording.fileName)
        ProtectedStorage.prepareDirectory(file.parentFile)
        return file
    }

    /**
     * Rows are already gone when this runs, so a failure leaves an orphan for the startup sweep
     * rather than failing the caller.
     */
    fun remove(recordings: List<Recording>) {
        for (recording in recordings) {
            removeLoggingFailure(url(recording.fileName))
        }
    }

    fun removeFolder(profileId: UUID) {
        removeLoggingFailure(File(root, profileId.uuidString))
    }

    fun removeEverything() {
        val items = root.listFiles() ?: throw IOException("Could not list ${root.path}")
        for (item in items) {
            if (!item.deleteRecursively()) throw IOException("Could not remove ${item.path}")
        }
    }

    /** Removes audio files no row points at, such as a take from a crash before its row was written. */
    fun removeOrphans(keeping: Set<String>) {
        val folders = root.listFiles() ?: throw IOException("Could not list ${root.path}")
        for (folder in folders) {
            // Like `contentsOfDirectory`, a plain file where a profile folder should be is an error.
            val files = folder.listFiles() ?: throw IOException("Could not list ${folder.path}")
            val orphans = files.filter { "${folder.name}/${it.name}" !in keeping }
            orphans.forEach(::removeLoggingFailure)
            if (orphans.size == files.size) {
                removeLoggingFailure(folder)
            }
        }
    }

    private fun removeLoggingFailure(file: File) {
        // A file that is already gone is not a failure (`CocoaError.fileNoSuchFile`).
        if (!file.exists()) return
        if (!file.deleteRecursively()) {
            // The path names a child's take, so it stays out of the log (`privacy: .private` on iOS).
            storeLog.severe("Could not remove a recording file")
        }
    }
}
