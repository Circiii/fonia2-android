package tech.fonia.store

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DisplayName("Records")
class RecordsTests {
    @Test
    @DisplayName("a profile keeps every field, including the absence of a name")
    fun profileRoundTrip() = runTest {
        TestStore().use { store ->
            val profile = Profile(
                kind = Profile.Kind.Adult,
                ageBand = Profile.AgeBand.Adult,
                voiceGroup = Profile.VoiceGroup.Female,
                color = "pink",
                createdAt = bucharest(2026, 11, 4, 10),
                targetSounds = listOf("r", "ș"),
                requireConfirmation = false,
                confirmedSounds = listOf("r"),
                openPath = true,
                notes = "Lucrăm la R",
                track = Profile.Track.En,
                sibilantCalibration = mapOf(
                    "boundaryHz" to JSONValue.Number(4_800.0),
                    "samples" to JSONValue.Array(listOf(JSONValue.Number(1.0), JSONValue.Bool(true), JSONValue.Null)),
                ),
            )
            store.repository.saveProfile(profile)

            assertEquals(profile, store.repository.profile(id = profile.id))
            assertNull(profile.name)
        }
    }

    @Test
    @DisplayName("new profiles need an adult's confirmation by default")
    fun confirmationIsTheDefault() {
        assertTrue(Profile.fixtureChild().requireConfirmation)
    }

    @ParameterizedTest
    @EnumSource(Profile.AgeBand::class, names = ["FourToFive", "SixToSeven", "EightToTen", "Adult"])
    fun ageBandStorage(band: Profile.AgeBand) = runTest {
        TestStore().use { store ->
            val profile = Profile.fixtureChild().copy(ageBand = band)
            store.repository.saveProfile(profile)
            val stored = store.repository.profile(id = profile.id)
            assertEquals(band, stored?.ageBand)
        }
    }

    @Test
    @DisplayName("saving a profile again updates it without touching its sessions")
    fun profileUpdateKeepsChildren() = runTest {
        TestStore().use { store ->
            var profile = store.addChild()
            store.addSession(profile)

            profile = profile.copy(name = "Ana", confirmedSounds = listOf("s"))
            store.repository.saveProfile(profile)

            assertEquals(profile, store.repository.profile(id = profile.id))
            assertEquals(1, store.repository.sessions(profileId = profile.id).size)
        }
    }

    @Test
    @DisplayName("sound progress is one row per profile and sound")
    fun soundProgressUpsert() = runTest {
        TestStore().use { store ->
            val profile = store.addChild()
            val block = SoundProgress.Block(rung = 1, good = 4, total = 6, endedAt = bucharest(2026, 11, 4, 10))

            var progress = SoundProgress(profileId = profile.id, sound = "s", rung = 1, blockHistory = listOf(block))
            store.repository.saveSoundProgress(progress)
            progress = progress.copy(
                rung = 2,
                blockHistory = progress.blockHistory +
                    SoundProgress.Block(rung = 2, good = 2, total = 6, endedAt = bucharest(2026, 11, 5, 10)),
            )
            store.repository.saveSoundProgress(progress)
            store.repository.saveSoundProgress(SoundProgress(profileId = profile.id, sound = "r"))

            val rows = store.repository.soundProgress(profileId = profile.id)
            assertEquals(listOf("r", "s"), rows.map { it.sound })
            assertEquals(progress, rows.last())
        }
    }

    @Test
    @DisplayName("an attempt adds to its session's count and leaves the seed alone")
    fun attemptsCountOnTheSession() = runTest {
        TestStore().use { store ->
            val profile = store.addChild()
            val session = store.addSession(profile, seed = 12_345)

            repeat(3) {
                store.repository.addAttempt(
                    Attempt(
                        sessionId = session.id,
                        profileId = profile.id,
                        exerciseKind = "repeat",
                        sound = "s",
                        machineOutcome = Attempt.Outcome.Good,
                    ),
                )
            }
            val end = bucharest(2026, 11, 4, 10, 10)
            store.repository.finishSession(id = session.id, endedAt = end)

            val stored = store.repository.sessions(profileId = profile.id).firstOrNull()
            assertEquals(3, stored?.attempts)
            assertEquals(end, stored?.endedAt)
            assertEquals(12_345L, stored?.seed)
        }
    }

    @Test
    @DisplayName("the extreme seeds survive storage")
    fun seedRange() = runTest {
        TestStore().use { store ->
            val profile = store.addChild()
            store.addSession(profile, seed = Long.MIN_VALUE)
            store.addSession(profile, at = bucharest(2026, 11, 4, 11), seed = Long.MAX_VALUE)
            val seeds = store.repository.sessions(profileId = profile.id).map { it.seed }
            assertEquals(listOf(Long.MIN_VALUE, Long.MAX_VALUE), seeds)
        }
    }

    @Test
    @DisplayName("the adult's verdict sits beside the machine's and can be withdrawn")
    fun adultVerdictIsSeparate() = runTest {
        TestStore().use { store ->
            val profile = store.addChild()
            val session = store.addSession(profile)
            val attempt = Attempt(
                sessionId = session.id,
                profileId = profile.id,
                ts = bucharest(2026, 11, 4, 10, 1),
                exerciseKind = "repeat",
                sound = "s",
                target = "sare",
                machineOutcome = Attempt.Outcome.Almost,
                detail = mapOf("centroidHz" to JSONValue.Number(6_200.0), "note" to JSONValue.String("mijloc")),
                timeZone = Bucharest,
            )
            store.repository.addAttempt(attempt)
            assertEquals(listOf(attempt), store.repository.attempts(sessionId = session.id))

            store.repository.setAdultOutcome(attemptId = attempt.id, outcome = Attempt.Outcome.Good)
            var stored = store.repository.attempts(sessionId = session.id).firstOrNull()
            assertEquals(Attempt.Outcome.Almost, stored?.machineOutcome)
            assertEquals(Attempt.Outcome.Good, stored?.adultOutcome)

            store.repository.setAdultOutcome(attemptId = attempt.id, outcome = null)
            stored = store.repository.attempts(sessionId = session.id).firstOrNull()
            assertNull(stored?.adultOutcome)
        }
    }

    @Test
    @DisplayName("a talk keeps its JSON columns and its recording link")
    fun speechRoundTrip() = runTest {
        TestStore().use { store ->
            val profile = store.addChild()
            val take = store.addTake(profile, sound = null)
            var speech = SpeechRecord(
                profileId = profile.id,
                ts = bucharest(2026, 11, 4, 12),
                lang = SpeechRecord.Language.Ro,
                prompt = "Povestește o zi bună",
                duration = 62.5,
                transcript = "Azi am mers la munte",
                metrics = mapOf(
                    "wpm" to JSONValue.Number(138.0),
                    "pauses" to JSONValue.Object(mapOf("count" to JSONValue.Number(4.0))),
                ),
                selfRating = 3,
                correctedFillers = listOf(
                    JSONValue.Object(mapOf("word" to JSONValue.String("ăă"), "start" to JSONValue.Number(4.2))),
                ),
                recordingId = take.id,
            )
            store.repository.saveSpeech(speech)
            assertEquals(listOf(speech), store.repository.speeches(profileId = profile.id))

            speech = speech.copy(selfRating = 4)
            store.repository.saveSpeech(speech)
            assertEquals(listOf(speech), store.repository.speeches(profileId = profile.id))
        }
    }

    @Test
    @DisplayName("notes come back newest day first and can be deleted")
    fun notesByDate() = runTest {
        TestStore().use { store ->
            val profile = store.addChild()
            val older = MoodlessNote(profileId = profile.id, localDay = "2026-10-30", text = "Am început cu S")
            val newer = MoodlessNote(profileId = profile.id, localDay = "2026-11-04", text = "S la început de cuvânt")
            store.repository.saveNote(older)
            store.repository.saveNote(newer)
            assertEquals(listOf(newer, older), store.repository.notes(profileId = profile.id))

            store.repository.deleteNote(id = older.id)
            assertEquals(listOf(newer), store.repository.notes(profileId = profile.id))
        }
    }

    @Test
    @DisplayName("a single take can be pinned and deleted")
    fun recordingPinAndDelete() = runTest {
        TestStore().use { store ->
            val profile = store.addChild()
            val take = store.addTake(profile)
            assertTrue(store.fileExists(take))

            val now = bucharest(2026, 11, 4, 10)
            store.repository.setPinned(recordingId = take.id, pinned = true, now = now)
            assertEquals(true, store.repository.recordings(profileId = profile.id).firstOrNull()?.pinned)

            store.repository.deleteRecording(id = take.id)
            assertTrue(store.repository.recordings(profileId = profile.id).isEmpty())
            assertFalse(store.fileExists(take))
        }
    }

    @Test
    @DisplayName("a take with no file on disk is refused")
    fun addRecordingNeedsTheFile() = runTest {
        TestStore().use { store ->
            val profile = store.addChild()
            val recording = Recording(profileId = profile.id, label = "lipsă", duration = 1.0)
            store.repository.recordingFileURL(recording)

            assertFailsWith<Exception> {
                store.repository.addRecording(recording)
            }
            assertTrue(store.repository.recordings(profileId = profile.id).isEmpty())
        }
    }
}
