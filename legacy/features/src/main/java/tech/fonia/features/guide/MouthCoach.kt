package tech.fonia.features.guide

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.produceState
import androidx.compose.ui.semantics.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tech.fonia.design.CelebrationBurst
import tech.fonia.design.ContinuousRoundedRectangle
import tech.fonia.design.FacePatch
import tech.fonia.design.FoniButtonStyle
import tech.fonia.design.Foni
import tech.fonia.design.FoniMood
import tech.fonia.design.FoniaIcon
import tech.fonia.design.FoniaIconView
import tech.fonia.design.FoniaText
import tech.fonia.design.LocalReduceMotion
import tech.fonia.design.MiniPillStyle
import tech.fonia.design.MouthView
import tech.fonia.design.Palette
import tech.fonia.design.Rating
import tech.fonia.design.SagittalPose
import tech.fonia.design.SagittalView
import tech.fonia.design.StyledButton
import tech.fonia.design.TextRole
import tech.fonia.design.UIWeight
import tech.fonia.design.VoiceBars
import tech.fonia.design.fBody
import tech.fonia.design.fButton
import tech.fonia.design.fCaption
import tech.fonia.design.fDisplay
import tech.fonia.design.fHeadline
import tech.fonia.design.fPrompt
import tech.fonia.design.hex
import tech.fonia.design.swiftSpring
import tech.fonia.design.ui
import tech.fonia.domain.CoachCheck
import tech.fonia.domain.CoachDiagram
import tech.fonia.domain.CoachFocus
import tech.fonia.domain.CoachFaultText
import tech.fonia.domain.CoachPlan
import tech.fonia.domain.CoachStep
import tech.fonia.domain.Exercise
import tech.fonia.domain.Language
import tech.fonia.domain.RepeatPrompt
import tech.fonia.domain.SoundID
import tech.fonia.domain.Viseme
import tech.fonia.dsp.heardSpeech
import tech.fonia.face.FaceCheck
import tech.fonia.face.FaceHold
import tech.fonia.face.FaceLevels
import tech.fonia.face.FaceSource
import tech.fonia.face.FaceSourceError
import tech.fonia.facecamera.CameraFaceSource
import tech.fonia.facecamera.MouthTracker
import tech.fonia.model.SoundId
import tech.fonia.features.app.ActivityIndicator
import tech.fonia.features.app.AppModel
import tech.fonia.features.app.FaceCamera
import tech.fonia.features.app.HeaderPage
import tech.fonia.features.app.LocalDismiss
import tech.fonia.features.app.LocalStillMeters
import tech.fonia.features.app.SoundEffect
import tech.fonia.features.app.ViewTask
import tech.fonia.features.app.calibration
import tech.fonia.features.app.dspGroup
import tech.fonia.features.app.shape
import tech.fonia.features.ml.shadowMirrorTake
import tech.fonia.features.session.TakeJudge
import tech.fonia.features.session.TakeVerdict
import tech.fonia.features.session.VerdictWriter
import tech.fonia.store.Profile

private sealed interface MicState {
    data object Idle : MicState

    data object Listening : MicState

    data object Thinking : MicState

    data class Heard(val title: String, val tip: String?, val isGood: Boolean) : MicState
}

/**
 * Puts the mouth in place one step at a time: the lips, then the tongue, then the sound. The camera checks what it can
 * see and the microphone what it can hear. What neither can check is left to the person, and "Am făcut" is always
 * there, so a bad guess from the phone never blocks anyone. Nothing is saved.
 */
@Composable
fun MouthCoach(plan: CoachPlan, profile: Profile, app: AppModel) {
    val dismiss = LocalDismiss.current
    val reduceMotion = LocalReduceMotion.current
    val haptics = LocalHapticFeedback.current
    var index by remember { mutableIntStateOf(0) }
    var source by remember { mutableStateOf<FaceSource?>(null) }
    val levels = remember { FaceLevels() }
    var cameraProblem by remember { mutableStateOf<String?>(null) }
    var held by remember { mutableDoubleStateOf(0.0) }
    var isDone by remember { mutableStateOf(false) }
    var micState by remember { mutableStateOf<MicState>(MicState.Idle) }
    var cheers by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    // The lip guide: the new mouth engine on the camera (or, in a debug build with `foniaFace=fixture`, on a still
    // portrait run through the same pipeline). One MouthTracker per camera tracker.
    val fixture = remember { MirrorFixture.make(context, app.arguments.face) }
    val mouth = remember(source, fixture) { fixture?.mouth ?: (source as? CameraFaceSource)?.let { MouthTracker(it) } }
    val target = remember(plan.key) { if (plan.language == Language.Ro) SoundId.of(plan.key) else null }
    CoachMouthLifetime(mouth, target, fixture)
    val selfView = remember { SelfViewState() }
    // Debug builds: `--es foniaCoachHint smile` shows one correction whatever the camera says (screenshots).
    val forcedHint = remember { if (tech.fonia.features.IS_DEBUG_BUILD) launchExtra(context, "foniaCoachHint")?.let { n -> Correction.entries.firstOrNull { it.name.equals(n, ignoreCase = true) } } else null }
    // The side view (the tongue) waits for a speech therapist in release builds; debug builds show it for review.
    tech.fonia.mouth3d.Mouth3DReview.showUnapprovedInside = tech.fonia.features.IS_DEBUG_BUILD
    val mouthData by produceState<tech.fonia.mouth3d.Mouth3DData?>(null, context) {
        value = withContext(Dispatchers.IO) { runCatching { tech.fonia.mouth3d.Mouth3DAssets.load(context) }.getOrNull() }
    }
    val insideAvailable = target != null && mouthData?.insideAvailable(target) == true

    val step: CoachStep? = plan.steps.getOrNull(index)

    fun advance() {
        cheers += 1
        val wasListening = micState == MicState.Listening
        app.scope.launch {
            app.audio.stopSpeaking()
            if (wasListening) app.audio.stopListening()
        }
        if (index + 1 < plan.steps.size) {
            index += 1
        } else {
            isDone = true
            app.audio.effect(SoundEffect.Star)
        }
    }

    suspend fun judge(clip: tech.fonia.audio.CapturedClip): TakeVerdict {
        val judge = TakeJudge(app.content, profile.dspGroup, profile.calibration, transcriber = null, thresholds = app.audio.analysisThresholds)
        val sound = SoundID.fromRawValue(plan.key)
        if (plan.language != Language.Ro || sound == null) {
            val summary = TakeJudge.summarise(clip, profile.dspGroup, profile.calibration, app.audio.analysisThresholds)
            return VerdictWriter.personJudges(heard = heardSpeech(summary, app.audio.analysisThresholds), tip = null, pose = null)
        }
        // Every microphone step is judged as the plan's first syllable, whatever the step says.
        val spoken = plan.syllables?.list?.firstOrNull() ?: plan.letter.lowercase()
        return judge.judge(Exercise.Repeat(RepeatPrompt(text = spoken, language = Language.Ro, sound = sound, level = RepeatPrompt.Level.Syllable)), clip)
    }

    fun fault(): CoachFaultText? = plan.faults.firstOrNull()

    suspend fun listen() {
        if (!app.audio.requestMicrophone()) {
            micState = MicState.Heard("Microfonul e oprit", "Îl pornești din Setări, la Fonia.", isGood = false)
            return
        }
        try {
            app.audio.startListening(group = profile.dspGroup, calibration = profile.calibration)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            micState = MicState.Heard("Nu pot porni microfonul", "Închide aplicațiile care îl folosesc și mai încearcă.", isGood = false)
            return
        }
        micState = MicState.Listening
        app.audio.waitForTakeEnd(silence = 0.6, startTimeout = 7.0, maximum = 6.0)
        if (!currentCoroutineContext().isActive) return
        val clip = app.audio.stopListening() ?: return
        micState = MicState.Thinking
        val verdict = judge(clip)
        // Nothing goes to FoniaStore, as on iOS. The pilot log counts the take as a mirror attempt (no audio), and
        // shadow mode scores the same syllable TakeJudge just judged; neither changes what happens next.
        val mirrored = SoundID.fromRawValue(plan.key)
        if (plan.language == Language.Ro && mirrored != null) {
            app.shadowMirrorTake(profile, mirrored, plan.syllables?.list?.firstOrNull() ?: plan.letter.lowercase(), clip, verdict)
        }
        if (verdict.rating == Rating.yes) {
            app.audio.effect(SoundEffect.Correct)
            micState = MicState.Heard(verdict.title, null, isGood = true)
            delay(900)
            advance()
        } else {
            micState = MicState.Heard(verdict.title, fault()?.tip ?: verdict.tip, isGood = false)
        }
    }

    /** The pose has to hold for a moment, so a passing smile doesn't count. */
    suspend fun watch(step: CoachStep) {
        val judge = step.face ?: return
        val check = FaceCheck.fromRawValue(judge.rawValue) ?: return
        val hold = FaceHold(step.holdMs)
        while (currentCoroutineContext().isActive) {
            delay(FaceHold.POLL_INTERVAL)
            val done = hold.poll(check, levels.snapshot())
            held = hold.held
            if (done) {
                advance()
                return
            }
        }
    }

    // The camera judges only what it can see: not the tongue, not once the coach is done.
    SideEffect {
        selfView.forced = forcedHint
        selfView.judging = !isDone && step?.check == CoachCheck.Face && step.focus != CoachFocus.Tongue
    }
    // A pose held for a second is celebrated (glow, tick, stars, Foni, the match chime), then the coach moves on.
    LaunchedEffect(selfView.successes) {
        if (selfView.successes == 0) return@LaunchedEffect
        val current = step ?: return@LaunchedEffect
        tech.fonia.design.FoniaSfx.play(tech.fonia.design.SfxCue.MirrorMatch)
        if (forcedHint != null) return@LaunchedEffect
        delay(1100)
        if (step?.id == current.id && !isDone && current.check == CoachCheck.Face) advance()
    }
    // Each new step: the hold starts again, with the step tick.
    LaunchedEffect(index) {
        selfView.resetHold()
        if (index > 0) tech.fonia.design.FoniaSfx.play(tech.fonia.design.SfxCue.GuideStep)
    }
    // `.sensoryFeedback(.success, trigger: cheers)`.
    LaunchedEffect(cheers) { if (cheers > 0) haptics.performHapticFeedback(HapticFeedbackType.Confirm) }
    // `.task { source = makePreferredFaceSource() }`.
    LaunchedEffect(Unit) { if (source == null) source = app.faces() }
    // `.task(id: index) { await run() }`.
    ViewTask(index) {
        val current = step ?: return@ViewTask
        held = 0.0
        micState = MicState.Idle
        app.audio.say(current.say, Language.Ro)
        if (!isActive) return@ViewTask
        when (current.check) {
            CoachCheck.Face -> watch(current)
            CoachCheck.Mic -> listen()
            CoachCheck.Self -> Unit
        }
    }
    // `.onDisappear`: the voice and the microphone stop with the page.
    DisposableEffect(Unit) {
        onDispose {
            app.scope.launch {
                app.audio.stopSpeaking()
                app.audio.stopListening()
            }
        }
    }

    HeaderPage("Cum spui ${plan.letter}") {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.padding(horizontal = 16.dp)) { Progress(plan.steps.size, index, isDone) }
            Spacer(Modifier.height(10.dp))
            Crossfade(isDone || step == null, Modifier.weight(1f), animationSpec = swiftSpring(0.42, 0.12), label = "coach") { done ->
                val shown = step
                if (done || shown == null) {
                    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Finished(plan, app, onDone = dismiss)
                    }
                } else {
                    // A video call: the right mouth on top, the child large below.
                    var insideChoice by remember(shown.id) { mutableStateOf<Boolean?>(null) }
                    val autoInside = shown.focus == CoachFocus.Tongue || shown.focus == CoachFocus.Air
                    val inside = insideAvailable && (insideChoice ?: autoInside)
                    Column(Modifier.fillMaxSize()) {
                        TopMouthPane(
                            step = shown,
                            target = target,
                            inside = inside,
                            insideAvailable = insideAvailable,
                            onToggleInside = { insideChoice = !inside },
                            modifier = Modifier.weight(0.42f).fillMaxWidth().padding(horizontal = 16.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                        CallSelfPane(
                            step = shown,
                            app = app,
                            state = selfView,
                            source = source,
                            fixture = fixture,
                            mouth = mouth,
                            levels = levels,
                            reduceMotion = reduceMotion,
                            cameraProblem = cameraProblem,
                            onCameraProblem = { cameraProblem = it },
                            micState = micState,
                            onRetry = { app.scope.launch { listen() } },
                            onAdvance = { advance() },
                            modifier = Modifier.weight(0.58f).fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

/** The screen's density, for offsets given in points inside a transition spec. */
private val density: Float get() = android.content.res.Resources.getSystem().displayMetrics.density

@Composable
private fun Progress(count: Int, index: Int, isDone: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = "Pasul ${minOf(index + 1, count)} din $count" },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (position in 0 until count) {
            val color = if (position < index || isDone) Palette.progress else if (position == index) Palette.accent else Palette.progressTrack
            Box(Modifier.weight(1f).height(6.dp).background(color, RoundedCornerShape(50)))
        }
    }
}

/**
 * The top pane: the right mouth for the step, large: the 3D mouth playing the sound's pose and lighting the step's part
 * (`highlight`), from the front, or from the side (the tongue) when that view is available; one tap switches. A plan
 * the 3D mouth does not know keeps the original drawing.
 */
@Composable
private fun TopMouthPane(
    step: CoachStep,
    target: SoundId?,
    inside: Boolean,
    insideAvailable: Boolean,
    onToggleInside: () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val can3d = remember(context) { tech.fonia.mouth3d.Mouth3DSupport.canRender3D(context) }
    Box(modifier.clip(ContinuousRoundedRectangle(24.dp)).background(Palette.sheet)) {
        if (target != null && can3d) {
            FacePatch(Modifier.fillMaxSize())
            var arrived by remember(step.id) { mutableStateOf(false) }
            // The pose plays for a few seconds when the step opens (and again on a tap), then the 3D view rests: a
            // settled scene with reduce motion draws no frames, which leaves the CPU to the camera's face model.
            var lively by remember(step.id) { mutableStateOf(true) }
            var replays by remember { mutableIntStateOf(0) }
            LaunchedEffect(step.id, replays) {
                lively = true
                arrived = false
                delay(260)
                arrived = true
                delay(3600)
                lively = false
            }
            tech.fonia.mouth3d.Mouth3D(
                sound = target,
                progress = if (arrived) 1f else 0f,
                inside = inside,
                colors = GuideMouthColors,
                modifier = Modifier.fillMaxSize().clickable(interactionSource = null, indication = null) { replays += 1 },
                playing = lively,
                reduceMotion = !lively || LocalReduceMotion.current,
                autoFallback = false,
                highlight = step.focus.articulator,
                onDoubleTap = if (insideAvailable) onToggleInside else null,
                onFrameStats = if (tech.fonia.features.IS_DEBUG_BUILD) { stats -> logMouthStats(stats) } else null,
            )
        } else {
            MouthDiagram(step.diagram, Modifier.fillMaxSize())
        }
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .padding(10.dp)
                .background(Palette.sheet.copy(alpha = 0.92f), RoundedCornerShape(50))
                .padding(horizontal = 12.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Glyph(step.focus.glyph, Palette.accentText, 22.dp)
            FoniaText(step.focus.rawValue, style = ui(17, UIWeight.bold, TextRole.headline), color = Palette.accentText, maxLines = 1)
        }
        if (insideAvailable && target != null && can3d) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .size(48.dp)
                    .background(if (inside) Palette.accentSoft else Palette.sheet.copy(alpha = 0.92f), CircleShape)
                    .clickable(role = Role.Switch, onClick = onToggleInside)
                    .semantics { contentDescription = if (inside) "Vezi din față" else "Vezi din lateral" },
                contentAlignment = Alignment.Center,
            ) { Glyph(if (inside) "lips" else "inside", Palette.ink, 26.dp) }
        }
    }
}

/**
 * The bottom pane: the child, edge to edge with rounded top corners like the self-view of a video call, the one big
 * correction, the praise, and the step's buttons over the picture. On a step the camera cannot see (the tongue) the
 * pane says so with an icon and points up at the side view.
 */
@Composable
private fun CallSelfPane(
    step: CoachStep,
    app: AppModel,
    state: SelfViewState,
    source: FaceSource?,
    fixture: MirrorFixture?,
    mouth: MouthTracker?,
    levels: FaceLevels,
    reduceMotion: Boolean,
    cameraProblem: String?,
    onCameraProblem: (String) -> Unit,
    micState: MicState,
    onRetry: () -> Unit,
    onAdvance: () -> Unit,
    modifier: Modifier,
) {
    val unseen = step.focus == CoachFocus.Tongue
    val pane = modifier.clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
    val overlay: @Composable BoxScope.() -> Unit = {
        if (unseen) {
            Column(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).padding(bottom = 90.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                HintGlyph("tongueUp", Color.White, Modifier.size(96.dp))
                FoniaText("Uită-te sus", style = ui(28, UIWeight.bold, TextRole.title2), color = Color.White)
            }
        } else if (cameraProblem == null) {
            CorrectionChip(state.correction, Modifier.align(Alignment.TopCenter).padding(top = 14.dp))
        }
        // Foni cheers each held pose; the stars fly from the mouth.
        var cheering by remember { mutableStateOf(false) }
        LaunchedEffect(state.successes) {
            if (state.successes == 0) return@LaunchedEffect
            cheering = true
            delay(1700)
            cheering = false
        }
        val cheer by animateFloatAsState(if (cheering) 1f else 0f, swiftSpring(0.45, 0.45), label = "cheer")
        if (cheer > 0.01f) {
            Foni(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 12.dp)
                    .size(96.dp)
                    .graphicsLayer { scaleX = cheer; scaleY = cheer; alpha = cheer.coerceIn(0f, 1f) },
                mood = FoniMood.happy,
            )
        }
        tech.fonia.designsystem.motion.CelebrationBurst(
            trigger = state.successes,
            modifier = Modifier.matchParentSize(),
            style = tech.fonia.designsystem.motion.BurstStyle.Stars,
            origin = androidx.compose.ui.geometry.Offset(0.5f, 0.55f),
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (step.check == CoachCheck.Mic) MicStatus(micState, app)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (step.check == CoachCheck.Mic && micState is MicState.Heard && !micState.isGood) {
                    StyledButton(
                        onClick = onRetry,
                        style = FoniButtonStyle(size = FoniButtonStyle.Size.regular, kind = FoniButtonStyle.Kind.secondary),
                        modifier = Modifier.weight(1f),
                    ) { FoniaText("Mai încerc") }
                }
                StyledButton(
                    onClick = onAdvance,
                    style = FoniButtonStyle(
                        size = FoniButtonStyle.Size.regular,
                        kind = if (step.check == CoachCheck.Face) FoniButtonStyle.Kind.secondary else FoniButtonStyle.Kind.primary,
                    ),
                    modifier = Modifier.weight(1f),
                ) { FoniaText(if (step.check == CoachCheck.Mic) "Mai departe" else "Am făcut") }
            }
        }
    }
    if ((source != null || fixture != null) && cameraProblem == null) {
        SelfView(
            state = state,
            source = source,
            fixture = fixture,
            mouth = mouth,
            levels = levels,
            reduceMotion = reduceMotion,
            modifier = pane,
            onFailure = { error ->
                onCameraProblem(if (error == FaceSourceError.CameraDenied) "Camera e oprită. O pornești din Setări, la Fonia." else "Camera nu merge acum.")
            },
            overlay = overlay,
        )
    } else {
        Box(pane.background(Palette.cloud)) {
            FoniaText(cameraProblem ?: "", Modifier.align(Alignment.Center).padding(24.dp), style = fBody, color = Palette.inkSoft, textAlign = TextAlign.Center)
            overlay()
        }
    }
}

/** The 3D mouth's part for a coach step's focus. */
private val CoachFocus.articulator: tech.fonia.mouth3d.Articulator
    get() = when (this) {
        CoachFocus.Lips, CoachFocus.Mouth -> tech.fonia.mouth3d.Articulator.Lips
        CoachFocus.Teeth -> tech.fonia.mouth3d.Articulator.Teeth
        CoachFocus.Tongue -> tech.fonia.mouth3d.Articulator.Tongue
        CoachFocus.Air, CoachFocus.Voice -> tech.fonia.mouth3d.Articulator.Air
    }

/** The chip icon for a coach step's focus. */
private val CoachFocus.glyph: String
    get() = when (this) {
        CoachFocus.Lips, CoachFocus.Mouth -> "lips"
        CoachFocus.Teeth -> "teeth"
        CoachFocus.Tongue -> "tongue"
        CoachFocus.Air, CoachFocus.Voice -> "air"
    }

private var lastStatsLog = 0L

/** Debug builds: the 3D mouth's GL frame times in logcat (`FoniaCoach`), every two seconds at most. */
private fun logMouthStats(stats: tech.fonia.mouth3d.Mouth3DFrameStats) {
    val now = System.nanoTime()
    if (now - lastStatsLog < 2_000_000_000L) return
    lastStatsLog = now
    android.util.Log.i("FoniaCoach", "mouth3d avg=%.2fms max=%.2fms frames=%d msaa=%d".format(stats.averageMs, stats.maxMs, stats.frames, stats.msaaSamples))
}

/** A string extra of the activity that opened the app (debug launch switches), or null. */
private fun launchExtra(context: android.content.Context, name: String): String? {
    var c: android.content.Context? = context
    while (c is android.content.ContextWrapper) {
        if (c is android.app.Activity) return c.intent?.getStringExtra(name)
        c = c.baseContext
    }
    return null
}

@Composable
private fun MicStatus(state: MicState, app: AppModel) {
    val live = app.audio.levels
    val still = LocalStillMeters.current
    when (state) {
        MicState.Idle -> Unit
        MicState.Listening -> Row(
            Modifier
                .heightIn(min = 52.dp)
                .background(Palette.sheet, RoundedCornerShape(50))
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FoniaIconView(FoniaIcon.microphone, size = 20f, tint = Palette.accentText)
            FoniaText("Acum tu", style = fButton, color = Palette.ink, maxLines = 1)
            VoiceBars(isLive = !still, color = Palette.accent, level = { live.level }, modifier = Modifier.weight(1f, fill = false).widthIn(min = 120.dp).height(26.dp))
        }
        MicState.Thinking -> ActivityIndicator()
        is MicState.Heard -> Column(verticalArrangement = Arrangement.spacedBy(2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            FoniaText(state.title, style = fHeadline, color = Palette.ink, textAlign = TextAlign.Center)
            state.tip?.let { FoniaText(it, style = fBody, color = Palette.inkSoft, textAlign = TextAlign.Center) }
        }
    }
}

@Composable
private fun Finished(plan: CoachPlan, app: AppModel, onDone: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        CelebrationBurst(isActive = true, modifier = Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(1f))
            Foni(Modifier.height(190.dp), mood = FoniMood.happy, reactsToTap = true)
            FoniaText("Așa se spune ${plan.letter}", style = fDisplay, color = Palette.ink, textAlign = TextAlign.Center)
            val syllables = plan.syllables
            val word = plan.word
            if (syllables != null) {
                FoniaText("Acum încearcă și așa:", style = fBody, color = Palette.inkSoft)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (syllable in syllables.list) {
                        StyledButton(onClick = { app.scope.launch { app.audio.say(syllable, plan.language) } }, style = MiniPillStyle()) { FoniaText(syllable) }
                    }
                }
            } else if (word != null) {
                StyledButton(onClick = { app.scope.launch { app.audio.say(word.word, plan.language) } }, style = MiniPillStyle()) { FoniaText(word.word) }
                FoniaText(word.cue, style = fBody, color = Palette.inkSoft, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.weight(1f))
            StyledButton(onClick = onDone, style = FoniButtonStyle()) { tech.fonia.design.CTALabel("Gata") }
        }
    }
}

/**
 * What the mouth should look like at one step: from the front, or cut from the side when the tongue matters. It starts
 * from where the mouth was and moves into place, because the movement is what gets copied. A tap plays it again.
 */
@Composable
fun MouthDiagram(diagram: CoachDiagram, modifier: Modifier = Modifier) {
    val reduceMotion = LocalReduceMotion.current
    var arrived by remember { mutableStateOf(false) }
    var replays by remember { mutableIntStateOf(0) }
    val shown: Viseme = if (arrived) diagram.pose ?: Viseme.Rest else diagram.from ?: diagram.pose ?: Viseme.Rest
    val tap = Modifier.pointerInput(Unit) { detectTapGestures { replays += 1 } }
    when (diagram.view) {
        CoachDiagram.View.Front -> Box(
            modifier
                .then(tap)
                .semantics { contentDescription = "Gura văzută din față" },
            contentAlignment = Alignment.Center,
        ) {
            FacePatch(Modifier.matchParentSize())
            MouthView(shown.mouth.shape, Modifier.fillMaxSize(), isLive = false, zoom = 1.5)
        }
        CoachDiagram.View.Side -> Box(
            modifier
                .background(Color.hex(0xFFF4E4))
                .then(tap)
                .semantics { contentDescription = "Gura văzută din lateral, cu limba la locul ei" },
            contentAlignment = Alignment.Center,
        ) {
            SagittalView(SagittalPose.named(diagram.sound ?: diagram.pose?.rawValue ?: "rest"), Modifier.fillMaxSize(), isLive = true)
        }
    }
    ViewTask("$replays-${diagram.pose?.rawValue ?: ""}") {
        arrived = reduceMotion || diagram.from == null
        if (arrived) return@ViewTask
        delay(450)
        arrived = true
    }
}
