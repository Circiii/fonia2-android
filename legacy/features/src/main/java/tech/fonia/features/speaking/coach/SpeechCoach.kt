package tech.fonia.features.speaking.coach

import tech.fonia.features.app.wordsTranscriber
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.fonia.audio.CapturedClip
import tech.fonia.design.Palette
import tech.fonia.design.swiftSpring
import tech.fonia.features.app.AppModel
import tech.fonia.features.app.HopTap
import tech.fonia.features.app.LocalDismiss
import tech.fonia.features.app.calibration
import tech.fonia.features.app.dspGroup
import tech.fonia.features.speaking.HubSpeakingAudio
import tech.fonia.features.speaking.NoUmTake
import tech.fonia.speaking.SpanKind
import tech.fonia.speaking.SpanSource
import tech.fonia.speaking.coach.Area
import tech.fonia.speaking.coach.CoachPrompt
import tech.fonia.speaking.coach.CoachPrompts
import tech.fonia.speaking.coach.CoachReport
import tech.fonia.speaking.coach.CoachReportBuilder
import tech.fonia.speaking.coach.CoachScores
import tech.fonia.speaking.coach.CoachSession
import tech.fonia.speaking.coach.CoachSpec
import tech.fonia.speaking.coach.Cue
import tech.fonia.speaking.coach.FillerMark
import tech.fonia.speaking.coach.SpeakerBaseline
import tech.fonia.speaking.coach.TalkLandmarks
import tech.fonia.speech.SpeechLanguage
import tech.fonia.speech.SpeechResampler
import tech.fonia.store.JSONValue
import tech.fonia.store.Profile
import tech.fonia.store.SpeechRecord
import java.util.UUID

/** The steps of one attempt. */
internal enum class CoachPhase { Setup, Calibrate, Countdown, Live, Thinking, Report }

/**
 * „La cameră”: the speech coach. He picks a length and a card, looks at the lens for 3-2-1, speaks to the camera with
 * one live cue at a time over his own picture, and gets a report with one thing to work on next.
 *
 * [launch] (debug, `foniaRoute speech:<seconds>:<promptId>[:go]`) preselects the setup and can start at once.
 */
@Composable
fun SpeechCoachScreen(profile: Profile, app: AppModel, launch: String? = null) {
    val scope = rememberCoroutineScope()
    val dismiss = LocalDismiss.current
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val coach = remember(profile.id) { CoachController(app, profile, app.scope, context) }
    LaunchedEffect(coach) {
        coach.loadPrevious()
        coach.applyLaunch(launch)
    }
    DisposableEffect(coach) { onDispose { coach.leave() } }
    BackHandler {
        when (coach.phase) {
            CoachPhase.Setup -> dismiss()
            CoachPhase.Calibrate, CoachPhase.Countdown, CoachPhase.Live -> coach.cancel()
            CoachPhase.Thinking -> Unit
            CoachPhase.Report -> coach.backToSetup()
        }
    }
    Box(Modifier.fillMaxSize().background(Palette.night)) {
        AnimatedContent(
            coach.phase.screen,
            transitionSpec = { fadeIn(swiftSpring(0.35, 0.0)) togetherWith fadeOut(swiftSpring(0.25, 0.0)) },
            label = "coach",
        ) { screen ->
            when (screen) {
                CoachScreenKind.Setup -> CoachSetup(
                    coach,
                    onClose = dismiss,
                    // The first talk begins with six seconds for his own gaze (it can be skipped there).
                    onStart = { if (!coach.calibrationOffered && coach.faceScript == null) coach.calibrate(thenStart = true) else scope.launch { coach.start() } },
                )
                CoachScreenKind.Stage -> CoachStage(coach)
                CoachScreenKind.Report -> CoachReportView(coach, onClose = dismiss)
            }
        }
    }
}

/** Countdown, live and thinking share one stage, so the camera never blinks between them. */
internal enum class CoachScreenKind { Setup, Stage, Report }

internal val CoachPhase.screen: CoachScreenKind
    get() = when (this) {
        CoachPhase.Setup -> CoachScreenKind.Setup
        CoachPhase.Calibrate, CoachPhase.Countdown, CoachPhase.Live, CoachPhase.Thinking -> CoachScreenKind.Stage
        CoachPhase.Report -> CoachScreenKind.Report
    }

/** His previous attempt, as stored: the five scores and his speaking level (for the loudness reference). */
internal data class PreviousAttempt(val scores: CoachScores, val levelDb: Double)

/**
 * The state of the coach screen (main thread) and the attempt in progress. The engine objects it owns run on the
 * audio and camera threads (see [CoachSession]).
 */
internal class CoachController(val app: AppModel, val profile: Profile, private val scope: CoroutineScope, context: android.content.Context) {
    val spec: CoachSpec = CoachSpec.v1
    val prompts: List<CoachPrompt> = CoachPrompts.v1.prompts

    var phase by mutableStateOf(CoachPhase.Setup)
        private set
    var seconds by mutableIntStateOf(60)
    /** A 30-second drill on one area (only its cues show); null for a normal talk. */
    var drill by mutableStateOf<tech.fonia.speaking.coach.Area?>(null)
    var prompt by mutableStateOf(prompts.first())
    var countdown by mutableIntStateOf(3)
        private set
    var cue by mutableStateOf<Cue?>(null)
        private set
    /** What to change so the coach can measure (too far, too dark, noise); null when all is fine. */
    var hint by mutableStateOf<tech.fonia.speaking.coach.InputIssue?>(null)
        private set
    var report by mutableStateOf<CoachReport?>(null)
        private set
    var previous by mutableStateOf<PreviousAttempt?>(null)
        private set
    var noUm by mutableStateOf<NoUmTake?>(null)
        private set

    /** The words of the talk, from the Romanian recogniser (null: no recogniser in this build, or it heard too little). */
    var said by mutableStateOf<tech.fonia.words.SpeechText?>(null)
        private set
    var clip by mutableStateOf<CapturedClip?>(null)
        private set
    var micOff by mutableStateOf(false)
        private set
    var startFailed by mutableStateOf(false)
        private set
    var attempts by mutableIntStateOf(0)
        private set
    var playing by mutableStateOf(false)
        private set
    var cleanView by mutableStateOf(true)

    /** The attempt being recorded; the camera listener reads it from its own thread. */
    @Volatile var session: CoachSession? = null
        private set

    /** CLOCK_MONOTONIC of the countdown's start: the coach's clock is seconds since then. */
    @Volatile var origin: Long = System.nanoTime()
        private set

    private var run: kotlinx.coroutines.Job? = null
    private var stopRequested = false
    private val speakingAudio = HubSpeakingAudio(app, profile)

    fun clock(): Double = (System.nanoTime() - origin) / 1e9

    fun clockOf(nanos: Long): Double = (nanos - origin) / 1e9

    /** The face fixture of debug builds (`foniaFace=coach[:script]`), or null for the camera. */
    val faceScript: String? = app.arguments.face?.takeIf { it == "coach" || it.startsWith("coach:") }
        ?.removePrefix("coach")?.removePrefix(":")?.ifEmpty { "calm:3600" }

    // MARK: His own gaze (six seconds, once; kept on the phone)

    private val prefs = context.getSharedPreferences("fonia.coach", android.content.Context.MODE_PRIVATE)

    /** His gaze calibration, or null: the coach then uses the values fitted on other people. */
    var gaze by mutableStateOf(tech.fonia.speaking.coach.GazeCalibration.decode(prefs.getString("gaze.${profile.id}", null)))
        private set

    /** True until he has calibrated or skipped once: the first talk offers it. */
    var calibrationOffered by mutableStateOf(prefs.getBoolean("gazeOffered.${profile.id}", false))
        private set

    /** The step on screen during [CoachPhase.Calibrate], and how far through it (0…1). */
    var calibrationStep by mutableStateOf(tech.fonia.speaking.coach.GazeCalibrator.Step.Lens)
        private set
    var calibrationFailed by mutableStateOf(false)
        private set
    @Volatile var calibrator: tech.fonia.speaking.coach.GazeCalibrator? = null
        private set

    /** Camera thread: a frame for the calibration, when one runs. */
    fun onCalibrationFrame(sample: tech.fonia.speaking.coach.FaceSample) {
        calibrator?.add(calibrationStep, sample)
    }

    fun skipCalibration() {
        calibrationOffered = true
        prefs.edit().putBoolean("gazeOffered.${profile.id}", true).apply()
    }

    /** Runs the three steps; then the talk starts when [thenStart]. */
    fun calibrate(thenStart: Boolean) {
        if (phase != CoachPhase.Setup) return
        skipCalibration()
        calibrationFailed = false
        val c = tech.fonia.speaking.coach.GazeCalibrator(spec)
        calibrator = c
        phase = CoachPhase.Calibrate
        run = scope.launch {
            try {
                for (step in tech.fonia.speaking.coach.GazeCalibrator.Step.entries) {
                    calibrationStep = step
                    // Half a second for the eyes to arrive, then the measured part.
                    delay(500)
                    delay((spec.calibration.stepSeconds * 1000).toLong())
                }
                val result = c.result()
                calibrator = null
                if (result != null) {
                    gaze = result
                    prefs.edit().putString("gaze.${profile.id}", result.encode()).apply()
                    android.util.Log.i("FoniaCoach", "gaze calibration: $result")
                } else {
                    calibrationFailed = true
                }
                phase = CoachPhase.Setup
                // The calibration is a help, not a gate: the talk starts either way.
                if (thenStart) start()
            } finally {
                calibrator = null
            }
        }
    }

    /** The scores of his last talks, oldest first (at most five): the trend. */
    var history by mutableStateOf<List<CoachScores>>(emptyList())
        private set

    /** His usual pace and pitch range, from his last talks; null before three of them. */
    var baseline by mutableStateOf<SpeakerBaseline?>(null)
        private set
    private var talks: List<TalkLandmarks> = emptyList()

    suspend fun loadPrevious() {
        val all = runCatching { withContext(Dispatchers.IO) { app.repository.speeches(profile.id) } }.getOrNull() ?: return
        val coach = all.filter { (it.metrics["mode"] as? JSONValue.String)?.value == MODE }.sortedBy { it.ts }
        val last = coach.lastOrNull() ?: return
        previous = PreviousAttempt(scoresOf(last), (last.metrics["levelDb"] as? JSONValue.Number)?.value ?: Double.NaN)
        history = coach.takeLast(5).map { scoresOf(it) }
        talks = coach.map { landmarksOf(it) }
        baseline = SpeakerBaseline.of(talks, spec)
    }

    /** `speech:<seconds>:<promptId>[:go]`. */
    suspend fun applyLaunch(launch: String?) {
        val parts = launch?.split(':') ?: return
        parts.getOrNull(0)?.toIntOrNull()?.let { if (it in 10..300) seconds = it }
        parts.getOrNull(1)?.let { id -> prompts.firstOrNull { it.id == id }?.let { prompt = it } }
        if (parts.getOrNull(2) == "go") {
            delay(600)
            start()
        }
    }

    suspend fun start() {
        if (phase != CoachPhase.Setup && phase != CoachPhase.Report) return
        stopPlayback()
        startFailed = false
        if (!app.audio.requestMicrophone()) {
            micOff = true
            return
        }
        micOff = false
        report = null
        noUm = null
        clip = null
        cue = null
        stopRequested = false
        val s = CoachSession(spec, previous?.levelDb ?: Double.NaN, gaze, focus = drill)
        origin = System.nanoTime()
        session = s
        s.face.startBaseline()
        phase = CoachPhase.Countdown
        run = scope.launch { runAttempt(s) }
    }

    private suspend fun runAttempt(s: CoachSession) {
        try {
            // 3-2-1 while he looks at the lens: the gaze baseline. Silent: nothing plays around the microphone.
            for (n in 3 downTo 1) {
                countdown = n
                // The picture is checked already: "Mai aproape", "Mai multă lumină" before he starts.
                repeat(10) {
                    hint = s.face.issueNow.takeIf { s.face.framesTotal > 3 }
                    delay(100)
                }
            }
            s.face.endBaseline()
            try {
                app.audio.startListening(
                    group = profile.dspGroup,
                    calibration = profile.calibration,
                    tap = { rate ->
                        val audio = s.attachAudio(rate)
                        // The phone model counts the vowels (one per syllable) in 2 s pieces, off the audio thread.
                        val vowels = app.judge?.zipa?.let { VowelFeeder(audio, rate, it, scope) }
                        HopTap { samples, offset, count, frame ->
                            audio.process(samples, offset, count, frame.pitchHz)
                            vowels?.add(samples, offset, count)
                        }
                    },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                startFailed = true
                phase = CoachPhase.Setup
                session = null
                return
            }
            s.startTalk(clock())
            phase = CoachPhase.Live
            val end = clock() + seconds
            while (scopeActive() && !stopRequested && clock() < end) {
                val c = s.tick(clock())
                if (c != cue) cue = c
                if (s.levels.hint != hint) hint = s.levels.hint
                delay(TICK_MS)
            }
            finish(s)
        } catch (cancelled: CancellationException) {
            runCatching { withContext(kotlinx.coroutines.NonCancellable) { app.audio.cancelListening() } }
            throw cancelled
        }
    }

    private suspend fun scopeActive() = kotlinx.coroutines.currentCoroutineContext().isActive

    private suspend fun finish(s: CoachSession) {
        val talked = s.levels.seconds
        val heard = app.audio.stopListening()
        cue = null
        phase = CoachPhase.Thinking
        val summary = withContext(Dispatchers.Default) { s.audio?.summary() }
        val captured = heard?.takeIf { it.sampleRate > 0 && it.samples.isNotEmpty() }
        clip = captured
        // „Fără ăăă”: the same take through the speaking pipeline (Whisper's words when the model is there).
        val take = captured?.let { c ->
            val transcript = app.wordsTranscriber?.let { t ->
                runCatching {
                    withContext(Dispatchers.Default) {
                        t.transcribe(SpeechResampler.resample(c.samples, c.sampleRate), SpeechLanguage.RO, verbatim = true)
                    }
                }.getOrNull()
            }
            NoUmTake.make(c, transcript)
        }
        noUm = take
        // Which words were said, and when: real words per minute, filler words, repeats and the text for the report.
        said = captured?.let { c ->
            app.judge?.words?.hear(c.samples, c.sampleRate, timeoutMs = (10_000 + 500 * c.duration).toLong())
                ?.let { tech.fonia.words.SpeechText.of(it) }
                ?.also { android.util.Log.i("FoniaWords", "talk ${"%.0f".format(c.duration)}s words=${it.words.size} wpm=${"%.0f".format(it.wordsPerMinute)} fillers=${it.fillers.size} repeats=${it.repeats.size} reliable=${it.reliable}") }
        }
        // The recogniser holds about 130 MB (more after a long talk): free it until the next talk or sentence.
        app.judge?.words?.trim()
        val fillers = take?.analysis?.spans?.let { fillerMarks(it, wordsArePermissive = app.speech.transcriber != null) }
            ?: emptyList()
        val built = withContext(Dispatchers.Default) {
            CoachReportBuilder.build(s.timeline(), talked.coerceAtLeast(1.0), summary, fillers, previous?.scores, spec, baseline)
        }
        save(built, summary?.medianLevelDb ?: Double.NaN)
        report = built
        attempts += 1
        session = null
        phase = CoachPhase.Report
    }

    private fun save(r: CoachReport, levelDb: Double) {
        // A talk played from a file is a test, not his: it stays out of his history and his comparisons.
        if (app.arguments.micFile != null || app.arguments.coachVideo != null || app.arguments.fixtures) return
        val metrics = HashMap<String, JSONValue>()
        metrics["mode"] = JSONValue.String(MODE)
        metrics["spec"] = JSONValue.String(r.specVersion)
        metrics["promptId"] = JSONValue.String(prompt.id)
        drill?.let { metrics["drill"] = JSONValue.String(it.key) }
        metrics["fillers"] = JSONValue.Number(r.fillers.toDouble())
        if (!r.rate.isNaN()) metrics["syllablesPerSecond"] = JSONValue.Number(r.rate)
        if (r.pitchRangeSt.isFinite()) metrics["pitchRangeSt"] = JSONValue.Number(r.pitchRangeSt)
        r.eyeContact?.let { metrics["eyeContact"] = JSONValue.Number(it) }
        if (!levelDb.isNaN()) metrics["levelDb"] = JSONValue.Number(levelDb)
        for (a in Area.entries) r.scores[a]?.let { metrics["score.${a.key}"] = JSONValue.Number(it.toDouble()) }
        metrics["moments"] = JSONValue.Array(r.moments.map { JSONValue.String("${it.kind.key}@${"%.1f".format(java.util.Locale.ROOT, it.start)}") })
        val record = SpeechRecord(
            id = UUID.randomUUID(),
            profileId = profile.id,
            lang = SpeechRecord.Language.Ro,
            prompt = prompt.title,
            duration = r.seconds,
            metrics = metrics,
        )
        val repository = app.repository
        scope.launch { runCatching { withContext(Dispatchers.IO) { repository.saveSpeech(record) } } }
        previous = PreviousAttempt(r.scores, levelDb)
        // The setup screen's "De lucrat" follows at once.
        history = (history + r.scores).takeLast(5)
        talks = talks + TalkLandmarks(r.rate, r.pitchRangeSt, drill != null)
        baseline = SpeakerBaseline.of(talks, spec)
    }

    /** "Gata": ends the talk now and makes the report. */
    fun stop() {
        if (phase == CoachPhase.Live) stopRequested = true
        if (phase == CoachPhase.Countdown) cancel()
    }

    fun cancel() {
        run?.cancel()
        run = null
        session = null
        calibrator = null
        cue = null
        hint = null
        phase = CoachPhase.Setup
    }

    fun again() {
        scope.launch { start() }
    }

    fun backToSetup() {
        stopPlayback()
        drill = null
        phase = CoachPhase.Setup
    }

    fun leave() {
        run?.cancel()
        stopPlayback()
        scope.launch { runCatching { app.audio.shutDown() } }
    }

    /** Plays a stretch of the take (a moment of the timeline). */
    fun playMoment(start: Double, end: Double) {
        val c = clip ?: return
        stopPlayback()
        scope.launch {
            playing = true
            try {
                app.audio.play(c, (start - 0.6).coerceAtLeast(0.0), (end + 0.6).coerceAtMost(c.duration))
            } finally {
                playing = false
            }
        }
    }

    /** „Ascultă-te fără ăăă” / „Ascultă-te”. */
    fun togglePlay() {
        val take = noUm ?: return
        if (playing) {
            stopPlayback()
            return
        }
        val c = clip
        val (samples, rate) = if (cleanView || c == null) take.clean.samples to take.clean.sampleRate else c.samples to c.sampleRate
        scope.launch {
            playing = true
            try {
                speakingAudio.play(samples, rate)
            } finally {
                playing = false
            }
        }
    }

    fun stopPlayback() {
        speakingAudio.stopPlayback()
        scope.launch { runCatching { app.audio.stopSpeaking() } }
        playing = false
    }

    companion object {
        const val MODE = "coach"
        const val TICK_MS = 100L

        fun scoresOf(record: SpeechRecord): CoachScores = CoachScores(
            Area.entries.associateWith { a -> (record.metrics["score.${a.key}"] as? JSONValue.Number)?.value?.toInt() },
        )

        /** What a stored talk leaves for his habit; talks saved before the range was kept have only the pace. */
        fun landmarksOf(record: SpeechRecord): TalkLandmarks = TalkLandmarks(
            syllablesPerSecond = (record.metrics["syllablesPerSecond"] as? JSONValue.Number)?.value ?: Double.NaN,
            pitchRangeSt = (record.metrics["pitchRangeSt"] as? JSONValue.Number)?.value ?: Double.NaN,
            drill = record.metrics.containsKey("drill"),
        )

        /**
         * The fillers of the take for the strip. The sound detector's „ăăă” always count; filler words only when the
         * words came from a recogniser whose licence allows it (Whisper). Otherwise only what the sound itself showed.
         */
        fun fillerMarks(spans: List<tech.fonia.speaking.FlaggedSpan>, wordsArePermissive: Boolean): List<FillerMark> =
            spans.mapNotNull { span ->
                when {
                    span.kind == SpanKind.HESITATION && (wordsArePermissive || span.source != SpanSource.TRANSCRIPT) ->
                        FillerMark(span.start, span.end)
                    span.kind == SpanKind.MARKER && wordsArePermissive ->
                        FillerMark(span.start, span.end, span.label.trim().lowercase().ifEmpty { null })
                    else -> null
                }
            }
    }
}


/**
 * Hands the microphone's sound to the phone model in pieces of about two seconds and gives the vowels it hears back
 * to the [AudioCoach] (the speaking rate). [add] runs on the audio thread and only copies; the model runs on its own
 * thread. While the model is busy the piece keeps growing (up to [MAX_SECONDS]), so nothing is queued and nothing is
 * skipped; if the model fails once, the feeder stops and the coach keeps its own count.
 */
internal class VowelFeeder(
    private val audio: tech.fonia.speaking.coach.AudioCoach,
    private val sampleRate: Double,
    private val host: tech.fonia.ml.ZipaHost,
    private val scope: CoroutineScope,
) {
    private val buffer = FloatArray((sampleRate * MAX_SECONDS).toInt())
    private var filled = 0
    private var startSample = 0L
    @Volatile private var busy = false
    @Volatile private var failed = false

    fun add(samples: FloatArray, offset: Int, count: Int) {
        if (failed) return
        val room = buffer.size - filled
        if (count > room) {
            // More than MAX_SECONDS behind (the model stalled): start again from here, the coach falls back meanwhile.
            startSample += filled
            filled = 0
        }
        System.arraycopy(samples, offset, buffer, filled, minOf(count, buffer.size - filled))
        filled += minOf(count, buffer.size - filled)
        if (!busy && filled >= sampleRate * PIECE_SECONDS) {
            val piece = buffer.copyOf(filled)
            val from = startSample / sampleRate
            val until = (startSample + filled) / sampleRate
            startSample += filled
            filled = 0
            busy = true
            scope.launch(Dispatchers.Default) {
                val started = System.nanoTime()
                val times = host.vowelOnsets(piece, sampleRate)
                if (times == null) {
                    failed = true
                } else {
                    for (i in times.indices) times[i] += from
                    audio.addVowels(from, until, times)
                    if (tech.fonia.features.IS_DEBUG_BUILD) {
                        android.util.Log.i("FoniaCoach", "vowels %.1f–%.1f s: %d in %.0f ms, rate %.2f".format(from, until, times.size, (System.nanoTime() - started) / 1e6, audio.rate))
                    }
                }
                busy = false
            }
        }
    }

    private companion object {
        const val PIECE_SECONDS = 2.0
        const val MAX_SECONDS = 8.0
    }
}
