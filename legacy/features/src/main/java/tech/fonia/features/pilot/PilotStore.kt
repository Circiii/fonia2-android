package tech.fonia.features.pilot

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_CREATE
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import java.io.File
import java.io.IOException
import java.security.SecureRandom

/**
 * One connection to the pilot's SQLite file: the bundled SQLite of :store, a file of its own, so FoniaStore's schema
 * stays the iOS one. Deleted rows are overwritten (`secure_delete`), as in FoniaStore. Not thread-safe: [PilotLog]
 * keeps it on one thread.
 */
internal class PilotDatabase private constructor(private val connection: SQLiteConnection) : AutoCloseable {
    fun execute(sql: String, arguments: List<Any?> = emptyList()) {
        connection.prepare(sql).use { statement ->
            bind(statement, arguments)
            while (statement.step()) {
                // PRAGMAs such as journal_mode answer with a row nobody reads.
            }
        }
    }

    fun <T> query(sql: String, arguments: List<Any?> = emptyList(), read: (SQLiteStatement) -> T): List<T> =
        connection.prepare(sql).use { statement ->
            bind(statement, arguments)
            val rows = ArrayList<T>()
            while (statement.step()) rows += read(statement)
            rows
        }

    fun <T> transaction(block: () -> T): T {
        execute("BEGIN IMMEDIATE TRANSACTION")
        try {
            val value = block()
            execute("COMMIT TRANSACTION")
            return value
        } catch (error: Throwable) {
            if (connection.inTransaction()) runCatching { execute("ROLLBACK TRANSACTION") }
            throw error
        }
    }

    override fun close() {
        connection.close()
    }

    private fun bind(statement: SQLiteStatement, arguments: List<Any?>) {
        arguments.forEachIndexed { offset, value ->
            val index = offset + 1
            when (value) {
                null -> statement.bindNull(index)
                is String -> statement.bindText(index, value)
                is Long -> statement.bindLong(index, value)
                is Int -> statement.bindLong(index, value.toLong())
                is Boolean -> statement.bindLong(index, if (value) 1L else 0L)
                is Double -> if (value.isFinite()) statement.bindDouble(index, value) else statement.bindNull(index)
                else -> throw IllegalArgumentException("Cannot store a ${value::class.java.name} in the pilot log")
            }
        }
    }

    companion object {
        private val driver by lazy { BundledSQLiteDriver() }

        /** Opens or creates `pilot.sqlite` in [folder] and brings its schema up to date. */
        fun open(folder: File): PilotDatabase {
            if (!folder.isDirectory && !folder.mkdirs() && !folder.isDirectory) throw IOException("Could not create ${folder.path}")
            val database = PilotDatabase(driver.open(File(folder, FILE_NAME).path, SQLITE_OPEN_READWRITE or SQLITE_OPEN_CREATE))
            try {
                database.execute("PRAGMA journal_mode = WAL")
                database.execute("PRAGMA synchronous = NORMAL")
                database.prepare()
            } catch (error: Throwable) {
                database.close()
                throw error
            }
            return database
        }

        /** For the tests. */
        fun inMemory(): PilotDatabase =
            PilotDatabase(driver.open(":memory:", SQLITE_OPEN_READWRITE or SQLITE_OPEN_CREATE)).also { it.prepare() }

        const val FILE_NAME = "pilot.sqlite"
    }

    private fun prepare() {
        execute("PRAGMA secure_delete = ON")
        val version = query("PRAGMA user_version") { it.getLong(0) }.firstOrNull() ?: 0L
        if (version < 1) {
            transaction {
                for (statement in PilotSchema.v1) execute(statement)
                execute("PRAGMA user_version = 1")
            }
        }
        if (version < 2) {
            transaction {
                for (statement in PilotSchema.v2) execute(statement)
                execute("PRAGMA user_version = 2")
            }
        }
    }
}

/** The tables. Each column is one field of 03 §4.1; nothing in them is free text. */
internal object PilotSchema {
    val v1 = listOf(
        // Activation: when the log started on this phone (install or first launch of this version) and on what.
        """
        CREATE TABLE install (
            id INTEGER PRIMARY KEY CHECK (id = 1),
            day TEXT NOT NULL,
            appVersion TEXT NOT NULL,
            phone TEXT NOT NULL,
            sdk INTEGER NOT NULL
        )
        """,
        // One row per profile: the pseudonym for the export, the age band, and the day setup ended.
        """
        CREATE TABLE profile (
            profileId TEXT PRIMARY KEY,
            pseudonym TEXT NOT NULL UNIQUE,
            kind TEXT NOT NULL,
            ageBand TEXT NOT NULL,
            setupDay TEXT NOT NULL
        )
        """,
        """
        CREATE TABLE session (
            sessionId TEXT PRIMARY KEY,
            profileId TEXT NOT NULL,
            day TEXT NOT NULL,
            startedAt INTEGER NOT NULL,
            lastAt INTEGER,
            endedAt INTEGER,
            finished INTEGER NOT NULL DEFAULT 0,
            track TEXT NOT NULL,
            focus TEXT,
            sound TEXT,
            rung TEXT
        )
        """,
        "CREATE INDEX session_by_profile ON session(profileId, day)",
        """
        CREATE TABLE attempt (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            profileId TEXT NOT NULL,
            sessionId TEXT,
            day TEXT NOT NULL,
            at INTEGER NOT NULL,
            take TEXT NOT NULL,
            mode TEXT NOT NULL,
            exercise TEXT NOT NULL,
            level TEXT,
            sound TEXT,
            position TEXT NOT NULL,
            rung TEXT,
            block TEXT,
            schedule TEXT,
            machine TEXT NOT NULL,
            adult TEXT,
            self TEXT,
            decided TEXT NOT NULL,
            counted INTEGER NOT NULL,
            dose INTEGER NOT NULL,
            retry INTEGER NOT NULL
        )
        """,
        "CREATE INDEX attempt_by_profile ON attempt(profileId, day)",
        """
        CREATE TABLE shadow (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            profileId TEXT NOT NULL,
            sessionId TEXT,
            day TEXT NOT NULL,
            at INTEGER NOT NULL,
            take TEXT NOT NULL,
            context TEXT NOT NULL,
            sound TEXT NOT NULL,
            position TEXT NOT NULL,
            level TEXT,
            schedule TEXT,
            shown TEXT NOT NULL,
            status TEXT NOT NULL,
            verdict TEXT,
            abstention TEXT,
            confidence REAL,
            lpr REAL,
            heardAs TEXT,
            fit REAL,
            frames INTEGER,
            targets INTEGER,
            scoreMs INTEGER,
            loadMs INTEGER,
            waitMs INTEGER NOT NULL,
            sampleRate INTEGER NOT NULL,
            durationMs INTEGER NOT NULL,
            droppedSamples INTEGER NOT NULL,
            snrDb REAL,
            narrowBand INTEGER NOT NULL,
            input TEXT NOT NULL,
            model TEXT,
            policy TEXT,
            dsp TEXT NOT NULL
        )
        """,
        "CREATE INDEX shadow_by_profile ON shadow(profileId, day)",
        """
        CREATE TABLE access (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            profileId TEXT,
            day TEXT NOT NULL,
            at INTEGER NOT NULL,
            action TEXT NOT NULL
        )
        """,
        // After the detail period only these counters stay (03 §2.3: "doar contoare agregate după perioada de retenție").
        """
        CREATE TABLE dailyAttempt (
            profileId TEXT NOT NULL,
            day TEXT NOT NULL,
            mode TEXT NOT NULL,
            sound TEXT NOT NULL,
            position TEXT NOT NULL,
            rung TEXT NOT NULL,
            schedule TEXT NOT NULL,
            attempts INTEGER NOT NULL,
            dose INTEGER NOT NULL,
            good INTEGER NOT NULL,
            almost INTEGER NOT NULL,
            notYet INTEGER NOT NULL,
            unjudged INTEGER NOT NULL,
            PRIMARY KEY (profileId, day, mode, sound, position, rung, schedule)
        )
        """,
        """
        CREATE TABLE dailySession (
            profileId TEXT NOT NULL,
            day TEXT NOT NULL,
            sessions INTEGER NOT NULL,
            finished INTEGER NOT NULL,
            seconds INTEGER NOT NULL,
            PRIMARY KEY (profileId, day)
        )
        """,
        """
        CREATE TABLE dailyShadow (
            profileId TEXT NOT NULL,
            day TEXT NOT NULL,
            sound TEXT NOT NULL,
            shown TEXT NOT NULL,
            status TEXT NOT NULL,
            verdict TEXT NOT NULL,
            takes INTEGER NOT NULL,
            PRIMARY KEY (profileId, day, sound, shown, status, verdict)
        )
        """,
    ).map { it.trimIndent() }

    /** The adaptive pilot's decisions (docs/PLAN.md §7.4), and their counters after the detail period. */
    val v2 = listOf(
        """
        CREATE TABLE adaptive (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            profileId TEXT NOT NULL,
            sessionId TEXT,
            day TEXT NOT NULL,
            at INTEGER NOT NULL,
            sound TEXT,
            kind TEXT NOT NULL,
            choice TEXT NOT NULL,
            reason TEXT NOT NULL,
            block INTEGER,
            rung TEXT,
            fast INTEGER,
            position TEXT,
            mode TEXT,
            good INTEGER,
            total INTEGER,
            skipped INTEGER,
            value REAL,
            previous REAL,
            kp INTEGER,
            kr INTEGER
        )
        """,
        "CREATE INDEX adaptive_by_profile ON adaptive(profileId, day)",
        """
        CREATE TABLE dailyAdaptive (
            profileId TEXT NOT NULL,
            day TEXT NOT NULL,
            sound TEXT NOT NULL,
            kind TEXT NOT NULL,
            choice TEXT NOT NULL,
            reason TEXT NOT NULL,
            decisions INTEGER NOT NULL,
            PRIMARY KEY (profileId, day, sound, kind, choice, reason)
        )
        """,
    ).map { it.trimIndent() }

    /** Every table a profile's rows live in. */
    val profileTables = listOf("profile", "session", "attempt", "shadow", "access", "dailyAttempt", "dailySession", "dailyShadow", "adaptive", "dailyAdaptive")
}

/** The install row. */
data class PilotInstall(val day: String, val appVersion: String, val phone: String, val sdk: Int)

/** A profile as the pilot log knows it. */
data class PilotProfile(val profileId: String, val pseudonym: String, val kind: String, val ageBand: String, val setupDay: String)

/** Attempts of one kind on one day, from the detailed rows and from the counters kept after the detail period. */
data class AttemptCounter(
    val day: String,
    val mode: String,
    val sound: String,
    val position: String,
    val rung: String,
    val feedback: String,
    val attempts: Int,
    val dose: Int,
    val good: Int,
    val almost: Int,
    val notYet: Int,
    val unjudged: Int,
)

/** Sessions of one day. */
data class SessionDay(val day: String, val sessions: Int, val finished: Int, val seconds: Long)

/** Shadow takes of one day, by sound, shown verdict and ZIPA verdict (`""` when not scored). */
data class ShadowCounter(val day: String, val sound: String, val shown: String, val status: String, val verdict: String, val takes: Int)

/** The detail of one scored take that the export summarises (detail period only). */
data class ShadowDetail(val sound: String, val verdict: String?, val abstention: String?, val scoreMs: Long?)

/** The phone's verdict against the adult's, per sound (detail period only). */
data class PhoneVsAdult(val sound: String, val compared: Int, val agreed: Int, val phoneAbstained: Int)

/** One line of the access log. */
data class AccessEntry(val day: String, val action: String)

/** The SQL behind [PilotLog]. Runs on the log's thread only. */
internal class PilotStore(private val db: PilotDatabase) : AutoCloseable {
    override fun close() = db.close()

    // MARK: Activation

    fun install(day: String, appVersion: String, phone: String, sdk: Int) {
        db.execute("INSERT OR IGNORE INTO install (id, day, appVersion, phone, sdk) VALUES (1, ?, ?, ?, ?)", listOf(day, appVersion, phone, sdk))
    }

    fun installInfo(): PilotInstall? =
        db.query("SELECT day, appVersion, phone, sdk FROM install WHERE id = 1") { PilotInstall(it.getText(0), it.getText(1), it.getText(2), it.getLong(3).toInt()) }
            .firstOrNull()

    /** Adds the profile with a new pseudonym, or updates what may change (the age band); the pseudonym stays. */
    fun profile(profileId: String, kind: String, ageBand: String, setupDay: String) {
        val known = db.query("SELECT 1 FROM profile WHERE profileId = ?", listOf(profileId)) { true }.isNotEmpty()
        if (known) {
            db.execute("UPDATE profile SET kind = ?, ageBand = ? WHERE profileId = ?", listOf(kind, ageBand, profileId))
            return
        }
        var pseudonym = newPseudonym()
        while (db.query("SELECT 1 FROM profile WHERE pseudonym = ?", listOf(pseudonym)) { true }.isNotEmpty()) pseudonym = newPseudonym()
        db.execute(
            "INSERT INTO profile (profileId, pseudonym, kind, ageBand, setupDay) VALUES (?, ?, ?, ?, ?)",
            listOf(profileId, pseudonym, kind, ageBand, setupDay),
        )
    }

    fun profiles(): List<PilotProfile> =
        db.query("SELECT profileId, pseudonym, kind, ageBand, setupDay FROM profile ORDER BY setupDay, profileId") {
            PilotProfile(it.getText(0), it.getText(1), it.getText(2), it.getText(3), it.getText(4))
        }

    fun pilotProfile(profileId: String): PilotProfile? =
        db.query("SELECT profileId, pseudonym, kind, ageBand, setupDay FROM profile WHERE profileId = ?", listOf(profileId)) {
            PilotProfile(it.getText(0), it.getText(1), it.getText(2), it.getText(3), it.getText(4))
        }.firstOrNull()

    // MARK: Deletion, the same reach as FoniaStore's

    /** The rows of every profile not in [kept]: what `deleteProfile` and `wipeAll` left behind elsewhere. */
    fun keepOnly(kept: Set<String>) {
        val known = db.query(PilotSchema.profileTables.joinToString(" UNION ") { "SELECT DISTINCT profileId FROM $it" }) {
            if (it.isNull(0)) null else it.getText(0)
        }.filterNotNull()
        val gone = known.filter { it !in kept }
        if (gone.isEmpty()) return
        db.transaction { for (id in gone) deleteRows(id) }
    }

    fun deleteProfile(profileId: String) {
        db.transaction { deleteRows(profileId) }
    }

    private fun deleteRows(profileId: String) {
        for (table in PilotSchema.profileTables) db.execute("DELETE FROM $table WHERE profileId = ?", listOf(profileId))
    }

    /** Everything, the install row included: after "Șterge tot" the phone starts over. */
    fun wipe() {
        db.transaction {
            for (table in PilotSchema.profileTables + "install") db.execute("DELETE FROM $table")
        }
    }

    // MARK: Sessions and attempts

    fun sessionStarted(sessionId: String, profileId: String, day: String, startedAt: Long, track: String, focus: String?, sound: String?, rung: String?) {
        db.execute(
            "INSERT OR IGNORE INTO session (sessionId, profileId, day, startedAt, track, focus, sound, rung) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            listOf(sessionId, profileId, day, startedAt, track, focus, sound, rung),
        )
    }

    fun sessionEnded(sessionId: String, endedAt: Long, finished: Boolean) {
        db.execute(
            "UPDATE session SET endedAt = ?, finished = MAX(finished, ?) WHERE sessionId = ? AND endedAt IS NULL",
            listOf(endedAt, if (finished) 1 else 0, sessionId),
        )
    }

    fun addAttempt(record: AttemptRecord, day: String) {
        db.execute(
            """
            INSERT INTO attempt (profileId, sessionId, day, at, take, mode, exercise, level, sound, position, rung, block, schedule,
                                 machine, adult, self, decided, counted, dose, retry)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            listOf(
                record.profileId.toString(), record.sessionId?.toString(), day, record.at.toEpochMilli(), record.take, record.mode.code,
                record.exercise, record.level, record.sound, record.position.code, record.rung, record.block, record.feedback,
                record.machine.rawValue, record.adult?.rawValue, record.self?.rawValue, record.decided.rawValue, record.counted, record.dose,
                record.retry,
            ),
        )
        record.sessionId?.let { id ->
            db.execute("UPDATE session SET lastAt = MAX(IFNULL(lastAt, 0), ?) WHERE sessionId = ?", listOf(record.at.toEpochMilli(), id.toString()))
        }
    }

    fun addShadow(record: ShadowRecord, day: String) {
        db.execute(
            """
            INSERT INTO shadow (profileId, sessionId, day, at, take, context, sound, position, level, schedule, shown, status, verdict,
                                abstention, confidence, lpr, heardAs, fit, frames, targets, scoreMs, loadMs, waitMs, sampleRate, durationMs,
                                droppedSamples, snrDb, narrowBand, input, model, policy, dsp)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            listOf(
                record.profileId.toString(), record.sessionId?.toString(), day, record.at.toEpochMilli(), record.take, record.context.code,
                record.sound, record.position.code, record.level, record.feedback, record.shown.rawValue, record.status.code,
                record.verdict, record.abstention, record.confidence, record.lpr, record.heardAs, record.fit, record.frames, record.targets,
                record.scoreMs, record.loadMs, record.waitMs, record.sampleRate, record.durationMs, record.droppedSamples, record.snrDb, record.narrowBand,
                record.input, record.model, record.policy, record.dsp,
            ),
        )
    }

    fun addAccess(profileId: String?, day: String, at: Long, action: AccessAction) {
        db.execute("INSERT INTO access (profileId, day, at, action) VALUES (?, ?, ?, ?)", listOf(profileId, day, at, action.code))
    }

    fun addAdaptive(record: AdaptiveRecord, day: String) {
        val decision = record.decision
        db.execute(
            """
            INSERT INTO adaptive (profileId, sessionId, day, at, sound, kind, choice, reason, block, rung, fast, position, mode,
                                  good, total, skipped, value, previous, kp, kr)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            listOf(
                record.profileId.toString(), record.sessionId?.toString(), day, record.at.toEpochMilli(), decision.sound?.rawValue,
                decision.kind.rawValue, decision.choice, decision.reason, decision.block, decision.rung?.rawValue, decision.fast,
                decision.position?.rawValue, decision.mode?.rawValue, decision.good, decision.total, decision.skipped, decision.value,
                decision.previous, decision.kp, decision.kr,
            ),
        )
    }

    fun adaptiveCounters(profileId: String): List<AdaptiveCounter> {
        val detailed = db.query(
            """
            SELECT day, IFNULL(sound, ''), kind, choice, reason, COUNT(*) FROM adaptive WHERE profileId = ?
            GROUP BY day, IFNULL(sound, ''), kind, choice, reason
            """.trimIndent(),
            listOf(profileId),
        ) { AdaptiveCounter(it.getText(0), it.getText(1), it.getText(2), it.getText(3), it.getText(4), it.getLong(5).toInt()) }
        val kept = db.query("SELECT day, sound, kind, choice, reason, decisions FROM dailyAdaptive WHERE profileId = ?", listOf(profileId)) {
            AdaptiveCounter(it.getText(0), it.getText(1), it.getText(2), it.getText(3), it.getText(4), it.getLong(5).toInt())
        }
        return (kept + detailed).sortedWith(compareBy({ it.day }, { it.sound }, { it.kind }, { it.choice }, { it.reason }))
    }

    /** The detailed adaptive rows of a profile, oldest first (tests and the instrumented check). */
    fun adaptiveRows(profileId: String): List<Map<String, Any?>> =
        db.query(
            "SELECT sound, kind, choice, reason, block, rung, fast, mode, good, total, value, kp, kr, sessionId FROM adaptive WHERE profileId = ? ORDER BY id",
            listOf(profileId),
        ) {
            mapOf(
                "sound" to if (it.isNull(0)) null else it.getText(0), "kind" to it.getText(1), "choice" to it.getText(2), "reason" to it.getText(3),
                "block" to if (it.isNull(4)) null else it.getLong(4).toInt(), "rung" to if (it.isNull(5)) null else it.getText(5),
                "fast" to if (it.isNull(6)) null else it.getLong(6) == 1L, "mode" to if (it.isNull(7)) null else it.getText(7),
                "good" to if (it.isNull(8)) null else it.getLong(8).toInt(), "total" to if (it.isNull(9)) null else it.getLong(9).toInt(),
                "value" to if (it.isNull(10)) null else it.getDouble(10), "kp" to if (it.isNull(11)) null else it.getLong(11) == 1L,
                "kr" to if (it.isNull(12)) null else it.getLong(12).toInt(), "sessionId" to if (it.isNull(13)) null else it.getText(13),
            )
        }

    // MARK: Reading, detail and counters together

    fun attemptCounters(profileId: String): List<AttemptCounter> {
        val detailed = db.query(
            """
            SELECT day, mode, IFNULL(sound, ''), position, IFNULL(rung, ''), IFNULL(schedule, ''),
                   COUNT(*), SUM(dose), SUM(decided = 'good'), SUM(decided = 'almost'), SUM(decided = 'notYet'),
                   SUM(decided IN ('unclear', 'notJudged'))
            FROM attempt WHERE profileId = ?
            GROUP BY day, mode, IFNULL(sound, ''), position, IFNULL(rung, ''), IFNULL(schedule, '')
            """.trimIndent(),
            listOf(profileId),
            ::attemptCounter,
        )
        val kept = db.query(
            "SELECT day, mode, sound, position, rung, schedule, attempts, dose, good, almost, notYet, unjudged FROM dailyAttempt WHERE profileId = ?",
            listOf(profileId),
            ::attemptCounter,
        )
        return (kept + detailed).sortedWith(compareBy({ it.day }, { it.mode }, { it.sound }, { it.position }, { it.rung }, { it.feedback }))
    }

    private fun attemptCounter(row: SQLiteStatement) = AttemptCounter(
        day = row.getText(0), mode = row.getText(1), sound = row.getText(2), position = row.getText(3), rung = row.getText(4),
        feedback = row.getText(5), attempts = row.getLong(6).toInt(), dose = row.getLong(7).toInt(), good = row.getLong(8).toInt(),
        almost = row.getLong(9).toInt(), notYet = row.getLong(10).toInt(), unjudged = row.getLong(11).toInt(),
    )

    fun sessionDays(profileId: String): List<SessionDay> {
        val detailed = db.query(
            """
            SELECT day, COUNT(*), SUM(finished), SUM((COALESCE(endedAt, lastAt, startedAt) - startedAt) / 1000)
            FROM session WHERE profileId = ? GROUP BY day
            """.trimIndent(),
            listOf(profileId),
        ) { SessionDay(it.getText(0), it.getLong(1).toInt(), it.getLong(2).toInt(), it.getLong(3)) }
        val kept = db.query("SELECT day, sessions, finished, seconds FROM dailySession WHERE profileId = ?", listOf(profileId)) {
            SessionDay(it.getText(0), it.getLong(1).toInt(), it.getLong(2).toInt(), it.getLong(3))
        }
        return (kept + detailed).groupBy { it.day }
            .map { (day, rows) -> SessionDay(day, rows.sumOf { it.sessions }, rows.sumOf { it.finished }, rows.sumOf { it.seconds }) }
            .sortedBy { it.day }
    }

    fun shadowCounters(profileId: String): List<ShadowCounter> {
        val detailed = db.query(
            "SELECT day, sound, shown, status, IFNULL(verdict, ''), COUNT(*) FROM shadow WHERE profileId = ? GROUP BY day, sound, shown, status, IFNULL(verdict, '')",
            listOf(profileId),
        ) { ShadowCounter(it.getText(0), it.getText(1), it.getText(2), it.getText(3), it.getText(4), it.getLong(5).toInt()) }
        val kept = db.query("SELECT day, sound, shown, status, verdict, takes FROM dailyShadow WHERE profileId = ?", listOf(profileId)) {
            ShadowCounter(it.getText(0), it.getText(1), it.getText(2), it.getText(3), it.getText(4), it.getLong(5).toInt())
        }
        return kept + detailed
    }

    fun shadowDetails(profileId: String): List<ShadowDetail> =
        db.query("SELECT sound, verdict, abstention, scoreMs FROM shadow WHERE profileId = ? AND status = 'scored'", listOf(profileId)) {
            ShadowDetail(it.getText(0), if (it.isNull(1)) null else it.getText(1), if (it.isNull(2)) null else it.getText(2), if (it.isNull(3)) null else it.getLong(3))
        }

    /** The models and thresholds the detailed shadow rows were scored with. */
    fun shadowVersions(profileId: String): List<Pair<String, String>> =
        db.query("SELECT DISTINCT IFNULL(model, ''), IFNULL(policy, '') FROM shadow WHERE profileId = ? AND status = 'scored'", listOf(profileId)) {
            it.getText(0) to it.getText(1)
        }

    fun phoneVsAdult(profileId: String): List<PhoneVsAdult> = db.query(
        """
        SELECT sound,
               SUM(machine IN ('good', 'almost', 'notYet')),
               SUM(machine = adult),
               SUM(machine IN ('unclear', 'notJudged'))
        FROM attempt
        WHERE profileId = ? AND mode = 'production' AND sound IS NOT NULL AND adult IN ('good', 'almost', 'notYet')
        GROUP BY sound ORDER BY sound
        """.trimIndent(),
        listOf(profileId),
    ) { PhoneVsAdult(it.getText(0), it.getLong(1).toInt(), it.getLong(2).toInt(), it.getLong(3).toInt()) }

    fun access(profileId: String): List<AccessEntry> =
        db.query("SELECT day, action FROM access WHERE profileId = ? ORDER BY at", listOf(profileId)) { AccessEntry(it.getText(0), it.getText(1)) }

    // MARK: Retention

    /**
     * Turns every detailed row of a day before [cutoffDay] into the day's counters and deletes it: what stays of those
     * days is how much was practised, not what happened on each take.
     */
    fun compact(cutoffDay: String) {
        db.transaction {
            db.execute(
                """
                INSERT INTO dailyAttempt (profileId, day, mode, sound, position, rung, schedule, attempts, dose, good, almost, notYet, unjudged)
                SELECT profileId, day, mode, IFNULL(sound, ''), position, IFNULL(rung, ''), IFNULL(schedule, ''),
                       COUNT(*), SUM(dose), SUM(decided = 'good'), SUM(decided = 'almost'), SUM(decided = 'notYet'),
                       SUM(decided IN ('unclear', 'notJudged'))
                FROM attempt WHERE day < ?
                GROUP BY profileId, day, mode, IFNULL(sound, ''), position, IFNULL(rung, ''), IFNULL(schedule, '')
                ON CONFLICT (profileId, day, mode, sound, position, rung, schedule) DO UPDATE SET
                    attempts = attempts + excluded.attempts, dose = dose + excluded.dose, good = good + excluded.good,
                    almost = almost + excluded.almost, notYet = notYet + excluded.notYet, unjudged = unjudged + excluded.unjudged
                """.trimIndent(),
                listOf(cutoffDay),
            )
            db.execute("DELETE FROM attempt WHERE day < ?", listOf(cutoffDay))
            db.execute(
                """
                INSERT INTO dailySession (profileId, day, sessions, finished, seconds)
                SELECT profileId, day, COUNT(*), SUM(finished), SUM((COALESCE(endedAt, lastAt, startedAt) - startedAt) / 1000)
                FROM session WHERE day < ? GROUP BY profileId, day
                ON CONFLICT (profileId, day) DO UPDATE SET
                    sessions = sessions + excluded.sessions, finished = finished + excluded.finished, seconds = seconds + excluded.seconds
                """.trimIndent(),
                listOf(cutoffDay),
            )
            db.execute("DELETE FROM session WHERE day < ?", listOf(cutoffDay))
            db.execute(
                """
                INSERT INTO dailyShadow (profileId, day, sound, shown, status, verdict, takes)
                SELECT profileId, day, sound, shown, status, IFNULL(verdict, ''), COUNT(*)
                FROM shadow WHERE day < ? GROUP BY profileId, day, sound, shown, status, IFNULL(verdict, '')
                ON CONFLICT (profileId, day, sound, shown, status, verdict) DO UPDATE SET takes = takes + excluded.takes
                """.trimIndent(),
                listOf(cutoffDay),
            )
            db.execute("DELETE FROM shadow WHERE day < ?", listOf(cutoffDay))
            db.execute("DELETE FROM access WHERE day < ?", listOf(cutoffDay))
            db.execute(
                """
                INSERT INTO dailyAdaptive (profileId, day, sound, kind, choice, reason, decisions)
                SELECT profileId, day, IFNULL(sound, ''), kind, choice, reason, COUNT(*)
                FROM adaptive WHERE day < ? GROUP BY profileId, day, IFNULL(sound, ''), kind, choice, reason
                ON CONFLICT (profileId, day, sound, kind, choice, reason) DO UPDATE SET decisions = decisions + excluded.decisions
                """.trimIndent(),
                listOf(cutoffDay),
            )
            db.execute("DELETE FROM adaptive WHERE day < ?", listOf(cutoffDay))
        }
    }

    /** Rows per table, for the tests and the developer row. */
    fun rowCounts(): Map<String, Int> =
        (PilotSchema.profileTables + "install").associateWith { table -> db.query("SELECT COUNT(*) FROM $table") { it.getLong(0).toInt() }.first() }

    /** The detailed shadow rows of a profile, newest last (tests and the instrumented check). */
    fun shadowRows(profileId: String): List<Map<String, Any?>> =
        db.query("SELECT take, sound, shown, status, verdict, abstention, scoreMs, input, model FROM shadow WHERE profileId = ? ORDER BY id", listOf(profileId)) {
            mapOf(
                "take" to it.getText(0), "sound" to it.getText(1), "shown" to it.getText(2), "status" to it.getText(3),
                "verdict" to if (it.isNull(4)) null else it.getText(4), "abstention" to if (it.isNull(5)) null else it.getText(5),
                "scoreMs" to if (it.isNull(6)) null else it.getLong(6), "input" to it.getText(7), "model" to if (it.isNull(8)) null else it.getText(8),
            )
        }

    /** The detailed attempt rows of a profile, oldest first. */
    fun attemptRows(profileId: String): List<Map<String, Any?>> =
        db.query(
            "SELECT take, mode, sound, position, machine, adult, decided, dose, exercise, level FROM attempt WHERE profileId = ? ORDER BY id",
            listOf(profileId),
        ) {
            mapOf(
                "take" to it.getText(0), "mode" to it.getText(1), "sound" to if (it.isNull(2)) null else it.getText(2),
                "position" to it.getText(3), "machine" to it.getText(4), "adult" to if (it.isNull(5)) null else it.getText(5),
                "decided" to it.getText(6), "dose" to (it.getLong(7) == 1L), "exercise" to it.getText(8),
                "level" to if (it.isNull(9)) null else it.getText(9),
            )
        }

    private companion object {
        private val random = SecureRandom()

        /** No 0/O, 1/I/L: easy to read out over the phone. */
        private const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"

        fun newPseudonym(): String = "P-" + (1..6).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
    }
}
