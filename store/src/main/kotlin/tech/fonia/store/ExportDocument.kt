package tech.fonia.store

import java.time.Instant
import java.util.Base64
import java.util.UUID

/** The shape of the file handed to the logoped. */
internal class ExportDocument(contents: Contents, val exportedAt: Instant, audio: RecordingFiles?) {
    class Contents(
        val profile: Profile,
        val soundProgress: List<SoundProgress>,
        val sessions: List<SessionRecord>,
        val attempts: List<Attempt>,
        val recordings: List<Recording>,
        val speeches: List<SpeechRecord>,
        val notes: List<MoodlessNote>,
    )

    class ExportedRecording(
        val id: UUID,
        val ts: Instant,
        val label: String,
        val sound: String?,
        val duration: Double,
        val pinned: Boolean,
        val audioBase64: String?,
    )

    val app = "fonia"
    val schemaVersion = 1
    val profile: Profile = contents.profile.copy(sibilantCalibration = null)
    val soundProgress: List<SoundProgress> = contents.soundProgress
    val sessions: List<SessionRecord> = contents.sessions
    val attempts: List<Attempt> = contents.attempts

    /** `audio` is null unless the adult asked to include the takes themselves. */
    val recordings: List<ExportedRecording> = contents.recordings.map { recording ->
        // `try? Data(contentsOf:)`: a take whose file is missing or unreadable is listed without audio.
        val file = audio?.let { files ->
            try {
                files.url(recording.fileName).readBytes()
            } catch (_: Exception) {
                null
            }
        }
        ExportedRecording(
            id = recording.id,
            ts = recording.ts,
            label = recording.label,
            sound = recording.sound,
            duration = recording.duration,
            pinned = recording.pinned,
            audioBase64 = file?.let { Base64.getEncoder().encodeToString(it) },
        )
    }
    val speeches: List<SpeechRecord> = contents.speeches
    val notes: List<MoodlessNote> = contents.notes

    /**
     * `JSONEncoder` with `[.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]` and `.iso8601` dates, as UTF-8.
     * Optional properties that are nil are left out, as the synthesized `Encodable` does.
     */
    fun encoded(): ByteArray =
        encodeJSON(node(), prettyPrinted = true, withoutEscapingSlashes = true).toByteArray(Charsets.UTF_8)

    private fun node(): JsonNode = jsonObject {
        put("app", JText(app))
        put("schemaVersion", jsonNumber(schemaVersion))
        put("exportedAt", jsonDate(exportedAt, DATES))
        put("profile", profile.exportNode())
        put("soundProgress", JArray(soundProgress.map { it.exportNode() }))
        put("sessions", JArray(sessions.map { it.exportNode() }))
        put("attempts", JArray(attempts.map { it.exportNode() }))
        put("recordings", JArray(recordings.map { it.exportNode() }))
        put("speeches", JArray(speeches.map { it.exportNode() }))
        put("notes", JArray(notes.map { it.exportNode() }))
    }

    private companion object {
        val DATES = DateEncodingStrategy.ISO8601
    }

    private fun Profile.exportNode(): JsonNode = jsonObject {
        put("id", jsonUUID(id))
        putIfPresent("name", name?.let(::JText))
        put("kind", JText(kind.rawValue))
        put("ageBand", JText(ageBand.rawValue))
        put("voiceGroup", JText(voiceGroup.rawValue))
        put("color", JText(color))
        put("createdAt", jsonDate(createdAt, DATES))
        put("targetSounds", stringsNode(targetSounds))
        put("requireConfirmation", JBool(requireConfirmation))
        put("confirmedSounds", stringsNode(confirmedSounds))
        put("openPath", JBool(openPath))
        putIfPresent("notes", notes?.let(::JText))
        put("track", JText(track.rawValue))
        putIfPresent("sibilantCalibration", sibilantCalibration?.toNode())
    }

    private fun SoundProgress.exportNode(): JsonNode = jsonObject {
        put("profileId", jsonUUID(profileId))
        put("sound", JText(sound))
        put("rung", jsonNumber(rung))
        put("blockHistory", JArray(blockHistory.map { it.toNode(DATES) }))
    }

    private fun SessionRecord.exportNode(): JsonNode = jsonObject {
        put("id", jsonUUID(id))
        put("profileId", jsonUUID(profileId))
        put("track", JText(track.rawValue))
        putIfPresent("sound", sound?.let(::JText))
        putIfPresent("focus", focus?.let(::JText))
        put("startedAt", jsonDate(startedAt, DATES))
        putIfPresent("endedAt", endedAt?.let { jsonDate(it, DATES) })
        put("attempts", jsonNumber(attempts))
        put("seed", jsonNumber(seed))
        put("localDay", JText(localDay))
    }

    private fun Attempt.exportNode(): JsonNode = jsonObject {
        put("id", jsonUUID(id))
        put("sessionId", jsonUUID(sessionId))
        put("profileId", jsonUUID(profileId))
        put("ts", jsonDate(ts, DATES))
        put("exerciseKind", JText(exerciseKind))
        putIfPresent("sound", sound?.let(::JText))
        putIfPresent("target", target?.let(::JText))
        put("machineOutcome", JText(machineOutcome.rawValue))
        putIfPresent("adultOutcome", adultOutcome?.let { JText(it.rawValue) })
        putIfPresent("detail", detail?.toNode())
        putIfPresent("recordingId", recordingId?.let(::jsonUUID))
        put("localDay", JText(localDay))
    }

    private fun ExportedRecording.exportNode(): JsonNode = jsonObject {
        put("id", jsonUUID(id))
        put("ts", jsonDate(ts, DATES))
        put("label", JText(label))
        putIfPresent("sound", sound?.let(::JText))
        put("duration", jsonNumber(duration))
        put("pinned", JBool(pinned))
        putIfPresent("audioBase64", audioBase64?.let(::JText))
    }

    private fun SpeechRecord.exportNode(): JsonNode = jsonObject {
        put("id", jsonUUID(id))
        put("profileId", jsonUUID(profileId))
        put("ts", jsonDate(ts, DATES))
        put("lang", JText(lang.rawValue))
        put("prompt", JText(prompt))
        put("duration", jsonNumber(duration))
        putIfPresent("transcript", transcript?.let(::JText))
        put("metrics", metrics.toNode())
        putIfPresent("selfRating", selfRating?.let { jsonNumber(it) })
        put("correctedFillers", JArray(correctedFillers.map { it.toNode() }))
        putIfPresent("recordingId", recordingId?.let(::jsonUUID))
    }

    private fun MoodlessNote.exportNode(): JsonNode = jsonObject {
        put("id", jsonUUID(id))
        put("profileId", jsonUUID(profileId))
        put("localDay", JText(localDay))
        put("text", JText(text))
    }
}

private inline fun jsonObject(build: MutableMap<String, JsonNode>.() -> Unit): JsonNode =
    JObject(LinkedHashMap<String, JsonNode>().apply(build))

private fun MutableMap<String, JsonNode>.putIfPresent(key: String, node: JsonNode?) {
    if (node != null) put(key, node)
}
