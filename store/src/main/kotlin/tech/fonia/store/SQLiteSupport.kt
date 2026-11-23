package tech.fonia.store

import androidx.sqlite.SQLITE_DATA_BLOB
import androidx.sqlite.SQLITE_DATA_FLOAT
import androidx.sqlite.SQLITE_DATA_INTEGER
import androidx.sqlite.SQLITE_DATA_NULL
import androidx.sqlite.SQLITE_DATA_TEXT
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_CREATE
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.File
import java.nio.ByteBuffer
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

// The part of GRDB that FoniaStore relies on, over the androidx.sqlite bundled driver with plain SQL:
// connections set up the way GRDB sets them up, its on-disk value conventions, DatabaseQueue, DatabasePool,
// DatabaseMigrator and Codable records. iOS gets all of this from GRDB 7; there is no Swift file to mirror.

// MARK: Values as GRDB stores them

/** `UUID.uuidString`: uppercase, as Foundation writes it in file names and in the export. */
internal val UUID.uuidString: String get() = toString().uppercase(Locale.ROOT)

/** GRDB stores a UUID as a 16-byte BLOB, in RFC 4122 byte order. */
internal fun UUID.databaseBytes(): ByteArray =
    ByteBuffer.allocate(16).putLong(mostSignificantBits).putLong(leastSignificantBits).array()

internal fun uuidFromDatabaseBytes(bytes: ByteArray): UUID? {
    if (bytes.size != 16) return null
    val buffer = ByteBuffer.wrap(bytes)
    return UUID(buffer.long, buffer.long)
}

private val uuidPattern = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

/** `UUID(uuidString:)`, which GRDB also accepts from a TEXT column. */
internal fun uuidFromString(text: String): UUID? = if (uuidPattern.matches(text)) UUID.fromString(text) else null

private val storageDateFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS", Locale.ROOT).withZone(ZoneOffset.UTC)

/** GRDB stores a date as `YYYY-MM-DD HH:MM:SS.SSS` in UTC, truncated to the millisecond. */
internal fun Instant.databaseText(): String = storageDateFormat.format(this)

private val databaseDatePattern =
    Regex("""(\d{4})-(\d{2})-(\d{2})(?:[ T](\d{2}):(\d{2})(?::(\d{2})(?:\.(\d+))?)?(Z|[+-]\d{2}:\d{2})?)?""")

/**
 * GRDB's `SQLiteDateParser`: `YYYY-MM-DD`, then optionally ` HH:MM` or `THH:MM`, `:SS`, a fraction of which only
 * the first three digits count, and `Z` or `±HH:MM`. Without an offset the time is UTC.
 */
internal fun parseDatabaseDate(text: String): Instant? {
    val match = databaseDatePattern.matchEntire(text) ?: return null
    val (year, month, day, hour, minute, second, fraction, zone) = match.destructured
    return try {
        val nanos = if (fraction.isEmpty()) 0 else fraction.take(3).padEnd(3, '0').toInt() * 1_000_000
        val local = LocalDateTime.of(
            year.toInt(), month.toInt(), day.toInt(),
            hour.ifEmpty { "0" }.toInt(), minute.ifEmpty { "0" }.toInt(), second.ifEmpty { "0" }.toInt(), nanos,
        )
        val offset = if (zone.isEmpty() || zone == "Z") ZoneOffset.UTC else ZoneOffset.of(zone)
        local.toInstant(offset)
    } catch (_: DateTimeException) {
        null
    }
}

/** A failure GRDB reports as a `DatabaseError`. */
internal class DatabaseError(message: String) : Exception(message)

/** A column GRDB could not decode into the record's property. */
internal class RowDecodingError(message: String) : Exception(message)

/** GRDB's `RecordError.recordNotFound`: an update found no row with the record's key. */
internal class RecordNotFound(table: String) : Exception("Key not found in table $table")

// MARK: Connections

/** GRDB's `Configuration`, reduced to the parts in use. */
internal class Configuration {
    /** On by default in GRDB, for every connection. */
    var foreignKeysEnabled: Boolean = true

    private val preparations = mutableListOf<(Database) -> Unit>()

    /** Runs on every new connection, after GRDB's own setup. */
    fun prepareDatabase(body: (Database) -> Unit) {
        preparations += body
    }

    internal fun setUp(db: Database) {
        if (foreignKeysEnabled) db.execute("PRAGMA foreign_keys = ON")
        for (preparation in preparations) preparation(db)
    }
}

private val driver = BundledSQLiteDriver()

internal fun openDatabase(path: String, flags: Int, configuration: Configuration): Database {
    val database = Database(driver.open(path, flags))
    try {
        configuration.setUp(database)
    } catch (error: Throwable) {
        database.close()
        throw error
    }
    return database
}

internal enum class TransactionKind(val sql: String) {
    Deferred("DEFERRED"),
    Immediate("IMMEDIATE"),
}

internal enum class CheckpointMode(val sql: String) {
    Passive("PASSIVE"),
    Full("FULL"),
    Restart("RESTART"),
    Truncate("TRUNCATE"),
}

/** One connection, with the calls FoniaStore makes on GRDB's `Database`. Not thread-safe: writers serialize it. */
internal class Database(private val connection: SQLiteConnection) {

    fun execute(sql: String, arguments: List<Any?> = emptyList()) {
        connection.prepare(sql).use { statement ->
            statement.bindAll(arguments)
            while (statement.step()) {
                // Some statements, such as `PRAGMA journal_mode`, answer with a row nobody reads.
            }
        }
    }

    fun <T> fetchAll(sql: String, arguments: List<Any?> = emptyList(), decode: (Row) -> T): List<T> =
        connection.prepare(sql).use { statement ->
            statement.bindAll(arguments)
            val row = Row(statement)
            val values = ArrayList<T>()
            while (statement.step()) values.add(decode(row))
            values
        }

    fun <T : Any> fetchOne(sql: String, arguments: List<Any?> = emptyList(), decode: (Row) -> T): T? =
        connection.prepare(sql).use { statement ->
            statement.bindAll(arguments)
            if (statement.step()) decode(Row(statement)) else null
        }

    fun fetchLong(sql: String, arguments: List<Any?> = emptyList()): Long? =
        fetchAll(sql, arguments) { row -> row.longOrNull(0) }.firstOrNull()

    fun fetchCount(sql: String, arguments: List<Any?> = emptyList()): Int =
        fetchOne(sql, arguments) { row -> row.int(0) } ?: 0

    fun tableExists(name: String): Boolean = fetchOne(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND LOWER(name) = LOWER(?) " +
            "UNION ALL SELECT 1 FROM sqlite_temp_master WHERE type = 'table' AND LOWER(name) = LOWER(?)",
        listOf(name, name),
    ) { true } ?: false

    /** Rows changed by the last INSERT, UPDATE or DELETE. */
    fun changesCount(): Int = fetchOne("SELECT changes()") { row -> row.int(0) } ?: 0

    /** GRDB's `checkpoint`: fails, like `sqlite3_wal_checkpoint_v2`, when a reader keeps it from completing. */
    fun checkpoint(mode: CheckpointMode) {
        val busy = fetchOne("PRAGMA wal_checkpoint(${mode.sql})") { row -> row.long(0) }
        if (busy == 1L) throw DatabaseError("database is locked: the ${mode.sql} checkpoint could not complete")
    }

    fun <T> inTransaction(kind: TransactionKind = TransactionKind.Deferred, block: () -> T): T {
        execute("BEGIN ${kind.sql} TRANSACTION")
        try {
            val value = block()
            execute("COMMIT TRANSACTION")
            return value
        } catch (error: Throwable) {
            if (connection.inTransaction()) {
                try {
                    execute("ROLLBACK TRANSACTION")
                } catch (rollback: Throwable) {
                    error.addSuppressed(rollback)
                }
            }
            throw error
        }
    }

    fun close() {
        connection.close()
    }
}

private fun SQLiteStatement.bindAll(arguments: List<Any?>) {
    arguments.forEachIndexed { offset, value ->
        val index = offset + 1
        when (value) {
            null -> bindNull(index)
            is String -> bindText(index, value)
            is Long -> bindLong(index, value)
            is Int -> bindLong(index, value.toLong())
            is Boolean -> bindLong(index, if (value) 1L else 0L)
            is Double -> bindDouble(index, value)
            is UUID -> bindBlob(index, value.databaseBytes())
            is Instant -> bindText(index, value.databaseText())
            is ByteArray -> bindBlob(index, value)
            else -> throw IllegalArgumentException("Cannot store a ${value::class.java.name} in SQLite")
        }
    }
}

/**
 * The current row of a statement, decoded the way GRDB decodes `Codable` records: columns by name (the first one
 * wins), SQLite's own conversions for text and numbers, and an error for NULL in a non-optional property.
 */
internal class Row(private val statement: SQLiteStatement) {
    private val indexes: Map<String, Int> = buildMap {
        for (index in 0 until statement.getColumnCount()) {
            putIfAbsent(statement.getColumnName(index).lowercase(Locale.ROOT), index)
        }
    }

    private fun index(column: String): Int =
        indexes[column.lowercase(Locale.ROOT)] ?: throw RowDecodingError("No such column: $column")

    private fun missing(column: Any): Nothing = throw RowDecodingError("Unexpected NULL in column $column")

    private fun mismatch(column: Any, type: String): Nothing =
        throw RowDecodingError("Could not decode $type from column $column")

    fun isNull(column: String): Boolean = statement.isNull(index(column))

    fun stringOrNull(index: Int): String? = if (statement.isNull(index)) null else statement.getText(index)

    fun stringOrNull(column: String): String? = stringOrNull(index(column))

    fun string(index: Int): String = stringOrNull(index) ?: missing(index)

    fun string(column: String): String = stringOrNull(column) ?: missing(column)

    fun longOrNull(index: Int): Long? = if (statement.isNull(index)) null else statement.getLong(index)

    fun long(index: Int): Long = longOrNull(index) ?: missing(index)

    fun long(column: String): Long = long(index(column))

    fun intOrNull(column: String): Int? {
        val value = longOrNull(index(column)) ?: return null
        if (value !in Int.MIN_VALUE..Int.MAX_VALUE) mismatch(column, "Int")
        return value.toInt()
    }

    fun int(index: Int): Int {
        val value = long(index)
        if (value !in Int.MIN_VALUE..Int.MAX_VALUE) mismatch(index, "Int")
        return value.toInt()
    }

    fun int(column: String): Int = int(index(column))

    fun double(column: String): Double {
        val index = index(column)
        if (statement.isNull(index)) missing(column)
        return statement.getDouble(index)
    }

    /** GRDB's `Bool`: any non-zero number. */
    fun bool(column: String): Boolean = long(column) != 0L

    /** A 16-byte BLOB, or the text form GRDB also accepts. */
    fun uuidOrNull(column: String): UUID? {
        val index = index(column)
        return when (statement.getColumnType(index)) {
            SQLITE_DATA_NULL -> null
            SQLITE_DATA_BLOB -> uuidFromDatabaseBytes(statement.getBlob(index)) ?: mismatch(column, "UUID")
            SQLITE_DATA_TEXT -> uuidFromString(statement.getText(index)) ?: mismatch(column, "UUID")
            else -> mismatch(column, "UUID")
        }
    }

    fun uuid(column: String): UUID = uuidOrNull(column) ?: missing(column)

    /** A date string, or a number of seconds since 1970, as GRDB reads a `Date`. */
    fun instantOrNull(column: String): Instant? {
        val index = index(column)
        return when (statement.getColumnType(index)) {
            SQLITE_DATA_NULL -> null
            SQLITE_DATA_TEXT -> parseDatabaseDate(statement.getText(index)) ?: mismatch(column, "Date")
            SQLITE_DATA_INTEGER -> Instant.ofEpochSecond(statement.getLong(index))
            SQLITE_DATA_FLOAT -> instantFromSecondsSince1970(statement.getDouble(index))
            else -> mismatch(column, "Date")
        }
    }

    fun instant(column: String): Instant = instantOrNull(column) ?: missing(column)

    /** A `RawRepresentable` enum stored as its raw value. */
    fun <E : Any> rawValue(column: String, fromRawValue: (String) -> E?): E {
        val raw = string(column)
        return fromRawValue(raw) ?: mismatch(column, "a case from \"$raw\"")
    }

    fun <E : Any> rawValueOrNull(column: String, fromRawValue: (String) -> E?): E? {
        val raw = stringOrNull(column) ?: return null
        return fromRawValue(raw) ?: mismatch(column, "a case from \"$raw\"")
    }

    /** A JSON column, decoded with GRDB's JSON decoder conventions by [decode]. */
    fun <T : Any> jsonOrNull(column: String, decode: (JsonElement) -> T): T? {
        val text = stringOrNull(column) ?: return null
        return try {
            decode(Json.parseToJsonElement(text))
        } catch (error: Exception) {
            throw RowDecodingError("Could not decode the JSON in column $column: ${error.message}")
        }
    }

    fun <T : Any> json(column: String, decode: (JsonElement) -> T): T = jsonOrNull(column, decode) ?: missing(column)
}

// MARK: Writers

/** GRDB's `DatabaseWriter`, with what FoniaStore calls. The blocking variants serve `open` and the migrator. */
internal abstract class DatabaseWriter : AutoCloseable {
    private val commitCount = MutableStateFlow(0L)

    /** Moves on after every committed write. Live observation re-reads its value from here. */
    val commits: StateFlow<Long> = commitCount.asStateFlow()

    /** Runs [block] in a transaction and commits it, or rolls it back if [block] throws. */
    abstract fun <T> writeBlocking(block: (Database) -> T): T

    abstract fun <T> writeWithoutTransactionBlocking(block: (Database) -> T): T

    /** Runs [block] on a snapshot of the last commit. */
    abstract fun <T> readBlocking(block: (Database) -> T): T

    suspend fun <T> write(block: (Database) -> T): T = withContext(Dispatchers.IO) { writeBlocking(block) }

    suspend fun <T> writeWithoutTransaction(block: (Database) -> T): T =
        withContext(Dispatchers.IO) { writeWithoutTransactionBlocking(block) }

    suspend fun <T> read(block: (Database) -> T): T = withContext(Dispatchers.IO) { readBlocking(block) }

    internal fun didCommit() {
        commitCount.update { it + 1 }
    }
}

/** GRDB's `DatabaseQueue`: one connection, one access at a time. Without a path the rows live in memory. */
internal class DatabaseQueue(
    path: String = ":memory:",
    configuration: Configuration = Configuration(),
) : DatabaseWriter() {
    private val lock = ReentrantLock()
    private val db = openDatabase(path, SQLITE_OPEN_READWRITE or SQLITE_OPEN_CREATE, configuration)

    override fun <T> writeBlocking(block: (Database) -> T): T = lock.withLock {
        val value = db.inTransaction { block(db) }
        didCommit()
        value
    }

    override fun <T> writeWithoutTransactionBlocking(block: (Database) -> T): T = lock.withLock { block(db) }

    override fun <T> readBlocking(block: (Database) -> T): T = lock.withLock {
        // As GRDB reads a queue: in a transaction, with writes refused.
        db.execute("PRAGMA query_only = 1")
        try {
            db.inTransaction { block(db) }
        } finally {
            db.execute("PRAGMA query_only = 0")
        }
    }

    override fun close() {
        lock.withLock { db.close() }
    }
}

/**
 * GRDB's `DatabasePool`: the file in WAL mode with `synchronous = NORMAL`, one writer connection and up to five
 * read-only connections, so reads go on while a write runs and see the last commit.
 */
internal class DatabasePool(
    private val path: String,
    private val configuration: Configuration,
) : DatabaseWriter() {
    private val writerLock = ReentrantLock()
    private val writer = openDatabase(path, SQLITE_OPEN_READWRITE or SQLITE_OPEN_CREATE, configuration)
    private val readerPermits = Semaphore(MAXIMUM_READER_COUNT)
    private val readersLock = Any()
    private val idleReaders = ArrayDeque<Database>()
    private var closed = false

    init {
        try {
            val journalMode = writer.fetchOne("PRAGMA journal_mode = WAL") { row -> row.string(0) }
            if (!"wal".equals(journalMode, ignoreCase = true)) {
                throw DatabaseError("could not activate WAL Mode at path: $path")
            }
            writer.execute("PRAGMA synchronous = NORMAL")
            val wal = File("$path-wal")
            if (!wal.exists() || wal.length() == 0L) {
                // Read-only connections need a non-empty -wal file (GRDB issues 102 and 1383).
                writer.inTransaction {
                    writer.execute("CREATE TABLE grdb_issue_102 (id INTEGER PRIMARY KEY)")
                    writer.execute("DROP TABLE grdb_issue_102")
                }
            }
        } catch (error: Throwable) {
            writer.close()
            throw error
        }
    }

    override fun <T> writeBlocking(block: (Database) -> T): T = writerLock.withLock {
        val value = writer.inTransaction { block(writer) }
        didCommit()
        value
    }

    override fun <T> writeWithoutTransactionBlocking(block: (Database) -> T): T = writerLock.withLock { block(writer) }

    override fun <T> readBlocking(block: (Database) -> T): T {
        readerPermits.acquire()
        try {
            val reader = takeReader()
            try {
                return reader.inTransaction { block(reader) }
            } finally {
                putBack(reader)
            }
        } finally {
            readerPermits.release()
        }
    }

    private fun takeReader(): Database {
        synchronized(readersLock) {
            check(!closed) { "The database is closed" }
            idleReaders.removeFirstOrNull()?.let { return it }
        }
        val reader = openDatabase(path, SQLITE_OPEN_READONLY, configuration)
        try {
            // GRDB gives pool readers `readonlyBusyMode = .timeout(10)` for the rare SQLITE_BUSY in WAL mode.
            reader.execute("PRAGMA busy_timeout = 10000")
        } catch (error: Throwable) {
            reader.close()
            throw error
        }
        return reader
    }

    private fun putBack(reader: Database) {
        val keep = synchronized(readersLock) {
            if (!closed) idleReaders.addLast(reader)
            !closed
        }
        if (!keep) reader.close()
    }

    override fun close() {
        val readers = synchronized(readersLock) {
            closed = true
            idleReaders.toList().also { idleReaders.clear() }
        }
        readers.forEach { it.close() }
        writerLock.withLock { writer.close() }
    }

    private companion object {
        /** GRDB's default `maximumReaderCount`. */
        const val MAXIMUM_READER_COUNT = 5
    }
}

// MARK: Migrations

/** GRDB's `DatabaseMigrator`: named migrations, each run once and in order, recorded in `grdb_migrations`. */
internal class DatabaseMigrator {
    private val registered = mutableListOf<Pair<String, (Database) -> Unit>>()

    /** The identifiers of the registered migrations, in order. */
    val migrations: List<String> get() = registered.map { it.first }

    fun registerMigration(identifier: String, migrate: (Database) -> Unit) {
        require(registered.none { it.first == identifier }) { "already registered migration: \"$identifier\"" }
        registered += identifier to migrate
    }

    fun migrate(writer: DatabaseWriter) {
        val ran = writer.writeWithoutTransactionBlocking { db -> migrate(db) }
        if (ran) writer.didCommit()
    }

    /** Runs the migrations not applied yet and says whether there were any. */
    fun migrate(db: Database): Boolean {
        db.execute("CREATE TABLE IF NOT EXISTS grdb_migrations (identifier TEXT NOT NULL PRIMARY KEY)")
        val applied = appliedIdentifiers(db)
        var ran = false
        for ((identifier, migration) in registered) {
            if (identifier in applied) continue
            run(db, identifier, migration)
            ran = true
        }
        return ran
    }

    /** Applied migrations this migrator knows about. Unknown identifiers, from a newer app, are ignored. */
    fun appliedIdentifiers(db: Database): Set<String> {
        if (!db.tableExists("grdb_migrations")) return emptySet()
        val known = migrations.toSet()
        return db.fetchAll("SELECT identifier FROM grdb_migrations") { row -> row.string(0) }
            .filterTo(mutableSetOf()) { it in known }
    }

    fun hasCompletedMigrations(db: Database): Boolean = appliedIdentifiers(db).containsAll(migrations)

    /** GRDB's default deferred foreign key checks: keys are off while the migration runs, checked before it commits. */
    private fun run(db: Database, identifier: String, migration: (Database) -> Unit) {
        val foreignKeys = db.fetchLong("PRAGMA foreign_keys") == 1L
        if (foreignKeys) db.execute("PRAGMA foreign_keys = OFF")
        try {
            db.inTransaction(TransactionKind.Immediate) {
                migration(db)
                db.execute("INSERT INTO grdb_migrations (identifier) VALUES (?)", listOf(identifier))
                if (foreignKeys) {
                    val table = db.fetchOne("PRAGMA foreign_key_check") { row -> row.string(0) }
                    if (table != null) throw DatabaseError("FOREIGN KEY constraint failed in table $table")
                }
            }
        } finally {
            if (foreignKeys) db.execute("PRAGMA foreign_keys = ON")
        }
    }
}

// MARK: Records

internal val String.quotedIdentifier: String get() = "\"" + replace("\"", "\"\"") + "\""

/**
 * GRDB's `FetchableRecord` and `PersistableRecord` for a `Codable` type: [encode] lists the columns in property
 * order, [decode] reads them back by name. `save` updates every column and inserts only when no row has the key,
 * so the children of a saved parent are never cascaded away.
 */
internal abstract class RecordType<Record>(
    val databaseTableName: String,
    private val primaryKey: List<String>,
) {
    abstract fun encode(record: Record): List<Pair<String, Any?>>

    abstract fun decode(row: Row): Record

    private val table: String get() = databaseTableName.quotedIdentifier

    private val keyCondition: String get() = primaryKey.joinToString(" AND ") { "${it.quotedIdentifier} = ?" }

    fun insert(db: Database, record: Record) {
        val columns = encode(record)
        db.execute(
            "INSERT INTO $table (${columns.joinToString(", ") { it.first.quotedIdentifier }}) " +
                "VALUES (${columns.joinToString(", ") { "?" }})",
            columns.map { it.second },
        )
    }

    /** Throws [RecordNotFound] when no row has the record's key. */
    fun update(db: Database, record: Record) {
        if (!updateRow(db, record)) throw RecordNotFound(databaseTableName)
    }

    fun save(db: Database, record: Record) {
        if (!updateRow(db, record)) insert(db, record)
    }

    fun delete(db: Database, record: Record): Boolean = deleteOne(db, keyValues(record))

    fun deleteOne(db: Database, key: List<Any?>): Boolean {
        db.execute("DELETE FROM $table WHERE $keyCondition", key)
        return db.changesCount() > 0
    }

    fun deleteAll(db: Database): Int {
        db.execute("DELETE FROM $table")
        return db.changesCount()
    }

    fun fetchOne(db: Database, key: List<Any?>): Record? =
        db.fetchAll("SELECT * FROM $table WHERE $keyCondition", key, ::decode).firstOrNull()

    fun fetchAll(db: Database, sql: String, arguments: List<Any?> = emptyList()): List<Record> =
        db.fetchAll(sql, arguments, ::decode)

    private fun keyValues(record: Record): List<Any?> {
        val columns = encode(record).toMap()
        return primaryKey.map { columns[it] }
    }

    private fun updateRow(db: Database, record: Record): Boolean {
        val columns = encode(record)
        val keys = columns.filter { it.first in primaryKey }
        val assignments = columns.filter { it.first !in primaryKey }.ifEmpty { keys }
        db.execute(
            "UPDATE $table SET ${assignments.joinToString(", ") { "${it.first.quotedIdentifier} = ?" }} " +
                "WHERE ${keys.joinToString(" AND ") { "${it.first.quotedIdentifier} = ?" }}",
            assignments.map { it.second } + keys.map { it.second },
        )
        return db.changesCount() > 0
    }
}
