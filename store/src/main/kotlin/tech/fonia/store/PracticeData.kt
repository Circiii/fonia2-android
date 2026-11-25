package tech.fonia.store

import java.util.UUID

/**
 * One profile's practice as values, for the PDF report for the logoped. It is the same pseudonymous copy the JSON
 * export starts from ([ExportDocument.Contents.pseudonymous]): no name, the name replaced in the notes, and the fixed
 * profile id. Talks are left out because the report doesn't show them; of the takes only their rows come, never the
 * sound.
 */
class PracticeData(
    val profile: Profile,
    val soundProgress: List<SoundProgress>,
    /** Oldest first. */
    val sessions: List<SessionRecord>,
    /** Oldest first. */
    val attempts: List<Attempt>,
    /** Newest day first. */
    val notes: List<MoodlessNote>,
    /** What the adult said about the missions, newest day first. */
    val missionAnswers: List<MissionCheck> = emptyList(),
    /** The kept takes' rows, without their sound, newest first: which attempts can still be heard. */
    val recordings: List<Recording> = emptyList(),
)

/** What the report is drawn from, never with the name in it. */
suspend fun FoniaRepository.practiceData(profileId: UUID): PracticeData {
    val missionAnswers = writer.read { db -> fetchMissionAnswers(db, profileId) }
    val contents = writer.read { db ->
        val profile = Profile.fetchOne(db, listOf(profileId)) ?: throw FoniaStoreError.ProfileNotFound()
        ExportDocument.Contents(
            profile = profile,
            soundProgress = SoundProgress.fetchAll(db, "SELECT * FROM soundProgress WHERE profileId = ? ORDER BY sound", listOf(profileId)),
            sessions = SessionRecord.fetchAll(db, "SELECT * FROM session WHERE profileId = ? ORDER BY startedAt", listOf(profileId)),
            attempts = Attempt.fetchAll(db, "SELECT * FROM attempt WHERE profileId = ? ORDER BY ts", listOf(profileId)),
            recordings = Recording.fetchAll(db, "SELECT * FROM recording WHERE profileId = ? ORDER BY ts DESC", listOf(profileId)),
            speeches = emptyList(),
            notes = MoodlessNote.fetchAll(db, "SELECT * FROM note WHERE profileId = ? ORDER BY localDay DESC", listOf(profileId)),
        )
    }.pseudonymous()
    return PracticeData(
        profile = contents.profile,
        soundProgress = contents.soundProgress,
        sessions = contents.sessions,
        attempts = contents.attempts,
        notes = contents.notes,
        missionAnswers = missionAnswers,
        recordings = contents.recordings,
    )
}
