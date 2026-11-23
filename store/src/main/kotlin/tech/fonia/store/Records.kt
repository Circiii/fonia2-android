package tech.fonia.store

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlin.math.floor
import kotlin.random.Random

/**
 * The calendar day of a moment, as `YYYY-MM-DD`, in the given time zone.
 *
 * Sessions and attempts store it at write time. Weekly aggregates group on it, so a plant stays in
 * the week the child saw it grow in, even after a clock change or a trip to another time zone.
 */
fun localDay(of: Instant, timeZone: ZoneId = ZoneId.systemDefault()): String {
    val day = of.atZone(timeZone).toLocalDate()
    return String.format(Locale.ROOT, "%04d-%02d-%02d", day.year, day.monthValue, day.dayOfMonth)
}

// Swift's `var` properties on value types become `val` plus `copy`, which keeps Swift's value semantics.
// Enum cases keep their Swift raw values; `fromRawValue` is `init?(rawValue:)`.

data class Profile(
    val id: UUID = UUID.randomUUID(),
    /** Nil until someone types a name. There are no default names. */
    val name: String? = null,
    val kind: Kind,
    val ageBand: AgeBand,
    val voiceGroup: VoiceGroup,
    val color: String,
    val createdAt: Instant = Instant.now(),
    val targetSounds: List<String> = emptyList(),
    /** Rungs past the plain sound stay closed until an adult confirms the sound. */
    val requireConfirmation: Boolean = true,
    val confirmedSounds: List<String> = emptyList(),
    val openPath: Boolean = false,
    val notes: String? = null,
    val track: Track,
    /** Stays on this device. Left out of exports and never used to recognise a speaker. */
    val sibilantCalibration: JSONObject? = null,
) {
    enum class Kind(val rawValue: String) {
        Child("child"),
        Adult("adult"),
        ;

        companion object {
            fun fromRawValue(rawValue: String): Kind? = entries.firstOrNull { it.rawValue == rawValue }
        }
    }

    enum class AgeBand(val rawValue: String) {
        FourToFive("4-5"),
        SixToSeven("6-7"),
        EightToTen("8-10"),
        Adult("adult"),
        ;

        companion object {
            fun fromRawValue(rawValue: String): AgeBand? = entries.firstOrNull { it.rawValue == rawValue }
        }
    }

    /** Acoustic norm group for the sibilant classifier. Children have higher fricative spectra. */
    enum class VoiceGroup(val rawValue: String) {
        Child("child"),
        Female("female"),
        Male("male"),
        ;

        companion object {
            fun fromRawValue(rawValue: String): VoiceGroup? = entries.firstOrNull { it.rawValue == rawValue }
        }
    }

    enum class Track(val rawValue: String) {
        Ro("ro"),
        En("en"),
        Dictie("dictie"),
        ;

        companion object {
            fun fromRawValue(rawValue: String): Track? = entries.firstOrNull { it.rawValue == rawValue }
        }
    }

    internal companion object : RecordType<Profile>("profile", listOf("id")) {
        override fun encode(record: Profile): List<Pair<String, Any?>> = listOf(
            "id" to record.id,
            "name" to record.name,
            "kind" to record.kind.rawValue,
            "ageBand" to record.ageBand.rawValue,
            "voiceGroup" to record.voiceGroup.rawValue,
            "color" to record.color,
            "createdAt" to record.createdAt,
            "targetSounds" to databaseJSON(stringsNode(record.targetSounds)),
            "requireConfirmation" to record.requireConfirmation,
            "confirmedSounds" to databaseJSON(stringsNode(record.confirmedSounds)),
            "openPath" to record.openPath,
            "notes" to record.notes,
            "track" to record.track.rawValue,
            "sibilantCalibration" to record.sibilantCalibration?.let { databaseJSON(it.toNode()) },
        )

        override fun decode(row: Row): Profile = Profile(
            id = row.uuid("id"),
            name = row.stringOrNull("name"),
            kind = row.rawValue("kind", Kind::fromRawValue),
            ageBand = row.rawValue("ageBand", AgeBand::fromRawValue),
            voiceGroup = row.rawValue("voiceGroup", VoiceGroup::fromRawValue),
            color = row.string("color"),
            createdAt = row.instant("createdAt"),
            targetSounds = row.json("targetSounds") { it.decodeStrings() },
            requireConfirmation = row.bool("requireConfirmation"),
            confirmedSounds = row.json("confirmedSounds") { it.decodeStrings() },
            openPath = row.bool("openPath"),
            notes = row.stringOrNull("notes"),
            track = row.rawValue("track", Track::fromRawValue),
            sibilantCalibration = row.jsonOrNull("sibilantCalibration") { it.toJSONObject() },
        )
    }
}

data class SoundProgress(
    val profileId: UUID,
    val sound: String,
    val rung: Int = 0,
    val blockHistory: List<Block> = emptyList(),
) {
    /** One finished block on a rung: how many of its attempts went well. */
    data class Block(
        val rung: Int,
        val good: Int,
        val total: Int,
        val endedAt: Instant,
    )

    internal companion object : RecordType<SoundProgress>("soundProgress", listOf("profileId", "sound")) {
        override fun encode(record: SoundProgress): List<Pair<String, Any?>> = listOf(
            "profileId" to record.profileId,
            "sound" to record.sound,
            "rung" to record.rung,
            "blockHistory" to databaseJSON(
                JArray(record.blockHistory.map { it.toNode(DateEncodingStrategy.MillisecondsSince1970) }),
            ),
        )

        override fun decode(row: Row): SoundProgress = SoundProgress(
            profileId = row.uuid("profileId"),
            sound = row.string("sound"),
            rung = row.int("rung"),
            blockHistory = row.json("blockHistory") { it.decodeBlocks() },
        )
    }
}

data class SessionRecord(
    val id: UUID,
    val profileId: UUID,
    val track: Profile.Track,
    val sound: String?,
    /** What was practised, so sessions on the same thing can share a history. */
    val focus: String?,
    val startedAt: Instant,
    val endedAt: Instant?,
    val attempts: Int,
    /** What the session's order of trials was drawn from. */
    val seed: Long,
    val localDay: String,
) {
    /** Swift's public `init`: [localDay] comes from [startedAt] in [timeZone]. */
    constructor(
        id: UUID = UUID.randomUUID(),
        profileId: UUID,
        track: Profile.Track,
        sound: String? = null,
        focus: String? = null,
        startedAt: Instant = Instant.now(),
        endedAt: Instant? = null,
        attempts: Int = 0,
        seed: Long = Random.nextLong(),
        timeZone: ZoneId = ZoneId.systemDefault(),
    ) : this(
        id, profileId, track, sound, focus, startedAt, endedAt, attempts, seed,
        tech.fonia.store.localDay(startedAt, timeZone),
    )

    internal companion object : RecordType<SessionRecord>("session", listOf("id")) {
        override fun encode(record: SessionRecord): List<Pair<String, Any?>> = listOf(
            "id" to record.id,
            "profileId" to record.profileId,
            "track" to record.track.rawValue,
            "sound" to record.sound,
            "focus" to record.focus,
            "startedAt" to record.startedAt,
            "endedAt" to record.endedAt,
            "attempts" to record.attempts,
            "seed" to record.seed,
            "localDay" to record.localDay,
        )

        override fun decode(row: Row): SessionRecord = SessionRecord(
            id = row.uuid("id"),
            profileId = row.uuid("profileId"),
            track = row.rawValue("track", Profile.Track::fromRawValue),
            sound = row.stringOrNull("sound"),
            focus = row.stringOrNull("focus"),
            startedAt = row.instant("startedAt"),
            endedAt = row.instantOrNull("endedAt"),
            attempts = row.int("attempts"),
            seed = row.long("seed"),
            localDay = row.string("localDay"),
        )
    }
}

data class Attempt(
    val id: UUID,
    val sessionId: UUID,
    val profileId: UUID,
    val ts: Instant,
    val exerciseKind: String,
    val sound: String?,
    val target: String?,
    val machineOutcome: Outcome,
    val adultOutcome: Outcome?,
    val detail: JSONObject?,
    val recordingId: UUID?,
    val localDay: String,
) {
    enum class Outcome(val rawValue: String) {
        Good("good"),
        Almost("almost"),
        NotYet("notYet"),
        Unclear("unclear"),
        NotJudged("notJudged"),
        ;

        companion object {
            fun fromRawValue(rawValue: String): Outcome? = entries.firstOrNull { it.rawValue == rawValue }
        }
    }

    /** Swift's public `init`: [localDay] comes from [ts] in [timeZone]. */
    constructor(
        id: UUID = UUID.randomUUID(),
        sessionId: UUID,
        profileId: UUID,
        ts: Instant = Instant.now(),
        exerciseKind: String,
        sound: String? = null,
        target: String? = null,
        machineOutcome: Outcome,
        adultOutcome: Outcome? = null,
        detail: JSONObject? = null,
        recordingId: UUID? = null,
        timeZone: ZoneId = ZoneId.systemDefault(),
    ) : this(
        id, sessionId, profileId, ts, exerciseKind, sound, target, machineOutcome, adultOutcome, detail, recordingId,
        tech.fonia.store.localDay(ts, timeZone),
    )

    internal companion object : RecordType<Attempt>("attempt", listOf("id")) {
        override fun encode(record: Attempt): List<Pair<String, Any?>> = listOf(
            "id" to record.id,
            "sessionId" to record.sessionId,
            "profileId" to record.profileId,
            "ts" to record.ts,
            "exerciseKind" to record.exerciseKind,
            "sound" to record.sound,
            "target" to record.target,
            "machineOutcome" to record.machineOutcome.rawValue,
            "adultOutcome" to record.adultOutcome?.rawValue,
            "detail" to record.detail?.let { databaseJSON(it.toNode()) },
            "recordingId" to record.recordingId,
            "localDay" to record.localDay,
        )

        override fun decode(row: Row): Attempt = Attempt(
            id = row.uuid("id"),
            sessionId = row.uuid("sessionId"),
            profileId = row.uuid("profileId"),
            ts = row.instant("ts"),
            exerciseKind = row.string("exerciseKind"),
            sound = row.stringOrNull("sound"),
            target = row.stringOrNull("target"),
            machineOutcome = row.rawValue("machineOutcome", Outcome::fromRawValue),
            adultOutcome = row.rawValueOrNull("adultOutcome", Outcome::fromRawValue),
            detail = row.jsonOrNull("detail") { it.toJSONObject() },
            recordingId = row.uuidOrNull("recordingId"),
            localDay = row.string("localDay"),
        )
    }
}

/**
 * Swift's `fileName` is a `let` set by the public `init`, so the constructor that takes it, and `copy`, stay
 * inside the module: only rows read back from the database bring their own.
 */
@ConsistentCopyVisibility
data class Recording internal constructor(
    val id: UUID,
    val profileId: UUID,
    val ts: Instant,
    val label: String,
    val sound: String?,
    /** Seconds. */
    val duration: Double,
    /** Relative to the recordings folder, because absolute container paths change between installs. */
    val fileName: String,
    /** Pinned takes survive retention until an adult unpins or deletes them. */
    val pinned: Boolean,
) {
    constructor(
        id: UUID = UUID.randomUUID(),
        profileId: UUID,
        ts: Instant = Instant.now(),
        label: String,
        sound: String? = null,
        duration: Double,
        pinned: Boolean = false,
    ) : this(id, profileId, ts, label, sound, duration, "${profileId.uuidString}/${id.uuidString}.m4a", pinned)

    internal companion object : RecordType<Recording>("recording", listOf("id")) {
        override fun encode(record: Recording): List<Pair<String, Any?>> = listOf(
            "id" to record.id,
            "profileId" to record.profileId,
            "ts" to record.ts,
            "label" to record.label,
            "sound" to record.sound,
            "duration" to record.duration,
            "fileName" to record.fileName,
            "pinned" to record.pinned,
        )

        override fun decode(row: Row): Recording = Recording(
            id = row.uuid("id"),
            profileId = row.uuid("profileId"),
            ts = row.instant("ts"),
            label = row.string("label"),
            sound = row.stringOrNull("sound"),
            duration = row.double("duration"),
            fileName = row.string("fileName"),
            pinned = row.bool("pinned"),
        )
    }
}

/** An adult's practice talk. */
data class SpeechRecord(
    val id: UUID = UUID.randomUUID(),
    val profileId: UUID,
    val ts: Instant = Instant.now(),
    val lang: Language,
    val prompt: String,
    /** Seconds. */
    val duration: Double,
    val transcript: String? = null,
    val metrics: JSONObject = emptyMap(),
    val selfRating: Int? = null,
    val correctedFillers: List<JSONValue> = emptyList(),
    val recordingId: UUID? = null,
) {
    enum class Language(val rawValue: String) {
        Ro("ro"),
        En("en"),
        ;

        companion object {
            fun fromRawValue(rawValue: String): Language? = entries.firstOrNull { it.rawValue == rawValue }
        }
    }

    internal companion object : RecordType<SpeechRecord>("speech", listOf("id")) {
        override fun encode(record: SpeechRecord): List<Pair<String, Any?>> = listOf(
            "id" to record.id,
            "profileId" to record.profileId,
            "ts" to record.ts,
            "lang" to record.lang.rawValue,
            "prompt" to record.prompt,
            "duration" to record.duration,
            "transcript" to record.transcript,
            "metrics" to databaseJSON(record.metrics.toNode()),
            "selfRating" to record.selfRating,
            "correctedFillers" to databaseJSON(JArray(record.correctedFillers.map { it.toNode() })),
            "recordingId" to record.recordingId,
        )

        override fun decode(row: Row): SpeechRecord = SpeechRecord(
            id = row.uuid("id"),
            profileId = row.uuid("profileId"),
            ts = row.instant("ts"),
            lang = row.rawValue("lang", Language::fromRawValue),
            prompt = row.string("prompt"),
            duration = row.double("duration"),
            transcript = row.stringOrNull("transcript"),
            metrics = row.json("metrics") { it.toJSONObject() },
            selfRating = row.intOrNull("selfRating"),
            correctedFillers = row.json("correctedFillers") { it.toJSONArray() },
            recordingId = row.uuidOrNull("recordingId"),
        )
    }
}

/** A therapist's note for a day. Plain text with no mood or emotion field, on purpose. */
data class MoodlessNote(
    val id: UUID = UUID.randomUUID(),
    val profileId: UUID,
    /** `YYYY-MM-DD`, the day the note is about. */
    val localDay: String,
    val text: String,
) {
    internal companion object : RecordType<MoodlessNote>("note", listOf("id")) {
        override fun encode(record: MoodlessNote): List<Pair<String, Any?>> = listOf(
            "id" to record.id,
            "profileId" to record.profileId,
            "localDay" to record.localDay,
            "text" to record.text,
        )

        override fun decode(row: Row): MoodlessNote = MoodlessNote(
            id = row.uuid("id"),
            profileId = row.uuid("profileId"),
            localDay = row.string("localDay"),
            text = row.string("text"),
        )
    }
}

// MARK: Nested values as JSON (GRDB's column encoder and decoder, and the export)

internal fun stringsNode(strings: List<String>): JsonNode = JArray(strings.map(::JText))

internal fun SoundProgress.Block.toNode(dates: DateEncodingStrategy): JsonNode = JObject(
    mapOf(
        "rung" to jsonNumber(rung),
        "good" to jsonNumber(good),
        "total" to jsonNumber(total),
        "endedAt" to jsonDate(endedAt, dates),
    ),
)

/** `[String]`: a string anywhere else is a type mismatch, as with `JSONDecoder`. */
private fun JsonElement.decodeStrings(): List<String> {
    val array = this as? JsonArray ?: throw SerializationException("Expected an array of strings")
    return array.map { element ->
        val primitive = element as? JsonPrimitive
        if (primitive == null || !primitive.isString) throw SerializationException("Expected a string")
        primitive.content
    }
}

/** `[Block]` with GRDB's `.millisecondsSince1970` dates. */
private fun JsonElement.decodeBlocks(): List<SoundProgress.Block> {
    val array = this as? JsonArray ?: throw SerializationException("Expected an array of blocks")
    return array.map { element ->
        val block = element as? JsonObject ?: throw SerializationException("Expected a block object")
        SoundProgress.Block(
            rung = block.decodeInt("rung"),
            good = block.decodeInt("good"),
            total = block.decodeInt("total"),
            endedAt = instantFromMillisecondsSince1970(block.decodeDouble("endedAt")),
        )
    }
}

private fun JsonObject.numberText(key: String): String {
    val primitive = this[key] as? JsonPrimitive ?: throw SerializationException("No value for key \"$key\"")
    if (primitive.isString || primitive.booleanOrNull != null || primitive.content == "null") {
        throw SerializationException("Expected a number for key \"$key\"")
    }
    return primitive.content
}

/** An `Int` accepts `4` and `4.0`, not `4.5`, as `JSONDecoder` does. */
private fun JsonObject.decodeInt(key: String): Int {
    val text = numberText(key)
    text.toIntOrNull()?.let { return it }
    val value = text.toDoubleOrNull() ?: throw SerializationException("Expected a number for key \"$key\"")
    if (value != floor(value) || value < Int.MIN_VALUE || value > Int.MAX_VALUE) {
        throw SerializationException("Parsed JSON number <$text> does not fit in Int")
    }
    return value.toInt()
}

private fun JsonObject.decodeDouble(key: String): Double =
    numberText(key).toDoubleOrNull() ?: throw SerializationException("Expected a number for key \"$key\"")
