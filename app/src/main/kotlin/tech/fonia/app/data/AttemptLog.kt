package tech.fonia.app.data

import kotlinx.coroutines.Dispatchers
import tech.fonia.app.judge.Judged
import tech.fonia.live.Clip
import tech.fonia.store.Recording
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tech.fonia.model.SoundId
import tech.fonia.model.Verdict
import tech.fonia.store.Attempt
import tech.fonia.store.FoniaRepository
import tech.fonia.store.JSONValue
import tech.fonia.store.Profile
import tech.fonia.store.SessionRecord
import tech.fonia.store.localDay
import java.time.Instant
import java.util.UUID

/** Versions saved with every attempt, so a row can always be re-read against the code that judged it. */
data class Versions(val app: String, val judge: String, val lesson: String, val content: String = "?", val models: String = "")

/** One finished take, as the lesson hands it to the store. */
data class TakeRecord(
    val sessionId: UUID,
    val sound: SoundId,
    val step: String,
    val text: String,
    val verdict: Verdict,
    val holdSeconds: Float,
    val startedAt: Instant,
    val endedAt: Instant,
    val audioSource: String,
    val endReason: String,
    /** The content v2 item (`s.wi.sanie`) and its type (`word`, `hold`…), when the take came from a LessonPlan. */
    val itemId: String? = null,
    val itemType: String? = null,
)

/** What PATH shows per sound. */
data class SoundStats(val sessions: Int, val practisedToday: Boolean)

/**
 * The lesson's door to `:core:data`: one default child profile (created on first start), a session per lesson, an
 * attempt row per take, the adult's 👍 on it, and the counts PATH shows. Uses the existing tables as they are.
 */
class AttemptLog(private val repository: () -> FoniaRepository, private val versions: () -> Versions) {
    private val lock = Mutex()
    @Volatile private var profileId: UUID? = null

    private suspend fun <T> io(block: suspend FoniaRepository.() -> T): T = withContext(Dispatchers.IO) { repository().block() }

    /** The child profile, created the first time. */
    suspend fun profile(): UUID = lock.withLock {
        profileId ?: io {
            val existing = profilesStream().first().firstOrNull { it.kind == Profile.Kind.Child }
            val id = existing?.id ?: Profile(
                kind = Profile.Kind.Child,
                ageBand = Profile.AgeBand.SixToSeven,
                voiceGroup = Profile.VoiceGroup.Child,
                color = "parrot",
                track = Profile.Track.Ro,
            ).also { saveProfile(it) }.id
            id
        }.also { profileId = it }
    }

    suspend fun startSession(id: UUID, sound: SoundId, focus: String) {
        val p = profile()
        io { addSession(SessionRecord(id = id, profileId = p, track = Profile.Track.Ro, sound = sound.raw, focus = focus)) }
    }

    suspend fun finishSession(id: UUID) = io { finishSession(id) }

    /**
     * Saves one take; returns the attempt id (for the adult's answer). With [judged], everything the judge gave goes
     * into the row's JSON payload (`detail.judgeTrace`, the anchor candidate, the F0); with [clip], the take's audio is
     * kept as a recording linked to the row (retention keeps the newest 3 per sound, `:core:data`). Retakes keep no audio.
     */
    suspend fun save(take: TakeRecord, judged: Judged? = null, clip: Clip? = null): UUID {
        val p = profile()
        val v = versions()
        val id = UUID.randomUUID()
        val verdict = take.verdict
        val recordingId = if (clip != null && !clip.isEmpty && verdict != Verdict.Retake) {
            runCatching { saveRecording(p, take, clip) }.getOrNull()
        } else {
            null
        }
        val detail = buildMap<String, JSONValue> {
            put("step", JSONValue.String(take.step))
            put("verdict", JSONValue.String(verdict.key))
            if (verdict is Verdict.NamedError) {
                put("errorKind", JSONValue.String(verdict.kind.raw))
                verdict.heardAs?.let { put("heardAs", JSONValue.String(it.raw)) }
            }
            put("holdSeconds", JSONValue.Number(Math.round(take.holdSeconds * 100) / 100.0))
            put("startedAt", JSONValue.String(take.startedAt.toString()))
            put("endedAt", JSONValue.String(take.endedAt.toString()))
            put("endReason", JSONValue.String(take.endReason))
            put("audioSource", JSONValue.String(take.audioSource))
            put("appVersion", JSONValue.String(v.app))
            put("judge", JSONValue.String(v.judge))
            put("lessonTuning", JSONValue.String(v.lesson))
            put("contentVersion", JSONValue.String(v.content))
            if (v.models.isNotEmpty()) put("models", JSONValue.String(v.models))
            take.itemId?.let { put("itemId", JSONValue.String(it)) }
            take.itemType?.let { put("itemType", JSONValue.String(it)) }
            if (judged != null) {
                put("judgeTrace", JSONValue.Object(judged.detail))
                judged.adult?.let { a ->
                    put("adultView", JSONValue.Object(buildMap {
                        put("kind", JSONValue.String(a.kind.raw))
                        a.heardAs?.let { put("heardAs", JSONValue.String(it.raw)) }
                        put("inTrial", JSONValue.Bool(a.inTrial))
                    }))
                }
                judged.anchor?.let { a ->
                    put("anchor", JSONValue.Object(mapOf(
                        "place" to JSONValue.String(a.place),
                        "centroidHz" to JSONValue.Number(a.centroidHz.toDouble()),
                        "peakHz" to JSONValue.Number(a.peakHz.toDouble()),
                        "bandRatioDb" to JSONValue.Number(a.bandRatioDb.toDouble()),
                        "tiltDb" to JSONValue.Number(a.tiltDb.toDouble()),
                    )))
                }
                judged.f0Hz?.let { put("f0Hz", JSONValue.Number(it.toDouble())) }
            }
        }
        io {
            addAttempt(
                Attempt(
                    id = id,
                    sessionId = take.sessionId,
                    profileId = p,
                    ts = take.endedAt,
                    exerciseKind = take.step,
                    sound = take.sound.raw,
                    target = take.text,
                    machineOutcome = outcome(verdict),
                    adultOutcome = null,
                    detail = detail,
                    recordingId = recordingId,
                ),
            )
        }
        return id
    }

    private suspend fun saveRecording(profileId: UUID, take: TakeRecord, clip: Clip): UUID = io {
        val rec = Recording(profileId = profileId, ts = take.endedAt, label = take.text, sound = take.sound.raw, duration = clip.duration)
        val file = recordingFileURL(rec)
        Wav.write(file, clip)
        addRecording(rec)
        rec.id
    }

    /** Every attempt of the child, oldest first (the adult area and the calibration read these). */
    suspend fun allAttempts(): List<AttemptView> {
        val p = profile()
        return io { sessions(p).flatMap { attempts(it.id) } }.map(AttemptView::from).sortedBy { it.ts }
    }

    /** The kept recordings (newest first) with the file to play. */
    suspend fun recordings(): List<Pair<Recording, File>> {
        val p = profile()
        return io { recordings(p).map { it to recordingFileURL(it) } }
    }

    /** „Șterge tot”: every row, every recording file; the next call creates a fresh child profile. */
    suspend fun wipe() {
        lock.withLock {
            io { wipeAll() }
            profileId = null
        }
    }

    /** The adult's thumb on an attempt (👍 = good, "mai încercăm" = not yet). */
    suspend fun adult(attemptId: UUID, good: Boolean) =
        io { setAdultOutcome(attemptId, if (good) Attempt.Outcome.Good else Attempt.Outcome.NotYet) }

    suspend fun todayCount(): Int {
        val p = profile()
        return io { attemptCount(p, localDay(Instant.now())) }
    }

    /** Finished lessons per sound, and whether it was practised today. */
    suspend fun soundStats(): Map<SoundId, SoundStats> {
        val p = profile()
        val today = localDay(Instant.now())
        val sessions = io { sessions(p) }
        return SoundId.entries.associateWith { s ->
            val mine = sessions.filter { it.sound == s.raw && it.attempts > 0 }
            SoundStats(sessions = mine.size, practisedToday = mine.any { it.localDay == today })
        }
    }

    /** The sound of the latest lesson with attempts (the "current" node), or null on the first day. */
    suspend fun lastSound(): SoundId? {
        val p = profile()
        return io { sessions(p) }.lastOrNull { it.attempts > 0 }?.sound?.let(SoundId::of)
    }

    companion object {
        fun outcome(v: Verdict): Attempt.Outcome = when (v) {
            Verdict.Correct -> Attempt.Outcome.Good
            Verdict.Almost -> Attempt.Outcome.Almost
            is Verdict.NamedError -> Attempt.Outcome.NotYet
            Verdict.Unsure -> Attempt.Outcome.Unclear
            Verdict.Retake -> Attempt.Outcome.NotJudged
        }
    }
}
