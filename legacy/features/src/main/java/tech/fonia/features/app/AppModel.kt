package tech.fonia.features.app

import android.content.Context
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.fonia.audio.ClipLocation
import tech.fonia.domain.ContentSource
import tech.fonia.domain.CourseUnit
import tech.fonia.domain.DictionAreaID
import tech.fonia.domain.EnglishFocus
import tech.fonia.domain.Exercise
import tech.fonia.domain.FeedbackLevel
import tech.fonia.domain.Language
import tech.fonia.domain.RepeatPrompt
import tech.fonia.domain.SessionBlock
import tech.fonia.domain.Trial
import tech.fonia.features.check.CheckStore
import tech.fonia.features.check.FileCheckStore
import tech.fonia.features.check.MemoryCheckStore
import tech.fonia.features.check.QuickCheckRules
import tech.fonia.domain.FoniaContent
import tech.fonia.domain.Lesson
import tech.fonia.domain.Rung
import tech.fonia.domain.SessionFocus
import tech.fonia.domain.SoundID
import tech.fonia.domain.VoiceIndex
import tech.fonia.dsp.FricativeSegment
import tech.fonia.dsp.SibilantPlace
import tech.fonia.dsp.TalkerCalibration
import tech.fonia.face.FaceSource
import tech.fonia.face.FakeFaceSource
import tech.fonia.features.IS_DEBUG_BUILD
import tech.fonia.features.adaptive.AdaptiveStore
import tech.fonia.features.ml.MlHub
import tech.fonia.features.pilot.PilotLog
import tech.fonia.features.pilot.PilotSlot
import tech.fonia.features.pilot.PilotSwitch
import tech.fonia.features.pilot.pilotEnvironment
import tech.fonia.features.reading.ReadingStore
import tech.fonia.features.session.soundID
import tech.fonia.store.FoniaDatabase
import tech.fonia.store.FoniaRepository
import tech.fonia.store.Profile
import tech.fonia.whisper.FoniaWhisper
import tech.fonia.whisper.WhisperCppModel
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

/** Where a tap can lead: the pages pushed on the one navigation stack of the tabs. */
sealed interface AppRoute {
    data class Guide(val sound: SoundID) : AppRoute

    data class Mirror(val sound: SoundID) : AppRoute

    data class Coach(val key: String) : AppRoute

    // `.unit(String)` on iOS. Inside this interface `Unit` names the route, not kotlin.Unit.
    data class Unit(val id: String) : AppRoute

    data object AllSounds : AppRoute

    data object Warmup : AppRoute

    data object Play : AppRoute

    data object Journal : AppRoute

    data object Guides : AppRoute

    data object Stories : AppRoute

    data object English : AppRoute

    data object Speaking : AppRoute

    data object Settings : AppRoute

    /** „Antrenament de dicție” of a sound: the ladder of drills. */
    data class Training(val sound: SoundID) : AppRoute

    companion object {
        /**
         * Parses `foniaRoute`: `journal`, `settings`, `sounds`, `warmup`, `play`, `guides`, `stories`, `english`,
         * `speaking`, `unit:<id>`, `guide:<sound>`, `mirror:<sound>`, `coach:<key>`.
         */
        fun parse(text: String): AppRoute? {
            val parts = text.split(":", limit = 2)
            return when (parts[0]) {
                "journal" -> Journal
                "settings" -> Settings
                "sounds" -> AllSounds
                "warmup" -> Warmup
                "play" -> Play
                "guides" -> Guides
                "stories" -> Stories
                "english" -> English
                "speaking" -> Speaking
                "unit" -> parts.getOrNull(1)?.let { Unit(it) }
                "guide" -> parts.getOrNull(1)?.let { SoundID.fromRawValue(it) }?.let { Guide(it) }
                "mirror" -> parts.getOrNull(1)?.let { SoundID.fromRawValue(it) }?.let { Mirror(it) }
                "coach" -> parts.getOrNull(1)?.let { Coach(it) }
                "training" -> parts.getOrNull(1)?.let { SoundID.fromRawValue(it) }?.let { Training(it) }
                else -> null
            }
        }
    }
}

/** What's on screen as a full-screen cover: a practice session. */
data class SessionTicket(
    val id: UUID = UUID.randomUUID(),
    val focus: SessionFocus,
    val title: String,
    val sound: SoundID?,
    /** Trials the app prepared itself (the quick check, a diction drill) instead of the session builder's. */
    val custom: CustomRun? = null,
)

/** A run of trials made by the app, played through the same session screen, take cycle and judge. */
data class CustomRun(
    val kind: Kind,
    val trials: List<Trial>,
    /** Per trial, the speed the model is played at (a twister's tempo ladder); null: normal. */
    val modelSpeeds: List<Float>? = null,
) {
    sealed interface Kind {
        /** „Verificare rapidă”: one or two words per sound; ends on the three groups. */
        data object Check : Kind

        /** One drill of the diction training of [sound], the [index]-th of its ladder. */
        data class Drill(val sound: SoundID, val index: Int) : Kind
    }
}

/**
 * The launch arguments of the iOS app, which arrive here as intent extras. `foniaFixtures` works in any build, as
 * `-foniaFixtures` does; the others only in a debug build, as on iOS (`#if DEBUG`).
 *
 * | iOS | intent extra | |
 * |---|---|---|
 * | `-foniaResetData` | `foniaResetData` (boolean) | wipes everything before the first read |
 * | `-foniaFixtures` | `foniaFixtures` (boolean) | takes come from the recorded clip of the target; on iOS always on in the simulator |
 * | `-foniaStart s:wordsInitial` | `foniaStart` (string) | opens one session once a profile is active |
 * | `-foniaMouths` | `foniaMouths` (boolean) | shows the mouths of the design gallery instead of the app |
 * | | `foniaGallery` (string) | shows the design gallery at a section (`componente`, `acasa`, `lectia`, `foni`, `guri`, `scene`) |
 * | | `foniaRoute` (string) | pushes one page once a profile is active, for tests and screenshots (see [AppRoute.parse]) |
 * | `-foniaSpeakOnly`, `-foniaShort` | `foniaSpeakOnly`, `foniaShort` (boolean) | read by the session (second half) |
 * | | `foniaFakeSpeech` (boolean) | a speech model that downloads in two seconds and answers canned results |
 * | | `foniaReduceMotion` (boolean) | Reduce Motion on whatever the system says, and the voice meters still, for the UI tests (Compose tests wait for idle, and Foni, the sky and a live meter never are) |
 * | | `foniaFace` (string) | the face the mouth coach sees, held still, as `name=value` pairs of `FaceFrame` (`smile=0.8,jawOpen=0.1`) |
 * | | `foniaMicFile` (string) | a WAV on the device read in real time in place of the microphone, then silence, through the same live path (levels, judge, screens) |
 *
 * The same switches can also come as iOS-style strings in a string-array extra `arguments`
 * (`["-foniaResetData", "-foniaStart", "s:wordsInitial"]`), as `ProcessInfo.arguments` has them.
 */
data class LaunchArguments(
    val resetData: Boolean = false,
    val fixtures: Boolean = false,
    val start: String? = null,
    val mouths: Boolean = false,
    val gallery: String? = null,
    val route: String? = null,
    val speakOnly: Boolean = false,
    val short: Boolean = false,
    val fakeSpeech: Boolean = false,
    /** Android only, for the UI tests: Reduce Motion on, so no animation runs forever (Foni, the sky). */
    val reduceMotion: Boolean = false,
    /** Android only: the mouth coach sees this face, held still, instead of the camera (`smile=0.8,jawOpen=0.1`). */
    val face: String? = null,
    /** Android only, debug: every take reads this WAV (a path on the device) instead of the microphone. */
    val micFile: String? = null,
) {
    companion object {
        val none = LaunchArguments()

        fun from(extras: Bundle?, debug: Boolean = IS_DEBUG_BUILD): LaunchArguments {
            if (extras == null) return none
            val listed = extras.getStringArray("arguments")?.toList() ?: emptyList()
            fun flag(name: String): Boolean = extras.getBoolean(name, false) || "-$name" in listed
            fun value(name: String): String? = extras.getString(name)
                ?: listed.indexOf("-$name").takeIf { it >= 0 && it + 1 < listed.size }?.let { listed[it + 1] }
            return LaunchArguments(
                resetData = debug && flag("foniaResetData"),
                fixtures = flag("foniaFixtures"),
                start = if (debug) value("foniaStart") else null,
                mouths = debug && flag("foniaMouths"),
                gallery = if (debug) value("foniaGallery") else null,
                route = if (debug) value("foniaRoute") else null,
                speakOnly = debug && flag("foniaSpeakOnly"),
                short = debug && flag("foniaShort"),
                fakeSpeech = debug && flag("foniaFakeSpeech"),
                reduceMotion = debug && flag("foniaReduceMotion"),
                face = if (debug) value("foniaFace") else null,
                micFile = if (debug) value("foniaMicFile") else null,
            )
        }
    }
}

/**
 * What the audio backend is built from, as `AudioHub(voices:effectsDirectory:usesFixtures:)` is on iOS: the app's
 * context, the folder of sound effects (`voice/sfx`, null when it isn't there), and whether takes come from clips.
 */
class AudioEnvironment(val context: Context, val usesFixtures: Boolean, val effectsDirectory: ClipLocation?)

/**
 * The platform engines. The defaults are the real ones: the :audio engines (with a `FixtureSource` in place of the
 * microphone when `foniaFixtures` asks for recorded takes) and the Whisper model of :whisper. The stand-ins are for
 * the JVM tests (`NoAudioBackend`) and for `foniaFakeSpeech` (`FakeSpeechModel`).
 */
class FoniaPlatform(
    val audio: (AudioEnvironment) -> AudioBackend = { environment ->
        LiveAudioBackend(environment.context, environment.effectsDirectory)
    },
    val speechModel: (Context, LaunchArguments) -> SpeechModelPort = { context, arguments ->
        if (arguments.fakeSpeech) {
            FakeSpeechModel()
        } else {
            StoreSpeechModel(FoniaWhisper.modelStore(context), WhisperCppModel.megabytes())
        }
    },
    /** `makePreferredFaceSource()`, asked each time the mouth coach opens. See `FaceSources.kt`. */
    val face: (Context, LaunchArguments) -> FaceSource = { context, arguments -> makePreferredFaceSource(context, arguments) },
)

/** `AppModel`: the app's state. Snapshot state instead of `@Observable`; main thread only, as `@MainActor`. */
class AppModel(
    val content: FoniaContent,
    val repository: FoniaRepository,
    val audio: AudioHub,
    val speech: SpeechHub,
    val arguments: LaunchArguments = LaunchArguments.none,
    /** A new face source for the mouth coach (`makePreferredFaceSource()` on iOS). */
    val faces: () -> FaceSource = { FakeFaceSource() },
    val preferences: FoniaPreferences? = null,
    private val database: FoniaDatabase? = null,
    /**
     * Where the screens start work that must outlive them, as an iOS `Task { }` started from a button does (saving a
     * profile, playing a recording): the app's own lifetime.
     */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    /** The pilot's event log (docs/PLAN.md §6), as the JVM tests hand it in; the app passes [pilotSlot]. */
    pilot: PilotLog? = null,
    /** Where the open log sits, shared with the shadow scorer; null builds one around [pilot]. */
    pilotSlot: PilotSlot? = null,
    /** Opens the log's file when an adult switches the log on; null in the JVM tests. */
    private val openPilot: (() -> PilotLog?)? = null,
    /** Downloaded models and shadow mode (PLAN §6, §7.1 P13); null in the JVM tests. */
    val ml: MlHub? = null,
    /** The reading probes (not on iOS): a small store beside the database, so FoniaStore's schema stays as on iOS. */
    val reading: ReadingStore? = null,
    /** The adaptive pilot (docs/PLAN.md §7.4, not on iOS): off for every profile until an adult switches it on. */
    val adaptive: AdaptiveStore? = null,
    /** The new take judge (speech/JUDGE.md) with ZIPA as its second head; null in the JVM tests (his judge decides). */
    val judge: tech.fonia.features.session.SoundJudge? = null,
    /** Who really speaks for each profile, heard from the voice (a man on a child profile is judged as a man). */
    val talkers: tech.fonia.features.session.TalkerMemory = tech.fonia.features.session.TalkerMemory.inMemory(),
    /** His own voice reference and the dots of his takes, per profile (no audio); in memory in the JVM tests. */
    val voice: tech.fonia.features.session.VoiceStore = tech.fonia.features.session.VoiceStore(null),
    /** The quick checks of each profile (a small file beside the database; in memory for the JVM tests). */
    val checks: CheckStore = MemoryCheckStore(),
    /** The original v2 texts (spec/content/v2) the diction training is made of; null when they are not bundled. */
    val lessonContent: tech.fonia.lessons.LessonContent? = null,
    /** The best result of each diction drill. */
    val drills: tech.fonia.features.diction.DrillStore = tech.fonia.features.diction.MemoryDrillStore(),
    /** Whether a word picture exists, by name. */
    val hasPicture: (String) -> Boolean = { true },
    /** The folders and settings files "Șterge tot" empties besides the stores above; null in the JVM tests. */
    val localData: LocalData? = null,
) {
    private val slot: PilotSlot = pilotSlot ?: PilotSlot(pilot)

    /**
     * The pilot's event log (docs/PLAN.md §6), its own file beside FoniaStore's. Null while the log is switched off
     * (iOS 007bfcf), or when its file can't be opened.
     */
    val pilot: PilotLog? get() = slot.log

    /** "Jurnalul de exersare" in Setări: off until an adult switches it on. */
    var isPilotLogOn: Boolean by mutableStateOf(preferences?.bool(PilotSwitch.KEY, PilotSwitch.DEFAULT) ?: (pilot != null))
        private set

    private var pilotOpening: kotlinx.coroutines.Job? = null

    /** Switching the log off stops the logging and keeps what was logged; wiping it is "Șterge tot". */
    fun setPilotLog(on: Boolean) {
        if (on == isPilotLogOn) return
        isPilotLogOn = on
        preferences?.setBool(PilotSwitch.KEY, on)
        if (on) openPilotLog() else closePilotLog()
    }

    private fun openPilotLog() {
        if (slot.log != null || pilotOpening != null) return
        val open = openPilot ?: return
        pilotOpening = scope.launch {
            val opened = withContext(Dispatchers.IO) { runCatching { open() }.getOrNull() }
            pilotOpening = null
            // Switched off while the file was opening: it closes again, and nothing is logged.
            if (!isPilotLogOn || opened == null) {
                runCatching { opened?.close() }
                return@launch
            }
            slot.log = opened
            opened.start()
            if (hasLoadedProfiles) opened.profiles(profiles)
        }
    }

    private fun closePilotLog() {
        val open = slot.log ?: return
        slot.log = null
        runCatching { open.close() }
    }

    /** The drills of a sound's training ladder (empty without the v2 texts). */
    fun drills(sound: SoundID): List<tech.fonia.features.diction.Drill> {
        val v2 = lessonContent ?: return emptyList()
        return runCatching {
            tech.fonia.features.diction.DictionDrills.build(content.sound(sound), v2, { audio.hasClip(it, Language.Ro) }, hasPicture)
        }.getOrDefault(emptyList())
    }

    /** Opens one drill of the diction training. */
    fun startDrill(sound: SoundID, index: Int) {
        val drill = drills(sound).getOrNull(index) ?: return
        ticket = SessionTicket(
            focus = SessionFocus.Sound(sound, if (index == 3) Rung.WordsInitial else Rung.Isolated),
            title = drill.title,
            sound = sound,
            custom = CustomRun(CustomRun.Kind.Drill(sound, index), drill.trials, drill.tempos?.map { it.speed }),
        )
    }

    var profiles: List<Profile> by mutableStateOf(emptyList())
        private set

    var hasLoadedProfiles: Boolean by mutableStateOf(false)
        private set

    var activeProfileID: UUID? by mutableStateOf(null)

    var ticket: SessionTicket? by mutableStateOf(null)

    val activeProfile: Profile? get() = profiles.firstOrNull { it.id == activeProfileID }

    /** Set when the first run ends: the home offers the quick check once („Hai să vedem ce sunete exersăm”). */
    var offerCheck: Boolean by mutableStateOf(false)

    /** Bumps when a check is saved, so the home and Progres read the plan again. */
    var checksVersion: Int by mutableStateOf(0)
        private set

    fun checkSaved() {
        checksVersion += 1
    }

    /** Opens „Verificare rapidă”: the words with a clip and a picture, one or two per sound, each with its model voice. */
    fun startCheck() {
        offerCheck = false
        val words = QuickCheckRules.words(content, { audio.hasClip(it, Language.Ro) })
        if (words.isEmpty()) return
        val trials = words.map { (id, word) ->
            Trial(
                exercise = Exercise.Repeat(RepeatPrompt(text = word.text, language = Language.Ro, sound = id, picture = word.picture, level = RepeatPrompt.Level.Word)),
                feedback = FeedbackLevel.Quick,
                block = SessionBlock(SessionBlock.Kind.TargetB),
            )
        }
        // The focus only tells the judge the words have the sound at the start; nothing here moves a ladder.
        ticket = SessionTicket(
            focus = SessionFocus.Sound(SoundID.S, Rung.WordsInitial),
            title = "Verificare rapidă",
            sound = null,
            custom = CustomRun(CustomRun.Kind.Check, trials),
        )
    }

    /** A page `foniaRoute` asked for, taken once by the tabs. */
    var pendingRoute: String? = arguments.route
        private set

    private var didJump = false

    fun takePendingRoute(): String? = pendingRoute.also { pendingRoute = null }

    suspend fun start() {
        if (arguments.resetData) ignoringFailure { repository.wipeAll() }
        if (arguments.resetData) ignoringFailure { pilot?.wipe() }
        ignoringFailure { repository.runStartupMaintenance() }
        pilot?.start()
        repository.profilesStream().collect { list ->
            // Debug launch (`foniaStart` on a fresh install): a plain child profile, so the session opens without the
            // first run. Reinstalls on a full emulator wipe the data, and an agent can't walk the first run each time.
            if (list.isEmpty() && arguments.start != null && IS_DEBUG_BUILD) {
                ignoringFailure {
                    repository.saveProfile(
                        Profile(
                            kind = Profile.Kind.Child, ageBand = Profile.AgeBand.SixToSeven, voiceGroup = Profile.VoiceGroup.Child,
                            color = "cold", targetSounds = listOf("s"), track = Profile.Track.Ro, requireConfirmation = false,
                        ),
                    )
                }
                return@collect
            }
            profiles = list
            // The log follows FoniaStore: a new profile gets its row, a deleted one loses all of them.
            pilot?.profiles(list)
            hasLoadedProfiles = true
            if (activeProfileID != null && activeProfile == null) activeProfileID = null
            if (activeProfileID == null && list.size == 1) activeProfileID = list[0].id
            // A deleted profile, or a wipe, takes its reading probes too.
            reading?.let { store -> scope.launch { ignoringFailure { store.keepOnly(list.map { it.id }.toSet()) } } }
            scope.launch { ignoringFailure { checks.keepOnly(list.map { it.id }.toSet()) } }
            // And its adaptive state.
            adaptive?.let { store -> scope.launch { ignoringFailure { store.keepOnly(list.map { it.id }.toSet()) } } }
            // And its voice: his own reference, the dots of his takes, the talker heard ("Șterge tot" empties the list).
            val voiceIds = list.map { it.id }.toSet()
            scope.launch(Dispatchers.IO) { ignoringFailure { voice.keepOnly(voiceIds); talkers.keepOnly(voiceIds) } }
            // And the best result of each diction drill.
            scope.launch { ignoringFailure { drills.keepOnly(voiceIds) } }
            jumpIfAsked()
        }
    }

    /** `-foniaStart s:wordsInitial`, `-foniaStart en:th`, `-foniaStart dictie:pitch` open a session on launch. */
    private fun jumpIfAsked() {
        val start = arguments.start ?: return
        if (ticket != null || didJump || activeProfile == null) return
        didJump = true
        val parts = start.split(":")
        when (parts[0]) {
            "check" -> startCheck()
            // The invitation the first run ends on, for screenshots.
            "offer" -> offerCheck = true
            "drill" -> {
                val sound = parts.getOrNull(1)?.let { SoundID.fromRawValue(it) }
                val index = parts.getOrNull(2)?.toIntOrNull()
                if (sound != null && index != null) startDrill(sound, index)
            }
            "en" -> parts.getOrNull(1)?.let { contrast ->
                ticket = SessionTicket(focus = SessionFocus.English(EnglishFocus.Speaking(contrast = contrast)), title = contrast, sound = null)
            }
            "melody" -> ticket = SessionTicket(focus = SessionFocus.English(EnglishFocus.Melody(lesson = 0)), title = "melody", sound = null)
            "dictie" -> {
                val area = parts.getOrNull(1)
                ticket = SessionTicket(focus = SessionFocus.Diction(area?.let { DictionAreaID.fromRawValue(it) } ?: DictionAreaID.Pitch), title = area ?: "", sound = null)
            }
            else -> {
                val sound = SoundID.fromRawValue(parts[0])
                val rung = parts.getOrNull(1)?.let { Rung.fromRawValue(it) }
                if (sound != null && rung != null) ticket = SessionTicket(focus = SessionFocus.Sound(sound, rung), title = parts[1], sound = sound)
            }
        }
    }

    /**
     * "Șterge tot": the profiles and attempts, the pilot log, each profile's voice reference, quick checks, drills,
     * reading probes and adaptive state, the files made for sharing, the settings, and the downloaded phoneme model.
     * Every part is tried; the names of those that stayed come back (empty: the phone holds nothing of anyone).
     * Whisper's model stays: it holds nothing about anyone, and without internet the app can't fetch it again.
     */
    suspend fun wipeEverything(): List<String> {
        ticket = null
        val local = localData
        val left = FullWipe.run(
            listOf(
                FullWipe.Part("profilurile și încercările") { repository.wipeAll() },
                FullWipe.Part("jurnalul de exersare") {
                    val log = pilot
                    if (log != null) log.wipe() else local?.let { withContext(Dispatchers.IO) { FullWipe.deleteFolder(it.pilotFolder) } }
                },
                FullWipe.Part("vocea, verificările și exercițiile") {
                    withContext(Dispatchers.IO) {
                        voice.keepOnly(emptySet())
                        talkers.keepOnly(emptySet())
                    }
                    checks.keepOnly(emptySet())
                    drills.keepOnly(emptySet())
                    reading?.keepOnly(emptySet())
                    adaptive?.keepOnly(emptySet())
                    local?.let { withContext(Dispatchers.IO) { it.profileFolders.forEach(FullWipe::deleteFolder) } }
                },
                FullWipe.Part("fișierele pentru logoped") {
                    local?.let { withContext(Dispatchers.IO) { it.exportFolders.forEach(FullWipe::deleteFolder) } }
                },
                FullWipe.Part("alegerile din Setări") {
                    if (local != null && !withContext(Dispatchers.IO) { local.clearSettings() }) throw java.io.IOException("settings")
                },
                FullWipe.Part("pachetul de sunete descărcat") {
                    ml?.models?.let { hub -> if (hub.state(tech.fonia.features.ml.ModelCatalog.ZIPA) != tech.fonia.features.ml.ModelsHub.State.Absent) hub.clear(tech.fonia.features.ml.ModelCatalog.ZIPA) }
                },
            ),
        )
        // The switches kept in memory go back to their defaults with the settings file.
        isPilotLogOn = PilotSwitch.DEFAULT
        closePilotLog()
        audio.effectsMuted = false
        offerCheck = false
        activeProfileID = null
        return left
    }

    suspend fun save(profile: Profile) {
        ignoringFailure { repository.saveProfile(profile) }
    }

    fun select(profile: Profile) {
        activeProfileID = profile.id
    }

    fun switchProfile() {
        activeProfileID = null
    }

    /** A sound an adult said was good. Enough of these and the S/Ș boundary follows this voice. */
    suspend fun addAnchor(segment: FricativeSegment, place: SibilantPlace, profile: Profile) {
        val current = profiles.firstOrNull { it.id == profile.id } ?: return
        val calibration = current.calibration ?: TalkerCalibration()
        calibration.add(segment, place)
        save(current.settingCalibration(calibration))
    }

    fun start(lesson: Lesson, unit: CourseUnit) {
        ticket = SessionTicket(focus = lesson.focus, title = lesson.title, sound = lesson.focus.soundID ?: unit.sound)
    }

    fun close() {
        runCatching { database?.close() }
        runCatching { ml?.close() }
        runCatching { pilot?.close() }
    }

    companion object {
        /**
         * Composition root. Null only if the bundled content or the database can't be opened. Reads files, so call it
         * off the main thread.
         */
        fun live(
            context: Context,
            arguments: LaunchArguments,
            platform: FoniaPlatform,
            microphone: MicrophonePermission,
            scope: CoroutineScope,
        ): AppModel? {
            val assets = context.applicationContext.assets
            val source = ContentSource { path ->
                try {
                    assets.open(path).use { it.readBytes() }
                } catch (_: FileNotFoundException) {
                    null
                }
            }
            val content = try {
                FoniaContent.load(source)
            } catch (_: Exception) {
                return null
            }
            val database = try {
                FoniaDatabase.live(context.noBackupFilesDir)
            } catch (_: Exception) {
                return null
            }
            val voices = runCatching { VoiceIndex.load(source) }.getOrNull()
            // His `voice/sfx` player is retired (2026-11-21): every effect plays from `assets/sfx` through FoniaSfx's
            // one gate, so no old effect can sound anywhere.
            val effects: ClipLocation? = null
            val fixtures = arguments.fixtures
            // A phone whose audio can't be set up still opens; its takes then can't start, which every screen handles.
            val backend = runCatching { platform.audio(AudioEnvironment(context.applicationContext, fixtures, effects)) }
                .getOrElse { NoAudioBackend(context.applicationContext) }
            // A bare name is looked up in the app's own external folder (adb push … /sdcard/Android/data/<app>/files/).
            val micFile = arguments.micFile?.let { if (it.startsWith("/")) File(it) else File(context.getExternalFilesDir(null), it) }
            val audio = AudioHub(voices, backend, fixtures, assets = assets, microphone = microphone, micFile = micFile)
            // The diction training: the original v2 texts and their generated clips (ElevenLabs), when bundled.
            fun text(path: String) = assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) }
            val v2 = runCatching { tech.fonia.lessons.LessonContent.load { text("spec/content/v2/$it") } }.getOrNull()
            runCatching {
                val index = tech.fonia.lessons.VoiceIndex.parse(text("spec/content/voice/index.json"))
                audio.extraClips = index.clips.values.filter { it.checked }.associate { it.text.trim().lowercase() to "spec/content/voice/${it.file}" }
            }
            val pictures = runCatching { assets.list("images/pictures")?.map { it.substringBeforeLast('.') }?.toSet() }.getOrNull() ?: emptySet()
            val speech = SpeechHub(runCatching { platform.speechModel(context.applicationContext, arguments) }.getOrNull(), scope)
            // The new ML is optional: a phone where its files can't be opened runs the app as before.
            val preferences = FoniaPreferences(context.applicationContext)
            val openPilot = { runCatching { PilotLog.open(PilotLog.defaultFolder(context), pilotEnvironment(context)) }.getOrNull() }
            // Off until an adult switches it on: a family that never opens Setări keeps no log.
            val pilotSlot = PilotSlot(if (preferences.bool(PilotSwitch.KEY, PilotSwitch.DEFAULT)) openPilot() else null)
            val ml = runCatching { MlHub.live(context.applicationContext, scope, pilotSlot) }.getOrNull()
            return AppModel(
                content = content,
                repository = FoniaRepository(database),
                audio = audio,
                speech = speech,
                arguments = arguments,
                faces = { platform.face(context.applicationContext, arguments) },
                preferences = preferences,
                database = database,
                scope = scope,
                pilotSlot = pilotSlot,
                openPilot = openPilot,
                ml = ml,
                reading = ReadingStore(File(context.noBackupFilesDir, "Fonia/Reading")),
                adaptive = AdaptiveStore(File(context.noBackupFilesDir, "Fonia/Adaptive")),
                judge = runCatching { tech.fonia.features.session.SoundJudge.live(context, scope) }.getOrNull(),
                talkers = tech.fonia.features.session.TalkerMemory.preferences(context),
                voice = tech.fonia.features.session.VoiceStore(File(context.noBackupFilesDir, "Fonia/Voice")),
                checks = FileCheckStore(File(context.noBackupFilesDir, "Fonia/Check")),
                lessonContent = v2,
                drills = tech.fonia.features.diction.FileDrillStore(File(context.noBackupFilesDir, "Fonia/Diction")),
                hasPicture = { name -> name in pictures },
                localData = LocalData(
                    profileFolders = listOf("Voice", "Check", "Diction", "Reading", "Adaptive").map { File(context.noBackupFilesDir, "Fonia/$it") },
                    exportFolders = listOf(File(context.cacheDir, "export")),
                    pilotFolder = PilotLog.defaultFolder(context),
                    clearSettings = {
                        val app = context.applicationContext
                        listOf("fonia", "fonia_talker").all { name -> app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit() }
                    },
                ),
            )
        }
    }
}

/** Swift's `try?`: the failure is dropped, a cancellation still ends the caller. */
internal suspend fun ignoringFailure(block: suspend () -> kotlin.Unit) {
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
    }
}
