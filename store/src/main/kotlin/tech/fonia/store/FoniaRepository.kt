package tech.fonia.store

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.UUID
import tech.fonia.domain.ProbeSummary

sealed class FoniaStoreError(message: String) : Exception(message) {
    /** Equal to any other `ProfileNotFound`, like the Swift case. */
    class ProfileNotFound : FoniaStoreError("profileNotFound") {
        override fun equals(other: Any?): Boolean = other is ProfileNotFound

        override fun hashCode(): Int = javaClass.hashCode()
    }
}

/**
 * Everything the app reads and writes. Writes run in one transaction each, and the file changes that
 * go with them happen right after it commits.
 *
 * Swift's `async throws` functions are `suspend` functions here; the database work runs on [Dispatchers.IO].
 * The streams are cold [Flow]s that start observing when collected.
 */
class FoniaRepository(
    database: FoniaDatabase,
    retention: RecordingRetentionPolicy = RecordingRetentionPolicy.default,
) {
    internal val writer: DatabaseWriter = database.writer
    internal val files: RecordingFiles = database.files
    internal val retention: RecordingRetentionPolicy = retention

    // MARK: Profiles

    suspend fun saveProfile(profile: Profile) {
        writer.write { db -> Profile.save(db, profile) }
    }

    suspend fun profile(id: UUID): Profile? = writer.read { db -> Profile.fetchOne(db, listOf(id)) }

    /**
     * Removes the profile, everything that belongs to it and its audio files. This is the wipe of
     * one person, so it takes their history too.
     */
    suspend fun deleteProfile(id: UUID): Unit = runToCompletion {
        writer.write { db -> Profile.deleteOne(db, listOf(id)) }
        scrubDeletedPages()
        fileWork { files.removeFolder(id) }
    }

    /** Removes every row and every audio file. Open streams report empty values and stay open. */
    suspend fun wipeAll(): Unit = runToCompletion {
        writer.write { db ->
            // Every other table cascades from `profile`.
            Profile.deleteAll(db)
        }
        scrubDeletedPages()
        fileWork { files.removeEverything() }
    }

    /**
     * Moves deleted data out of the WAL, where `secure_delete` does not reach it. A reader can make
     * this fail, and SQLite will still overwrite those pages later, so a failure is only logged.
     */
    private suspend fun scrubDeletedPages() {
        try {
            writer.writeWithoutTransaction { db -> db.checkpoint(CheckpointMode.Truncate) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            storeLog.severe("WAL checkpoint after a delete failed: ${error.message}")
        }
    }

    // MARK: Sound progress

    suspend fun saveSoundProgress(progress: SoundProgress) {
        writer.write { db -> SoundProgress.save(db, progress) }
    }

    suspend fun soundProgress(profileId: UUID): List<SoundProgress> = writer.read { db ->
        SoundProgress.fetchAll(db, "SELECT * FROM soundProgress WHERE profileId = ? ORDER BY sound", listOf(profileId))
    }

    // MARK: Sessions

    /** There is no call that deletes a single session: the history only grows. */
    suspend fun addSession(session: SessionRecord) {
        writer.write { db -> SessionRecord.insert(db, session) }
    }

    suspend fun finishSession(id: UUID, endedAt: Instant = Instant.now()) {
        writer.write { db -> db.execute("UPDATE session SET endedAt = ? WHERE id = ?", listOf(endedAt, id)) }
    }

    suspend fun sessions(profileId: UUID): List<SessionRecord> = writer.read { db ->
        SessionRecord.fetchAll(db, "SELECT * FROM session WHERE profileId = ? ORDER BY startedAt", listOf(profileId))
    }

    // MARK: Attempts

    /** Every attempt since a day (`YYYY-MM-DD`), on any track, for "this week". */
    suspend fun attemptCount(profileId: UUID, sinceLocalDay: String): Int = writer.read { db ->
        db.fetchCount("SELECT COUNT(*) FROM attempt WHERE (profileId = ?) AND (localDay >= ?)", listOf(profileId, sinceLocalDay))
    }

    /** Saves the attempt and adds it to its session's count. */
    suspend fun addAttempt(attempt: Attempt) {
        writer.write { db ->
            Attempt.insert(db, attempt)
            db.execute("UPDATE session SET attempts = attempts + 1 WHERE id = ?", listOf(attempt.sessionId))
        }
    }

    /** Records the adult's verdict next to the machine's, which stays as it was. */
    suspend fun setAdultOutcome(attemptId: UUID, outcome: Attempt.Outcome?) {
        writer.write { db ->
            db.execute("UPDATE attempt SET adultOutcome = ? WHERE id = ?", listOf(outcome?.rawValue, attemptId))
        }
    }

    suspend fun attempts(sessionId: UUID): List<Attempt> = writer.read { db ->
        Attempt.fetchAll(db, "SELECT * FROM attempt WHERE sessionId = ? ORDER BY ts", listOf(sessionId))
    }

    /** Every take by one person between two days (`YYYY-MM-DD`, both included), on any sound, oldest first. */
    suspend fun attempts(profileId: UUID, fromLocalDay: String, throughLocalDay: String): List<Attempt> = writer.read { db ->
        Attempt.fetchAll(
            db,
            "SELECT * FROM attempt WHERE profileId = ? AND localDay >= ? AND localDay <= ? ORDER BY ts",
            listOf(profileId, fromLocalDay, throughLocalDay),
        )
    }

    // MARK: Recordings

    /** Where to write a new take, or read an existing one. Creates the protected folder if needed. */
    fun recordingFileURL(recording: Recording): File = files.prepareFolder(recording)

    /**
     * Registers a take whose file is already written, then applies retention to its profile.
     * Returns the takes that retention removed.
     */
    suspend fun addRecording(recording: Recording, now: Instant = Instant.now()): List<Recording> = runToCompletion {
        ProtectedStorage.protect(files.url(recording.fileName))
        val policy = retention
        val removed = writer.write { db ->
            Recording.insert(db, recording)
            applyRetention(policy, db, now, recording.profileId)
        }
        fileWork { files.remove(removed) }
        removed
    }

    /** Pinning or unpinning can change what retention keeps, so it runs again here. */
    suspend fun setPinned(recordingId: UUID, pinned: Boolean, now: Instant = Instant.now()): Unit = runToCompletion {
        val policy = retention
        val removed = writer.write { db ->
            val recording = Recording.fetchOne(db, listOf(recordingId)) ?: return@write emptyList()
            val updated = recording.copy(pinned = pinned)
            Recording.update(db, updated)
            applyRetention(policy, db, now, updated.profileId)
        }
        fileWork { files.remove(removed) }
    }

    suspend fun deleteRecording(id: UUID): Unit = runToCompletion {
        val removed = writer.write { db ->
            val recording = Recording.fetchOne(db, listOf(id)) ?: return@write null
            Recording.delete(db, recording)
            recording
        }
        if (removed != null) {
            fileWork { files.remove(listOf(removed)) }
        }
    }

    suspend fun recordings(profileId: UUID): List<Recording> = writer.read { db -> fetchRecordings(db, profileId) }

    /**
     * Call once at launch, before anything records: expires old takes across all profiles and
     * removes audio files that no row points at.
     */
    suspend fun runStartupMaintenance(now: Instant = Instant.now()): Unit = runToCompletion {
        val policy = retention
        val removed = writer.write { db -> applyRetention(policy, db, now, profileId = null) }
        fileWork { files.remove(removed) }
        val known = writer.read { db -> db.fetchAll("SELECT fileName FROM recording") { row -> row.string(0) }.toSet() }
        fileWork { files.removeOrphans(keeping = known) }
    }

    private fun applyRetention(
        policy: RecordingRetentionPolicy,
        db: Database,
        now: Instant,
        profileId: UUID?,
    ): List<Recording> {
        val recordings = if (profileId != null) {
            Recording.fetchAll(db, "SELECT * FROM recording WHERE profileId = ?", listOf(profileId))
        } else {
            Recording.fetchAll(db, "SELECT * FROM recording")
        }
        val doomed = policy.takesToDelete(recordings, now)
        for (recording in doomed) {
            Recording.delete(db, recording)
        }
        return doomed
    }

    private fun fetchRecordings(db: Database, profileId: UUID): List<Recording> =
        Recording.fetchAll(db, "SELECT * FROM recording WHERE profileId = ? ORDER BY ts DESC", listOf(profileId))

    // MARK: Adult talks

    suspend fun saveSpeech(speech: SpeechRecord) {
        writer.write { db -> SpeechRecord.save(db, speech) }
    }

    suspend fun speeches(profileId: UUID): List<SpeechRecord> = writer.read { db -> fetchSpeeches(db, profileId) }

    private fun fetchSpeeches(db: Database, profileId: UUID): List<SpeechRecord> =
        SpeechRecord.fetchAll(db, "SELECT * FROM speech WHERE profileId = ? ORDER BY ts DESC", listOf(profileId))

    // MARK: Therapist notes

    suspend fun saveNote(note: MoodlessNote) {
        writer.write { db -> MoodlessNote.save(db, note) }
    }

    suspend fun deleteNote(id: UUID) {
        writer.write { db -> MoodlessNote.deleteOne(db, listOf(id)) }
    }

    /** Newest day first. */
    suspend fun notes(profileId: UUID): List<MoodlessNote> = writer.read { db -> fetchNotes(db, profileId) }

    private fun fetchNotes(db: Database, profileId: UUID): List<MoodlessNote> =
        MoodlessNote.fetchAll(db, "SELECT * FROM note WHERE profileId = ? ORDER BY localDay DESC", listOf(profileId))

    // MARK: Missions

    /** Notes that a session gave a mission. A second call for the same session changes nothing. */
    suspend fun addMission(mission: MissionCheck) {
        writer.write { db ->
            if (db.fetchCount("SELECT COUNT(*) FROM missionCheck WHERE sessionId = ?", listOf(mission.sessionId)) == 0) {
                MissionCheck.insert(db, mission)
            }
        }
    }

    /** The latest mission, when it was for a day before [today] and its question hasn't been shown yet. */
    suspend fun pendingMission(profileId: UUID, today: String): MissionCheck? = writer.read { db ->
        val latest = MissionCheck.fetchAll(
            db,
            "SELECT * FROM missionCheck WHERE profileId = ? ORDER BY givenAt DESC LIMIT 1",
            listOf(profileId),
        ).firstOrNull()
        latest?.takeIf { !it.asked && it.localDay < today }
    }

    /** The question was shown: kept with the answer, or with none for "Nu acum". It is not asked again. */
    suspend fun answerMission(id: UUID, answer: MissionCheck.Answer?, at: Instant = Instant.now()) {
        writer.write { db ->
            val mission = MissionCheck.fetchOne(db, listOf(id)) ?: return@write
            MissionCheck.update(db, mission.copy(asked = true, answer = answer, answeredAt = if (answer == null) null else at))
        }
    }

    /** The missions an adult answered about, newest day first. */
    suspend fun missionAnswers(profileId: UUID): List<MissionCheck> = writer.read { db -> fetchMissionAnswers(db, profileId) }

    internal fun fetchMissionAnswers(db: Database, profileId: UUID): List<MissionCheck> =
        MissionCheck.fetchAll(
            db,
            "SELECT * FROM missionCheck WHERE profileId = ? AND answer IS NOT NULL ORDER BY localDay DESC, givenAt DESC",
            listOf(profileId),
        )

    // MARK: Streams

    fun profilesStream(): Flow<List<Profile>> =
        observe { db -> Profile.fetchAll(db, "SELECT * FROM profile ORDER BY createdAt, id") }

    fun weeklyAttemptsStream(profileId: UUID): Flow<List<WeeklySoundCount>> =
        observe { db -> Aggregates.weeklyAttempts(db, profileId) }

    fun agreementStream(profileId: UUID): Flow<List<SoundAgreement>> =
        observe { db -> Aggregates.agreement(db, profileId) }

    fun soundOutcomesStream(profileId: UUID): Flow<List<SoundOutcomeCounts>> =
        observe { db -> Aggregates.soundOutcomes(db, profileId) }

    fun recordingsStream(profileId: UUID): Flow<List<Recording>> =
        observe { db -> fetchRecordings(db, profileId) }

    /**
     * Emits the current value, then each change. A slow reader only gets the newest value.
     * An observation failure is logged and ends the stream.
     *
     * GRDB's `ValueObservation` re-fetches after each commit that touches what it read; here every commit
     * re-fetches, and `distinctUntilChanged` (`removeDuplicates`) drops the values that did not change, so the
     * same values come out. `conflate` is `bufferingNewest(1)`.
     */
    private fun <Value> observe(fetch: (Database) -> Value): Flow<Value> =
        writer.commits
            .map { writer.read(fetch) }
            .distinctUntilChanged()
            .catch { error -> storeLog.severe("Observation failed: ${error.message}") }
            .conflate()

    // MARK: Export

    /**
     * A JSON file for handing one profile's data to the logoped through the share sheet.
     * Audio stays out unless [includeAudio] is true, and the sibilant calibration always stays out.
     */
    /**
     * The file for the logoped. Without [includeName] nothing in it names the person or ties it to this phone: no name,
     * the name replaced by "[nume]" in free text, a fixed profile id (iOS 007bfcf). [probes] are the „Cuvinte noi”
     * counts, which the app keeps outside the database (iOS 98c737d).
     */
    suspend fun exportJSON(
        profileId: UUID,
        includeAudio: Boolean = false,
        includeName: Boolean = false,
        probes: List<ProbeSummary> = emptyList(),
        now: Instant = Instant.now(),
    ): ByteArray {
        val contents = writer.read { db ->
            val profile = Profile.fetchOne(db, listOf(profileId)) ?: throw FoniaStoreError.ProfileNotFound()
            ExportDocument.Contents(
                profile = profile,
                soundProgress = SoundProgress.fetchAll(
                    db, "SELECT * FROM soundProgress WHERE profileId = ? ORDER BY sound", listOf(profileId),
                ),
                sessions = SessionRecord.fetchAll(
                    db, "SELECT * FROM session WHERE profileId = ? ORDER BY startedAt", listOf(profileId),
                ),
                attempts = Attempt.fetchAll(db, "SELECT * FROM attempt WHERE profileId = ? ORDER BY ts", listOf(profileId)),
                recordings = fetchRecordings(db, profileId),
                speeches = fetchSpeeches(db, profileId),
                notes = fetchNotes(db, profileId),
                missionChecks = MissionCheck.fetchAll(
                    db, "SELECT * FROM missionCheck WHERE profileId = ? ORDER BY givenAt", listOf(profileId),
                ),
            )
        }
        val exported = if (includeName) contents else contents.pseudonymous()
        return fileWork {
            ExportDocument(exported, exportedAt = now, audio = if (includeAudio) files else null, probes = probes).encoded()
        }
    }

    // MARK: Kotlin plumbing

    private suspend fun <T> fileWork(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    /**
     * Once a change that removes files has started, its write and the file changes after it run to the end, so
     * rows and audio never part ways when the caller is cancelled midway. A caller cancelled before it starts
     * gets a cancellation and nothing changes.
     */
    private suspend fun <T> runToCompletion(block: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        return withContext(NonCancellable) { block() }
    }
}
