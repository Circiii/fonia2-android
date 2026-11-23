package tech.fonia.features.session

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tech.fonia.design.Field
import tech.fonia.design.WordPicture
import tech.fonia.design.fDisplay
import tech.fonia.domain.SoundHero
import tech.fonia.features.app.AppModel

/**
 * The hero's chapter for this lesson, told before the first trial (iOS `Session/ChapterBeat.swift`). The voice tells
 * it; the screen shows only the hero and its name, so a child faces no sentence to read (the owner's "as little text
 * as possible"). A screen reader gets the chapter text. It goes away when the voice ends or on a tap, so it never
 * stands between a child and the lesson for long.
 */
@Composable
fun ChapterBeat(hero: SoundHero, text: String, field: Field, app: AppModel, onDone: () -> Unit) {
    var isDone by remember { mutableStateOf(false) }
    // True once the voice has finished on its own: closing then has nothing to stop.
    var hasSpoken by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    suspend fun close() {
        if (isDone) return
        isDone = true
        // A voice that already ended needs no stop; a stop sent then could cut off whatever speaks next.
        if (!hasSpoken) app.audio.stopSpeaking()
        onDone()
    }
    Column(
        Modifier
            .fillMaxSize()
            // A tap anywhere starts, as on iOS; the button is there for anyone who looks for one.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { scope.launch { close() } }
            .semantics { contentDescription = text },
        verticalArrangement = Arrangement.spacedBy(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        WordPicture(hero.picture, Modifier.heightIn(min = 80.dp, max = 220.dp))
        WaveTitle(hero.name, fDisplay, field.foreground)
        Spacer(Modifier.weight(1f))
        MainAction("Începem", field) { scope.launch { close() } }
    }
    ExerciseTask {
        app.audio.say(text)
        if (!isActive) return@ExerciseTask
        hasSpoken = true
        close()
    }
}
