package tech.fonia.features.pilot

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_CREATE
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import androidx.sqlite.execSQL
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.domain.Rung
import tech.fonia.domain.SoundID
import tech.fonia.domain.adaptive.AdaptiveDecision
import tech.fonia.domain.adaptive.PracticeMode
import tech.fonia.store.Profile
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** The adaptive pilot's rows in the pilot log: kept like the others, counted after 90 days, and a v1 file upgraded. */
class AdaptiveLogTests {
    private val zone = ZoneId.of("Europe/Bucharest")
    private val log = PilotLog.inMemory(PilotEnvironment("0.1.0", "Test Phone", 35), zone)
    private val today: LocalDate = LocalDate.of(2026, 11, 6)

    @After
    fun close() = log.close()

    private fun at(day: LocalDate): Instant = ZonedDateTime.of(day.atTime(9, 0), zone).toInstant()

    private val child = Profile(
        kind = Profile.Kind.Child,
        ageBand = Profile.AgeBand.SixToSeven,
        voiceGroup = Profile.VoiceGroup.Child,
        color = "cold",
        createdAt = at(today.minusDays(200)),
        targetSounds = listOf("s"),
        track = Profile.Track.Ro,
    )

    private fun block(on: LocalDate, choice: String) = AdaptiveRecord(
        child.id, null, at(on),
        AdaptiveDecision(AdaptiveDecision.Kind.Block, SoundID.S, choice, "below50", block = 1, rung = Rung.Syllables, fast = false, mode = PracticeMode.Acquisition, good = 2, total = 6, value = 2 / 6.0, kp = true, kr = 0),
    )

    @Test
    fun afterTheDetailPeriodOnlyTheDaysCountersStay() = runBlocking {
        log.profiles(listOf(child))
        val old = today.minusDays(PilotLog.DETAIL_DAYS.toLong() + 10)
        log.adaptive(block(old, "easier"))
        log.adaptive(block(old, "easier"))
        log.adaptive(block(today, "harder"))
        log.start(at(today))
        log.flush()
        val rows = log.snapshot().adaptive.getValue(child.id.toString())
        assertEquals(listOf("harder"), rows.map { it["choice"] })
        assertEquals(listOf(true, 1, "syllables", 6), listOf(rows.single()["kp"], rows.single()["block"], rows.single()["rung"], rows.single()["total"]))
        val counters = log.read { adaptiveCounters(child.id.toString()) }
        assertEquals(
            listOf(AdaptiveCounter(old.toString(), "s", "block", "easier", "below50", 2), AdaptiveCounter(today.toString(), "s", "block", "harder", "below50", 1)),
            counters,
        )
    }

    @Test
    fun aProfileTheLogDoesNotKnowGetsNoRow() = runBlocking {
        log.adaptive(block(today, "stay"))
        log.flush()
        assertEquals(0, log.snapshot().rows.getValue("adaptive"))
    }

    @Test
    fun aFileFromBeforeTheAdaptivePilotIsUpgraded() = runBlocking {
        val folder: File = Files.createTempDirectory("fonia-pilot-v1").toFile()
        try {
            // The pilot log as the first version wrote it: version 1, no adaptive tables.
            BundledSQLiteDriver().open(File(folder, PilotDatabase.FILE_NAME).path, SQLITE_OPEN_READWRITE or SQLITE_OPEN_CREATE).use { connection ->
                for (statement in PilotSchema.v1) connection.execSQL(statement)
                connection.execSQL("PRAGMA user_version = 1")
            }
            val upgraded = PilotLog.open(folder, PilotEnvironment("0.2.0", "Test Phone", 35))
            try {
                upgraded.profiles(listOf(child))
                upgraded.adaptive(block(today, "stay"))
                upgraded.flush()
                assertEquals(1, upgraded.snapshot().rows.getValue("adaptive"))
                assertTrue(upgraded.read { rowCounts() }.keys.containsAll(listOf("adaptive", "dailyAdaptive")))
            } finally {
                upgraded.close()
            }
        } finally {
            folder.deleteRecursively()
        }
    }
}
