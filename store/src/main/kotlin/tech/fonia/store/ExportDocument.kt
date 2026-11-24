package tech.fonia.store

import java.text.BreakIterator
import java.text.Normalizer
import java.time.Instant
import java.util.Base64
import java.util.Locale
import java.util.UUID

/**
 * The shape of the file handed to the logoped.
 *
 * `schemaVersion` 2 (as iOS 007bfcf): the profile carries a `label` and only carries `name` when the adult chose to add
 * it, and in a pseudonymous file every `id`/`profileId` of the profile is [ANONYMOUS_PROFILE_ID]. Version 1 always
 * carried the name and the profile's real id. Nothing reads these files back.
 */
internal class ExportDocument(contents: Contents, val exportedAt: Instant, audio: RecordingFiles?) {
    class Contents(
        val profile: Profile,
        val soundProgress: List<SoundProgress>,
        val sessions: List<SessionRecord>,
        val attempts: List<Attempt>,
        val recordings: List<Recording>,
        val speeches: List<SpeechRecord>,
        val notes: List<MoodlessNote>,
    ) {
        /**
         * The same data with nothing that names the person or ties the file to this phone: no name, the name replaced
         * in free text, and a fixed profile id. Session, attempt and take ids are random and stay, so the rows still
         * point at each other.
         */
        fun pseudonymous(): Contents {
            val redactor = NameRedactor(profile.name)
            val id = ANONYMOUS_PROFILE_ID
            return Contents(
                profile = profile.copy(id = id, name = null, notes = profile.notes?.let(redactor::redact)),
                soundProgress = soundProgress.map { it.copy(profileId = id) },
                sessions = sessions.map { it.copy(profileId = id) },
                attempts = attempts.map { row ->
                    row.copy(profileId = id, target = row.target?.let(redactor::redact), detail = row.detail?.let(redactor::redact))
                },
                recordings = recordings.map { it.copy(profileId = id, label = redactor.redact(it.label)) },
                speeches = speeches.map { row ->
                    row.copy(
                        profileId = id,
                        prompt = redactor.redact(row.prompt),
                        transcript = row.transcript?.let(redactor::redact),
                        metrics = redactor.redact(row.metrics),
                        correctedFillers = row.correctedFillers.map(redactor::redact),
                    )
                },
                notes = notes.map { it.copy(profileId = id, text = redactor.redact(it.text)) },
            )
        }
    }

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
    val schemaVersion = 2
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

    companion object {
        private val DATES = DateEncodingStrategy.ISO8601

        /** What a pseudonymous file calls its one profile. The file only ever holds one. */
        val ANONYMOUS_PROFILE_ID = UUID(0L, 0L)

        /** The profile's label when the name stays out. */
        const val ANONYMOUS_LABEL = "Profil"
    }

    /** The profile as exported: a label always, the name only on request, never the sibilant calibration. */
    private fun Profile.exportNode(): JsonNode = jsonObject {
        put("id", jsonUUID(id))
        put("label", JText(name?.takeIf { it.isNotEmpty() } ?: ANONYMOUS_LABEL))
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

/**
 * Replaces each word of a profile's name, as a whole word, in free text: whatever the case, with or without diacritics
 * ("Ștefan", "stefan", "STEFAN"). Declined forms ("Anei") are not caught. As iOS `NameRedactor`.
 */
internal class NameRedactor(name: String?) {
    /** Each name word of two letters or more, as folded characters. */
    private val words: List<List<String>> =
        (name ?: "").split(Regex("[^\\p{L}\\p{N}]+"))
            .map(::characters)
            .filter { it.size >= 2 }
            .map { word -> word.map(::fold) }
            .distinct()

    fun redact(text: String): String {
        if (words.isEmpty()) return text
        val characters = characters(text)
        val folded = characters.map(::fold)
        val out = StringBuilder()
        var index = 0
        scan@ while (index < characters.size) {
            if (index == 0 || !isWordCharacter(characters[index - 1])) {
                for (word in words) {
                    val end = index + word.size
                    if (end > characters.size) continue
                    if (folded.subList(index, end) != word) continue
                    if (end != characters.size && isWordCharacter(characters[end])) continue
                    out.append(PLACEHOLDER)
                    index = end
                    continue@scan
                }
            }
            out.append(characters[index])
            index += 1
        }
        return out.toString()
    }

    fun redact(value: JSONValue): JSONValue = when (value) {
        is JSONValue.String -> JSONValue.String(redact(value.value))
        is JSONValue.Array -> JSONValue.Array(value.value.map(::redact))
        is JSONValue.Object -> JSONValue.Object(redact(value.value))
        JSONValue.Null, is JSONValue.Bool, is JSONValue.Number -> value
    }

    fun redact(value: JSONObject): JSONObject = value.mapValues { redact(it.value) }

    companion object {
        const val PLACEHOLDER = "[nume]"

        /** The text's user-perceived characters (Swift's `Character`). */
        private fun characters(text: String): List<String> {
            val breaks = BreakIterator.getCharacterInstance(Locale.ROOT)
            breaks.setText(text)
            val out = ArrayList<String>()
            var start = breaks.first()
            var end = breaks.next()
            while (end != BreakIterator.DONE) {
                out += text.substring(start, end)
                start = end
                end = breaks.next()
            }
            return out
        }

        /** `folding(options: [.caseInsensitive, .diacriticInsensitive])`. */
        private fun fold(character: String): String =
            Normalizer.normalize(character, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT)

        private fun isWordCharacter(character: String): Boolean = Character.isLetterOrDigit(character.codePointAt(0))
    }
}
