package tech.fonia.features.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.features.reading.CopyRules
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

class PracticeReminderTests {
    private val bucharest = ZoneId.of("Europe/Bucharest")

    @Test
    fun theDefaultIsTheEveningAt1930() {
        assertEquals(19 * 60 + 30, PracticeReminder.DEFAULT_MINUTES)
        assertEquals("19:30", PracticeReminder.clock(PracticeReminder.DEFAULT_MINUTES))
    }

    @Test
    fun theClockHasTwoDigitsEachSide() {
        assertEquals("08:00", PracticeReminder.clock(480))
        assertEquals("00:05", PracticeReminder.clock(5))
        assertEquals("23:59", PracticeReminder.clock(23 * 60 + 59))
    }

    @Test
    fun theNextReminderIsTodayWhileTheHourIsAhead() {
        val now = ZonedDateTime.of(2026, 11, 24, 18, 0, 0, 0, bucharest)
        assertEquals(ZonedDateTime.of(2026, 11, 24, 19, 30, 0, 0, bucharest), PracticeReminder.nextTrigger(now, 19 * 60 + 30))
    }

    @Test
    fun onceTheHourHasComeTheNextReminderIsTomorrow() {
        val atTheHour = ZonedDateTime.of(2026, 11, 24, 19, 30, 0, 0, bucharest)
        val tomorrow = ZonedDateTime.of(2026, 11, 25, 19, 30, 0, 0, bucharest)
        assertEquals(tomorrow, PracticeReminder.nextTrigger(atTheHour, 19 * 60 + 30))
        assertEquals(tomorrow, PracticeReminder.nextTrigger(atTheHour.plusHours(3), 19 * 60 + 30))
    }

    @Test
    fun theHourHoldsAcrossTheClockChange() {
        // The night the clocks go back in Romania: the reminder still comes at 19:30 local time.
        val now = ZonedDateTime.of(2026, 10, 24, 20, 0, 0, 0, bucharest)
        val next = PracticeReminder.nextTrigger(now, 19 * 60 + 30)
        assertEquals(25, next.dayOfMonth)
        assertEquals(19, next.hour)
        assertEquals(30, next.minute)
    }

    @Test
    fun theMomentsAreTheFamilysOwnTimes() {
        assertEquals(listOf(480, 1020, 1170, 1230), PracticeMoment.entries.map { it.minutes })
        assertEquals(PracticeMoment.Evening.minutes, PracticeReminder.DEFAULT_MINUTES)
        assertEquals(PracticeMoment.Bedtime, PracticeMoment.of("bedtime"))
        assertEquals(null, PracticeMoment.of("noon"))
    }

    @Test
    fun theWordingOnlyInvites() {
        val texts = listOf(
            PracticeReminder.TITLE,
            PracticeReminder.BODY,
            PracticeReminder.ROW_TITLE,
            PracticeReminder.ROW_DETAIL,
            PracticeReminder.DENIED,
            PracticeReminder.OPEN_SETTINGS,
        ) + PracticeMoment.entries.map { it.title }
        val pressure = listOf("ieri", "ratat", "pierd", "zile la rând", "serie", "uitat", "nu ai")
        for (text in texts) {
            for (word in pressure) assertFalse("$word in \"$text\"", text.lowercase().contains(word))
            assertEquals("\"$text\"", emptyList<String>(), CopyRules.problems(text))
        }
        assertFalse(PracticeReminder.DENIED.contains("Configurări"))
    }

    @Test
    fun theStoredKeysAreTheOnesSettingsClears() {
        // "Șterge tot" clears every key in the "fonia" file; the reminder's live there with the others.
        for (key in listOf(PracticeReminder.ON_KEY, PracticeReminder.MINUTES_KEY, PracticeReminder.MOMENT_KEY)) {
            assertTrue(key, key.startsWith("fonia.reminder."))
        }
    }

    @Test
    fun theManifestAsksOnlyForWhatTheReminderNeeds() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android.permission.POST_NOTIFICATIONS"))
        assertTrue(manifest.contains("android.permission.RECEIVE_BOOT_COMPLETED"))
        assertFalse(manifest.contains("android.permission.INTERNET"))
        assertFalse(manifest.contains("SCHEDULE_EXACT_ALARM"))
        assertFalse(manifest.contains("USE_EXACT_ALARM"))
    }
}
