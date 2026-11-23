package tech.fonia.collector.core

import kotlinx.serialization.Serializable
import java.io.File

/** The session being recorded, saved after every step so a killed app resumes at the same prompt. */
@Serializable
data class ActiveSession(
    val session: SessionRecord,
    val index: Int = 0,
    val autoFlow: Boolean = true,
    /** A take that was written but not yet kept or redone. On resume it is logged as "interrupted". */
    val pendingTake: ClipRecord? = null,
)

data class SpeakerSummary(val round: String, val speaker: String, val clips: Int, val bytes: Long, val sessions: Int)

/**
 * The data on the phone. [dataRoot] holds `<rundă>/<vorbitor>/` folders exactly as they go into the archive:
 * `session.json`, `clips.jsonl`, `<sunet>/…wav`, `room/…wav`. Pure JVM, so the tests run it on the PC.
 */
class Store(val dataRoot: File, private val activeFile: File) {
    fun file(relative: String): File = File(dataRoot, relative)

    fun speakerDir(round: String, speaker: String): File = file(Naming.speakerDir(round, speaker))

    fun loadActive(): ActiveSession? = runCatching {
        if (!activeFile.isFile) null else Records.json.decodeFromString(ActiveSession.serializer(), activeFile.readText())
    }.getOrNull()

    fun saveActive(active: ActiveSession) = writeAtomically(activeFile, Records.json.encodeToString(ActiveSession.serializer(), active))

    fun clearActive() {
        activeFile.delete()
    }

    /** The first take number whose file does not exist yet, so a second session never overwrites the first. */
    fun nextTake(round: String, speaker: String, soundKey: String, promptId: String, label: String): Int {
        var n = 1
        while (file(Naming.clipPath(round, speaker, soundKey, promptId, label, n)).exists()) n++
        return n
    }

    fun nextRoomTake(round: String, speaker: String): Int {
        var n = 1
        while (file(Naming.roomPath(round, speaker, n)).exists()) n++
        return n
    }

    fun appendClip(record: ClipRecord) {
        val dir = speakerDir(record.round, record.speaker)
        dir.mkdirs()
        File(dir, CLIPS).appendText(Records.clipLine(record) + "\n")
    }

    fun readClips(round: String, speaker: String): List<ClipRecord> {
        val f = File(speakerDir(round, speaker), CLIPS)
        if (!f.isFile) return emptyList()
        return f.readLines().filter { it.isNotBlank() }.mapNotNull { runCatching { Records.parseClipLine(it) }.getOrNull() }
    }

    fun readSpeakerFile(round: String, speaker: String): SpeakerFile? {
        val f = File(speakerDir(round, speaker), SESSION)
        if (!f.isFile) return null
        return runCatching { Records.json.decodeFromString(SpeakerFile.serializer(), f.readText()) }.getOrNull()
    }

    /** Adds or replaces [session] (by id) in the speaker's `session.json`. */
    fun upsertSession(session: SessionRecord) {
        val current = readSpeakerFile(session.round, session.speaker)
            ?: SpeakerFile(round = session.round, speaker = session.speaker, role = session.role, ageBand = session.ageBand)
        val others = current.sessions.filter { it.sessionId != session.sessionId }
        val updated = current.copy(role = session.role, ageBand = session.ageBand, sessions = others + session)
        val dir = speakerDir(session.round, session.speaker)
        dir.mkdirs()
        writeAtomically(File(dir, SESSION), Records.pretty.encodeToString(SpeakerFile.serializer(), updated))
    }

    fun speakers(): List<SpeakerSummary> {
        val rounds = dataRoot.listFiles { f -> f.isDirectory && Naming.ROUND.matches(f.name) }.orEmpty()
        return rounds.flatMap { round ->
            round.listFiles { f -> f.isDirectory && Naming.SPEAKER.matches(f.name) }.orEmpty().map { dir ->
                val clips = readClips(round.name, dir.name).count { it.outcome == "accepted" }
                val bytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                SpeakerSummary(round.name, dir.name, clips, bytes, readSpeakerFile(round.name, dir.name)?.sessions?.size ?: 0)
            }
        }.sortedWith(compareBy({ it.round }, { it.speaker }))
    }

    fun deleteSpeaker(round: String, speaker: String): Boolean {
        val active = loadActive()
        if (active != null && active.session.round == round && active.session.speaker == speaker) clearActive()
        return speakerDir(round, speaker).deleteRecursively()
    }

    private fun writeAtomically(target: File, text: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "Could not write ${target.name}" }
        }
    }

    companion object {
        const val CLIPS = "clips.jsonl"
        const val SESSION = "session.json"
    }
}
