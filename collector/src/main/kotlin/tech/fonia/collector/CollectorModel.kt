package tech.fonia.collector

import android.app.Application
import android.os.Build
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.fonia.audio.ClipLocation
import tech.fonia.audio.PlaybackEngine
import tech.fonia.collector.core.ActiveSession
import tech.fonia.collector.core.CaptureSummary
import tech.fonia.collector.core.ClipRecord
import tech.fonia.collector.core.ConsentRecord
import tech.fonia.collector.core.DeviceInfo
import tech.fonia.collector.core.Export
import tech.fonia.collector.core.Level
import tech.fonia.collector.core.Levels
import tech.fonia.collector.core.Mode
import tech.fonia.collector.core.Naming
import tech.fonia.collector.core.Prompt
import tech.fonia.collector.core.PromptCatalog
import tech.fonia.collector.core.PromptFile
import tech.fonia.collector.core.QualitySummary
import tech.fonia.collector.core.Records
import tech.fonia.collector.core.Role
import tech.fonia.collector.core.RoomCheck
import tech.fonia.collector.core.SessionRecord
import tech.fonia.collector.core.SpeakerSummary
import tech.fonia.collector.core.Store
import tech.fonia.dsp.DSPThresholds
import tech.fonia.dsp.VoiceGroup
import tech.fonia.dsp.analyzeClip
import java.io.File
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

enum class Screen { Home, Consent, Speaker, Room, Record, Done, Export }

enum class TakePhase { Idle, Starting, Listening, Saving, Review }

class ExportState(val zip: File, val files: Int, val sha: String, val problems: List<String>)

/** The whole flow: acord → fișa vorbitorului → camera → rostiri → reascultare → export. */
class CollectorModel(app: Application) : AndroidViewModel(app) {
    private companion object {
        const val TAG = "Colector"
    }

    private val context = app.applicationContext
    val store = Store(File(context.filesDir, "data"), File(context.filesDir, "active.json"))
    val exportDir: File get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "export")
    val catalog: PromptFile = PromptCatalog.parse(context.assets.open("prompts.json").use { it.readBytes().toString(Charsets.UTF_8) })
    val device = DeviceInfo(Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE ?: "?", Build.VERSION.SDK_INT)

    var fixture: FixtureConfig? = null
        private set
    private var recorderInstance: Recorder? = null
    val recorder: Recorder
        get() = recorderInstance ?: Recorder(context, fixture).also { recorderInstance = it }
    private val playback by lazy { PlaybackEngine(context) }

    var screen by mutableStateOf(Screen.Home)

    // Consent draft.
    var consentChild by mutableStateOf(false)
    var consentAgreed by mutableStateOf(false)
    var consentExtended by mutableStateOf(false)
    var initials by mutableStateOf("")
    var formCode by mutableStateOf("")
    private var consent: ConsentRecord? = null

    // Speaker sheet draft.
    var round by mutableStateOf("r01")
    var speaker by mutableStateOf("")
    var ageBand by mutableStateOf<String?>(null)
    var packId by mutableStateOf("A")
    var autoFlow by mutableStateOf(true)

    // The session.
    var active by mutableStateOf<ActiveSession?>(null)
        private set
    var plan by mutableStateOf<List<Prompt>>(emptyList())
        private set

    // Room.
    var roomMeasuring by mutableStateOf(false)
    var room by mutableStateOf<RoomCheck?>(null)
    var capture by mutableStateOf<CaptureSummary?>(null)
    var micMessage by mutableStateOf<String?>(null)

    // Takes.
    var phase by mutableStateOf(TakePhase.Idle)
    var lastTake by mutableStateOf<ClipRecord?>(null)
    var autoNextIn by mutableIntStateOf(0)
    var playing by mutableStateOf(false)
    private var takeJob: Job? = null
    private var autoJob: Job? = null

    // Done and export.
    var finished by mutableStateOf<SessionRecord?>(null)
    var speakers by mutableStateOf<List<SpeakerSummary>>(emptyList())
    val exports = mutableStateMapOf<String, ExportState>()
    var exportBusy by mutableStateOf<String?>(null)
    var deleteArmed by mutableStateOf<String?>(null)

    init {
        active = store.loadActive()
        active?.let { plan = planFor(it.session) }
    }

    fun configure(fixture: FixtureConfig?) {
        if (recorderInstance == null) this.fixture = fixture
    }

    val role: Role? get() = Naming.roleOf(speaker)

    private fun planFor(s: SessionRecord): List<Prompt> =
        PromptCatalog.plan(catalog, s.pack, Role.of(s.role) ?: Role.Adult)

    fun planSize(pack: String, role: Role): Int = PromptCatalog.plan(catalog, pack, role).size

    // --- Navigation -----------------------------------------------------------------------------------------

    fun newSession() {
        consent = null
        consentAgreed = false
        consentExtended = false
        initials = ""
        formCode = ""
        speaker = ""
        ageBand = null
        screen = Screen.Consent
    }

    fun goHome() {
        cancelTake()
        stopPlayback()
        screen = Screen.Home
    }

    fun back() {
        when (screen) {
            Screen.Consent, Screen.Export, Screen.Done -> goHome()
            Screen.Speaker -> screen = Screen.Consent
            Screen.Room -> if (active?.index == 0 && active?.pendingTake == null) screen = Screen.Speaker else goHome()
            Screen.Record -> goHome()
            Screen.Home -> Unit
        }
    }

    val consentValid: Boolean
        get() = consentAgreed && if (consentChild) Naming.FORM_CODE.matches(formCode.trim()) else Naming.INITIALS.matches(initials.trim())

    fun acceptConsent() {
        if (!consentValid) return
        consent = ConsentRecord(
            type = if (consentChild) "parent" else "adult",
            version = if (consentChild) Records.CONSENT_PARENT else Records.CONSENT_ADULT,
            initials = if (consentChild) null else initials.trim().uppercase(),
            formCode = if (consentChild) formCode.trim().uppercase() else null,
            extendedRetention = consentExtended,
            at = now(),
        )
        autoFlow = !consentChild
        screen = Screen.Speaker
    }

    /** Why the speaker sheet cannot go on yet, or null. */
    val speakerProblem: String?
        get() {
            val r = role ?: return "Codul are o literă și două cifre: A07, T02 sau C13."
            if (!Naming.ROUND.matches(round)) return "Runda are forma r01."
            if (consentChild && r != Role.Child) return "Pentru un copil, codul începe cu C."
            if (!consentChild && r == Role.Child) return "Codul unui copil începe cu C și cere acordul părintelui."
            if (ageBand == null || ageBand !in Naming.ageBandsFor(r)) return "Alegeți grupa de vârstă."
            return null
        }

    fun startSession() {
        val r = role ?: return
        val c = consent ?: return
        if (speakerProblem != null) return
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val session = SessionRecord(
            sessionId = "$round-$speaker-$packId-$stamp",
            round = round, speaker = speaker, role = r.raw, ageBand = ageBand!!,
            mode = if (r == Role.Child) Mode.Child.raw else Mode.Adult.raw,
            pack = packId, sounds = catalog.pack(packId)!!.sounds, consent = c, device = device,
            startedAt = now(), updatedAt = now(), promptCount = planSize(packId, r),
            appVersion = BuildConfig.VERSION_NAME, promptsVersion = catalog.version,
            thresholdsVersion = DSPThresholds.v1.version, fixture = fixture != null,
        )
        val a = ActiveSession(session, index = 0, autoFlow = autoFlow)
        store.upsertSession(session)
        store.saveActive(a)
        active = a
        plan = planFor(session)
        room = null
        screen = Screen.Room
    }

    fun resume() {
        val a = active ?: return
        autoFlow = a.autoFlow
        consent = a.session.consent
        room = null
        screen = Screen.Room
    }

    /** Same speaker and consent, another pack. */
    fun anotherPack() {
        val f = finished ?: return
        consent = f.consent
        consentChild = f.consent.type == "parent"
        round = f.round
        speaker = f.speaker
        ageBand = f.ageBand
        screen = Screen.Speaker
    }

    // --- Room -----------------------------------------------------------------------------------------------

    fun measureRoom() {
        val a = active ?: return
        if (roomMeasuring) return
        roomMeasuring = true
        micMessage = null
        viewModelScope.launch {
            try {
                val result = recorder.measureRoom()
                val take = store.nextRoomTake(a.session.round, a.session.speaker)
                val rel = Naming.roomPath(a.session.round, a.session.speaker, take)
                writeWav(result.clip, store.file(rel))
                capture = recorder.captureSummary(result.clip.sampleRate)
                room = RoomCheck(
                    at = now(), floorDb = result.floorDb, noisy = Levels.isNoisy(result.floorDb), continuedAnyway = false,
                    file = rel, seconds = result.clip.duration,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "room check failed", e)
                micMessage = "Microfonul nu a pornit (${e.javaClass.simpleName}). Verificați permisiunea și încercați din nou."
            } finally {
                roomMeasuring = false
            }
        }
    }

    fun startRecording() {
        val a = active ?: return
        val r = room ?: return
        val check = r.copy(continuedAnyway = r.noisy)
        val s = a.session.copy(roomChecks = a.session.roomChecks + check, capture = capture ?: a.session.capture, updatedAt = now())
        update(a.copy(session = s, autoFlow = autoFlow))
        phase = if (a.pendingTake != null) TakePhase.Review else TakePhase.Idle
        lastTake = a.pendingTake
        screen = Screen.Record
        if (phase == TakePhase.Idle && autoFlow) startTake(delayMs = 700)
    }

    // --- Takes ----------------------------------------------------------------------------------------------

    val current: Prompt? get() = active?.let { plan.getOrNull(it.index) }

    private fun update(a: ActiveSession) {
        active = a
        store.saveActive(a)
        store.upsertSession(a.session)
    }

    fun startTake(delayMs: Long = 0) {
        val a = active ?: return
        val prompt = current ?: return
        if (phase == TakePhase.Starting || phase == TakePhase.Listening || phase == TakePhase.Saving) return
        autoJob?.cancel()
        autoNextIn = 0
        stopPlayback()
        phase = TakePhase.Starting
        takeJob = viewModelScope.launch {
            try {
                if (delayMs > 0) delay(delayMs)
                val child = a.session.mode == Mode.Child.raw
                val maximum = when (prompt.level) {
                    Level.Sentence -> 10.0
                    Level.Isolated -> 6.0
                    else -> 6.0
                }
                val result = recorder.recordTake(maximum = maximum, startTimeout = if (child) 10.0 else 6.0) {
                    phase = TakePhase.Listening
                }
                phase = TakePhase.Saving
                val record = saveTake(a, prompt, result)
                val summary = recorder.captureSummary(result.clip.sampleRate)
                capture = summary
                val latest = active ?: a
                update(latest.copy(pendingTake = record, session = latest.session.copy(capture = summary, updatedAt = now())))
                lastTake = record
                phase = TakePhase.Review
                if (autoFlow && result.speechDetected && result.endedBy != EndedBy.Maximum) autoAdvance()
            } catch (e: CancellationException) {
                phase = if (active?.pendingTake != null) TakePhase.Review else TakePhase.Idle
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "take failed", e)
                micMessage = "Înregistrarea nu a pornit (${e.javaClass.simpleName}). Încercați din nou."
                phase = TakePhase.Idle
            }
        }
    }

    private suspend fun saveTake(a: ActiveSession, prompt: Prompt, result: TakeResult): ClipRecord {
        val s = a.session
        val take = store.nextTake(s.round, s.speaker, prompt.soundKey, prompt.promptId, prompt.label)
        val rel = Naming.clipPath(s.round, s.speaker, prompt.soundKey, prompt.promptId, prompt.label, take)
        writeWav(result.clip, store.file(rel))
        val quality = withContext(Dispatchers.Default) {
            runCatching {
                val group = if (s.role == Role.Child.raw) VoiceGroup.CHILD else VoiceGroup.FEMALE
                val q = analyzeClip(result.clip.samples, result.clip.sampleRate, group, null).quality
                QualitySummary(
                    usable = q.isUsable, clipped = q.isClipped, tooQuiet = q.isTooQuiet, tooShort = q.isTooShort,
                    clippedFraction = q.clippedFraction, peak = q.peakAmplitude, speechRmsDb = q.speechRmsDb,
                    dynamicRangeDb = q.dynamicRangeDb, speechSeconds = q.speechSeconds, bandwidthHz = q.bandwidthHz,
                    canJudgeSibilants = q.canJudgeSibilants,
                )
            }.getOrNull()
        }
        val cap = recorder.captureSummary(result.clip.sampleRate)
        return ClipRecord(
            file = rel, sessionId = s.sessionId, round = s.round, speaker = s.speaker, role = s.role, ageBand = s.ageBand,
            mode = s.mode, device = "${device.manufacturer} ${device.model}", android = device.android, sdk = device.sdk,
            audioSource = cap.audioSource, sampleRate = result.clip.sampleRate.toInt(), narrowBand = cap.narrowBand,
            bandwidthHz = quality?.bandwidthHz, promptId = prompt.promptId, sound = prompt.sound.raw, soundKey = prompt.soundKey,
            level = prompt.level.raw, position = prompt.position?.raw, text = prompt.text, imitation = prompt.imitation,
            hint = prompt.hint, intendedLabel = prompt.intendedLabel, label = prompt.label, errorKind = prompt.kind?.name,
            heardAs = prompt.heardAs, wave = prompt.wave, take = take, outcome = "pending",
            speechDetected = result.speechDetected, speechStartSec = result.speechStartSec?.round3(),
            speechEndSec = result.speechEndSec?.round3(), endedBy = result.endedBy.raw,
            durationSec = result.clip.duration.round3(), samples = result.clip.samples.size, dropped = result.clip.dropped,
            roomFloorDb = s.roomChecks.lastOrNull()?.floorDb, quality = quality, recordedAt = now(),
            appVersion = s.appVersion, promptsVersion = s.promptsVersion, thresholdsVersion = s.thresholdsVersion,
            consentVersion = s.consent.version, fixture = s.fixture,
        )
    }

    private fun autoAdvance() {
        autoJob?.cancel()
        autoJob = viewModelScope.launch {
            for (n in 2 downTo 1) {
                autoNextIn = n
                delay(700)
            }
            autoNextIn = 0
            accept()
        }
    }

    fun holdAuto() {
        autoJob?.cancel()
        autoNextIn = 0
    }

    fun stopTake() = recorder.stop()

    private fun cancelTake() {
        autoJob?.cancel()
        autoNextIn = 0
        takeJob?.cancel()
        takeJob = null
    }

    fun accept() = decide("accepted", advance = true)

    fun redo() {
        decide("redo", advance = false)
        startTake()
    }

    fun couldNotImitate() {
        val a = active ?: return
        val prompt = current ?: return
        if (a.pendingTake == null) {
            cancelTake()
            val s = a.session
            val cap = capture ?: s.capture
            store.appendClip(
                ClipRecord(
                    file = null, sessionId = s.sessionId, round = s.round, speaker = s.speaker, role = s.role, ageBand = s.ageBand,
                    mode = s.mode, device = "${device.manufacturer} ${device.model}", android = device.android, sdk = device.sdk,
                    audioSource = cap?.audioSource ?: "unknown", sampleRate = cap?.sampleRate ?: 0, narrowBand = cap?.narrowBand ?: false,
                    bandwidthHz = null, promptId = prompt.promptId, sound = prompt.sound.raw, soundKey = prompt.soundKey,
                    level = prompt.level.raw, position = prompt.position?.raw, text = prompt.text, imitation = prompt.imitation,
                    hint = prompt.hint, intendedLabel = prompt.intendedLabel, label = prompt.label, errorKind = prompt.kind?.name,
                    heardAs = prompt.heardAs, wave = prompt.wave, take = 0, outcome = "couldNotImitate", speechDetected = false,
                    speechStartSec = null, speechEndSec = null, endedBy = "none", durationSec = 0.0, samples = 0, dropped = 0,
                    roomFloorDb = s.roomChecks.lastOrNull()?.floorDb, quality = null, recordedAt = now(), appVersion = s.appVersion,
                    promptsVersion = s.promptsVersion, thresholdsVersion = s.thresholdsVersion, consentVersion = s.consent.version,
                    fixture = s.fixture,
                ),
            )
            advance(a, kept = false)
        } else {
            decide("couldNotImitate", advance = true)
        }
    }

    private fun decide(outcome: String, advance: Boolean) {
        autoJob?.cancel()
        autoNextIn = 0
        stopPlayback()
        val a = active ?: return
        val pending = a.pendingTake ?: return
        store.appendClip(pending.copy(outcome = outcome))
        val cleared = a.copy(pendingTake = null)
        lastTake = null
        if (advance) advance(cleared, kept = outcome == "accepted") else update(cleared)
        if (!advance) phase = TakePhase.Idle
    }

    private fun advance(a: ActiveSession, kept: Boolean, to: Int = a.index + 1) {
        val s = a.session.copy(
            promptsDone = to.coerceAtMost(plan.size),
            clipsKept = a.session.clipsKept + if (kept) 1 else 0,
            updatedAt = now(),
        )
        val next = a.copy(index = to, session = s, pendingTake = null)
        lastTake = null
        phase = TakePhase.Idle
        if (to >= plan.size) {
            finish(next)
            return
        }
        update(next)
        if (autoFlow) startTake(delayMs = 600)
    }

    /** Leaves the rest of this sound for later (or never); the session notes it. */
    fun skipSound() {
        val a = active ?: return
        val prompt = current ?: return
        cancelTake()
        a.pendingTake?.let { store.appendClip(it.copy(outcome = "interrupted")) }
        var to = a.index
        while (to < plan.size && plan[to].sound == prompt.sound) to++
        val s = a.session.copy(skippedSounds = a.session.skippedSounds + prompt.sound.raw)
        advance(a.copy(session = s, pendingTake = null), kept = false, to = to)
    }

    fun setAuto(on: Boolean) {
        autoFlow = on
        if (!on) holdAuto()
        active?.let { update(it.copy(autoFlow = on)) }
        if (on && phase == TakePhase.Idle && screen == Screen.Record) startTake(delayMs = 400)
    }

    private fun finish(a: ActiveSession) {
        val s = a.session.copy(finishedAt = now(), updatedAt = now())
        store.upsertSession(s)
        store.clearActive()
        active = null
        finished = s
        screen = Screen.Done
    }

    fun keptClips(session: SessionRecord): List<ClipRecord> =
        store.readClips(session.round, session.speaker).filter { it.sessionId == session.sessionId && it.outcome == "accepted" }

    // --- Playback -------------------------------------------------------------------------------------------

    fun play(relative: String?) {
        if (relative == null) return
        holdAuto()
        viewModelScope.launch {
            playing = true
            try {
                playback.play(ClipLocation.File(store.file(relative)), null)
            } finally {
                playing = false
            }
        }
    }

    fun stopPlayback() {
        if (!playing) return
        viewModelScope.launch { playback.stop() }
    }

    // --- Export ---------------------------------------------------------------------------------------------

    fun openExport() {
        speakers = store.speakers()
        deleteArmed = null
        screen = Screen.Export
    }

    fun export(summary: SpeakerSummary) {
        val id = "${summary.round}/${summary.speaker}"
        if (exportBusy != null) return
        exportBusy = id
        viewModelScope.launch {
            try {
                val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
                val out = File(exportDir, Naming.exportName(summary.round, summary.speaker, stamp))
                val result = withContext(Dispatchers.IO) { Export.writeZip(store.dataRoot, id, out) }
                val problems = withContext(Dispatchers.IO) { Export.verifyZip(out) }
                exports[id] = ExportState(out, result.fileCount, result.zipSha256, problems)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                exports[id] = ExportState(File("?"), 0, "", listOf(e.message ?: e.javaClass.simpleName))
            } finally {
                exportBusy = null
            }
        }
    }

    fun delete(summary: SpeakerSummary) {
        val id = "${summary.round}/${summary.speaker}"
        if (deleteArmed != id) {
            deleteArmed = id
            return
        }
        deleteArmed = null
        store.deleteSpeaker(summary.round, summary.speaker)
        if (active?.session?.let { "${it.round}/${it.speaker}" } == id) {
            active = null
            plan = emptyList()
        }
        exports.remove(id)
        speakers = store.speakers()
    }

    private fun now(): String = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString()

    private fun Double.round3(): Double = Math.round(this * 1000.0) / 1000.0
}
