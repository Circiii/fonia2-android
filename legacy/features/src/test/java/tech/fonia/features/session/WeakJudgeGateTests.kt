package tech.fonia.features.session

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.fonia.domain.Rung
import tech.fonia.domain.SoundID
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

/** Android only: on the sounds the phone hears poorly, the adult's marks move a child on. */
class WeakJudgeGateTests {
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

    /** the sounds the phone hears poorly are the four under 80 % at v29 */
    @Test
    fun theWeakSoundsAreTheFourUnderEightyPercent() {
        assertEquals(setOf(SoundID.Ce, SoundID.Ge, SoundID.J, SoundID.Ts), WeakJudgeGate.sounds)
    }

    /** two „Sună bine” on two days pass; one day, or a „Mai lucrăm” on or after the earlier, doesn't */
    @Test
    fun twoGoodDaysWithNoKeepWorkingSinceThePass() {
        val rung = Rung.WordsInitial
        assertTrue(WeakJudgeGate.passes(listOf(mark(rung, 18, good), mark(rung, 19, good)), rung))
        assertFalse(WeakJudgeGate.passes(listOf(mark(rung, 18, good), mark(rung, 18, good)), rung))
        assertFalse(WeakJudgeGate.passes(listOf(mark(rung, 18, good), mark(Rung.Syllables, 19, good)), rung))
        // A „Mai lucrăm” on the earlier day, between them or after them stops it.
        for (day in listOf(18, 19, 20)) {
            assertFalse(WeakJudgeGate.passes(listOf(mark(rung, 18, good), mark(rung, 19, good), mark(rung, day, keepWorking)), rung))
        }
        // One before both doesn't, and two good days after a „Mai lucrăm” pass again.
        assertTrue(WeakJudgeGate.passes(listOf(mark(rung, 17, keepWorking), mark(rung, 18, good), mark(rung, 19, good)), rung))
        assertTrue(WeakJudgeGate.passes(listOf(mark(rung, 18, good), mark(rung, 19, keepWorking), mark(rung, 20, good), mark(rung, 21, good)), rung))
        // A „Mai lucrăm” elsewhere doesn't apply.
        assertTrue(WeakJudgeGate.passes(listOf(mark(rung, 18, good), mark(rung, 19, good), mark(Rung.Syllables, 20, keepWorking)), rung))
    }

    /** only a child on a weak sound below sentences moves, and the lock after the plain sound still waits */
    @Test
    fun whoTheAdultsEarMoves() {
        val passed = { rung: Rung -> listOf(mark(rung, 18, good), mark(rung, 19, good)) }
        fun progress(profile: Profile, sound: String, rung: Rung) = SoundProgress(profileId = profile.id, sound = sound, rung = rung.index)
        val kid = child()
        assertEquals(Rung.WordsMedial, WeakJudgeGate.nextRung(kid, SoundID.J, progress(kid, "j", Rung.WordsInitial), passed(Rung.WordsInitial)))
        assertEquals(Rung.Sentences, WeakJudgeGate.nextRung(kid, SoundID.J, progress(kid, "j", Rung.Pairs), passed(Rung.Pairs)))
        // Sentences and retelling stay on the adult's „Am ascultat, e bine”.
        assertNull(WeakJudgeGate.nextRung(kid, SoundID.J, progress(kid, "j", Rung.Sentences), passed(Rung.Sentences)))
        assertNull(WeakJudgeGate.nextRung(kid, SoundID.J, progress(kid, "j", Rung.Retell), passed(Rung.Retell)))
        // A sound the phone hears well keeps its own blocks.
        val s = child(sound = "s")
        assertNull(WeakJudgeGate.nextRung(s, SoundID.S, progress(s, "s", Rung.WordsInitial), passed(Rung.WordsInitial)))
        // An adult paces themselves.
        val adult = kid.copy(kind = Profile.Kind.Adult, ageBand = Profile.AgeBand.Adult)
        assertNull(WeakJudgeGate.nextRung(adult, SoundID.J, progress(adult, "j", Rung.WordsInitial), passed(Rung.WordsInitial)))
        // Past the plain sound the lock waits for its adult; once confirmed, or with the lock off, the marks move him.
        val waiting = child(confirmed = emptyList())
        assertNull(WeakJudgeGate.nextRung(waiting, SoundID.J, progress(waiting, "j", Rung.Isolated), passed(Rung.Isolated)))
        assertEquals(Rung.Syllables, WeakJudgeGate.nextRung(kid, SoundID.J, progress(kid, "j", Rung.Isolated), passed(Rung.Isolated)))
        val unlocked = waiting.copy(requireConfirmation = false)
        assertEquals(Rung.Syllables, WeakJudgeGate.nextRung(unlocked, SoundID.J, progress(unlocked, "j", Rung.Isolated), passed(Rung.Isolated)))
        // Not passed: stays.
        assertNull(WeakJudgeGate.nextRung(kid, SoundID.J, progress(kid, "j", Rung.WordsInitial), passed(Rung.Syllables)))
    }

    /** from the store: two marked days move the sound up one rung, once */
    @Test
    fun theGateMovesTheSoundOnceFromTheStore() = runBlocking {
        val repository = repository()
        val profile = child()
        store(repository, profile, "j", Rung.WordsInitial, listOf(
            practice(profile, "j", 18, Rung.WordsInitial, good, good = 2),
            practice(profile, "j", 19, Rung.WordsInitial, good, good = 3),
        ))
        assertEquals(listOf(SoundID.J), WeakJudgeGate.apply(repository, profile))
        val moved = repository.soundProgress(profile.id).single()
        assertEquals(Rung.WordsMedial.index, moved.rung)
        assertEquals(2, moved.blockHistory.size)
        assertEquals(listOf(SoundProgress.Move.Source.AdultMarks), moved.moves.map { it.by })
        assertEquals(listOf(Rung.WordsInitial.index), moved.moves.map { it.from })
        // The marks were at the rung below: nothing moves again.
        assertEquals(emptyList<SoundID>(), WeakJudgeGate.apply(repository, profile))
        assertEquals(Rung.WordsMedial.index, repository.soundProgress(profile.id).single().rung)
    }

    /** from the store: a „Mai lucrăm” after the two good days keeps the sound where it is */
    @Test
    fun aLaterKeepWorkingHoldsTheSound() = runBlocking {
        val repository = repository()
        val profile = child()
        store(repository, profile, "j", Rung.WordsInitial, listOf(
            practice(profile, "j", 18, Rung.WordsInitial, good),
            practice(profile, "j", 19, Rung.WordsInitial, good),
            practice(profile, "j", 20, Rung.WordsInitial, keepWorking),
        ))
        assertEquals(emptyList<SoundID>(), WeakJudgeGate.apply(repository, profile))
        assertEquals(Rung.WordsInitial.index, repository.soundProgress(profile.id).single().rung)
        assertEquals(date(20), AdultMarks.notBefore(AdultMarks.load(repository, profile.id, repository.soundProgress(profile.id).single(), Rung.WordsInitial), Rung.WordsInitial))
    }
}
