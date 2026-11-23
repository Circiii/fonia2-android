package tech.fonia.feature.speaking

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import tech.fonia.designsystem.theme.FoniaShell
import tech.fonia.designsystem.theme.FoniaTheme
import tech.fonia.designsystem.tokens.FoniaSpace
import tech.fonia.live.Clip
import tech.fonia.speaking.CleanResult
import tech.fonia.speaking.SpeakingAudio
import tech.fonia.speaking.SpeakingFixtures
import tech.fonia.speaking.SpeakingPipeline
import tech.fonia.speaking.SpeakingText
import tech.fonia.speech.Transcriber

/**
 * Creates the NO-UM state for a screen and closes it (stops audio, drops the takes) when the screen leaves.
 * [transcriber] null = sound-only analysis (hesitations only; no markers, repetitions or false starts).
 */
@Composable
fun rememberNoUmState(
    audio: SpeakingAudio,
    transcriber: Transcriber? = null,
    topic: String? = null,
    onKeep: ((original: Clip, clean: CleanResult) -> Unit)? = null,
): NoUmState {
    val scope = rememberCoroutineScope()
    val state = remember(audio, transcriber) {
        NoUmState(scope, audio, SpeakingPipeline(transcriber = transcriber), topic = topic, onKeep = onKeep)
    }
    DisposableEffect(state) { onDispose { state.close() } }
    return state
}

/**
 * NO-UM, „Tu, fără ăăă” (plan 01 §10): prompt → record (live wave, 60 s ring) → „Mă gândesc” → the prosodic
 * transcript with the fillers struck in place → „Ascultă-te fără ăăă” with the A/B switch → „Acum spune-o tu așa” →
 * „Umpluturi: 3 → 0”. Expects `FoniaTheme(shell = FoniaShell.Grown)` around it.
 */
@Composable
fun NoUmScreen(state: NoUmState, modifier: Modifier = Modifier, onDone: () -> Unit = {}) {
    val ui by state.ui.collectAsState()
    val clock = rememberPlaybackClock(ui.take?.transcript, ui.playing != null) { state.position() }
    ScreenColumn(SpeakingStrings.NO_UM_TITLE, modifier) {
        when (ui.step) {
            NoUmStep.Prepare -> PreparePane(ui.topic ?: SpeakingStrings.FREE_TOPIC, ui.message, onStart = state::start)
            NoUmStep.Recording, NoUmStep.SecondTake -> RecordingPane(
                if (ui.step == NoUmStep.SecondTake) SpeakingStrings.SAY_IT_AGAIN else ui.topic ?: SpeakingStrings.FREE_TOPIC,
                state.levels, state.maxRecordSeconds, onDone = state::finishRecording,
            )
            NoUmStep.Thinking, NoUmStep.SecondThinking -> ThinkingLine(Modifier.padding(vertical = FoniaSpace.xl))
            NoUmStep.Transcript -> ui.take?.let { take ->
                TranscriptPane(
                    take, ui.playing, ui.version, clock, ui.busy,
                    onToggle = state::toggleCut, onListen = { state.listen() }, onStop = state::stopPlayback,
                    onVersion = state::setVersion, onSayItAgain = state::sayItAgain,
                )
            }
            NoUmStep.Result -> ResultPane(
                ui.comparison.orEmpty(), ui.measured, canKeep = state.canKeep,
                onAgain = state::again, onDone = onDone, onKeep = state::keep,
            )
        }
    }
}

@Composable
internal fun ScreenColumn(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = FoniaTheme.colors
    Column(
        modifier
            .fillMaxSize()
            .background(c.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = FoniaSpace.pageGrown, vertical = FoniaSpace.m),
        verticalArrangement = Arrangement.spacedBy(FoniaSpace.m),
    ) {
        BasicText(title, style = FoniaTheme.type.title.copy(color = c.ink))
        content()
    }
}

@Composable
internal fun PreparePane(topic: String, message: String?, onStart: () -> Unit, extra: @Composable () -> Unit = {}) {
    val c = FoniaTheme.colors
    BasicText(topic, style = FoniaTheme.type.title2.copy(color = c.ink))
    BasicText(SpeakingStrings.PREPARE, style = FoniaTheme.type.body.copy(color = c.inkSecondary))
    if (message != null) {
        BasicText(message, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = FoniaTheme.type.headline.copy(color = c.accentDeep))
    }
    extra()
    Spacer(Modifier.height(FoniaSpace.l))
    PrimaryButton(SpeakingStrings.START, onStart, Modifier.fillMaxWidth())
}

@Composable
internal fun RecordingPane(topic: String, levels: LevelHistory, maxSeconds: Double, onDone: () -> Unit) {
    val c = FoniaTheme.colors
    BasicText(topic, style = FoniaTheme.type.headline.copy(color = c.inkSecondary))
    RecordingRing(levels, maxSeconds, Modifier.widthIn(max = 320.dp).centred())
    PrimaryButton(SpeakingStrings.DONE_TALKING, onDone, Modifier.fillMaxWidth())
}

/** Centres a child of the screen column. */
private fun Modifier.centred(): Modifier = this.then(Modifier.fillMaxWidth().padding(horizontal = FoniaSpace.l))

@Composable
internal fun TranscriptPane(
    take: TakeView,
    playing: Version?,
    version: Version,
    clock: PlaybackClock,
    busy: Boolean,
    onToggle: (Int) -> Unit,
    onListen: () -> Unit,
    onStop: () -> Unit,
    onVersion: (Version) -> Unit,
    onSayItAgain: () -> Unit,
) {
    val c = FoniaTheme.colors
    BasicText(take.headline, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = FoniaTheme.type.headline.copy(color = c.ink))
    BasicText(SpeakingStrings.TAP_HINT, style = FoniaTheme.type.caption.copy(color = c.inkSecondary))
    ProsodicTranscriptView(
        take.transcript, take.spans, take.cuts, clock,
        modifier = Modifier.fillMaxWidth(), timeMap = take.timeMap, onToggle = onToggle,
    )
    BasicText(SpeakingStrings.TRANSCRIPT_LEGEND, style = FoniaTheme.type.caption.copy(color = c.inkSecondary))
    if (playing == null) {
        PrimaryButton(if (version == Version.Clean) SpeakingStrings.LISTEN_CLEAN else SpeakingStrings.LISTEN, onListen, Modifier.fillMaxWidth(), enabled = !busy)
    } else {
        SecondaryButton(SpeakingStrings.STOP, onStop, Modifier.fillMaxWidth())
    }
    VersionSwitch(version, onVersion, Modifier.fillMaxWidth())
    BasicText(take.cleanedLine, style = FoniaTheme.type.body.copy(color = c.ink))
    SecondaryButton(SpeakingStrings.SAY_IT_AGAIN, onSayItAgain, Modifier.fillMaxWidth())
}

@Composable
internal fun ResultPane(comparison: String, measured: String?, canKeep: Boolean, onAgain: () -> Unit, onDone: () -> Unit, onKeep: () -> Unit) {
    val c = FoniaTheme.colors
    BasicText(comparison, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = FoniaTheme.type.counter.copy(color = c.ink))
    if (measured != null) BasicText(measured, style = FoniaTheme.type.body.copy(color = c.ink))
    Spacer(Modifier.height(FoniaSpace.m))
    PrimaryButton(SpeakingStrings.AGAIN, onAgain, Modifier.fillMaxWidth())
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FoniaSpace.s), verticalAlignment = Alignment.CenterVertically) {
        SecondaryButton(SpeakingStrings.FINISH, onDone, Modifier.weight(1f))
        if (canKeep) QuietButton(SpeakingStrings.KEEP, onKeep)
    }
    BasicText(SpeakingStrings.KEPT_NOTE, style = FoniaTheme.type.caption.copy(color = c.inkSecondary))
}

// ——— Previews (fixture data, no audio) ———

@Preview(name = "NO-UM · transcript", widthDp = 380, heightDp = 860, showBackground = true)
@Composable
private fun PreviewTranscript() {
    val s = SpeakingFixtures.sample()
    val take = TakeView(
        s.transcript, s.spans, s.spans.filter { it.cut }.map { it.id }.toSet(),
        "6 umpluturi · 1 pauză de 0,9 s", SpeakingText.cleaned(s.stats), s.timeMap, s.stats,
    )
    FoniaTheme(shell = FoniaShell.Grown) {
        ScreenColumn(SpeakingStrings.NO_UM_TITLE) {
            TranscriptPane(take, null, Version.Clean, remember { PlaybackClock() }, false, {}, {}, {}, {}, {})
        }
    }
}

@Preview(name = "NO-UM · prepare", widthDp = 380, heightDp = 600, showBackground = true)
@Composable
private fun PreviewPrepare() {
    FoniaTheme(shell = FoniaShell.Grown) {
        ScreenColumn(SpeakingStrings.NO_UM_TITLE) { PreparePane(SpeakingStrings.FREE_TOPIC, SpeakingStrings.SAY_MORE, {}) }
    }
}

@Preview(name = "NO-UM · recording", widthDp = 380, heightDp = 700, showBackground = true)
@Composable
private fun PreviewRecording() {
    val levels = remember { LevelHistory().apply { reset(); repeat(96) { push((0.2f + 0.6f * kotlin.math.abs(kotlin.math.sin(it * 0.4f))), true, 0.02) } } }
    FoniaTheme(shell = FoniaShell.Grown) {
        ScreenColumn(SpeakingStrings.NO_UM_TITLE) { RecordingPane(SpeakingStrings.TOPICS[0], levels, 60.0) {} }
    }
}

@Preview(name = "NO-UM · result", widthDp = 380, heightDp = 600, showBackground = true)
@Composable
private fun PreviewResult() {
    FoniaTheme(shell = FoniaShell.Grown) {
        ScreenColumn(SpeakingStrings.NO_UM_TITLE) {
            ResultPane(SpeakingText.comparison(6, 1), "Ai făcut 6 pauze, niciuna prea lungă.", true, {}, {}, {})
        }
    }
}
