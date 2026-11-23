package tech.fonia.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import tech.fonia.app.adult.AdultGate
import tech.fonia.app.data.AppPrefs
import tech.fonia.app.data.AppSettings
import tech.fonia.app.data.AttemptLog
import tech.fonia.app.data.CalibrationStore
import tech.fonia.app.data.MemoryPrefs
import tech.fonia.app.data.SharedAppPrefs
import tech.fonia.app.data.Versions
import tech.fonia.app.data.Wiper
import tech.fonia.app.judge.ProvisionalTuning
import tech.fonia.app.judge.RealJudge
import tech.fonia.app.judge.TakeJudging
import tech.fonia.app.content.ContentRepo
import tech.fonia.app.data.FilePracticeStore
import tech.fonia.app.data.MemoryPracticeStore
import tech.fonia.app.data.PracticeBook
import tech.fonia.app.judge.PhoneScoring
import tech.fonia.app.lesson.LessonTuning
import tech.fonia.ml.ModelMemoryPolicy
import tech.fonia.ml.ModelRegistry
import tech.fonia.ml.ZipaHost
import tech.fonia.app.shell.PermissionGateway
import tech.fonia.app.speaking.DirectorSpeakingAudio
import tech.fonia.app.speaking.SpeakingTakes
import tech.fonia.speech.SpeechModelStore
import tech.fonia.speech.Transcriber
import tech.fonia.speech.WhisperEngine
import tech.fonia.whisper.FoniaWhisper
import tech.fonia.whisper.WhisperCppLoader
import tech.fonia.whisper.WhisperCppModel
import java.io.File
import tech.fonia.audio.MicrophoneSource
import tech.fonia.audio.OboeAudioDirector
import tech.fonia.live.AudioDirector
import tech.fonia.live.SuspendReason
import tech.fonia.store.FoniaDatabase
import tech.fonia.store.FoniaRepository

/** What tests and the fixture mode replace (docs/v2/02 §8). */
data class Overrides(
    val audio: AudioDirector? = null,
    val judge: TakeJudging? = null,
    val takeSources: TakeSources? = null,
    val prefs: AppPrefs? = null,
)

/**
 * The whole object graph, written by hand (constructor injection, no DI framework). The only place that calls
 * constructors of services; screens receive what they need from here. One per process ([FoniaApp.graph]).
 *
 * Routing hooks for the next packages live in [tech.fonia.app.shell.FoniaShell]: `openMirror()` (MIRROR) and
 * `openSpeaking()` (`:feature:speaking`).
 */
class AppGraph(context: Context, val flags: LaunchFlags, overrides: Overrides = Overrides()) {
    private val app = context.applicationContext

    /** Process scope for saves that must outlive a screen. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val lessonTuning = LessonTuning()
    val judgeTuning = ProvisionalTuning(holdGoalSeconds = lessonTuning.holdGoalSeconds)

    /** Content v2 + Foni's voice index, read once on IO (`assets/content`, the `contentAssets` build step). */
    val content = ContentRepo(scope) { ContentRepo.fromAssets(app.assets) }

    /**
     * ZIPA, the phoneme model in the APK (ml/README.md): cheap to create (reads the manifest only), warmed when a lesson
     * opens, trimmed when it closes and on memory pressure. Null if the manifest is missing; with `-Pfonia.zipa=false`
     * the model file is absent, the first load fails and every take keeps the fast head.
     */
    val zipa: ZipaHost? = runCatching { ZipaHost.create(app) }.onFailure { android.util.Log.w("FoniaZipa", "no ZIPA: $it") }.getOrNull()
        ?.also { app.registerComponentCallbacks(ModelMemoryPolicy(listOf(it))) }

    private val modelVersions: String = runCatching { ModelRegistry.load(app.assets).versions() }.getOrDefault("")

    /** Rungs, locks, LessonHistory, days, today's mission: one JSON file in no-backup storage (memory for the demo). */
    val practice = PracticeBook(
        if (flags.demo || flags.screen == "onboarding") MemoryPracticeStore() else FilePracticeStore(File(app.noBackupFilesDir, "practice/practice.json")),
    )

    /** Where takes come from when it is not the microphone (debug fixture mode only). */
    val takeSources: TakeSources? = overrides.takeSources ?: VariantHooks.takeSources(app, flags)

    val fixtureMode: Boolean get() = takeSources != null

    val audio: AudioDirector = overrides.audio ?: OboeAudioDirector(
        context = app,
        scope = scope,
        sourceFor = { target -> takeSources?.source(target) ?: MicrophoneSource(app) },
    )

    /** THE judge: `tech.fonia.judge.TakeJudge` over `assets/spec` (parsed once, off the main thread). */
    val judge: TakeJudging = overrides.judge ?: RealJudge(
        scope,
        phones = zipa?.let { host -> PhoneScoring { clip, request -> host.score(clip, request) } },
    ) { RealJudge.fromAssets(app.assets) }

    val permissions = PermissionGateway(app)

    private val database by lazy { FoniaDatabase.live(app.noBackupFilesDir) }
    private val repository by lazy { FoniaRepository(database) }

    val versions = Versions(
        app = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "?",
        judge = judge.version,
        lesson = lessonTuning.version,
    )

    /** With the content version (once loaded) and the model versions, for every attempt row. */
    private fun versionsNow() = versions.copy(content = content.now()?.contentVersion ?: "?", models = modelVersions)

    val attempts = AttemptLog({ repository }, { versionsNow() })

    /** Onboarding state, talker group, reduce motion. The demo film runs on its own memory copy (it shows ON-0). */
    val settings = AppSettings(overrides.prefs ?: if (flags.demo || flags.screen == "onboarding") MemoryPrefs() else SharedAppPrefs(app))

    val gate = AdultGate()

    val calibration = CalibrationStore(attempts) { settings.talker }

    val wiper = Wiper(
        wipeStore = { attempts.wipe() },
        settings = settings,
        gate = gate,
        extraDirs = listOf(app.cacheDir, app.noBackupFilesDir),
        // the store's own folder (database + recordings), already emptied by wipeAll; the Whisper model (no personal data)
        keep = setOf("Fonia", "FoniaSpeech"),
        afterWipe = { calibration.clear(); scope.launch { practice.wipe() } },
    )

    // ---- public speaking (`:feature:speaking`)

    /** NO-UM and MINUTE capture and play through the same director (quiet rule included). */
    val speakingAudio = DirectorSpeakingAudio(audio, beforeRecord = { takeSources?.beforeSpeaking() })

    /** Takes kept with „Păstrează” (WAVs in no-backup storage; wiped by „Șterge tot”). */
    val speakingTakes = SpeakingTakes(File(app.noBackupFilesDir, "speaking"))

    /**
     * whisper.cpp's files, if someone copied them onto the phone (app/README: adb). Built without a fetcher: the app
     * never downloads anything. The model is not in the APK (148 MB).
     */
    private val speechModel = SpeechModelStore(
        manifest = WhisperCppModel.WHISPER_BASE_FROM_HUGGING_FACE,
        root = FoniaWhisper.defaultRoot(app),
        loader = WhisperCppLoader(),
    )
    @Volatile private var whisper: WhisperEngine? = null

    /** The Transcriber when the Whisper model is on the phone (and not switched off by `--ez whisper false`), else null. */
    fun transcriber(): Transcriber? {
        if (!flags.whisper || !speechModel.isReady) return null
        return whisper ?: synchronized(this) { whisper ?: speechModel.makeEngine().also { whisper = it } }
    }

    val whisperReady: Boolean get() = flags.whisper && speechModel.isReady

    /** Loads Whisper's weights in the background (TEEN-HOME calls it), so the first NO-UM / MINUTE take does not wait. */
    fun prewarmTranscriber() {
        val engine = transcriber() as? WhisperEngine ?: return
        scope.launch { runCatching { engine.load() } }
    }

    /** MIRROR opened from a lesson: the camera's mouth values go to the live band (N4). Camera thread. */
    fun postMouth(open: Float, round: Float, nanos: Long) {
        (audio as? OboeAudioDirector)?.pipeline?.postMouth(open, round, nanos)
    }

    init {
        applyTalkerFlag()
        scope.launch {
            runCatching { repository.runStartupMaintenance() }
            calibration.refresh()
        }
    }

    /** Debug: `--es talker child|adultHigh|adultLow` (fixture tests of the adult-voice gate); re-applied after onboarding. */
    fun applyTalkerFlag() {
        flags.talker?.let { raw -> tech.fonia.app.judge.Talker.entries.firstOrNull { it.raw == raw }?.let(settings::updateTalker) }
    }

    // ---- lifecycle (the activity calls these; docs: live/README "Wiring OboeAudioDirector")
    fun onForeground() {
        gate.onForeground()
        audio.resume()
    }

    fun onBackground() {
        gate.onBackground()
        audio.suspendAll(SuspendReason.Background)
    }

    fun release() {
        scope.launch { (audio as? OboeAudioDirector)?.release() ?: audio.closeMic() }
    }
}
