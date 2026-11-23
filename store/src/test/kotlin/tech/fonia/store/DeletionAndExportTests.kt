package tech.fonia.store

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Base64
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

private val allTables = listOf("profile", "soundProgress", "session", "attempt", "recording", "speech", "note")

private suspend fun rowCount(store: TestStore, table: String): Int =
    store.database.writer.read { db -> db.fetchLong("SELECT COUNT(*) FROM $table")?.toInt() ?: -1 }

/** A profile with something in every table. */
private suspend fun addFullProfile(store: TestStore, name: String): Pair<Profile, Recording> {
    val profile = Profile.fixtureChild(name = name).copy(
        sibilantCalibration = mapOf("boundaryHz" to JSONValue.Number(4_800.0)),
    )
    store.repository.saveProfile(profile)
    store.repository.saveSoundProgress(SoundProgress(profileId = profile.id, sound = "s", rung = 2))
    val session = store.addSession(profile, seed = 11)
    val take = store.addTake(profile)
    store.repository.addAttempt(
        Attempt(
            sessionId = session.id,
            profileId = profile.id,
            ts = bucharest(2026, 11, 4, 10, 5),
            exerciseKind = "repeat",
            sound = "s",
            target = "sare",
            machineOutcome = Attempt.Outcome.Good,
            adultOutcome = Attempt.Outcome.Almost,
            detail = mapOf("centroidHz" to JSONValue.Number(6_200.0)),
            recordingId = take.id,
            timeZone = Bucharest,
        ),
    )
    store.repository.saveSpeech(
        SpeechRecord(
            profileId = profile.id,
            ts = bucharest(2026, 11, 4, 11),
            lang = SpeechRecord.Language.Ro,
            prompt = "O zi bună",
            duration = 30.0,
            metrics = mapOf("wpm" to JSONValue.Number(130.0)),
            recordingId = take.id,
        ),
    )
    store.repository.saveNote(MoodlessNote(profileId = profile.id, localDay = "2026-11-04", text = "Bine la S"))
    return profile to take
}

@DisplayName("Deletion")
class DeletionTests {
    @Test
    @DisplayName("deleting a profile removes its rows and files and leaves other profiles alone")
    fun deleteProfile() = runTest {
        TestStore().use { store ->
            val (ana, anaTake) = addFullProfile(store, name = "Ana")
            val (mihai, mihaiTake) = addFullProfile(store, name = "Mihai")

            store.repository.deleteProfile(id = ana.id)

            assertNull(store.repository.profile(id = ana.id))
            assertFalse(store.fileExists(anaTake))
            assertFalse(File(store.folder, ana.id.uuidString).exists())
            for (table in allTables) {
                assertEquals(1, rowCount(store, table = table), "$table should hold only Mihai's row")
            }
            assertEquals(mihai, store.repository.profile(id = mihai.id))
            assertTrue(store.fileExists(mihaiTake))
        }
    }

    @Test
    @DisplayName("deleting a profile with no recordings works")
    fun deleteEmptyProfile() = runTest {
        TestStore().use { store ->
            val profile = store.addChild()
            store.repository.deleteProfile(id = profile.id)
            assertNull(store.repository.profile(id = profile.id))
        }
    }

    @Test
    @DisplayName("a wipe empties every table and the recordings folder")
    fun wipe() = runTest {
        TestStore().use { store ->
            val (_, anaTake) = addFullProfile(store, name = "Ana")
            val (_, mihaiTake) = addFullProfile(store, name = "Mihai")
            assertTrue(store.fileExists(anaTake))

            store.repository.wipeAll()

            for (table in allTables) {
                assertEquals(0, rowCount(store, table = table), "$table was not emptied")
            }
            assertFalse(store.fileExists(anaTake))
            assertFalse(store.fileExists(mihaiTake))
            assertTrue(assertNotNull(store.folder.list()).isEmpty())
        }
    }

    @Test
    @DisplayName("the store works normally after a wipe, on disk too")
    fun usableAfterWipe() = runTest {
        val folder = temporaryFolder("fonia-wipe")
        try {
            FoniaDatabase.open(folder).use { database ->
                val repository = FoniaRepository(database)

                val before = Profile.fixtureChild(name = "Ana")
                repository.saveProfile(before)
                val take = Recording(profileId = before.id, label = "take", sound = "s", duration = 1.0)
                repository.recordingFileURL(take).writeBytes(byteArrayOf(1))
                repository.addRecording(take)

                repository.wipeAll()
                assertNull(repository.profile(id = before.id))

                val after = Profile.fixtureChild(name = "Mihai")
                repository.saveProfile(after)
                val newTake = Recording(profileId = after.id, label = "take", sound = "s", duration = 1.0)
                repository.recordingFileURL(newTake).writeBytes(byteArrayOf(1))
                repository.addRecording(newTake)
                assertEquals(1, repository.recordings(profileId = after.id).size)
            }
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    @DisplayName("a stream open during a wipe reports empty and stays open")
    fun streamSurvivesWipe() = runTest(timeout = 1.minutes) {
        TestStore().use { store ->
            val profile = store.addChild(name = "Ana")
            val iterator = store.repository.profilesStream().iterator(backgroundScope)
            val initial = iterator.receive()
            assertEquals(listOf(profile), initial)

            store.repository.wipeAll()
            val afterWipe = iterator.receive()
            assertEquals(emptyList(), afterWipe)

            val next = Profile.fixtureChild(name = "Mihai")
            store.repository.saveProfile(next)
            val afterInsert = iterator.receive()
            assertEquals(listOf(next), afterInsert)
        }
    }
}

// `as? String`, `as? Int` and `as? Double` on what `JSONSerialization` returns.
private val JsonElement?.string: String?
    get() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private val JsonElement?.int: Int?
    get() = (this as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()

private val JsonElement?.double: Double?
    get() = (this as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()

@DisplayName("Export")
class ExportTests {
    private fun json(data: ByteArray): JsonObject =
        assertNotNull(Json.parseToJsonElement(data.decodeToString()) as? JsonObject)

    @Test
    @DisplayName("the export holds one profile's data under fixed keys")
    fun shape() = runTest {
        TestStore().use { store ->
            val (ana, _) = addFullProfile(store, name = "Ana")
            addFullProfile(store, name = "Mihai")

            val root = json(store.repository.exportJSON(profileId = ana.id, now = bucharest(2026, 11, 4, 12)))

            assertEquals(
                setOf(
                    "app", "schemaVersion", "exportedAt", "profile", "soundProgress",
                    "sessions", "attempts", "recordings", "speeches", "notes",
                ),
                root.keys,
            )
            assertEquals("fonia", root["app"].string)
            assertEquals(1, root["schemaVersion"].int)
            assertEquals("2026-11-04T10:00:00Z", root["exportedAt"].string)

            for (key in listOf("soundProgress", "sessions", "attempts", "recordings", "speeches", "notes")) {
                assertEquals(1, (root[key] as? JsonArray)?.size, "$key should hold only Ana's row")
            }
            val profile = assertNotNull(root["profile"] as? JsonObject)
            assertEquals("Ana", profile["name"].string)
            assertEquals(ana.id.uuidString, profile["id"].string)
            assertEquals("6-7", profile["ageBand"].string)
        }
    }

    @Test
    @DisplayName("verdicts stay separate and JSON columns nest as real JSON")
    fun attemptShape() = runTest {
        TestStore().use { store ->
            val (ana, _) = addFullProfile(store, name = "Ana")
            val root = json(store.repository.exportJSON(profileId = ana.id))

            val attempt = assertNotNull((root["attempts"] as? JsonArray)?.firstOrNull() as? JsonObject)
            assertEquals("good", attempt["machineOutcome"].string)
            assertEquals("almost", attempt["adultOutcome"].string)
            val detail = assertNotNull(attempt["detail"] as? JsonObject)
            assertEquals(6_200.0, detail["centroidHz"].double)
        }
    }

    @Test
    @DisplayName("audio and internal file paths stay out by default")
    fun noAudioByDefault() = runTest {
        TestStore().use { store ->
            val (ana, take) = addFullProfile(store, name = "Ana")
            val data = store.repository.exportJSON(profileId = ana.id)
            val root = json(data)

            val recording = assertNotNull((root["recordings"] as? JsonArray)?.firstOrNull() as? JsonObject)
            assertEquals(take.id.uuidString, recording["id"].string)
            assertEquals("s", recording["sound"].string)
            assertNull(recording["audioBase64"])
            assertNull(recording["fileName"])
            assertFalse(data.decodeToString().contains(".m4a"))
        }
    }

    @Test
    @DisplayName("the takes come along when asked for")
    fun audioOnRequest() = runTest {
        TestStore().use { store ->
            val (ana, _) = addFullProfile(store, name = "Ana")
            val root = json(store.repository.exportJSON(profileId = ana.id, includeAudio = true))

            val recording = assertNotNull((root["recordings"] as? JsonArray)?.firstOrNull() as? JsonObject)
            assertEquals(Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)), recording["audioBase64"].string)
        }
    }

    @Test
    @DisplayName("the sibilant calibration never leaves the device")
    fun calibrationStaysLocal() = runTest {
        TestStore().use { store ->
            val (ana, _) = addFullProfile(store, name = "Ana")
            for (includeAudio in listOf(false, true)) {
                val data = store.repository.exportJSON(profileId = ana.id, includeAudio = includeAudio)
                assertFalse(data.decodeToString().contains("sibilant"))
                assertFalse(data.decodeToString().contains("boundaryHz"))
            }
            assertTrue(store.repository.profile(id = ana.id)?.sibilantCalibration != null)
        }
    }

    @Test
    @DisplayName("exporting an unknown profile fails")
    fun unknownProfile() = runTest {
        TestStore().use { store ->
            val error = assertFailsWith<FoniaStoreError.ProfileNotFound> {
                store.repository.exportJSON(profileId = UUID.randomUUID())
            }
            assertEquals<FoniaStoreError>(FoniaStoreError.ProfileNotFound(), error)
        }
    }
}
