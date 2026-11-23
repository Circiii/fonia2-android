package tech.fonia.features.session

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tech.fonia.design.FoniaIcon
import tech.fonia.design.FoniaIconView
import tech.fonia.design.FoniaText
import tech.fonia.design.Palette
import tech.fonia.design.PracticeWeight
import tech.fonia.design.RoundButtonStyle
import tech.fonia.design.StyledButton
import tech.fonia.design.TextRole
import tech.fonia.design.TileButtonStyle
import tech.fonia.design.UIWeight
import tech.fonia.design.WordPicture
import tech.fonia.design.fDisplay
import tech.fonia.design.practice
import tech.fonia.design.swiftSpring
import tech.fonia.design.ui
import tech.fonia.domain.Exercise
import tech.fonia.domain.FeedbackLevel
import tech.fonia.domain.Language
import tech.fonia.domain.SessionBlock
import tech.fonia.domain.SortSide
import tech.fonia.domain.Trial
import tech.fonia.domain.Voice
import tech.fonia.features.app.AppModel
import tech.fonia.features.app.SoundEffect
import tech.fonia.features.app.isChild
import tech.fonia.features.app.shortLetter
import tech.fonia.store.Attempt
import java.text.Normalizer

private class Option(
    val id: Int,
    val title: String,
    val picture: String? = null,
    val isRight: Boolean,
    val usesPracticeFont: Boolean = true,
)

/** Swift's `String <`: the Unicode scalars of the composed forms, in order. */
private fun List<Option>.sortedByTitle(): List<Option> =
    sortedWith { a, b -> Normalizer.normalize(a.title, Normalizer.Form.NFC).compareTo(Normalizer.normalize(b.title, Normalizer.Form.NFC)) }

/** Hear something, then pick. Two big answers, and a wrong one plays both so the difference is heard. */
@Composable
fun ListenExercise(trial: Trial, run: SessionRun, app: AppModel) {
    var answered by remember { mutableStateOf<Int?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Job?>(null) }
    val exercise = trial.exercise
    val haptics = LocalHapticFeedback.current

    val question = when (exercise) {
        is Exercise.Hear -> "Auzi ${app.content.sound(exercise.sound).shortLetter}?"
        is Exercise.Choose -> "Ce ai auzit?"
        is Exercise.Sort -> "${app.content.sound(exercise.a).shortLetter} sau ${app.content.sound(exercise.b).shortLetter}?"
        is Exercise.Stress -> "Care sună bine?"
        else -> ""
    }

    val options: List<Option> = when (exercise) {
        is Exercise.Hear -> listOf(
            Option(0, "Da", isRight = exercise.has, usesPracticeFont = false),
            Option(1, "Nu", isRight = !exercise.has, usesPracticeFont = false),
        )
        // Sorted by the words themselves: the side must say nothing about which one is right.
        is Exercise.Choose -> listOf(
            Option(0, exercise.prompt.answer.text, exercise.prompt.answer.picture, isRight = true),
            Option(1, exercise.prompt.other.text, exercise.prompt.other.picture, isRight = false),
        ).sortedByTitle()
        is Exercise.Sort -> listOf(
            Option(0, app.content.sound(exercise.a).letter, isRight = exercise.answer == SortSide.A),
            Option(1, app.content.sound(exercise.b).letter, isRight = exercise.answer == SortSide.B),
        )
        is Exercise.Stress -> listOf(
            Option(0, exercise.word, isRight = true),
            Option(1, exercise.wrong, isRight = false),
        ).sortedByTitle()
        else -> emptyList()
    }

    val heard: Triple<String, Language, Voice?> = when (exercise) {
        is Exercise.Hear -> Triple(exercise.item.text, Language.Ro, null)
        is Exercise.Choose -> Triple(exercise.prompt.answer.text, exercise.prompt.language, exercise.prompt.voice)
        is Exercise.Sort -> Triple(exercise.item.text, Language.Ro, null)
        is Exercise.Stress -> Triple(exercise.word, Language.En, null)
        else -> Triple("", Language.Ro, null)
    }

    val pictureForHeard = when (exercise) {
        is Exercise.Hear -> exercise.item.picture
        is Exercise.Sort -> exercise.item.picture
        else -> null
    }

    val shouldShowResult = trial.feedback != FeedbackLevel.Silent && trial.block.kind != SessionBlock.Kind.Retest

    suspend fun play() {
        isPlaying = true
        app.audio.say(heard.first, heard.second, heard.third)
        isPlaying = false
    }

    /** A wrong answer plays what was said and what was picked, once each. No red, no sound of failure. */
    suspend fun playContrast() {
        when (exercise) {
            is Exercise.Choose -> {
                app.audio.say(exercise.prompt.answer.text, exercise.prompt.language, exercise.prompt.voice)
                app.audio.say(exercise.prompt.other.text, exercise.prompt.language, exercise.prompt.voice)
            }
            is Exercise.Hear, is Exercise.Sort, is Exercise.Stress -> play()
            else -> Unit
        }
    }

    fun pick(option: Option) {
        if (answered != null) return
        answered = option.id
        val outcome = if (option.isRight) Attempt.Outcome.Good else Attempt.Outcome.NotYet
        // `Task { }` kept in `pending`, so leaving the screen cancels it (`onDisappear`).
        pending = app.scope.launch {
            if (shouldShowResult) {
                app.audio.effect(if (option.isRight) SoundEffect.Correct else SoundEffect.Almost)
                delay(if (option.isRight) 450 else 600)
                if (!option.isRight && isActive) playContrast()
            }
            if (!isActive) return@launch
            run.finishAttempt(exercise, machine = outcome, adult = null, take = null)
        }
    }

    // `.sensoryFeedback(.selection, trigger: answered)`.
    LaunchedEffect(answered) { if (answered != null) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick) }
    // `.onDisappear { pending?.cancel() }`: also as soon as the exercise starts to leave.
    val active = LocalExerciseActive.current
    LaunchedEffect(active) { if (!active) pending?.cancel() }
    DisposableEffect(Unit) { onDispose { pending?.cancel() } }

    val field = run.field
    val playingScale by animateFloatAsState(if (isPlaying) 1.12f else 1f, swiftSpring(0.25, 0.3), label = "speaker")
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        WaveTitle(question, fDisplay, field.foreground, Modifier.fillMaxWidth().padding(top = 8.dp), letter = run.ticket.sound?.let { app.content.sound(it).shortLetter }, field = field)
        Spacer(Modifier.weight(1f))
        if (pictureForHeard != null) WordPicture(pictureForHeard, Modifier.heightIn(max = 200.dp))
        StyledButton(
            onClick = { app.scope.launch { play() } },
            style = RoundButtonStyle(fill = Palette.sheet, ink = field.deep, diameter = 92.dp),
            modifier = Modifier.semantics { contentDescription = "Ascultă din nou" },
        ) {
            FoniaIconView(FoniaIcon.speaker, Modifier.graphicsLayer { scaleX = playingScale; scaleY = playingScale }, size = 42f)
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (option in options) {
                AnswerButton(option, reveal = answered != null && shouldShowResult, enabled = answered == null, modifier = Modifier.weight(1f)) { pick(option) }
            }
        }
    }
    ExerciseTask {
        // Under seven, nobody should have to read the question.
        if (run.profile.isChild) app.audio.sayIfRecorded(question)
        delay(180)
        play()
    }
}

@Composable
private fun AnswerButton(option: Option, reveal: Boolean, enabled: Boolean, modifier: Modifier, onPick: () -> Unit) {
    // `.animation(.spring(duration: 0.2, bounce: 0.1), value: answered)`.
    val spring = swiftSpring<Color>(0.2, 0.1)
    val face by animateColorAsState(if (reveal && option.isRight) Palette.rightSoft else Palette.sheet, spring, label = "face")
    val border by animateColorAsState(if (reveal && option.isRight) Palette.right else Palette.right.copy(alpha = 0f), spring, label = "border")
    val opacity by animateFloatAsState(if (reveal && !option.isRight) 0.5f else 1f, swiftSpring(0.2, 0.1), label = "opacity")
    StyledButton(onClick = onPick, style = TileButtonStyle(face = face, border = border), modifier = modifier, enabled = enabled) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = if (option.picture == null) 104.dp else 164.dp)
                .padding(horizontal = 8.dp)
                .alpha(opacity),
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            option.picture?.let { WordPicture(it, Modifier.height(96.dp)) }
            FoniaText(
                option.title,
                style = if (option.usesPracticeFont) practice(28, PracticeWeight.bold, TextRole.title) else ui(28, UIWeight.black, TextRole.title),
                color = Palette.ink,
                maxLines = 1,
                minimumScaleFactor = 0.6f,
            )
        }
    }
}
