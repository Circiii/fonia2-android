package tech.fonia.app.speaking

import android.Manifest
import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tech.fonia.app.AppGraph
import tech.fonia.app.shell.MicPermission
import tech.fonia.app.text.T
import tech.fonia.app.ui.FoniaSheet
import tech.fonia.app.ui.RoundGlyphButton
import tech.fonia.app.ui.SheetBody
import tech.fonia.app.ui.SheetTitle
import tech.fonia.designsystem.components.ContinueButton
import tech.fonia.designsystem.components.ContinueStyle
import tech.fonia.designsystem.theme.FoniaShell
import tech.fonia.designsystem.theme.FoniaTheme
import tech.fonia.designsystem.tokens.FoniaSpace
import tech.fonia.feature.speaking.MinuteScreen
import tech.fonia.feature.speaking.NoUmScreen
import tech.fonia.feature.speaking.NoUmStep
import tech.fonia.feature.speaking.Version
import tech.fonia.feature.speaking.rememberMinuteState
import tech.fonia.feature.speaking.rememberNoUmState
import tech.fonia.speaking.SpeakingThresholds

/** Which speaking screen a host draws. */
enum class SpeakingKind(val raw: String) { NoUm("noum"), Minute("minute") }

/**
 * NO-UM / MINUTE in the app (chapter 01 §10): the grown shell (`FoniaTheme(shell = Grown)`), a back control, the
 * microphone asked for before the screen starts (a refusal returns to TEEN-HOME), Whisper when its model is on the
 * phone ([AppGraph.transcriber]; acoustic-only otherwise), and „Păstrează” as the only way a take is stored.
 *
 * Debug fixture (`--es fixture fillers`, or the demo): the screen starts by itself, analyses the bundled take, shows
 * the transcript and plays „fără ăăă” ([autoPlay]).
 */
@Composable
fun SpeakingHost(graph: AppGraph, kind: SpeakingKind, topic: String?, onDone: () -> Unit) {
    val context = LocalContext.current
    val perms = graph.permissions
    val reduce = FoniaTheme.reduceMotion
    var micReady by remember { mutableStateOf(graph.fixtureMode || perms.granted) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        perms.onResult(granted, context as? Activity)
        if (granted) micReady = true else onDone()
    }

    FoniaTheme(shell = FoniaShell.Grown, reduceMotion = reduce) {
        val c = FoniaTheme.colors
        Box(Modifier.fillMaxSize().background(c.bg)) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars)) {
                Row(
                    Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = FoniaSpace.pageGrown, vertical = FoniaSpace.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoundGlyphButton("←", T.adultBack, onClick = onDone)
                    Spacer(Modifier.width(FoniaSpace.s))
                    val note = when {
                        graph.fixtureMode -> T.speakFixture
                        graph.whisperReady -> T.teenWhisperOn
                        else -> null
                    }
                    if (note != null) BasicText(note, style = FoniaTheme.type.caption.copy(color = c.inkSecondary), modifier = Modifier.weight(1f))
                }
                Box(Modifier.weight(1f)) {
                    if (micReady) {
                        when (kind) {
                            SpeakingKind.NoUm -> NoUm(graph, topic, onDone)
                            SpeakingKind.Minute -> Minute(graph, onDone)
                        }
                    }
                }
            }
            if (!micReady) {
                BackHandler { onDone() }
                val forever = perms.mic.value == MicPermission.DeniedForever
                FoniaSheet(true, onDismiss = onDone, dismissible = true) {
                    SheetTitle(T.speakMicTitle)
                    SheetBody(if (forever) T.speakMicDenied else T.speakMicBody)
                    if (forever) {
                        ContinueButton(T.micOpenSettings, onClick = { (context as? Activity)?.let(perms::openSettings) }, arrow = false)
                    } else {
                        ContinueButton(T.speakMicAllow, onClick = { launcher.launch(Manifest.permission.RECORD_AUDIO) }, arrow = false)
                    }
                    ContinueButton(T.speakMicLater, style = ContinueStyle.Outlined, arrow = false, onClick = onDone)
                }
            }
        }
    }
}

@Composable
private fun NoUm(graph: AppGraph, topic: String?, onDone: () -> Unit) {
    var kept by remember { mutableStateOf(false) }
    val transcriber = remember { graph.transcriber() }
    val state = rememberNoUmState(graph.speakingAudio, transcriber, topic) { original, clean ->
        graph.scope.launch {
            runCatching { graph.speakingTakes.keep("noum", original, clean, SpeakingThresholds().version) }
            kept = true
        }
    }
    BackHandler { onDone() }
    if (graph.fixtureMode) {
        // the fixture: start, wait for the transcript, then play the cleaned take (what a person would tap)
        LaunchedEffect(state) {
            delay(1200)
            state.start()
            state.ui.first { it.step == NoUmStep.Transcript }
            delay(2600)
            state.listen(Version.Clean)
            if (graph.flags.demo) {
                delay(400)
                state.ui.first { it.playing == null }
                delay(1500)
                onDone()
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        NoUmScreen(state, Modifier.fillMaxSize(), onDone = onDone)
        KeptNote(kept) { kept = false }
    }
}

@Composable
private fun Minute(graph: AppGraph, onDone: () -> Unit) {
    val transcriber = remember { graph.transcriber() }
    val state = rememberMinuteState(graph.speakingAudio, transcriber, startRung = graph.settings.minuteRung) { rung, _ ->
        graph.settings.updateMinuteRung(rung)
    }
    BackHandler { onDone() }
    MinuteScreen(state, Modifier.fillMaxSize(), onDone = onDone)
}

/** „Păstrat pe telefon.” for two seconds after „Păstrează”. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.KeptNote(visible: Boolean, onGone: () -> Unit) {
    val c = FoniaTheme.colors
    LaunchedEffect(visible) { if (visible) { delay(2200); onGone() } }
    AnimatedVisibility(visible, Modifier.align(Alignment.BottomCenter).padding(bottom = FoniaSpace.l), enter = fadeIn(), exit = fadeOut()) {
        BasicText(
            T.speakKept,
            style = FoniaTheme.type.label.copy(color = c.surface),
            modifier = Modifier.background(c.ink.copy(alpha = 0.8f), RoundedCornerShape(50)).padding(horizontal = FoniaSpace.m, vertical = 10.dp),
        )
    }
}
