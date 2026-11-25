package tech.fonia.features.session

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import tech.fonia.features.home.isWarmup
import tech.fonia.features.app.shortLetter
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tech.fonia.designsystem.motion.FoniaOverlayHost
import tech.fonia.design.FoniaIcon
import tech.fonia.design.FoniaIconView
import tech.fonia.design.FoniaText
import tech.fonia.design.LocalReduceMotion
import tech.fonia.design.Palette
import tech.fonia.design.SceneBackdrop
import tech.fonia.design.SessionBar
import tech.fonia.design.StyledButton
import tech.fonia.design.ToolButtonStyle
import tech.fonia.design.fHeadline
import tech.fonia.design.scene
import tech.fonia.design.swiftSpring
import tech.fonia.domain.AttemptsSummary
import tech.fonia.domain.SessionFocus
import tech.fonia.features.app.ActionRole
import tech.fonia.features.app.AppModel
import tech.fonia.features.app.DialogAction
import tech.fonia.features.app.FoniaConfirmationDialog
import tech.fonia.features.app.SessionTicket
import tech.fonia.features.app.ViewTask
import tech.fonia.features.app.isChild
import tech.fonia.store.Profile
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

/**
 * The session as a whole: one field, one bar at the top, one thing to do. `FoniaRoot` shows it as a full-screen cover
 * while `app.ticket` is set; [onDone] clears the ticket.
 */
@Composable
fun SessionScreen(ticket: SessionTicket, profile: Profile, app: AppModel, onDone: () -> Unit) {
    var run by remember { mutableStateOf<SessionRun?>(null) }
    var confirmLeaving by remember { mutableStateOf(false) }

    /** What came before on this focus, for the builder: finished sessions, this week's attempts, the last items. */
    suspend fun history(): AttemptsSummary {
        val sessions = orNull { app.repository.sessions(profile.id) } ?: emptyList()
        val same = sessions.filter { it.focus == ticket.focus.key && it.endedAt != null }
        val weekAgo = Instant.now().minus(Duration.ofSeconds(7 * 86_400L))
        val thisWeek = same.filter { it.startedAt.isAfter(weekAgo) }.fold(0) { sum, session -> sum + session.attempts }
        var recent = emptySet<String>()
        val last = same.maxByOrNull { it.startedAt }
        if (last != null) {
            val attempts = orNull { app.repository.attempts(last.id) } ?: emptyList()
            recent = attempts.mapNotNull { it.target }.toSet()
        }
        return AttemptsSummary(sessionsCompleted = same.size, attemptsThisWeek = thisWeek, recentItems = recent)
    }

    /** What the last three runs on this focus played (listening items included), so the next one draws others. */
    suspend fun recentListening(): Set<String> {
        val sessions = orNull { app.repository.sessions(profile.id) } ?: emptyList()
        return sessions.filter { it.focus == ticket.focus.key }.sortedByDescending { it.startedAt }.take(3)
            .flatMap { s -> (orNull { app.repository.attempts(s.id) } ?: emptyList()).mapNotNull { it.target } }
            .toSet()
    }

    fun leave(current: SessionRun) {
        app.scope.launch {
            current.leaveEarly()
            onDone()
        }
    }

    fun finish() {
        app.scope.launch {
            app.audio.shutDown()
            onDone()
        }
    }

    ViewTask(Unit) {
        if (run != null) return@ViewTask
        val progress = orNull { app.repository.soundProgress(profile.id) }?.firstOrNull { it.sound == ticket.sound?.rawValue }
        val focus = ticket.focus
        val marks = if (profile.isChild && focus is SessionFocus.Sound) {
            orNull { AdultMarks.load(app.repository, profile.id, progress, focus.rung) } ?: emptyList()
        } else {
            emptyList()
        }
        val made = SessionRun(ticket, profile, app, progress, history(), recentListening(), marks)
        made.start()
        run = made
    }

    // The Android back gesture is the X, or the one button at the end.
    BackHandler {
        val current = run
        when {
            current == null -> finish()
            current.phase == SessionRun.Phase.Finished -> finish()
            current.attempts == 0 -> leave(current)
            else -> confirmLeaving = true
        }
    }

    // What „Încă una” opens and the recap names: read again once this session's saves are in.
    var nextStep by remember { mutableStateOf<tech.fonia.features.home.NextStep?>(null) }
    var nextTitle by remember { mutableStateOf<String?>(null) }
    var nextDrill by remember { mutableStateOf<Int?>(null) }
    var nextSound by remember { mutableStateOf<String?>(null) }
    // Whether the look for what comes next has finished, so the goodbye waits until it's known there is no more.
    var nextKnown by remember { mutableStateOf(false) }
    val drillKind = ticket.custom?.kind as? tech.fonia.features.app.CustomRun.Kind.Drill
    val drill = remember { drillKind?.let { app.drills(it.sound).getOrNull(it.index) } }
    val finished = run?.phase == SessionRun.Phase.Finished
    LaunchedEffect(finished) {
        val ended = run ?: return@LaunchedEffect
        if (!finished || ended.isCheck) return@LaunchedEffect
        if (drillKind != null && drill != null) {
            // A drill: its best result opens the next one (his own success); „Încă una” is the next drill, or this
            // one again while the next is still closed.
            val takes = ended.takes.values
            val judged = takes.count { it.outcome == tech.fonia.store.Attempt.Outcome.Good || it.outcome == tech.fonia.store.Attempt.Outcome.Almost || it.outcome == tech.fonia.store.Attempt.Outcome.NotYet }
            val share = tech.fonia.features.diction.DictionDrills.share(takes.count { it.outcome == tech.fonia.store.Attempt.Outcome.Good }, judged, drill.judged)
            runCatching { app.drills.record(profile.id, drillKind.sound, drillKind.index, share) }
            val best = runCatching { app.drills.best(profile.id, drillKind.sound) }.getOrDefault(emptyMap())
            val all = app.drills(drillKind.sound)
            val following = drillKind.index + 1
            val goTo = if (following < all.size && tech.fonia.features.diction.DictionDrills.isOpen(following, best)) following else drillKind.index
            nextDrill = goTo
            nextTitle = all.getOrNull(goTo)?.title
            nextKnown = true
            return@LaunchedEffect
        }
        ended.awaitSaves()
        val now = app.profiles.firstOrNull { it.id == profile.id } ?: profile
        val path = tech.fonia.features.home.PathModel(now, app.content)
        path.reload(app.repository, app.checks)
        // The next lesson of the unit just practised, or today's sound once that unit has nothing left to open. Null
        // when there's nothing more, so „Încă una” only shows when another one exists.
        val practised = path.catalog.allUnits(path.soundOrder).firstOrNull { unit ->
            unit.lessons.any { it.focus.key == ended.ticket.focus.key }
        }
        val step = practised?.lessons
            ?.firstOrNull { !it.isWarmup && path.state(it, practised) != tech.fonia.features.home.LessonState.done && path.canOpen(it, practised) }
            ?.let { tech.fonia.features.home.NextStep(practised, it) }
            ?: path.continuing
        nextStep = step
        nextTitle = step?.lesson?.title
        nextSound = step?.unit?.sound?.let { app.content.sound(it).shortLetter }
        nextKnown = true
    }

    fun more() {
        val step = nextStep
        val goTo = nextDrill
        app.scope.launch {
            app.audio.shutDown()
            onDone()
            if (drillKind != null && goTo != null) app.startDrill(drillKind.sound, goTo)
            else if (step != null) app.start(step.lesson, step.unit)
        }
    }

    val current = run
    if (current == null) {
        // `Color.clear` inside a cover, which iOS shows on the system background.
        Box(Modifier.fillMaxSize().background(Palette.sheet))
        return
    }
    // The motion kit reads its own reduce-motion flag: it follows his. The overlay host draws the letters' bursts and
    // the stars above every card, unclipped.
    CompositionLocalProvider(tech.fonia.designsystem.theme.LocalReduceMotion provides LocalReduceMotion.current) {
    FoniaOverlayHost(Modifier.fillMaxSize()) {
    Box(Modifier.fillMaxSize()) {
        if (current.phase != SessionRun.Phase.Finished) SceneBackdrop(current.field.scene)
        if (current.phase == SessionRun.Phase.Finished && current.isCheck) {
            tech.fonia.features.check.CheckResultScreen(current, app, onDone = { finish() })
        } else if (current.phase == SessionRun.Phase.Finished) {
            RecapScreen(current, app, next = nextTitle, nextSound = nextSound, nextKnown = nextKnown, isDrill = drillKind != null, onMore = { more() }, onDone = { finish() })
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                TopBar(current) {
                    if (current.attempts == 0) leave(current) else confirmLeaving = true
                }
                val note = current.blockNote
                AnimatedVisibility(note != null, enter = fadeIn(swiftSpring(0.28, 0.0)), exit = fadeOut(swiftSpring(0.28, 0.0))) {
                    var shown by remember { mutableStateOf(note ?: "") }
                    if (note != null) shown = note
                    FoniaText(shown, Modifier.fillMaxWidth(), style = fHeadline, color = current.field.foreground, textAlign = TextAlign.Center)
                }
                if (note != null) {
                    LaunchedEffect(note) {
                        delay(2400)
                        current.clearBlockNote()
                    }
                }
                val chapter = current.chapter
                val chapterSound = current.ticket.sound
                if (current.isTellingChapter && chapter != null && chapterSound != null) {
                    Box(Modifier.weight(1f)) {
                        ChapterBeat(app.content.sound(chapterSound).hero, chapter, current.field, app) { current.finishChapter() }
                    }
                } else {
                    if (drill != null) {
                        tech.fonia.features.diction.DrillStrip(drill, current.index, current.takes.mapValues { it.value.outcome })
                    }
                    Exercises(current, app, Modifier.weight(1f))
                }
            }
        }
        // Debug builds only, behind a developer switch: shadow mode's agreement, over the scene, taking no touches.
        ShadowBadge(
            app,
            Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(top = 64.dp, end = 12.dp),
        )
    }
    }
    }

    FoniaConfirmationDialog(
        isPresented = confirmLeaving,
        onDismiss = { confirmLeaving = false },
        title = "Te oprești aici?",
        message = if (current.attempts > 0) "Ce ai lucrat până acum se păstrează." else null,
        actions = listOf(
            DialogAction("Mă opresc", ActionRole.Destructive) { leave(current) },
            DialogAction("Mai rămân", ActionRole.Cancel),
        ),
    )
}

/** Swift's `try?` on a read. */
private suspend fun <T> orNull(read: suspend () -> T): T? = try {
    read()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}

@Composable
private fun TopBar(run: SessionRun, onClose: () -> Unit) {
    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        StyledButton(onClick = onClose, style = ToolButtonStyle(field = run.field), modifier = Modifier.semantics { contentDescription = "Mă opresc" }) {
            FoniaIconView(FoniaIcon.close, size = 18f)
        }
        SessionBar(run.progressValue, color = Palette.accent, track = run.field.track, modifier = Modifier.weight(1f), height = 12.dp)
    }
}

/** The exercise of the moment, which gives way to the next: the old one leaves, then the next slides in. */
@Composable
private fun Exercises(run: SessionRun, app: AppModel, modifier: Modifier) {
    // `.id("\(run.index)-\(run.retryToken)")`: a new trial, or the same one again, is a new view with a new task.
    val identity = ExerciseIdentity(run.index, run.retryToken)
    val reduceMotion = LocalReduceMotion.current
    val pageDensity = LocalDensity.current
    // Set this large, a task can be taller than the screen. It scrolls instead of pushing the bar away. Android's font
    // scale stands in for the accessibility sizes, which start at about 1.6 times the normal text on iOS.
    val isAccessibilitySize = LocalDensity.current.fontScale >= 1.5f
    AnimatedContent(
        targetState = identity,
        modifier = modifier.fillMaxWidth(),
        // The page that leaves fades out quickly; the next one (or the same one again) comes in after it, with a short
        // slide. The two never stand on top of each other while either can be read. With Reduce Motion on, plain fades.
        transitionSpec = { DockMotion.page(reduceMotion, pageDensity) },
        contentKey = { it },
        label = "exercise",
    ) { shown ->
        val trial = run.session.trials.getOrNull(shown.index) ?: return@AnimatedContent
        val leaving = transition.targetState == EnterExitState.PostExit
        CompositionLocalProvider(LocalExerciseActive provides !leaving) {
            if (isAccessibilitySize) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight)) {
                        ExerciseHost(trial, run, app)
                    }
                }
            } else {
                Box(Modifier.fillMaxSize()) { ExerciseHost(trial, run, app) }
            }
        }
    }
}

private val density: androidx.compose.ui.unit.Density
    get() = androidx.compose.ui.unit.Density(android.content.res.Resources.getSystem().displayMetrics.density)

private data class ExerciseIdentity(val index: Int, val retry: Int)
