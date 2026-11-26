package tech.fonia.features.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.store.Profile
import tech.fonia.store.SessionRecord
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.UUID

/**
 * A second short round on the same day, for a child of 4–5 (iOS `DailyRoundsTests`): offered once the pause after the
 * day's first lesson is over, and never more than once.
 */
class DailyRoundsTests {
    private val day = "2026-11-18"

    private fun moment(hour: Int, minute: Int = 0): Instant = ZonedDateTime.of(2026, 11, 18, hour, minute, 0, 0, ZoneOffset.UTC).toInstant()

    private fun lesson(hour: Int, finished: Boolean = true, profile: UUID = UUID.randomUUID()): SessionRecord {
        val start = moment(hour)
        return SessionRecord(
            profileId = profile,
            track = Profile.Track.Ro,
            sound = "s",
            startedAt = start,
            endedAt = if (finished) start.plusSeconds(300) else null,
            attempts = 24,
            timeZone = ZoneOffset.UTC,
        )
    }

    /** no lesson yet is the first round; one lesson rests for two hours, then the second is offered; two is done */
    @Test
    fun rounds() {
        assertEquals(DailyRounds.Round.First, DailyRounds.round(emptyList(), day, moment(9)))
        // A lesson left halfway doesn't count.
        assertEquals(DailyRounds.Round.First, DailyRounds.round(listOf(lesson(9, finished = false)), day, moment(12)))

        val morning = lesson(9)
        val ended = moment(9, 5)
        assertEquals(DailyRounds.Round.Resting(ended.plus(DailyRounds.pause)), DailyRounds.round(listOf(morning), day, moment(10)))
        assertEquals(DailyRounds.Round.Second, DailyRounds.round(listOf(morning), day, moment(11, 5)))
        assertEquals(DailyRounds.Round.Done, DailyRounds.round(listOf(morning, lesson(16)), day, moment(17)))

        // Yesterday's lessons belong to yesterday.
        assertEquals(DailyRounds.Round.First, DailyRounds.round(listOf(morning), "2026-11-19", moment(9)))
    }

    /** offered only to a child of 4–5, off by default, and kept per child */
    @Test
    fun offered() {
        fun child(band: Profile.AgeBand) = Profile(
            kind = Profile.Kind.Child, ageBand = band, voiceGroup = Profile.VoiceGroup.Child, color = "cold", track = Profile.Track.Ro,
        )
        val small = child(Profile.AgeBand.FourToFive)
        val older = child(Profile.AgeBand.SixToSeven)
        assertTrue(DailyRounds.isOffered(small))
        assertFalse(DailyRounds.isOffered(older))
        val stored = mutableSetOf<String>()
        assertFalse(DailyRounds.isOn(small) { it in stored })

        stored += DailyRounds.key(small.id)
        stored += DailyRounds.key(older.id)
        assertTrue(DailyRounds.isOn(small) { it in stored })
        assertFalse(DailyRounds.isOn(older) { it in stored })
        assertTrue(DailyRounds.key(small.id).startsWith("fonia."))
        assertFalse(DailyRounds.isOn(small, preferences = null))
    }
}
