package tech.fonia.features.grownups

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.fonia.design.Field
import tech.fonia.design.FoniButtonStyle
import tech.fonia.design.FoniaText
import tech.fonia.design.SessionBar
import tech.fonia.design.StyledButton
import tech.fonia.design.TextRole
import tech.fonia.design.UIWeight
import tech.fonia.design.easeInOut
import tech.fonia.design.fDisplay
import tech.fonia.design.letterPoster
import tech.fonia.design.ui
import tech.fonia.dsp.FricativeSegment
import tech.fonia.dsp.SibilantPlace
import tech.fonia.dsp.analyzeClip
import tech.fonia.features.app.AppModel
import tech.fonia.features.app.LocalDismiss
import tech.fonia.features.app.SoundEffect
import tech.fonia.features.app.SystemNavigationBar
import tech.fonia.features.app.dspGroup
import java.util.UUID
import kotlin.math.min

@Suppress("EnumEntryName")
private enum class Phase { ready, listening, review, missed, done }

private class Step(val letter: String, val say: String, val place: SibilantPlace, val field: Field)

private val steps = (0 until 10).map { i ->
    if (i % 2 == 0) Step("S", "Ssssss", SibilantPlace.S, Field.cold) else Step("Ș", "Șșșșșș", SibilantPlace.SH, Field.warm)
}

/**
 * Every voice puts the line between S and Ș somewhere else. Ten sounds with an adult listening let the phone learn
 * where it sits for this person. Only sounds the adult says are good are kept.
 */
@Composable
fun CalibrationScreen(profileID: UUID, app: AppModel) {
    var index by rememberSaveable { mutableStateOf(0) }
    var phase by rememberSaveable { mutableStateOf(Phase.ready) }
    var segment by remember { mutableStateOf<FricativeSegment?>(null) }
    val scope = rememberCoroutineScope()
    val dismiss = LocalDismiss.current
    val step = steps[min(index, steps.size - 1)]
    val field = if (phase == Phase.done) Field.night else step.field
    val profile = app.profiles.firstOrNull { it.id == profileID }

    // The back button is hidden while it listens, and with it the way back.
    BackHandler(enabled = phase == Phase.listening) {}
    DisposableEffect(Unit) {
        onDispose { app.scope.launch { app.audio.shutDown() } }
    }

    suspend fun listen() {
        if (!app.audio.requestMicrophone()) {
            phase = Phase.missed
            return
        }
        val current = profile ?: return
        phase = Phase.listening
        // Heard without the stored boundary, so an old calibration can't colour the new one.
        try {
            app.audio.startListening(group = current.dspGroup, calibration = null, fixtureText = step.say)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
        }
        app.audio.waitForTakeEnd(silence = 0.5, startTimeout = 5.0, maximum = 4.0)
        val clip = app.audio.stopListening()
        if (clip == null) {
            phase = Phase.missed
            return
        }
        // `TakeJudge.summarise` is `analyzeClip`, off the main thread.
        val summary = withContext(Dispatchers.Default) { analyzeClip(clip.samples, clip.sampleRate, current.dspGroup, null) }
        segment = summary.fricatives.maxByOrNull { it.end - it.start }
        phase = if (segment == null) Phase.missed else Phase.review
    }

    fun accept() {
        val kept = segment
        if (kept != null && profile != null) {
            val place = step.place
            app.scope.launch { app.addAnchor(kept, place, profile) }
        }
        segment = null
        if (index + 1 >= steps.size) {
            phase = Phase.done
            app.audio.effect(SoundEffect.Star)
        } else {
            index += 1
            phase = Phase.ready
        }
    }

    val background by animateColorAsState(field.background, easeInOut(0.2), label = "field")
    Column(Modifier.fillMaxSize().background(background)) {
        SystemNavigationBar("", onBack = if (phase == Phase.listening) null else dismiss, topInset = true)
        AnimatedContent(
            targetState = Triple(index, phase, field),
            transitionSpec = { fadeIn(easeInOut(0.2)) togetherWith fadeOut(easeInOut(0.2)) },
            modifier = Modifier.weight(1f),
            label = "calibration",
        ) { (shownIndex, shownPhase, shownField) ->
            val shownStep = steps[min(shownIndex, steps.size - 1)]
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (shownPhase == Phase.done) {
                    Spacer(Modifier.weight(1f))
                    FoniaText("Gata", style = fDisplay, color = shownField.foreground)
                    FoniaText(
                        "De acum, telefonul ascultă S și Ș după vocea asta.",
                        style = ui(20, UIWeight.medium, TextRole.title3),
                        color = shownField.secondary,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.weight(1f))
                    StyledButton(onClick = dismiss, style = FoniButtonStyle(field = shownField)) { FoniaText("Închide") }
                } else {
                    SessionBar(
                        shownIndex.toDouble() / steps.size,
                        color = shownField.deep,
                        track = shownField.track,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    FoniaText(
                        shownStep.letter,
                        Modifier.semantics { contentDescription = "Sunetul ${shownStep.letter}" },
                        style = letterPoster(180),
                        color = shownField.deep,
                    )
                    val message = when (shownPhase) {
                        Phase.ready -> "Spune un ${shownStep.letter} lung, cam două secunde."
                        Phase.listening -> "Te ascult"
                        Phase.review -> "A sunat bine?"
                        Phase.missed -> "N-am auzit sunetul. Mai încercăm."
                        Phase.done -> ""
                    }
                    FoniaText(message, style = ui(20, UIWeight.medium, TextRole.title3), color = shownField.foreground, textAlign = TextAlign.Center)
                    Spacer(Modifier.weight(1f))
                    when (shownPhase) {
                        Phase.ready, Phase.missed -> StyledButton(onClick = { scope.launch { listen() } }, style = FoniButtonStyle(field = shownField)) {
                            FoniaText("Ascultă-mă")
                        }
                        Phase.listening -> Box(Modifier.fillMaxWidth().height(64.dp))
                        Phase.review -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            StyledButton(
                                onClick = { phase = Phase.ready },
                                style = FoniButtonStyle(field = shownField, size = FoniButtonStyle.Size.regular, kind = FoniButtonStyle.Kind.secondary),
                                modifier = Modifier.weight(1f),
                            ) { FoniaText("Mai încercăm") }
                            StyledButton(
                                onClick = { accept() },
                                style = FoniButtonStyle(field = shownField, size = FoniButtonStyle.Size.regular),
                                modifier = Modifier.weight(1f),
                            ) { FoniaText("Da, bine") }
                        }
                        Phase.done -> Unit
                    }
                }
            }
        }
    }
}
