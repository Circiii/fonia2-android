package tech.fonia.app.data

import tech.fonia.app.adult.AdultGate
import java.io.File

/**
 * „Șterge tot” (ADULT-SETTINGS): the database rows and the recordings (`FoniaRepository.wipeAll`, which also scrubs
 * the WAL), the settings, every file the app keeps outside the database ([extraDirs]: cache, no-backup leftovers) and
 * the in-memory calibration; the gate closes and the app returns to the first start (ON-0).
 */
class Wiper(
    private val wipeStore: suspend () -> Unit,
    private val settings: AppSettings,
    private val gate: AdultGate,
    private val extraDirs: List<File>,
    /** Files the store owns (its database) that must not be deleted from under it. */
    private val keep: Set<String> = emptySet(),
    private val afterWipe: () -> Unit = {},
) {
    suspend fun wipe() {
        wipeStore()
        for (dir in extraDirs) {
            dir.listFiles()?.forEach { f -> if (f.name !in keep) f.deleteRecursively() } // exact names only ("FoniaPilot" is not "Fonia")
        }
        settings.clear()
        gate.close()
        afterWipe()
    }
}
