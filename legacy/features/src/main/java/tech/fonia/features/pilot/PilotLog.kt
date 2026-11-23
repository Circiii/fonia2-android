package tech.fonia.features.pilot

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import tech.fonia.store.Profile
import tech.fonia.store.SessionRecord
import tech.fonia.store.localDay
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.Executors
import java.util.logging.Level
import java.util.logging.Logger

/** The phone and app the log runs on, written once into the install row. */
data class PilotEnvironment(val appVersion: String, val phone: String, val sdk: Int)

/** [PilotLog.snapshot]: the detailed rows by profile id, and the number of rows in each table. */
data class PilotSnapshot(
    val install: PilotInstall?,
    val profiles: List<PilotProfile>,
    val attempts: Map<String, List<Map<String, Any?>>>,
    val shadow: Map<String, List<Map<String, Any?>>>,
    val rows: Map<String, Int>,
    /** The adaptive pilot's decisions, by profile id. */
    val adaptive: Map<String, List<Map<String, Any?>>> = emptyMap(),
)

/**
 * The pilot's event log on the phone (docs/PLAN.md §6, 03_cercetare_produs.md §4.1): activation, sessions per day and
 * their length, every production, listening and mirror attempt with its sound and position (the dose), the verdict the
 * child got next to the adult's and to the shadow scorer's, and the retention markers that follow from the days.
 * No audio, ever, and no free text.
 *
 * Writes never wait: each call queues its row on the log's own thread and returns, in the order the calls were made.
 * Reads and the export run on the same thread, after every write asked for before them.
 *
 * Retention and deletion follow FoniaStore's: deleting a profile ([deleteProfile], or a profile that disappears from
 * [profiles]) removes all of its rows, "Șterge tot" ([wipe]) removes everything, and after [DETAIL_DAYS] days the
 * detailed rows of a day become that day's counters (the 90 days recordings are kept at most).
 */
class PilotLog internal constructor(
    private val store: PilotStore,
    private val environment: PilotEnvironment,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val dispatcher: CoroutineDispatcher = newLogThread(),
) : AutoCloseable {
    private val scope = CoroutineScope(
        SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, error -> log.log(Level.WARNING, "pilot log", error) },
    )

    @Volatile private var closed = false

    private fun dayOf(at: Instant): String = localDay(at, zone())

    /** Queues [block] behind every write asked for so far. A failure loses that row and nothing else. */
    private fun write(block: PilotStore.() -> Unit) {
        if (closed) return
        scope.launch {
            try {
                store.block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                log.log(Level.WARNING, "pilot log write failed", error)
            }
        }
    }

    /** Runs [block] on the log's thread, after the writes queued before it. */
    internal suspend fun <T> read(block: PilotStore.() -> T): T = withContext(dispatcher) { store.block() }

    /** At launch: the install row the first time, then the retention pass. */
    fun start(now: Instant = Instant.now()) {
        write {
            install(dayOf(now), environment.appVersion, environment.phone, environment.sdk)
            compact(LocalDate.parse(dayOf(now)).minusDays(DETAIL_DAYS.toLong()).toString())
        }
    }

    /** The profiles FoniaStore has now. New ones get a pseudonym; the rows of the ones gone are deleted. */
    fun profiles(profiles: List<Profile>) {
        val rows = profiles.map { Triple(it, it.id.toString(), dayOf(it.createdAt)) }
        write {
            for ((profile, id, setupDay) in rows) profile(id, profile.kind.rawValue, profile.ageBand.rawValue, setupDay)
            keepOnly(rows.map { it.second }.toSet())
        }
    }

    fun sessionStarted(record: SessionRecord, rung: String?) {
        val day = dayOf(record.startedAt)
        write {
            if (pilotProfile(record.profileId.toString()) == null) return@write
            sessionStarted(
                sessionId = record.id.toString(),
                profileId = record.profileId.toString(),
                day = day,
                startedAt = record.startedAt.toEpochMilli(),
                track = record.track.rawValue,
                focus = record.focus,
                sound = record.sound,
                rung = rung,
            )
        }
    }

    fun sessionEnded(sessionId: UUID, finished: Boolean, at: Instant = Instant.now()) {
        write { sessionEnded(sessionId.toString(), at.toEpochMilli(), finished) }
    }

    fun attempt(record: AttemptRecord) {
        val day = dayOf(record.at)
        write { if (pilotProfile(record.profileId.toString()) != null) addAttempt(record, day) }
    }

    fun shadow(record: ShadowRecord) {
        val day = dayOf(record.at)
        write { if (pilotProfile(record.profileId.toString()) != null) addShadow(record, day) }
    }

    fun exported(profileId: UUID, at: Instant = Instant.now()) {
        val day = dayOf(at)
        write { addAccess(profileId.toString(), day, at.toEpochMilli(), AccessAction.Export) }
    }

    /** A decision of the adaptive pilot (docs/PLAN.md §7.4). */
    fun adaptive(record: AdaptiveRecord) {
        val day = dayOf(record.at)
        write { if (pilotProfile(record.profileId.toString()) != null) addAdaptive(record, day) }
    }

    suspend fun deleteProfile(id: UUID) = read { deleteProfile(id.toString()) }

    suspend fun wipe() = read { wipe() }

    /** Waits for the writes queued so far. */
    suspend fun flush() = read { }

    /** The dose counter of the grown-ups' area, sounds in [order] first. */
    suspend fun dose(profileId: UUID, today: LocalDate = LocalDate.now(zone()), order: List<String> = emptyList()): DoseSummary =
        read { PilotMath.doses(attemptCounters(profileId.toString()), today, order) }

    /**
     * The summary for the logoped (03 §4.3) as a file name and its bytes. A profile the log hasn't met yet is added
     * first, so the summary is never missing. [adaptive] is the adaptive pilot's state of the profile
     * (`AdaptiveCodec.summary`), for a profile that was ever in it; with it, or with adaptive decisions logged, the
     * summary ends with an `adaptive` section.
     */
    suspend fun export(
        profile: Profile,
        dspThresholdsVersion: Int,
        today: LocalDate = LocalDate.now(zone()),
        adaptive: JsonObject? = null,
    ): Pair<String, ByteArray> {
        val id = profile.id.toString()
        val setupDay = dayOf(profile.createdAt)
        return read {
            profile(id, profile.kind.rawValue, profile.ageBand.rawValue, setupDay)
            val known = pilotProfile(id) ?: error("profile not in the pilot log")
            val input = PilotExportInput(
                generatedOn = today,
                install = installInfo(),
                profile = known,
                targetSounds = profile.targetSounds,
                detailDays = DETAIL_DAYS,
                dspThresholdsVersion = dspThresholdsVersion,
                counters = attemptCounters(id),
                sessionDays = sessionDays(id),
                shadowCounters = shadowCounters(id),
                shadowDetails = shadowDetails(id),
                shadowVersions = shadowVersions(id),
                phoneVsAdult = phoneVsAdult(id),
                access = access(id),
                adaptive = adaptive,
                adaptiveCounters = adaptiveCounters(id),
            )
            PilotExport.fileName(known.pseudonym) to PilotExport.encode(input)
        }
    }

    /** Row by row, per profile, for the instrumented check and a developer's look. Nothing in it is audio or text. */
    suspend fun snapshot(): PilotSnapshot = read {
        val known = profiles()
        PilotSnapshot(
            install = installInfo(),
            profiles = known,
            attempts = known.associate { it.profileId to attemptRows(it.profileId) },
            shadow = known.associate { it.profileId to shadowRows(it.profileId) },
            rows = rowCounts(),
            adaptive = known.associate { it.profileId to adaptiveRows(it.profileId) },
        )
    }

    /** Lets the queued writes finish, then closes the file. Later calls are ignored. */
    override fun close() {
        if (closed) return
        closed = true
        scope.launch { runCatching { store.close() } }
        (dispatcher as? ExecutorCoroutineDispatcher)?.close()
    }

    companion object {
        /** Days a detailed row stays before only its day's counters do: as long as a recording may stay. */
        const val DETAIL_DAYS = 90

        private val log: Logger = Logger.getLogger("tech.fonia.pilot")

        fun defaultFolder(context: Context): File = File(context.noBackupFilesDir, "FoniaPilot")

        /** The log in `noBackupFilesDir/FoniaPilot`, a folder Android never backs up. */
        fun open(folder: File, environment: PilotEnvironment): PilotLog = PilotLog(PilotStore(PilotDatabase.open(folder)), environment)

        /** For the tests: rows in memory, the caller's clock zone. */
        internal fun inMemory(environment: PilotEnvironment = PilotEnvironment("test", "JVM", 0), zone: ZoneId = ZoneId.of("Europe/Bucharest")): PilotLog =
            PilotLog(PilotStore(PilotDatabase.inMemory()), environment, zone = { zone })

        private fun newLogThread(): ExecutorCoroutineDispatcher =
            Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "fonia-pilot").apply { isDaemon = true } }.asCoroutineDispatcher()
    }
}
