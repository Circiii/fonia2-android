package tech.fonia.features.session

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.fonia.domain.Rung
import tech.fonia.features.app.index
import tech.fonia.store.Attempt
import tech.fonia.store.FoniaDatabase
import tech.fonia.store.FoniaRepository
import tech.fonia.store.Profile
import tech.fonia.store.SessionRecord
import tech.fonia.store.SoundProgress
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Android only: the adult's „Sună bine” and „Mai lucrăm” read back for a sound's progression. */
class AdultMarksTests {
    private val zone: ZoneId = ZoneId.of("Europe/Bucharest")

    private fun at(day: Int, hour: Int = 10): Instant = LocalDateTime.of(2026, 11, day, hour, 0).atZone(zone).toInstant()

    private fun date(day: Int): LocalDate = LocalDate.of(2026, 11, day)

    private fun child(sound: String = "j", confirmed: List<String> = listOf(sound)): Profile = Profile(
        kind = Profile.Kind.Child,
        ageBand = Profile.AgeBand.SixToSeven,
        voiceGroup = Profile.VoiceGroup.Child,
        color = "cold",
        targetSounds = listOf(sound),
        confirmedSounds = confirmed,
        track = Profile.Track.Ro,
    )

    private fun repository(): FoniaRepository {
        val folder = Files.createTempDirectory("fonia-marks").toFile()
        return FoniaRepository(FoniaDatabase.inMemory(recordingsDirectory = File(folder, "Recordings")))
    }

    private fun mark(rung: Rung, day: Int, outcome: Attempt.Outcome) = AdultMarks.Mark(rung, date(day), outcome)

    private val good = Attempt.Outcome.Good
    private val keepWorking = Attempt.Outcome.NotYet

    /** One session of [sound] on [day] at [rung]: its target block, and one take the adult marked [outcome]. */
    private class Practice(val session: SessionRecord, val take: Attempt, val block: SoundProgress.Block)

    private fun practice(profile: Profile, sound: String, day: Int, rung: Rung, outcome: Attempt.Outcome?, good: Int = 6): Practice {
        val session = SessionRecord(profileId = profile.id, track = Profile.Track.Ro, sound = sound, focus = "sound:$sound", startedAt = at(day, 9), endedAt = at(day, 11), timeZone = zone)
        val take = Attempt(sessionId = session.id, profileId = profile.id, ts = at(day, 9).plusSeconds(600), exerciseKind = "say", sound = sound, target = "joc", machineOutcome = Attempt.Outcome.Almost, adultOutcome = outcome, timeZone = zone)
        return Practice(session, take, SoundProgress.Block(rung = rung.index, good = good, total = 6, endedAt = at(day, 10)))
    }

    private suspend fun store(repository: FoniaRepository, profile: Profile, sound: String, rung: Rung, practices: List<Practice>) {
        repository.saveProfile(profile)
        for (p in practices) {
            repository.addSession(p.session)
            repository.addAttempt(p.take)
        }
        repository.saveSoundProgress(SoundProgress(profileId = profile.id, sound = sound, rung = rung.index, blockHistory = practices.map { it.block }))
    }

    /** a take gets the rung its session practised; takes with no block, of another sound or with no mark are left out */
    @Test
    fun aTakeGetsItsSessionsRung() {
        val profile = child()
        val syllables = practice(profile, "j", 17, Rung.Syllables, keepWorking)
        val words = practice(profile, "j", 18, Rung.WordsInitial, good)
        val unmarked = practice(profile, "j", 19, Rung.WordsInitial, null)
        val progress = SoundProgress(profileId = profile.id, sound = "j", rung = Rung.WordsInitial.index, blockHistory = listOf(syllables.block, words.block, unmarked.block))
        val noBlock = SessionRecord(profileId = profile.id, track = Profile.Track.Ro, sound = "j", startedAt = at(20, 9), timeZone = zone)
        val review = Attempt(sessionId = noBlock.id, profileId = profile.id, ts = at(20, 9).plusSeconds(60), exerciseKind = "say", sound = "j", machineOutcome = good, adultOutcome = keepWorking, timeZone = zone)
        val otherSound = words.take.copy(id = java.util.UUID.randomUUID(), sound = "s", adultOutcome = keepWorking)
        val almost = words.take.copy(id = java.util.UUID.randomUUID(), adultOutcome = Attempt.Outcome.Almost)
        val marks = AdultMarks.of(
            attempts = listOf(syllables.take, words.take, unmarked.take, review, otherSound, almost),
            sessions = listOf(syllables.session, words.session, unmarked.session, noBlock),
            progress = progress,
        )
        assertEquals(listOf(mark(Rung.Syllables, 17, keepWorking), mark(Rung.WordsInitial, 18, good)), marks)
    }

    /** „Mai lucrăm” counts from its latest day at the rung; marks at other rungs don't apply */
    @Test
    fun keepWorkingCountsOnlyAtItsRung() {
        val marks = listOf(
            mark(Rung.WordsInitial, 17, keepWorking),
            mark(Rung.WordsInitial, 19, keepWorking),
            mark(Rung.WordsInitial, 20, good),
            mark(Rung.Syllables, 21, keepWorking),
        )
        assertEquals(date(19), AdultMarks.notBefore(marks, Rung.WordsInitial))
        assertEquals(date(21), AdultMarks.notBefore(marks, Rung.Syllables))
        assertNull(AdultMarks.notBefore(marks, Rung.WordsMedial))
    }

    /** from the store: the marks come back for the rung asked about, and only for it */
    @Test
    fun loadReadsTheMarksAtOneRung() = runBlocking {
        val repository = repository()
        val profile = child()
        store(repository, profile, "j", Rung.WordsInitial, listOf(
            practice(profile, "j", 17, Rung.Syllables, keepWorking),
            practice(profile, "j", 18, Rung.WordsInitial, good),
            practice(profile, "j", 19, Rung.WordsInitial, keepWorking),
        ))
        val progress = repository.soundProgress(profile.id).single()
        assertEquals(
            listOf(mark(Rung.WordsInitial, 18, good), mark(Rung.WordsInitial, 19, keepWorking)),
            AdultMarks.load(repository, profile.id, progress, Rung.WordsInitial),
        )
        assertEquals(listOf(mark(Rung.Syllables, 17, keepWorking)), AdultMarks.load(repository, profile.id, progress, Rung.Syllables))
        assertEquals(emptyList<AdultMarks.Mark>(), AdultMarks.load(repository, profile.id, progress, Rung.WordsMedial))
    }
}
