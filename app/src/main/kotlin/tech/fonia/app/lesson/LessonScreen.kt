package tech.fonia.app.lesson

import android.Manifest
import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.fonia.app.AppGraph
import tech.fonia.app.content.LoadedContent
import tech.fonia.app.data.PendingMission
import tech.fonia.app.data.Ladder
import tech.fonia.app.house.Exercise
import tech.fonia.app.mirror.MirrorScreen
import tech.fonia.app.shell.MicPermission
import tech.fonia.app.text.T
import tech.fonia.app.ui.Foni
import tech.fonia.app.ui.FoniMood
import tech.fonia.app.ui.FoniaSheet
import tech.fonia.app.ui.MouthButton
import tech.fonia.app.ui.RoundGlyphButton
import tech.fonia.app.ui.SheetBody
import tech.fonia.app.ui.SheetTitle
import tech.fonia.app.ui.SpeechBubble
import tech.fonia.app.ui.StepDots
import tech.fonia.designsystem.components.ContinueButton
import tech.fonia.designsystem.components.ContinueStyle
import tech.fonia.designsystem.components.MicCapsule
import tech.fonia.designsystem.components.QuietButton
import tech.fonia.designsystem.components.StampKind
import tech.fonia.designsystem.components.VerdictStamp
import tech.fonia.designsystem.theme.FoniaStage
import tech.fonia.designsystem.theme.FoniaTheme
import tech.fonia.designsystem.tokens.FoniaCorner
import tech.fonia.designsystem.tokens.FoniaSpace
import tech.fonia.lessons.LessonPlan
import tech.fonia.live.SuspendReason
import tech.fonia.navigation.EndKey
import tech.fonia.navigation.LessonKey
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

private enum class MicGate { Asking, Ready }

private class Built(val loaded: LoadedContent, val plan: LessonPlan, val exercise: Exercise?)

/**
 * LESSON (chapter 01 §6): the guided session or one exercise card, built by LessonBuilder from content v2. Foni's line,
 * the step's stage (the sound's hold game, pictures, pairs, the train, the story…), the mic capsule, the adult band.
 */
@Composable
fun LessonScreen(
    key: LessonKey,
    graph: AppGraph,
    onFinished: (EndKey) -> Unit,
    onHome: () -> Unit,
) {
    val context = LocalContext.current
    var mirrorOpen by remember(key) { mutableStateOf(false) }
    val perms = graph.permissions
    var gate by remember(key) { mutableStateOf(if (!graph.fixtureMode && perms.shouldAsk) MicGate.Asking else MicGate.Ready) }
    var micAllowed by remember(key) { mutableStateOf(graph.fixtureMode || perms.granted) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        perms.onResult(granted, context as? Activity)
        micAllowed = granted
        gate = MicGate.Ready
    }

    // ZIPA warms while LESSON is open and is trimmed when it closes (ml/README.md)
    DisposableEffect(key) {
        val z = graph.zipa
        if (z != null) graph.scope.launch { runCatching { z.warmUp() }.onFailure { android.util.Log.w("FoniaZipa", "warm-up failed: $it") } }
        onDispose { graph.zipa?.trim() }
    }

    val built by produceState<Built?>(null, key) {
        val loaded = graph.content.get()
        val st = graph.practice.state()
        val p = st.of(key.sound)
        val ex = Exercise.of(key.exercise)
        val today = LocalDate.now()
        val last = st.days.maxOrNull()?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val days = last?.let { ChronoUnit.DAYS.between(it, today).toInt() } ?: 0
        value = withContext(Dispatchers.Default) {
            val plan = if (ex != null) PlanMaker.exercise(loaded.content, key.sound, ex, p, graph.flags.demo)
            else PlanMaker.guided(loaded.content, key.sound, p, days, key.oneMore, graph.flags.demo)
            plan?.let { Built(loaded, it, ex) }
        }
    }

    val b = built
    val controller = remember(key, gate, b) {
        if (gate != MicGate.Ready || b == null) null else LessonController(
            sound = key.sound,
            sessionId = runCatching { UUID.fromString(key.sessionId) }.getOrElse { UUID.randomUUID() },
            plan = b.plan,
            loaded = b.loaded,
            exercise = b.exercise,
            audio = graph.audio,
            judge = graph.judge,
            log = graph.attempts,
            tuning = graph.lessonTuning,
            sources = graph.takeSources,
            saveScope = graph.scope,
            demo = graph.flags.demo,
            micAllowed = micAllowed,
            onFinished = onFinished,
            onHome = onHome,
            onResult = { r -> graph.scope.launch { saveResult(graph, key, r) } },
            judgeContext = { seen -> graph.calibration.context(key.sound, seen) },
            onAdultAnswered = { graph.scope.launch { graph.calibration.refresh() } },
            demoThumb = key.sound != tech.fonia.model.SoundId.Sh,
        )
    }

    Box(Modifier.fillMaxSize().background(FoniaTheme.colors.bg)) {
        if (controller != null) {
            LaunchedEffect(controller) { controller.run() }
            LaunchedEffect(controller) { while (isActive) withFrameNanos { controller.onFrame(it) } }
            BackHandler { controller.exitAsked = true }
            LessonBody(controller, onMirror = {
                graph.audio.suspendAll(SuspendReason.Other)
                mirrorOpen = true
            })
            FoniaSheet(controller.exitAsked, onDismiss = { controller.exitAsked = false }) {
                SheetTitle(T.exitTitle)
                ContinueButton(T.exitStay, onClick = { controller.exitAsked = false }, arrow = false)
                ContinueButton(T.exitDone, onClick = { controller.leave() }, style = ContinueStyle.Outlined, arrow = false)
            }
            if (mirrorOpen) {
                MirrorScreen(graph, key.sound, inLesson = true, onClose = {
                    mirrorOpen = false
                    graph.audio.resume()
                    if (controller.phase == Phase.Paused) controller.resume()
                })
            }
        } else if (gate == MicGate.Asking) {
            BackHandler { onHome() }
            Column(Modifier.align(Alignment.TopCenter).padding(top = 160.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Foni(FoniMood.Unsure, size = 120.dp)
            }
            val forever = perms.mic.value == MicPermission.DeniedForever
            FoniaSheet(true, onDismiss = {}, dismissible = false) {
                SheetTitle(T.micTitle)
                SheetBody(if (forever) T.micDeniedForever else T.micBody)
                if (forever) {
                    ContinueButton(T.micOpenSettings, onClick = { (context as? Activity)?.let(perms::openSettings) }, arrow = false)
                } else {
                    ContinueButton(T.micAllow, onClick = { launcher.launch(Manifest.permission.RECORD_AUDIO) }, arrow = false)
                }
                ContinueButton(T.micLater, style = ContinueStyle.Outlined, arrow = false, onClick = {
                    perms.declinedForNow = true
                    micAllowed = false
                    gate = MicGate.Ready
                })
            }
        } else {
            // the plan is being built (content loads once, off the main thread): Foni waits
            BackHandler { onHome() }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Foni(FoniMood.Thinking, size = 96.dp) }
        }
    }
}

/** The run's results into the practice book: LessonHistory, the ladder, the card's dots, the days, today's mission. */
private suspend fun saveResult(graph: AppGraph, key: LessonKey, r: RunResult) {
    val today = LocalDate.now().toString()
    graph.practice.update { st ->
        val p = st.of(key.sound)
        val next = if (r.exercise == null) Ladder.afterGuided(p, r.plan, r.results) else Ladder.afterExercise(p, r.exercise, r.plan, r.results)
        st.with(key.sound) { next }.copy(
            lastSound = key.sound,
            days = if (r.counted > 0) st.days + today else st.days,
            mission = r.missionItem?.let { PendingMission(key.sound, it.id, it.text, today) } ?: st.mission,
        )
    }
}

@Composable
private fun LessonBody(c: LessonController, onMirror: () -> Unit) {
    val colors = FoniaTheme.colors
    val reduce = FoniaTheme.reduceMotion
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = FoniaSpace.pageKid),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = FoniaSpace.xs), verticalAlignment = Alignment.CenterVertically) {
            RoundGlyphButton("✕", T.lessonExitA11y, onClick = { c.exitAsked = true })
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                StepDots(c.stepIndex, c.totalSteps, T.lessonDotsA11y(c.stepIndex, c.totalSteps))
            }
            MouthButton(T.lessonMouthA11y, onClick = onMirror)
        }
        Spacer(Modifier.height(FoniaSpace.s))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val mood = when {
                c.talking -> FoniMood.Talking
                c.phase == Phase.Listening -> FoniMood.Listening
                c.phase == Phase.Thinking -> FoniMood.Thinking
                c.phase == Phase.AdultAsk -> FoniMood.Unsure
                c.phase == Phase.Break -> FoniMood.Cheering
                c.phase == Phase.Verdict -> if (c.stamp?.kind == StampKind.Correct) FoniMood.Cheering else FoniMood.Encouraging
                c.phase == Phase.Choosing -> FoniMood.Pointing
                else -> FoniMood.Idle
            }
            Foni(mood, size = 64.dp, voiceLevel = { if (c.talking) (0.5f + 0.5f * kotlin.math.sin(c.tick.longValue / 6e7f)) else 0f })
            Spacer(Modifier.width(FoniaSpace.xs))
            AnimatedContent(
                targetState = c.line,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                modifier = Modifier.weight(1f),
                label = "line",
            ) { text -> SpeechBubble(text, Modifier.fillMaxWidth().heightIn(min = 84.dp)) }
        }
        Spacer(Modifier.height(FoniaSpace.s))
        FoniaStage {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(FoniaCorner.sheet))
                    .background(colors.accent, RoundedCornerShape(FoniaCorner.sheet)),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    targetState = c.step?.kind to (c.item?.id ?: ""),
                    transitionSpec = {
                        ((fadeIn(tween(220)) + scaleIn(tween(280), initialScale = if (reduce) 1f else 0.9f)) togetherWith fadeOut(tween(140)))
                            .using(SizeTransform(clip = false))
                    },
                    label = "stage",
                ) { _ -> StepStage(c, Modifier.fillMaxSize()) }
                Box(Modifier.align(Alignment.BottomCenter).padding(bottom = FoniaSpace.s)) { StampArea(c.stamp, reduce) }
            }
        }
        Spacer(Modifier.height(FoniaSpace.s))
        Box(Modifier.fillMaxWidth().heightIn(min = 72.dp), contentAlignment = Alignment.Center) {
            when (c.phase) {
                Phase.Paused -> ContinueButton(T.pausedButton, onClick = c::resume)
                Phase.Quiet -> Row(horizontalArrangement = Arrangement.spacedBy(FoniaSpace.s)) {
                    ContinueButton(T.quietMore, onClick = { c.quietChoice(true) }, modifier = Modifier.weight(1f), arrow = false)
                    ContinueButton(T.quietSkip, onClick = { c.quietChoice(false) }, modifier = Modifier.weight(1f), style = ContinueStyle.Outlined, arrow = false)
                }
                Phase.Together -> ContinueButton(T.togetherDone, onClick = c::done, arrow = false)
                Phase.Break -> ContinueButton(T.moveDone, onClick = c::done, arrow = false)
                Phase.Choosing -> BasicText(T.listenAsk, style = FoniaTheme.type.title2.copy(color = colors.ink))
                else -> MicCapsule(c.capsule, c.capsuleLabel, level = { c.micLevel.floatValue }, nudge = { c.nudge })
            }
        }
        Spacer(Modifier.height(FoniaSpace.xs))
        AdultBand(c)
        Spacer(Modifier.height(FoniaSpace.m))
    }
}

/** Room for the verdict stamp (fixed height: nothing jumps when it appears). */
@Composable
private fun StampArea(stamp: Stamp?, reduce: Boolean) {
    Box(Modifier.height(80.dp).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        AnimatedContent(
            targetState = stamp,
            transitionSpec = { (fadeIn(tween(160)) + scaleIn(tween(240), initialScale = if (reduce) 1f else 0.7f)) togetherWith fadeOut(tween(120)) },
            label = "stamp",
        ) { st -> if (st != null) VerdictStamp(st.kind, st.word) else Box(Modifier.fillMaxWidth()) }
    }
}

/**
 * „Pentru adult”: small, above the dead zone; lights up when the phone is unsure, when the adult judges (twister,
 * story) or when there is no microphone.
 */
@Composable
private fun AdultBand(c: LessonController) {
    val colors = FoniaTheme.colors
    val lit = c.adultLit
    val enabled = lit || c.lastAttempt != null
    val showAsk = c.adultQuestion == AdultQuestion.ShowInstruction
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (lit) colors.surface else colors.bg, RoundedCornerShape(FoniaCorner.card))
            .padding(horizontal = FoniaSpace.s, vertical = FoniaSpace.xs),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val hint = c.adultHint ?: if (!c.micAvailable) T.micOffBanner else null
        val title = when {
            showAsk -> T.adultShowAsk
            lit -> T.adultAsk
            else -> T.adultBandIdle
        }
        BasicText(
            if (hint != null) "$title  ·  $hint" else title,
            style = FoniaTheme.type.label.copy(color = if (lit) colors.ink else colors.inkSecondary),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.heightIn(min = 40.dp),
        )
        Row(Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0f), horizontalArrangement = Arrangement.spacedBy(FoniaSpace.s)) {
            QuietButton(if (showAsk) T.adultShowYes else T.adultYes, onClick = { if (enabled) c.adult(true) }, emphasised = lit, modifier = Modifier.weight(1f))
            QuietButton(if (showAsk) T.adultShowNo else T.adultNo, onClick = { if (enabled) c.adult(false) }, modifier = Modifier.weight(1f))
        }
        BasicText(
            c.adultTrial ?: "",
            style = FoniaTheme.type.caption.copy(color = colors.inkSecondary),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().heightIn(min = 16.dp),
        )
    }
}
