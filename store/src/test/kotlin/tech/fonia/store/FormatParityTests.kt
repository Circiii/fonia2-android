package tech.fonia.store

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

// Extra parity checks, not ports of iOS tests: the byte formats that let a database or an export move between
// platforms. The expectations come from swift-foundation's JSONWriter and JSONEncoder (release/6.2, iOS 26),
// Swift's FloatingPointToString and GRDB 7's SQL generator and value conventions.

private val profileId: UUID = UUID.fromString("6F2C0B1A-3D4E-4F50-8A6B-7C8D9E0F1A2B")
private val sessionId: UUID = UUID.fromString("0A0B0C0D-0E0F-4011-8213-141516171819")
private val recordingId: UUID = UUID.fromString("C0FFEE00-1234-4ABC-9DEF-00112233AABB")
private val attemptId: UUID = UUID.fromString("A1B2C3D4-E5F6-4789-8ABC-DEF012345678")
private val speechId: UUID = UUID.fromString("5EEC4000-0000-4000-8000-000000000001")
private val noteId: UUID = UUID.fromString("B07E0000-0000-4000-8000-000000000002")

/** One row in every table, with fixed ids and times, empty containers, a slash and an escaped quote. */
private suspend fun addFixedProfile(store: TestStore) {
    store.repository.saveProfile(
        Profile(
            id = profileId,
            name = "Ana",
            kind = Profile.Kind.Child,
            ageBand = Profile.AgeBand.SixToSeven,
            voiceGroup = Profile.VoiceGroup.Child,
            color = "cold",
            createdAt = bucharest(2026, 11, 1, 9),
            targetSounds = listOf("s", "ș"),
            track = Profile.Track.Ro,
            sibilantCalibration = mapOf(
                "b/c" to JSONValue.String("x/y"),
                "Zeta" to JSONValue.Number(1.0),
                "alpha" to JSONValue.Array(listOf(JSONValue.Number(1.5), JSONValue.Bool(true), JSONValue.Null)),
            ),
        ),
    )
    store.repository.saveSoundProgress(
        SoundProgress(
            profileId = profileId,
            sound = "s",
            rung = 1,
            blockHistory = listOf(SoundProgress.Block(rung = 0, good = 5, total = 6, endedAt = bucharest(2026, 11, 3, 18, 30))),
        ),
    )
    store.repository.addSession(
        SessionRecord(
            id = sessionId,
            profileId = profileId,
            track = Profile.Track.Ro,
            sound = "s",
            focus = "sound:s",
            startedAt = bucharest(2026, 11, 4, 10),
            seed = -42,
            timeZone = Bucharest,
        ),
    )
    val recording = Recording(
        id = recordingId,
        profileId = profileId,
        ts = bucharest(2026, 11, 4, 10, 5),
        label = "sare",
        sound = "s",
        duration = 1.25,
        pinned = true,
    )
    store.repository.recordingFileURL(recording).writeBytes(byteArrayOf(1, 2, 3))
    store.repository.addRecording(recording, now = recording.ts)
    store.repository.addAttempt(
        Attempt(
            id = attemptId,
            sessionId = sessionId,
            profileId = profileId,
            ts = bucharest(2026, 11, 4, 10, 5),
            exerciseKind = "repeat",
            sound = "s",
            target = "sare",
            machineOutcome = Attempt.Outcome.Good,
            adultOutcome = Attempt.Outcome.Almost,
            detail = mapOf(
                "selfRating" to JSONValue.String("yes"),
                "centroidHz" to JSONValue.Number(6_200.5),
                "path" to JSONValue.String("a/b"),
            ),
            recordingId = recordingId,
            timeZone = Bucharest,
        ),
    )
    store.repository.finishSession(id = sessionId, endedAt = bucharest(2026, 11, 4, 10, 12))
    store.repository.saveSpeech(
        SpeechRecord(
            id = speechId,
            profileId = profileId,
            ts = bucharest(2026, 11, 4, 11),
            lang = SpeechRecord.Language.Ro,
            prompt = "O zi bună",
            duration = 30.0,
            transcript = "Azi \"bine\"",
            metrics = mapOf(
                "wpm" to JSONValue.Number(130.0),
                "pitchSpread" to JSONValue.Number(2.75),
                "pauses" to JSONValue.Object(emptyMap()),
            ),
            selfRating = 3,
            recordingId = recordingId,
        ),
    )
    store.repository.saveNote(MoodlessNote(id = noteId, profileId = profileId, localDay = "2026-11-04", text = "Bine la S"))
}

@DisplayName("Foundation JSON")
class FoundationJSONTests {
    @Test
    @DisplayName("doubles print as Swift's description, and JSON drops a trailing .0")
    fun doublesPrintLikeSwift() {
        val cases = listOf(
            4_800.0 to "4800.0",
            62.5 to "62.5",
            0.1 to "0.1",
            0.1 + 0.2 to "0.30000000000000004",
            1.0 / 3.0 to "0.3333333333333333",
            0.0001 to "0.0001",
            0.00001 to "1e-05",
            123_456.789 to "123456.789",
            1_793_723_400_000.0 to "1793723400000.0",
            9_007_199_254_740_992.0 to "9007199254740992.0",
            9_007_199_254_740_994.0 to "9.007199254740994e+15",
            1e16 to "1e+16",
            1e100 to "1e+100",
            5e-324 to "5e-324",
            Double.MAX_VALUE to "1.7976931348623157e+308",
            -4.2 to "-4.2",
            0.0 to "0.0",
            -0.0 to "-0.0",
        )
        for ((value, swift) in cases) {
            assertEquals(swift, swiftDescription(value), "description of $value")
        }
        assertEquals(JNumber("4800"), jsonNumber(4_800.0))
        assertEquals(JNumber("-0"), jsonNumber(-0.0))
        assertEquals(JNumber("1e+16"), jsonNumber(1e16))
        assertFailsWith<IllegalArgumentException> { jsonNumber(Double.NaN) }
        assertFailsWith<IllegalArgumentException> { jsonNumber(Double.POSITIVE_INFINITY) }
    }

    @Test
    @DisplayName("sorted keys follow their UTF-8 bytes, not case or numbers")
    fun keysSortByUtf8Bytes() {
        val keys = listOf("b", "B", "a10", "a2", "_x", "a", "é", "z", "😀", "�")
        assertEquals(
            listOf("B", "_x", "a", "a10", "a2", "b", "z", "é", "�", "😀"),
            keys.sortedWith(FoundationKeyOrder),
        )
    }

    @Test
    @DisplayName("strings escape what JSONEncoder escapes, with lowercase hex")
    fun stringsEscapeLikeJSONEncoder() {
        val text = JText("a/b\"c\\d\n\r\t\b\u000C\u0001\u001F\u007Fé")
        assertEquals(
            "\"a\\/b\\\"c\\\\d\\n\\r\\t\\b\\f\\u0001\\u001f\u007Fé\"",
            encodeJSON(text, prettyPrinted = false, withoutEscapingSlashes = false),
        )
        assertEquals(
            "\"a/b\\\"c\\\\d\\n\\r\\t\\b\\f\\u0001\\u001f\u007Fé\"",
            encodeJSON(text, prettyPrinted = true, withoutEscapingSlashes = true),
        )
    }

    @Test
    @DisplayName("pretty printing uses two spaces, \" : \" and a blank line in empty containers")
    fun prettyPrintingMatchesJSONEncoder() {
        val node = JObject(
            mapOf(
                "c" to JArray(listOf(jsonNumber(1), JText("x"))),
                "b" to JObject(emptyMap()),
                "a" to JArray(emptyList()),
            ),
        )
        assertEquals(
            "{\n  \"a\" : [\n\n  ],\n  \"b\" : {\n\n  },\n  \"c\" : [\n    1,\n    \"x\"\n  ]\n}",
            encodeJSON(node, prettyPrinted = true, withoutEscapingSlashes = true),
        )
        assertEquals(
            "{\"a\":[],\"b\":{},\"c\":[1,\"x\"]}",
            encodeJSON(node, prettyPrinted = false, withoutEscapingSlashes = false),
        )
    }

    @Test
    @DisplayName("JSONValue reads and writes through kotlinx.serialization, keeping true apart from 1")
    fun jsonValueSerializer() {
        val decoded: JSONObject = Json.decodeFromString(
            """{"anchors":[{"place":"s","centroidHz":6200}],"ok":true,"n":1,"none":null,"text":"1"}""",
        )
        assertEquals(JSONValue.Bool(true), decoded["ok"])
        assertEquals(JSONValue.Number(1.0), decoded["n"])
        assertEquals(JSONValue.Null, decoded["none"])
        assertEquals(JSONValue.String("1"), decoded["text"])
        assertEquals(decoded, Json.decodeFromString<JSONObject>(Json.encodeToString(decoded)))
    }
}

@DisplayName("Export format")
class ExportFormatTests {
    @Test
    @DisplayName("the export is the text JSONEncoder writes, byte for byte")
    fun exportIsByteIdentical() = runTest {
        TestStore().use { store ->
            addFixedProfile(store)
            val text = store.repository.exportJSON(profileId = profileId, now = bucharest(2026, 11, 4, 12)).decodeToString()
            assertEquals(EXPECTED_EXPORT, text)
        }
    }

    @Test
    @DisplayName("audio comes as plain Base64 next to the take")
    fun audioIsBase64() = runTest {
        TestStore().use { store ->
            addFixedProfile(store)
            val text = store.repository.exportJSON(profileId = profileId, includeAudio = true).decodeToString()
            assertTrue(text.contains("      \"audioBase64\" : \"AQID\",\n      \"duration\" : 1.25,\n"))
        }
    }

    private companion object {
        val EXPECTED_EXPORT = """
            {
              "app" : "fonia",
              "attempts" : [
                {
                  "adultOutcome" : "almost",
                  "detail" : {
                    "centroidHz" : 6200.5,
                    "path" : "a/b",
                    "selfRating" : "yes"
                  },
                  "exerciseKind" : "repeat",
                  "id" : "A1B2C3D4-E5F6-4789-8ABC-DEF012345678",
                  "localDay" : "2026-11-04",
                  "machineOutcome" : "good",
                  "profileId" : "00000000-0000-0000-0000-000000000000",
                  "recordingId" : "C0FFEE00-1234-4ABC-9DEF-00112233AABB",
                  "sessionId" : "0A0B0C0D-0E0F-4011-8213-141516171819",
                  "sound" : "s",
                  "target" : "sare",
                  "ts" : "2026-11-04T08:05:00Z"
                }
              ],
              "exportedAt" : "2026-11-04T10:00:00Z",
              "missionChecks" : [

              ],
              "notes" : [
                {
                  "id" : "B07E0000-0000-4000-8000-000000000002",
                  "localDay" : "2026-11-04",
                  "profileId" : "00000000-0000-0000-0000-000000000000",
                  "text" : "Bine la S"
                }
              ],
              "profile" : {
                "ageBand" : "6-7",
                "color" : "cold",
                "confirmedSounds" : [

                ],
                "createdAt" : "2026-11-01T07:00:00Z",
                "id" : "00000000-0000-0000-0000-000000000000",
                "kind" : "child",
                "label" : "Profil",
                "openPath" : false,
                "requireConfirmation" : true,
                "targetSounds" : [
                  "s",
                  "ș"
                ],
                "track" : "ro",
                "voiceGroup" : "child"
              },
              "recordings" : [
                {
                  "duration" : 1.25,
                  "id" : "C0FFEE00-1234-4ABC-9DEF-00112233AABB",
                  "label" : "sare",
                  "pinned" : true,
                  "sound" : "s",
                  "ts" : "2026-11-04T08:05:00Z"
                }
              ],
              "schemaVersion" : 3,
              "sessions" : [
                {
                  "attempts" : 1,
                  "endedAt" : "2026-11-04T08:12:00Z",
                  "focus" : "sound:s",
                  "id" : "0A0B0C0D-0E0F-4011-8213-141516171819",
                  "localDay" : "2026-11-04",
                  "profileId" : "00000000-0000-0000-0000-000000000000",
                  "seed" : -42,
                  "sound" : "s",
                  "startedAt" : "2026-11-04T08:00:00Z",
                  "track" : "ro"
                }
              ],
              "soundProgress" : [
                {
                  "blockHistory" : [
                    {
                      "endedAt" : "2026-11-03T16:30:00Z",
                      "good" : 5,
                      "rung" : 0,
                      "total" : 6
                    }
                  ],
                  "moves" : [

                  ],
                  "profileId" : "00000000-0000-0000-0000-000000000000",
                  "rung" : 1,
                  "sound" : "s"
                }
              ],
              "speeches" : [
                {
                  "correctedFillers" : [

                  ],
                  "duration" : 30,
                  "id" : "5EEC4000-0000-4000-8000-000000000001",
                  "lang" : "ro",
                  "metrics" : {
                    "pauses" : {

                    },
                    "pitchSpread" : 2.75,
                    "wpm" : 130
                  },
                  "profileId" : "00000000-0000-0000-0000-000000000000",
                  "prompt" : "O zi bună",
                  "recordingId" : "C0FFEE00-1234-4ABC-9DEF-00112233AABB",
                  "selfRating" : 3,
                  "transcript" : "Azi \"bine\"",
                  "ts" : "2026-11-04T09:00:00Z"
                }
              ]
            }
        """.trimIndent()
    }
}

@DisplayName("GRDB conventions")
class GRDBConventionTests {
    @Test
    @DisplayName("rows are stored as GRDB stores them")
    fun columnsFollowGRDB() = runTest {
        TestStore().use { store ->
            addFixedProfile(store)
            val stored = store.database.writer.read { db ->
                listOf(
                    "SELECT typeof(id) || ' ' || hex(id) FROM profile",
                    "SELECT createdAt FROM profile",
                    "SELECT typeof(requireConfirmation) || ' ' || requireConfirmation FROM profile",
                    "SELECT targetSounds FROM profile",
                    "SELECT confirmedSounds FROM profile",
                    "SELECT sibilantCalibration FROM profile",
                    "SELECT typeof(notes) || ' ' || typeof(name) FROM profile",
                    "SELECT blockHistory FROM soundProgress",
                    "SELECT startedAt || ' | ' || endedAt || ' | ' || typeof(seed) || ' ' || seed FROM session",
                    "SELECT fileName || ' ' || typeof(duration) || ' ' || pinned FROM recording",
                    "SELECT detail || ' ' || typeof(recordingId) || ' ' || length(recordingId) FROM attempt",
                    "SELECT metrics || ' ' || correctedFillers FROM speech",
                ).map { sql -> db.fetchAll(sql) { row -> row.stringOrNull(0) }.joinToString(",") }
            }
            assertEquals(
                listOf(
                    "blob 6F2C0B1A3D4E4F508A6B7C8D9E0F1A2B",
                    "2026-11-01 07:00:00.000",
                    "integer 1",
                    """["s","ș"]""",
                    "[]",
                    """{"Zeta":1,"alpha":[1.5,true,null],"b\/c":"x\/y"}""",
                    "null text",
                    """[{"endedAt":1793723400000,"good":5,"rung":0,"total":6}]""",
                    "2026-11-04 08:00:00.000 | 2026-11-04 08:12:00.000 | integer -42",
                    "6F2C0B1A-3D4E-4F50-8A6B-7C8D9E0F1A2B/C0FFEE00-1234-4ABC-9DEF-00112233AABB.m4a real 1",
                    """{"centroidHz":6200.5,"path":"a\/b","selfRating":"yes"} blob 16""",
                    """{"pauses":{},"pitchSpread":2.75,"wpm":130} []""",
                ),
                stored,
            )
            assertEquals(Instant.parse("2026-11-03T16:30:00Z").toEpochMilli(), 1_793_723_400_000L)
        }
    }

    @Test
    @DisplayName("rows written the GRDB way on iOS read back")
    fun readsRowsWrittenOnIOS() = runTest {
        TestStore().use { store ->
            store.database.writer.write { db ->
                db.execute(
                    """
                    INSERT INTO profile (id, name, kind, ageBand, voiceGroup, color, createdAt, targetSounds,
                                         requireConfirmation, confirmedSounds, openPath, notes, track, sibilantCalibration)
                    VALUES (X'6F2C0B1A3D4E4F508A6B7C8D9E0F1A2B', NULL, 'adult', 'adult', 'male', 'cold',
                            '2026-11-01T07:00:00.1239Z', '["r\/x"]', 0, '[]', 1, 'Lucrăm la R', 'en',
                            '{"ageYears":9,"anchors":[{"centroidHz":6200.25,"place":"s"}]}')
                    """.trimIndent(),
                )
                db.execute(
                    """
                    INSERT INTO soundProgress (profileId, sound, rung, blockHistory)
                    VALUES (X'6F2C0B1A3D4E4F508A6B7C8D9E0F1A2B', 's', 2,
                            '[{"endedAt":1793723400123.5,"good":1,"rung":2,"total":3}]')
                    """.trimIndent(),
                )
                db.execute(
                    """
                    INSERT INTO session (id, profileId, track, sound, startedAt, endedAt, attempts, seed, localDay, focus)
                    VALUES ('0a0b0c0d-0e0f-4011-8213-141516171819', X'6F2C0B1A3D4E4F508A6B7C8D9E0F1A2B', 'dictie',
                            NULL, '2026-11-04 10:00+02:00', NULL, 0, -9223372036854775808, '2026-11-04', NULL)
                    """.trimIndent(),
                )
            }

            val profile = assertNotNull(store.repository.profile(id = profileId))
            assertEquals(Instant.parse("2026-11-01T07:00:00.123Z"), profile.createdAt)
            assertEquals(listOf("r/x"), profile.targetSounds)
            assertEquals(false, profile.requireConfirmation)
            assertEquals(true, profile.openPath)
            assertEquals("Lucrăm la R", profile.notes)
            assertEquals(
                mapOf(
                    "ageYears" to JSONValue.Number(9.0),
                    "anchors" to JSONValue.Array(
                        listOf(
                            JSONValue.Object(
                                mapOf("centroidHz" to JSONValue.Number(6_200.25), "place" to JSONValue.String("s")),
                            ),
                        ),
                    ),
                ),
                profile.sibilantCalibration,
            )

            val block = store.repository.soundProgress(profileId = profileId).single().blockHistory.single()
            assertEquals(Instant.parse("2026-11-03T16:30:00.1235Z"), block.endedAt)

            val session = store.repository.sessions(profileId = profileId).single()
            assertEquals(sessionId, session.id)
            assertEquals(Profile.Track.Dictie, session.track)
            assertEquals(Instant.parse("2026-11-04T08:00:00Z"), session.startedAt)
            assertEquals(Long.MIN_VALUE, session.seed)
        }
    }

    @Test
    @DisplayName("the schema has GRDB's tables, columns, keys and indexes")
    fun schemaShape() {
        DatabaseQueue().use { queue ->
            Schema.migrator.migrate(queue)
            val shape = queue.readBlocking { db -> describeSchema(db) }
            assertEquals(EXPECTED_SCHEMA, shape)
            val applied = queue.readBlocking { db ->
                db.fetchAll("SELECT identifier FROM grdb_migrations ORDER BY rowid") { row -> row.string(0) }
            }
            assertEquals(Schema.migrator.migrations, applied)
        }
    }

    private fun describeSchema(db: Database): Map<String, List<String>> =
        listOf("profile", "soundProgress", "recording", "session", "attempt", "speech", "note", "missionCheck").associateWith { table ->
            val columns = db.fetchAll("PRAGMA table_info(\"$table\")") { row ->
                buildString {
                    append(row.string("name")).append(' ').append(row.string("type"))
                    if (row.long("notnull") == 1L) append(" NOT NULL")
                    row.stringOrNull("dflt_value")?.let { append(" DEFAULT ").append(it) }
                    if (row.long("pk") > 0) append(" PK").append(row.long("pk"))
                }
            }
            val foreignKeys = db.fetchAll("PRAGMA foreign_key_list(\"$table\")") { row ->
                "FK ${row.string("from")} -> ${row.string("table")}(${row.string("to")}) ON DELETE ${row.string("on_delete")}"
            }.sorted()
            val indexes = db.fetchAll("PRAGMA index_list(\"$table\")") { row ->
                Triple(row.string("name"), row.long("unique") == 1L, row.string("origin"))
            }.map { (name, unique, origin) ->
                val columnNames = db.fetchAll("PRAGMA index_info(\"$name\")") { row -> row.string("name") }
                "INDEX $name(${columnNames.joinToString(", ")})" + (if (unique) " UNIQUE" else "") + " $origin"
            }.sorted()
            columns + foreignKeys + indexes
        }

    private companion object {
        val EXPECTED_SCHEMA = mapOf(
            "profile" to listOf(
                "id BLOB NOT NULL PK1",
                "name TEXT",
                "kind TEXT NOT NULL",
                "ageBand TEXT NOT NULL",
                "voiceGroup TEXT NOT NULL",
                "color TEXT NOT NULL",
                "createdAt DATETIME NOT NULL",
                "targetSounds TEXT NOT NULL DEFAULT '[]'",
                "requireConfirmation BOOLEAN NOT NULL DEFAULT 1",
                "confirmedSounds TEXT NOT NULL DEFAULT '[]'",
                "openPath BOOLEAN NOT NULL DEFAULT 0",
                "notes TEXT",
                "track TEXT NOT NULL",
                "sibilantCalibration TEXT",
                "INDEX sqlite_autoindex_profile_1(id) UNIQUE pk",
            ),
            "soundProgress" to listOf(
                "profileId BLOB NOT NULL PK1",
                "sound TEXT NOT NULL PK2",
                "rung INTEGER NOT NULL DEFAULT 0",
                "blockHistory TEXT NOT NULL DEFAULT '[]'",
                "moves TEXT NOT NULL DEFAULT '[]'",
                "FK profileId -> profile(id) ON DELETE CASCADE",
                "INDEX sqlite_autoindex_soundProgress_1(profileId, sound) UNIQUE pk",
            ),
            "recording" to listOf(
                "id BLOB NOT NULL PK1",
                "profileId BLOB NOT NULL",
                "ts DATETIME NOT NULL",
                "label TEXT NOT NULL",
                "sound TEXT",
                "duration DOUBLE NOT NULL",
                "fileName TEXT NOT NULL",
                "pinned BOOLEAN NOT NULL DEFAULT 0",
                "FK profileId -> profile(id) ON DELETE CASCADE",
                "INDEX recording_on_profileId(profileId) c",
                "INDEX sqlite_autoindex_recording_1(id) UNIQUE pk",
                "INDEX sqlite_autoindex_recording_2(fileName) UNIQUE u",
            ),
            "session" to listOf(
                "id BLOB NOT NULL PK1",
                "profileId BLOB NOT NULL",
                "track TEXT NOT NULL",
                "sound TEXT",
                "startedAt DATETIME NOT NULL",
                "endedAt DATETIME",
                "attempts INTEGER NOT NULL DEFAULT 0",
                "seed INTEGER NOT NULL",
                "localDay TEXT NOT NULL",
                "focus TEXT",
                "FK profileId -> profile(id) ON DELETE CASCADE",
                "INDEX session_on_profile_startedAt(profileId, startedAt) c",
                "INDEX sqlite_autoindex_session_1(id) UNIQUE pk",
            ),
            "attempt" to listOf(
                "id BLOB NOT NULL PK1",
                "sessionId BLOB NOT NULL",
                "profileId BLOB NOT NULL",
                "ts DATETIME NOT NULL",
                "exerciseKind TEXT NOT NULL",
                "sound TEXT",
                "target TEXT",
                "machineOutcome TEXT NOT NULL",
                "adultOutcome TEXT",
                "detail TEXT",
                "recordingId BLOB",
                "localDay TEXT NOT NULL",
                "FK profileId -> profile(id) ON DELETE CASCADE",
                "FK recordingId -> recording(id) ON DELETE SET NULL",
                "FK sessionId -> session(id) ON DELETE CASCADE",
                "INDEX attempt_on_profile_sound(profileId, sound) c",
                "INDEX attempt_on_recordingId(recordingId) c",
                "INDEX attempt_on_sessionId(sessionId) c",
                "INDEX sqlite_autoindex_attempt_1(id) UNIQUE pk",
            ),
            "speech" to listOf(
                "id BLOB NOT NULL PK1",
                "profileId BLOB NOT NULL",
                "ts DATETIME NOT NULL",
                "lang TEXT NOT NULL",
                "prompt TEXT NOT NULL",
                "duration DOUBLE NOT NULL",
                "transcript TEXT",
                "metrics TEXT NOT NULL DEFAULT '{}'",
                "selfRating INTEGER",
                "correctedFillers TEXT NOT NULL DEFAULT '[]'",
                "recordingId BLOB",
                "FK profileId -> profile(id) ON DELETE CASCADE",
                "FK recordingId -> recording(id) ON DELETE SET NULL",
                "INDEX speech_on_profileId(profileId) c",
                "INDEX speech_on_recordingId(recordingId) c",
                "INDEX sqlite_autoindex_speech_1(id) UNIQUE pk",
            ),
            "note" to listOf(
                "id BLOB NOT NULL PK1",
                "profileId BLOB NOT NULL",
                "localDay TEXT NOT NULL",
                "text TEXT NOT NULL",
                "FK profileId -> profile(id) ON DELETE CASCADE",
                "INDEX note_on_profile_day(profileId, localDay) c",
                "INDEX sqlite_autoindex_note_1(id) UNIQUE pk",
            ),
            "missionCheck" to listOf(
                "id BLOB NOT NULL PK1",
                "profileId BLOB NOT NULL",
                "sessionId BLOB NOT NULL",
                "sound TEXT NOT NULL",
                "givenAt DATETIME NOT NULL",
                "localDay TEXT NOT NULL",
                "asked BOOLEAN NOT NULL DEFAULT 0",
                "answer TEXT",
                "answeredAt DATETIME",
                "FK profileId -> profile(id) ON DELETE CASCADE",
                "FK sessionId -> session(id) ON DELETE CASCADE",
                "INDEX missionCheck_on_profile_givenAt(profileId, givenAt) c",
                "INDEX sqlite_autoindex_missionCheck_1(id) UNIQUE pk",
                "INDEX sqlite_autoindex_missionCheck_2(sessionId) UNIQUE u",
            ),
        )
    }
}

@DisplayName("Repository extras")
class RepositoryExtraTests {
    @Test
    @DisplayName("an observation that fails ends its stream")
    fun failingObservationEndsTheStream() = runTest(timeout = 1.minutes) {
        TestStore().use { store ->
            val iterator = store.repository.profilesStream().iterator(backgroundScope)
            assertEquals(emptyList(), iterator.receive())

            // A kind no Swift case matches: decoding the row fails, as it would in GRDB.
            store.database.writer.write { db ->
                db.execute(
                    """
                    INSERT INTO profile (id, kind, ageBand, voiceGroup, color, createdAt, track)
                    VALUES (?, 'teen', '6-7', 'child', 'sky', '2026-11-04 10:00:00.000', 'ro')
                    """.trimIndent(),
                    listOf(UUID.randomUUID()),
                )
            }
            assertTrue(iterator.receiveCatching().isClosed)
        }
    }

    @Test
    @DisplayName("attempts are counted from a local day on, on every track")
    fun attemptCountSinceADay() = runTest {
        TestStore().use { store ->
            val profile = store.addChild()
            val session = store.addSession(profile)
            for (day in 1..3) {
                store.repository.addAttempt(
                    Attempt(
                        sessionId = session.id,
                        profileId = profile.id,
                        ts = bucharest(2026, 11, day, 10),
                        exerciseKind = "repeat",
                        machineOutcome = Attempt.Outcome.Good,
                        timeZone = Bucharest,
                    ),
                )
            }
            assertEquals(2, store.repository.attemptCount(profileId = profile.id, sinceLocalDay = "2026-11-02"))
        }
    }
}
