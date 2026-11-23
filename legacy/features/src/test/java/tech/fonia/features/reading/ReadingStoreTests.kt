package tech.fonia.features.reading

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.util.UUID

/** The probes' own small store: what it keeps, and that a deleted profile takes its probes along. */
class ReadingStoreTests {
    @get:Rule
    val folder = TemporaryFolder()

    private fun record(kind: ProbeKind = ProbeKind.Mono, day: String = "2026-11-06", read: Int = 40, errors: List<Int> = listOf(2, 9)) = ProbeRecord(
        takenAt = Instant.parse("${day}T10:15:30.250Z"),
        localDay = day,
        probeID = "A-${kind.rawValue}",
        kind = kind,
        set = "A",
        contentVersion = "ciorna-2026-11-06",
        preset = ReadingPreset.Airy,
        startedBy = StartSource.Voice,
        seconds = 60.0,
        totalWords = 60,
        wordsRead = read,
        errorIndices = errors,
        syllablesRead = read,
        syllablesCorrect = read - errors.size,
    )

    @Test
    fun aProbeComesBackAsItWasKept() = runBlocking {
        val store = ReadingStore(folder.newFolder("Reading"))
        val child = UUID.randomUUID()
        val kept = record()
        store.add(child, kept)
        val again = ReadingStore(File(folder.root, "Reading")).load(child)
        assertEquals(listOf(kept), again.probes)
        assertEquals(38, again.probes.single().score.wcpmShown)
    }

    @Test
    fun theSettingAndTheReportDetailsStay() = runBlocking {
        val store = ReadingStore(folder.newFolder("Reading"))
        val child = UUID.randomUUID()
        store.update(child) { it.copy(preset = ReadingPreset.Wide, report = ReportDetails("Elevul A", "7 ani", SchoolGrade.First)) }
        val data = store.load(child)
        assertEquals(ReadingPreset.Wide, data.preset)
        assertEquals(ReportDetails("Elevul A", "7 ani", SchoolGrade.First), data.report)
    }

    @Test
    fun aDeletedProfileTakesItsProbesAlong() = runBlocking {
        val directory = folder.newFolder("Reading")
        val store = ReadingStore(directory)
        val stays = UUID.randomUUID()
        val goes = UUID.randomUUID()
        store.add(stays, record())
        store.add(goes, record())
        File(directory, "$goes.json.tmp").writeText("left from a crash")
        store.keepOnly(setOf(stays))
        assertEquals(listOf("$stays.json"), directory.list()!!.toList())
        assertTrue(store.load(goes).probes.isEmpty())
        store.keepOnly(emptySet())
        assertTrue(directory.list()!!.isEmpty())
    }

    @Test
    fun aBrokenFileReadsAsEmptyAndABrokenRecordIsSkipped() = runBlocking {
        val directory = folder.newFolder("Reading")
        val store = ReadingStore(directory)
        val child = UUID.randomUUID()
        File(directory, "$child.json").writeText("{ not json")
        assertTrue(store.load(child).probes.isEmpty())
        store.add(child, record())
        val text = File(directory, "$child.json").readText()
        val broken = text.replace("\"probes\":[", "\"probes\":[{\"id\":\"nope\"},")
        File(directory, "$child.json").writeText(broken)
        assertEquals(1, store.load(child).probes.size)
    }

    @Test
    fun removingAProbeLeavesTheOthers() = runBlocking {
        val store = ReadingStore(folder.newFolder("Reading"))
        val child = UUID.randomUUID()
        val first = record(day = "2026-10-01")
        val second = record(day = "2026-11-01")
        store.add(child, first)
        store.add(child, second)
        val after = store.remove(child, first.id)
        assertEquals(listOf(second), after.probes)
        assertFalse(store.load(child).probes.any { it.id == first.id })
    }

    @Test
    fun probesReadOldestFirstByKind() {
        val data = ReadingProfileData(
            probes = listOf(record(day = "2026-11-01"), record(ProbeKind.Text, "2026-10-01"), record(day = "2026-09-01")),
        )
        assertEquals(listOf("2026-09-01", "2026-11-01"), data.probes(ProbeKind.Mono).map { it.localDay })
        assertEquals("2026-11-01", data.latest?.localDay)
    }
}
