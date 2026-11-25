package tech.fonia.features.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import tech.fonia.design.CTALabel
import tech.fonia.design.Field
import tech.fonia.design.FoniButtonStyle
import tech.fonia.design.FoniaText
import tech.fonia.design.MouthDriver
import tech.fonia.design.MouthShape
import tech.fonia.design.Palette
import tech.fonia.design.PracticeSheet
import tech.fonia.design.PracticeWeight
import tech.fonia.design.StyledButton
import tech.fonia.design.TextRole
import tech.fonia.design.UIWeight
import tech.fonia.design.WordPicture
import tech.fonia.design.fBody
import tech.fonia.design.fDisplay
import tech.fonia.design.fHeadline
import tech.fonia.design.fLead
import tech.fonia.design.monospacedDigit
import tech.fonia.design.practice
import tech.fonia.design.ui
import tech.fonia.domain.Exercise
import tech.fonia.domain.HowTarget
import tech.fonia.domain.Language
import tech.fonia.domain.MissionPrompt
import tech.fonia.domain.SoundDef
import tech.fonia.domain.Trial
import tech.fonia.domain.Viseme
import tech.fonia.domain.Warmup
import tech.fonia.features.app.AppModel
import tech.fonia.features.app.LocalStillMeters
import tech.fonia.features.app.ViewTask
import tech.fonia.features.app.isChild
import tech.fonia.features.app.lowercasedFirstLetterForSentence
import tech.fonia.features.app.shape
import tech.fonia.features.app.shortLetter

/**
 * Whether the exercise on screen is the current one. On iOS a view whose identity changed (`.id("\(index)-\(retry)")`)
 * is gone, and its task with it; here the old exercise stays on screen for its exit animation, so its flow stops as
 * soon as it starts to leave.
 */
internal val LocalExerciseActive = compositionLocalOf { true }

/** An exercise's `.task`: runs while the exercise is the current one, on the main thread. */
@Composable
internal fun ExerciseTask(vararg keys: Any?, block: suspend CoroutineScope.() -> Unit) {
    val active = LocalExerciseActive.current
    ViewTask(active, *keys) { if (active) block() }
}

/** One exhaustive switch from exercise to screen. */
@Composable
fun ExerciseHost(trial: Trial, run: SessionRun, app: AppModel) {
    when (val exercise = trial.exercise) {
        is Exercise.Intro -> IntroExercise(app.content.sound(exercise.sound), run, app)
        is Exercise.How -> HowExercise(exercise.target, run, app)
        is Exercise.Hear, is Exercise.Choose, is Exercise.Sort, is Exercise.Stress -> ListenExercise(trial, run, app)
        is Exercise.Warmup -> WarmupExercise(app.content.warmup(exercise.id), run, app)
        is Exercise.Sustain -> SustainExercise(trial, exercise.target, exercise.seconds, exercise.role, run, app)
        is Exercise.Mission -> MissionExercise(exercise.prompt, run, app)
        is Exercise.Repeat, is Exercise.Name, is Exercise.Twister, Exercise.Volume, Exercise.Glide, is Exercise.Intonation,
        is Exercise.Expression, is Exercise.Pace, is Exercise.Pauses, is Exercise.Shadow, is Exercise.Retell, is Exercise.Describe,
        -> SpeakExercise(trial, run, app)
    }
}

/** The one main button of a teaching screen. */
@Composable
internal fun MainAction(title: String, field: Field, onClick: () -> Unit) {
    StyledButton(onClick = onClick, style = FoniButtonStyle(field = field)) { CTALabel(title) }
}

/** Meet the sound: its hero, the story around it, and the sound itself. */
@Composable
fun IntroExercise(sound: SoundDef, run: SessionRun, app: AppModel) {
    val field = run.field
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        WordPicture(sound.hero.picture, Modifier.heightIn(max = 200.dp))
        WaveTitle(sound.hero.name, fDisplay, field.foreground)
        // The story is told by the voice; on screen, the picture, the name and the sound (less text).
        FoniaText(
            sound.hero.says,
            Modifier.padding(top = 6.dp),
            style = practice(56, PracticeWeight.bold, TextRole.largeTitle),
            color = field.deep,
            maxLines = 1,
            minimumScaleFactor = 0.5f,
        )
        Spacer(Modifier.weight(1f))
        MainAction("Mai departe", field) { run.advance() }
    }
    ExerciseTask {
        // The lesson's chapter when there is one: the intro tells it in place of the hero's general story.
        app.audio.say((if (sound.id == run.ticket.sound) run.chapter else null) ?: sound.hero.story)
        app.audio.say(sound.hero.says)
    }
}

/** The one teaching screen: where the mouth goes, shown and said. */
@Composable
fun HowExercise(target: HowTarget, run: SessionRun, app: AppModel) {
    var isLive by remember { mutableStateOf(false) }
    var step by remember { mutableIntStateOf(0) }
    val lines: List<String> = when (target) {
        is HowTarget.Sound -> app.content.sound(target.id).how
        is HowTarget.English -> app.content.english.contrast(target.contrast)?.let { listOf(it.cue) } ?: emptyList()
    }
    val shape: MouthShape = when (target) {
        is HowTarget.Sound -> app.content.sound(target.id).shape
        is HowTarget.English -> (app.content.english.contrast(target.contrast)?.pose ?: Viseme.Rest).mouth.shape
    }
    val title = when (target) {
        is HowTarget.Sound -> "Cum spun ${app.content.sound(target.id).shortLetter}?"
        is HowTarget.English -> app.content.english.contrast(target.contrast)?.title ?: ""
    }
    val viseme = (target as? HowTarget.Sound)?.let { app.content.sound(it.id).viseme }
    val field = run.field
    val still = LocalStillMeters.current
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        WaveTitle(title, fDisplay, field.foreground, Modifier.padding(top = 8.dp), letter = (target as? HowTarget.Sound)?.let { app.content.sound(it.id).shortLetter }, field = field)
        MouthCard(shape, viseme = viseme, driver = talkDriver(app), isLive = isLive && !still, height = 200.dp)
        // Each step shows as the voice says it, not all at once (less text to face).
        StepList(lines.take(step + 1), current = step, field = field)
        Spacer(Modifier.weight(1f))
        MainAction("Încerc", field) { run.advance() }
    }
    ExerciseTask {
        isLive = true
        for ((index, line) in lines.withIndex()) {
            step = index
            // A device voice reading instructions to an adult who can read adds nothing.
            if (run.profile.isChild || app.audio.hasClip(line, Language.Ro)) app.audio.say(line)
            if (!isActive) break
        }
        isLive = false
    }
}

/** Optional, and it never decides anything. */
@Composable
fun WarmupExercise(warmup: Warmup?, run: SessionRun, app: AppModel) {
    var useSecondPose by remember { mutableStateOf(false) }
    val field = run.field
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        if (warmup != null) {
            FoniaText(warmup.name, style = fDisplay, color = field.foreground, textAlign = TextAlign.Center)
            MouthCard((if (useSecondPose) warmup.poseB ?: warmup.pose else warmup.pose).mouth.shape, height = 200.dp)
            FoniaText(warmup.say, style = fLead, color = field.foreground, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.weight(1f))
        MainAction("Am făcut", field) { run.advance() }
    }
    ExerciseTask {
        if (warmup == null) return@ExerciseTask
        app.audio.say(warmup.say)
        if (warmup.poseB == null) return@ExerciseTask
        while (isActive) {
            delay(Pacing.current.warmupPoseMs)
            useSecondPose = !useSecondPose
        }
    }
}

/** A real-life task. The phone stays out of it: the parent listens and notes how it went. */
@Composable
fun MissionExercise(prompt: MissionPrompt, run: SessionRun, app: AppModel) {
    val field = run.field
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        FoniaText("Azi, peste zi", style = fHeadline, color = field.foreground, textAlign = TextAlign.Center)
        PracticeSheet {
            Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                FoniaText(prompt.text, style = ui(28, UIWeight.bold, TextRole.title), color = Palette.ink, textAlign = TextAlign.Center)
            }
        }
        Spacer(Modifier.weight(1f))
        MainAction("Ne-am înțeles", field) { run.advance() }
    }
    ExerciseTask {
        if (run.profile.isChild || app.audio.hasClip(prompt.text, Language.Ro)) app.audio.say(prompt.text)
    }
}

/** Numbered steps, with the one being said in bold. */
@Composable
fun StepList(lines: List<String>, current: Int?, field: Field) {
    val circleBaseline = with(LocalDensity.current) { 8.dp.roundToPx() }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        lines.forEachIndexed { index, line ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (lines.size > 1) {
                    Box(
                        Modifier
                            // `.alignmentGuide(.firstTextBaseline) { $0[.bottom] - 8 }`.
                            .alignBy { it.measuredHeight - circleBaseline }
                            .size(26.dp)
                            .background(Palette.buttonFill, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        FoniaText("${index + 1}", style = ui(15, UIWeight.bold, TextRole.footnote).monospacedDigit(), color = Palette.buttonLabel)
                    }
                }
                FoniaText(
                    line,
                    Modifier.alignBy(FirstBaseline).weight(1f, fill = false),
                    style = ui(18, if (index == current) UIWeight.semibold else UIWeight.regular, TextRole.body),
                    color = field.foreground,
                )
            }
        }
    }
}

/** The mouth that follows the voice playing now. */
fun talkDriver(app: AppModel): MouthDriver {
    val levels = app.audio.levels
    return MouthDriver(talk = { levels.talk.toDouble() })
}
