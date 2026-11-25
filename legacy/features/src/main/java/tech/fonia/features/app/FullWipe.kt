package tech.fonia.features.app

import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException

/**
 * "Șterge tot" (iOS 007bfcf): everything the phone keeps goes, part by part. One part that fails doesn't stop the
 * others, and the adult hears which ones stayed, instead of a wipe that looks done and isn't.
 */
object FullWipe {
    /** One kind of data the phone keeps, named as the adult reads it, and how it goes. */
    class Part(val name: String, val wipe: suspend () -> Unit)

    /** Runs every part; returns the names of those that left something behind (empty: all gone). */
    suspend fun run(parts: List<Part>): List<String> {
        val left = ArrayList<String>()
        for (part in parts) {
            try {
                part.wipe()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (part.name !in left) left += part.name
            }
        }
        return left
    }

    /** Deletes [folder] and all it holds; throws when anything is still there. A missing folder is already gone. */
    fun deleteFolder(folder: File) {
        if (!folder.exists()) return
        folder.deleteRecursively()
        if (folder.exists()) throw IOException("$folder is still there")
    }

    /**
     * Deletes everything inside [folder] and keeps the folder, which an open store still writes into; throws when
     * anything is still there. A missing folder is already empty.
     */
    fun emptyFolder(folder: File) {
        if (!folder.exists()) return
        val items = folder.listFiles() ?: throw IOException("Could not list $folder")
        items.forEach { it.deleteRecursively() }
        val after = folder.listFiles() ?: throw IOException("Could not list $folder")
        if (after.isNotEmpty()) throw IOException("$folder still holds ${after.size} items")
    }

    /** The line under "Nu am putut șterge tot": what is still on the phone. */
    fun leftLine(left: List<String>): String =
        "Au rămas pe telefon: ${left.joinToString(", ")}. Încearcă din nou. Dacă tot nu merge, repornește telefonul și încearcă iar."
}

/**
 * The folders and settings files outside the database that "Șterge tot" empties. Built in [AppModel.live]; null in the
 * JVM tests, where every store is in memory.
 */
class LocalData(
    /** Per-profile files: voice reference and dots, quick checks, drills, reading probes, adaptive state. */
    val profileFolders: List<File>,
    /** Files made for sharing (the export for the logoped, the reading reports). */
    val exportFolders: List<File>,
    /** The pilot log's folder, for when the log could not be opened. */
    val pilotFolder: File,
    /** Clears the app's settings files; false when one could not be written. */
    val clearSettings: () -> Boolean,
    /**
     * The audio of the takes, the pinned first tries included. The store empties it with the rows; this goes again,
     * so the audio goes even when the rows could not. Emptied, not removed: the open store writes into it.
     */
    val recordingFolders: List<File> = emptyList(),
    /**
     * Copies of the data a test run worked on (`TestRun`: profiles, tries, voice and takes), removed whole. Empty while
     * the app runs on that copy.
     */
    val copyFolders: List<File> = emptyList(),
)
