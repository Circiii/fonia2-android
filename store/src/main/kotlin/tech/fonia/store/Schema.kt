package tech.fonia.store

// On iOS, GRDB 7 builds these tables from `db.create(table:)`. Each statement below is, character for character,
// the SQL GRDB generates for the Swift definition, so `sqlite_master` holds the same text on both platforms and a
// database from either one opens on the other:
// - `primaryKey(_, .blob)` is `PRIMARY KEY NOT NULL`; `.jsonText` is plain `TEXT`; `.datetime` is DATETIME;
// - `.references("profile")` spells out the primary key: `REFERENCES "profile"("id")`;
// - `.indexed()` adds `CREATE INDEX "<table>_on_<column>"` right after its table.
// Applied migrations are recorded in GRDB's own `grdb_migrations` table.

/**
 * Migrations are append-only: a shipped one never changes. Add a new named one at the end.
 * `SchemaTests` freezes the list.
 */
internal object Schema {
    val migrator: DatabaseMigrator
        get() {
            val migrator = DatabaseMigrator()

            migrator.registerMigration("v1-profiles") { db ->
                db.execute(
                    "CREATE TABLE \"profile\" (" +
                        "\"id\" BLOB PRIMARY KEY NOT NULL, " +
                        "\"name\" TEXT, " +
                        "\"kind\" TEXT NOT NULL, " +
                        "\"ageBand\" TEXT NOT NULL, " +
                        "\"voiceGroup\" TEXT NOT NULL, " +
                        "\"color\" TEXT NOT NULL, " +
                        "\"createdAt\" DATETIME NOT NULL, " +
                        "\"targetSounds\" TEXT NOT NULL DEFAULT '[]', " +
                        "\"requireConfirmation\" BOOLEAN NOT NULL DEFAULT 1, " +
                        "\"confirmedSounds\" TEXT NOT NULL DEFAULT '[]', " +
                        "\"openPath\" BOOLEAN NOT NULL DEFAULT 0, " +
                        "\"notes\" TEXT, " +
                        "\"track\" TEXT NOT NULL, " +
                        "\"sibilantCalibration\" TEXT)",
                )
                db.execute(
                    "CREATE TABLE \"soundProgress\" (" +
                        "\"profileId\" BLOB NOT NULL REFERENCES \"profile\"(\"id\") ON DELETE CASCADE, " +
                        "\"sound\" TEXT NOT NULL, " +
                        "\"rung\" INTEGER NOT NULL DEFAULT 0, " +
                        "\"blockHistory\" TEXT NOT NULL DEFAULT '[]', " +
                        "PRIMARY KEY (\"profileId\", \"sound\"))",
                )
            }

            migrator.registerMigration("v1-recordings") { db ->
                db.execute(
                    "CREATE TABLE \"recording\" (" +
                        "\"id\" BLOB PRIMARY KEY NOT NULL, " +
                        "\"profileId\" BLOB NOT NULL REFERENCES \"profile\"(\"id\") ON DELETE CASCADE, " +
                        "\"ts\" DATETIME NOT NULL, " +
                        "\"label\" TEXT NOT NULL, " +
                        "\"sound\" TEXT, " +
                        "\"duration\" DOUBLE NOT NULL, " +
                        "\"fileName\" TEXT NOT NULL UNIQUE, " +
                        "\"pinned\" BOOLEAN NOT NULL DEFAULT 0)",
                )
                db.execute("CREATE INDEX \"recording_on_profileId\" ON \"recording\"(\"profileId\")")
            }

            migrator.registerMigration("v1-practice") { db ->
                db.execute(
                    "CREATE TABLE \"session\" (" +
                        "\"id\" BLOB PRIMARY KEY NOT NULL, " +
                        "\"profileId\" BLOB NOT NULL REFERENCES \"profile\"(\"id\") ON DELETE CASCADE, " +
                        "\"track\" TEXT NOT NULL, " +
                        "\"sound\" TEXT, " +
                        "\"startedAt\" DATETIME NOT NULL, " +
                        "\"endedAt\" DATETIME, " +
                        "\"attempts\" INTEGER NOT NULL DEFAULT 0, " +
                        "\"seed\" INTEGER NOT NULL, " +
                        "\"localDay\" TEXT NOT NULL)",
                )
                db.execute(
                    "CREATE INDEX \"session_on_profile_startedAt\" ON \"session\"(\"profileId\", \"startedAt\")",
                )

                db.execute(
                    "CREATE TABLE \"attempt\" (" +
                        "\"id\" BLOB PRIMARY KEY NOT NULL, " +
                        "\"sessionId\" BLOB NOT NULL REFERENCES \"session\"(\"id\") ON DELETE CASCADE, " +
                        "\"profileId\" BLOB NOT NULL REFERENCES \"profile\"(\"id\") ON DELETE CASCADE, " +
                        "\"ts\" DATETIME NOT NULL, " +
                        "\"exerciseKind\" TEXT NOT NULL, " +
                        "\"sound\" TEXT, " +
                        "\"target\" TEXT, " +
                        "\"machineOutcome\" TEXT NOT NULL, " +
                        "\"adultOutcome\" TEXT, " +
                        "\"detail\" TEXT, " +
                        "\"recordingId\" BLOB REFERENCES \"recording\"(\"id\") ON DELETE SET NULL, " +
                        "\"localDay\" TEXT NOT NULL)",
                )
                db.execute("CREATE INDEX \"attempt_on_sessionId\" ON \"attempt\"(\"sessionId\")")
                db.execute("CREATE INDEX \"attempt_on_recordingId\" ON \"attempt\"(\"recordingId\")")
                db.execute("CREATE INDEX \"attempt_on_profile_sound\" ON \"attempt\"(\"profileId\", \"sound\")")
            }

            migrator.registerMigration("v1-talks") { db ->
                db.execute(
                    "CREATE TABLE \"speech\" (" +
                        "\"id\" BLOB PRIMARY KEY NOT NULL, " +
                        "\"profileId\" BLOB NOT NULL REFERENCES \"profile\"(\"id\") ON DELETE CASCADE, " +
                        "\"ts\" DATETIME NOT NULL, " +
                        "\"lang\" TEXT NOT NULL, " +
                        "\"prompt\" TEXT NOT NULL, " +
                        "\"duration\" DOUBLE NOT NULL, " +
                        "\"transcript\" TEXT, " +
                        "\"metrics\" TEXT NOT NULL DEFAULT '{}', " +
                        "\"selfRating\" INTEGER, " +
                        "\"correctedFillers\" TEXT NOT NULL DEFAULT '[]', " +
                        "\"recordingId\" BLOB REFERENCES \"recording\"(\"id\") ON DELETE SET NULL)",
                )
                db.execute("CREATE INDEX \"speech_on_profileId\" ON \"speech\"(\"profileId\")")
                db.execute("CREATE INDEX \"speech_on_recordingId\" ON \"speech\"(\"recordingId\")")
            }

            migrator.registerMigration("v1-notes") { db ->
                db.execute(
                    "CREATE TABLE \"note\" (" +
                        "\"id\" BLOB PRIMARY KEY NOT NULL, " +
                        "\"profileId\" BLOB NOT NULL REFERENCES \"profile\"(\"id\") ON DELETE CASCADE, " +
                        "\"localDay\" TEXT NOT NULL, " +
                        "\"text\" TEXT NOT NULL)",
                )
                db.execute("CREATE INDEX \"note_on_profile_day\" ON \"note\"(\"profileId\", \"localDay\")")
            }

            migrator.registerMigration("v2-session-focus") { db ->
                db.execute("ALTER TABLE \"session\" ADD COLUMN \"focus\" TEXT")
            }

            migrator.registerMigration("v3-mission-checks") { db ->
                db.execute(
                    "CREATE TABLE \"missionCheck\" (" +
                        "\"id\" BLOB PRIMARY KEY NOT NULL, " +
                        "\"profileId\" BLOB NOT NULL REFERENCES \"profile\"(\"id\") ON DELETE CASCADE, " +
                        "\"sessionId\" BLOB NOT NULL UNIQUE REFERENCES \"session\"(\"id\") ON DELETE CASCADE, " +
                        "\"sound\" TEXT NOT NULL, " +
                        "\"givenAt\" DATETIME NOT NULL, " +
                        "\"localDay\" TEXT NOT NULL, " +
                        "\"asked\" BOOLEAN NOT NULL DEFAULT 0, " +
                        "\"answer\" TEXT, " +
                        "\"answeredAt\" DATETIME)",
                )
                db.execute("CREATE INDEX \"missionCheck_on_profile_givenAt\" ON \"missionCheck\"(\"profileId\", \"givenAt\")")
            }

            return migrator
        }
}
