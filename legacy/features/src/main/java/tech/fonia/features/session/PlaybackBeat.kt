package tech.fonia.features.session

import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tech.fonia.design.CTALabel
import tech.fonia.design.Field
import tech.fonia.design.FoniButtonStyle
import tech.fonia.design.FoniaText
import tech.fonia.design.PracticeWeight
import tech.fonia.design.StyledButton
import tech.fonia.design.TextRole
import tech.fonia.design.easeOut
import tech.fonia.design.practice
import tech.fonia.features.app.AppModel
import tech.fonia.features.app.ViewTask

/**
 * The end of a lesson's mixed block ([Playback], iOS `Session/PlaybackBeat.swift`): the line, said to a child in the
 * recorded voice, then the good takes one after another, a dot lighting for each. It goes on by itself when they have
 * played, or on a tap, like the chapter before the lesson.
 *
 * iOS caps the type at `accessibility3`, past the largest font scale Android offers, so nothing is capped here.
 */
@Composable
fun PlaybackBeat(playback: Playback, field: Field, app: AppModel, isChild: Boolean, onDone: () -> Unit) {
    // The take playing now.
    var playing by remember { mutableStateOf<Int?>(null) }
    var played by remember { mutableIntStateOf(0) }
    var isDone by remember { mutableStateOf(false) }
    // True once everything has played on its own: closing then has nothing to stop.
    var hasPlayed by remember { mutableStateOf(false) }
    // `accessibilityVoiceOverEnabled`: TalkBack, read when the wait ends.
    val context = LocalContext.current
    val accessibility = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    val scope = rememberCoroutineScope()

    // Stops a take still playing first, so the stop can't land on the next trial's prompt.
    suspend fun close() {
        if (isDone) return
        isDone = true
        // A voice that already ended needs no stop; a stop sent then could cut off whatever speaks next.
        if (!hasPlayed) app.audio.stopSpeaking()
        onDone()
    }

    Column(
        Modifier
            .fillMaxSize()
            // `.onTapGesture`: a tap anywhere goes on, with no node of its own for TalkBack, which has the button.
            .pointerInput(Unit) { detectTapGestures { scope.launch { close() } } },
        // `LessonSize.gap`.
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FoniaText(
                playback.line,
                // `SessionVoice.announce`: TalkBack reads the line as it shows.
                Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                style = practice(30, PracticeWeight.semibold, TextRole.title),
                color = field.foreground,
                textAlign = TextAlign.Center,
            )
            if (playback.takes.isNotEmpty()) {
                Row(Modifier.clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    playback.takes.indices.forEach { take ->
                        val alpha by animateFloatAsState(
                            when {
                                take == playing -> 1f
                                take < played -> 0.55f
                                else -> 0.2f
                            },
                            easeOut(0.2),
                            label = "take",
                        )
                        Box(Modifier.size(14.dp).background(field.foreground.copy(alpha = alpha), CircleShape))
                    }
                }
            }
        }
        StyledButton(
            onClick = { scope.launch { close() } },
            // `LessonSize.main`.
            style = FoniButtonStyle(field = field, minHeight = 64.dp),
            onClickLabel = "Trece la ce urmează în lecție",
        ) { CTALabel("Mai departe") }
    }

    ViewTask(playback.id) {
        if (isChild) app.audio.sayIfRecorded(playback.line)
        for ((index, take) in playback.takes.withIndex()) {
            delay(400)
            if (!isActive || isDone) return@ViewTask
            playing = index
            app.audio.play(take)
            played = index + 1
        }
        playing = null
        hasPlayed = true
        delay(if (playback.takes.isEmpty()) 2400 else 800)
        // With TalkBack on, the reader may still be reading the line: it stays until a tap.
        if (!isActive || accessibility?.isTouchExplorationEnabled == true) return@ViewTask
        close()
    }
}
