package tech.fonia.collector.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The JSON written next to the clips. Field names are the contract with B3 (labelling) and B4 (archive). */
object Records {
    const val SCHEMA = 1
    const val CONSENT_ADULT = "acord-adult-v1-2026-11-21"
    const val CONSENT_PARENT = "acord-parinte-v1-2026-11-21"

    val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = true
    }
    val pretty = Json(json) { prettyPrint = true }

    fun clipLine(record: ClipRecord): String = json.encodeToString(ClipRecord.serializer(), record)

    fun parseClipLine(line: String): ClipRecord = json.decodeFromString(ClipRecord.serializer(), line)
}

@Serializable
data class ConsentRecord(
    /** "adult" (on screen, initials) or "parent" (paper form, its code). */
    val type: String,
    val version: String,
    val initials: String? = null,
    val formCode: String? = null,
    /** Kept until 31 Dec 2027 instead of 31 Jan 2027. */
    val extendedRetention: Boolean = false,
    val at: String,
)

@Serializable
data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val android: String,
    val sdk: Int,
)

/** What the input really opened with (`CaptureInfo` of `:platform:audio`), or the fixture. */
@Serializable
data class CaptureSummary(
    /** "UNPROCESSED", "VOICE_RECOGNITION", "fixture", or "other(<n>)". */
    val audioSource: String,
    val requestedSource: String,
    val sampleRate: Int,
    val narrowBand: Boolean,
    val bluetooth: Boolean,
    val deviceType: Int? = null,
    val lowLatency: Boolean = false,
    val framesPerBurst: Int = 0,
    val effectsSwitchedOff: List<String> = emptyList(),
    val fixture: Boolean = false,
)

@Serializable
data class RoomCheck(
    val at: String,
    /** 20th percentile of the 10 ms levels over the check, dBFS. */
    val floorDb: Float,
    val noisy: Boolean,
    /** The adult chose "Continuăm aici" although it was noisy. */
    val continuedAnyway: Boolean,
    val file: String?,
    val seconds: Double,
)

@Serializable
data class SessionRecord(
    val sessionId: String,
    val round: String,
    val speaker: String,
    val role: String,
    val ageBand: String,
    val mode: String,
    val pack: String,
    val sounds: List<String>,
    val consent: ConsentRecord,
    val device: DeviceInfo,
    val capture: CaptureSummary? = null,
    val roomChecks: List<RoomCheck> = emptyList(),
    val startedAt: String,
    val updatedAt: String,
    val finishedAt: String? = null,
    val promptCount: Int,
    val promptsDone: Int = 0,
    val clipsKept: Int = 0,
    val skippedSounds: List<String> = emptyList(),
    val appVersion: String,
    val promptsVersion: String,
    val thresholdsVersion: Int,
    val fixture: Boolean = false,
)

/** `session.json` of one speaker folder: every session (one per pack run) of that speaker in that round. */
@Serializable
data class SpeakerFile(
    val schema: Int = Records.SCHEMA,
    val round: String,
    val speaker: String,
    val role: String,
    val ageBand: String,
    val sessions: List<SessionRecord> = emptyList(),
)

/** `CaptureQuality` of `:engine:dsp`, for one clip. */
@Serializable
data class QualitySummary(
    val usable: Boolean,
    val clipped: Boolean,
    val tooQuiet: Boolean,
    val tooShort: Boolean,
    val clippedFraction: Float,
    val peak: Float,
    val speechRmsDb: Float,
    val dynamicRangeDb: Float,
    val speechSeconds: Double,
    val bandwidthHz: Float,
    val canJudgeSibilants: Boolean,
)

/** One line of `clips.jsonl`: a take and what the adult decided about it. */
@Serializable
data class ClipRecord(
    val schema: Int = Records.SCHEMA,
    /** Relative to the archive root; null when nothing was recorded ("n-am reușit s-o imit" before a take). */
    val file: String?,
    val sessionId: String,
    val round: String,
    val speaker: String,
    val role: String,
    val ageBand: String,
    val mode: String,
    val device: String,
    val android: String,
    val sdk: Int,
    val audioSource: String,
    val sampleRate: Int,
    val narrowBand: Boolean,
    val bandwidthHz: Float?,
    val promptId: String,
    val sound: String,
    val soundKey: String,
    val level: String,
    val position: String?,
    val text: String,
    val imitation: String?,
    val hint: String?,
    /** "correct", or "<ErrorKind>:<heardAs>" / "<ErrorKind>". */
    val intendedLabel: String,
    /** The label in the file name. */
    val label: String,
    val errorKind: String?,
    val heardAs: String?,
    val wave: Int?,
    val take: Int,
    /** "accepted", "redo", "couldNotImitate" or "interrupted" (the app stopped before a decision). */
    val outcome: String,
    val speechDetected: Boolean,
    /** Seconds from the start of the file to the first and last 10 ms hop over the speech gate. */
    val speechStartSec: Double?,
    val speechEndSec: Double?,
    /** "auto" (silence after speech), "manual", "maximum", "noSpeech". */
    val endedBy: String,
    val durationSec: Double,
    val samples: Int,
    val dropped: Int,
    val roomFloorDb: Float?,
    val quality: QualitySummary?,
    val recordedAt: String,
    val appVersion: String,
    val promptsVersion: String,
    val thresholdsVersion: Int,
    val consentVersion: String,
    val fixture: Boolean,
)
