package tech.fonia.store

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DisplayName("Schema")
class SchemaTests {
    @Test
    @DisplayName("the shipped migrations keep their names and order")
    fun migrationsAreAppendOnly() {
        assertEquals(
            listOf(
                "v1-profiles",
                "v1-recordings",
                "v1-practice",
                "v1-talks",
                "v1-notes",
                "v2-session-focus",
                "v3-mission-checks",
            ),
            Schema.migrator.migrations,
        )
    }

    @Test
    @DisplayName("an empty database migrates to every table")
    fun migratesFromEmpty() {
        DatabaseQueue().use { queue ->
            Schema.migrator.migrate(queue)
            val completed = queue.readBlocking { db -> Schema.migrator.hasCompletedMigrations(db) }
            assertTrue(completed)
            for (table in listOf("profile", "soundProgress", "session", "attempt", "recording", "speech", "note", "missionCheck")) {
                val exists = queue.readBlocking { db -> db.tableExists(table) }
                assertTrue(exists, "missing table $table")
            }
        }
    }

    @Test
    @DisplayName("migrating twice changes nothing and keeps the rows")
    fun migratingAgainKeepsData() = runTest {
        TestStore().use { store ->
            val profile = store.addChild(name = "Ana")
            Schema.migrator.migrate(store.database.writer)
            assertEquals(profile, store.repository.profile(id = profile.id))
        }
    }

    @Test
    @DisplayName("a profile inserted without the optional columns gets the safe defaults")
    fun columnDefaults() {
        DatabaseQueue().use { queue ->
            Schema.migrator.migrate(queue)
            val id = UUID.randomUUID()
            val profile = queue.writeBlocking { db ->
                db.execute(
                    """
                    INSERT INTO profile (id, kind, ageBand, voiceGroup, color, createdAt, track)
                    VALUES (?, 'child', '4-5', 'child', 'sky', '2026-11-04 10:00:00.000', 'ro')
                    """.trimIndent(),
                    listOf(id),
                )
                Profile.fetchOne(db, listOf(id))
            }
            val fetched = assertNotNull(profile)
            assertTrue(fetched.requireConfirmation)
            assertNull(fetched.name)
            assertTrue(fetched.targetSounds.isEmpty())
            assertTrue(fetched.confirmedSounds.isEmpty())
            assertFalse(fetched.openPath)
            assertNull(fetched.sibilantCalibration)
        }
    }

    @Test
    @DisplayName("connections enforce foreign keys and zero deleted data")
    fun connectionSettings() = runTest {
        TestStore().use { store ->
            val (foreignKeys, secureDelete) = store.database.writer.read { db ->
                db.fetchLong("PRAGMA foreign_keys") to db.fetchLong("PRAGMA secure_delete")
            }
            assertEquals(1L, foreignKeys)
            assertEquals(1L, secureDelete)
        }
    }

    @Test
    @DisplayName("the on-disk store reopens with its data")
    fun diskStoreReopens() = runTest {
        val folder = temporaryFolder("fonia-disk")
        try {
            val profile = Profile.fixtureChild(name = "Ana")
            FoniaDatabase.open(folder).use { database -> FoniaRepository(database).saveProfile(profile) }

            FoniaDatabase.open(folder).use { database ->
                val reopened = FoniaRepository(database)
                assertEquals(profile, reopened.profile(id = profile.id))
            }
        } finally {
            folder.deleteRecursively()
        }
    }

    // Android has no per-file backup flag or protection class. Both come from the folder the app opens the store
    // in (`Context.getNoBackupFilesDir()`: never backed up, credential-encrypted). So the parity check is that every
    // one of these paths exists inside that folder and passes `ProtectedStorage.protect`.
    @Test
    @DisplayName("database and recordings folders are left out of backups, and protected on a device")
    fun storageIsProtected() = runTest {
        val folder = temporaryFolder("fonia-protected")
        try {
            FoniaDatabase.open(folder).use { database ->
                val repository = FoniaRepository(database)
                val profile = Profile.fixtureChild()
                repository.saveProfile(profile)

                val recording = Recording(profileId = profile.id, label = "take", sound = "s", duration = 1.0)
                val file = repository.recordingFileURL(recording)
                file.writeBytes(byteArrayOf(1))
                repository.addRecording(recording)

                val paths = listOf(
                    File(folder, "Database"),
                    File(folder, "Recordings"),
                    file.parentFile,
                    file,
                )
                for (path in paths) {
                    assertTrue(path.exists(), "${path.name} was not created")
                    assertTrue(
                        path.canonicalPath.startsWith(folder.canonicalPath + File.separator),
                        "${path.name} would be backed up: it is outside the store's folder",
                    )
                    ProtectedStorage.protect(path)
                }
            }
        } finally {
            folder.deleteRecursively()
        }
    }
}

@DisplayName("Local day")
class LocalDayTests {
    @Test
    @DisplayName("the day follows the given time zone, not UTC")
    fun usesTimeZone() {
        val lateSunday = bucharest(2026, 11, 8, 23, 30)
        val afterMidnight = bucharest(2026, 11, 9, 0, 30)
        assertEquals("2026-11-08", localDay(of = lateSunday, timeZone = Bucharest))
        assertEquals("2026-11-09", localDay(of = afterMidnight, timeZone = Bucharest))
        assertEquals("2026-11-08", localDay(of = afterMidnight, timeZone = ZoneId.of("UTC")))
    }
}
