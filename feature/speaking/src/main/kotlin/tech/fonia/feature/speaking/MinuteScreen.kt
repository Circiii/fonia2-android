package tech.fonia.feature.speaking

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import tech.fonia.designsystem.theme.FoniaShell
import tech.fonia.designsystem.theme.FoniaTheme
import tech.fonia.designsystem.tokens.FoniaSpace
import tech.fonia.speaking.FlaggedSpan
import tech.fonia.speaking.ProsodicTranscript
import tech.fonia.speaking.SpeakingAudio
import tech.fonia.speaking.SpeakingFixtures
import tech.fonia.speaking.SpeakingPipeline
import tech.fonia.speaking.SpeakingText
import tech.fonia.speech.Transcriber

@Composable
fun rememberMinuteState(
    audio: SpeakingAudio,
    transcriber: Transcriber? = null,
    topics: List<String> = SpeakingStrings.TOPICS,
    startRung: Int = 0,
    onSelfView: ((rung: Int, view: SelfView) -> Unit)? = null,
): MinuteState {
    val scope = rememberCoroutineScope()
    val state = remember(audio, transcriber) {
        MinuteState(scope, audio, SpeakingPipeline(transcriber = transcriber), topics, startRung, onSelfView = onSelfView)
    }
    DisposableEffect(state) { onDispose { state.close() } }
    return state
}

/**
 * MINUTE, „Un minut pe zi” (plan 01 §10): a topic from the 7-step ladder, one take, „Cum ți s-a părut?”, then the
 * prosodic transcript (thickness = loudness, baseline = pitch, gap = pause, fillers struck) with the measured numbers,
 * one thing that went well and one to try. No score.
 */
@Composable
fun MinuteScreen(state: MinuteState, modifier: Modifier = Modifier, onDone: () -> Unit = {}) {
    val ui by state.ui.collectAsState()
    val clock = rememberPlaybackClock(ui.transcript, ui.playing) { state.position() }
    ScreenColumn(SpeakingStrings.MINUTE_TITLE, modifier) {
        when (ui.step) {
            MinuteStep.Prepare -> PreparePane(ui.topic, ui.message, onStart = state::start) {
                BasicText(SpeakingStrings.step(ui.rung + 1, ui.topics), style = FoniaTheme.type.label.copy(color = FoniaTheme.colors.inkSecondary))
                Row(horizontalArrangement = Arrangement.spacedBy(FoniaSpace.s)) {
                    if (ui.rung > 0) QuietButton(SpeakingStrings.STEP_BACK, { state.moveRung(-1) })
                    if (ui.rung < ui.topics - 1) QuietButton(SpeakingStrings.NEXT_STEP, { state.moveRung(1) })
                }
            }
            MinuteStep.Recording -> RecordingPane(ui.topic, state.levels, state.maxRecordSeconds, onDone = state::finishRecording)
            MinuteStep.Thinking -> ThinkingLine()
            MinuteStep.SelfCheck -> SelfCheckPane(state::answer)
            MinuteStep.Result -> ui.transcript?.let { t ->
                MinuteResult(t, ui.spans, ui.lines, ui.worked, ui.toTry, clock, ui.playing, onListen = state::listen, onStop = state::stopPlayback, onAgain = state::again, onDone = onDone)
            }
        }
    }
}

@Composable
internal fun SelfCheckPane(onAnswer: (SelfView) -> Unit) {
    BasicText(SpeakingStrings.HOW_WAS_IT, Modifier.semantics { heading() }, style = FoniaTheme.type.title2.copy(color = FoniaTheme.colors.ink))
    Column(verticalArrangement = Arrangement.spacedBy(FoniaSpace.s)) {
        SecondaryButton(SpeakingStrings.SELF_HARD, { onAnswer(SelfView.Hard) }, Modifier.fillMaxWidth())
        SecondaryButton(SpeakingStrings.SELF_SOSO, { onAnswer(SelfView.SoSo) }, Modifier.fillMaxWidth())
        SecondaryButton(SpeakingStrings.SELF_EASY, { onAnswer(SelfView.Easy) }, Modifier.fillMaxWidth())
    }
}

/** The prosodic transcript alone with the summary numbers (also usable outside the MINUTE flow). */
@Composable
fun MinuteResult(
    transcript: ProsodicTranscript,
    spans: List<FlaggedSpan>,
    lines: List<String>,
    worked: String?,
    toTry: String?,
    clock: PlaybackClock,
    playing: Boolean,
    onListen: () -> Unit,
    onStop: () -> Unit,
    onAgain: () -> Unit,
    onDone: () -> Unit,
) {
    val c = FoniaTheme.colors
    // Fillers are thin, struck words here too; nothing is toggled in MINUTE.
    ProsodicTranscriptView(transcript, spans, spans.filter { it.cut }.map { it.id }.toSet(), clock, Modifier.fillMaxWidth())
    BasicText(SpeakingStrings.TRANSCRIPT_LEGEND, style = FoniaTheme.type.caption.copy(color = c.inkSecondary))
    Column(verticalArrangement = Arrangement.spacedBy(FoniaSpace.xxs)) {
        for (l in lines) BasicText(l, style = FoniaTheme.type.body.copy(color = c.ink))
    }
    if (worked != null) {
        BasicText(SpeakingStrings.WORKED, style = FoniaTheme.type.label.copy(color = c.inkSecondary))
        BasicText(worked, style = FoniaTheme.type.body.copy(color = c.ink))
    }
    if (toTry != null) {
        BasicText(SpeakingStrings.TO_TRY, style = FoniaTheme.type.label.copy(color = c.inkSecondary))
        BasicText(toTry, style = FoniaTheme.type.body.copy(color = c.ink))
    }
    PrimaryButton(SpeakingStrings.AGAIN_SHORTER, onAgain, Modifier.fillMaxWidth())
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FoniaSpace.s)) {
        SecondaryButton(if (playing) SpeakingStrings.STOP else SpeakingStrings.LISTEN, if (playing) onStop else onListen, Modifier.weight(1f))
        SecondaryButton(SpeakingStrings.FINISH, onDone, Modifier.weight(1f))
    }
}

@Preview(name = "MINUTE · result", widthDp = 380, heightDp = 900, showBackground = true)
@Composable
private fun PreviewMinuteResult() {
    val s = SpeakingFixtures.sample()
    val sum = s.transcript.summary
    FoniaTheme(shell = FoniaShell.Grown) {
        ScreenColumn(SpeakingStrings.MINUTE_TITLE) {
            MinuteResult(s.transcript, s.spans, SpeakingText.lines(sum), SpeakingText.worked(sum), SpeakingText.toTry(sum), remember { PlaybackClock() }, false, {}, {}, {}, {})
        }
    }
}

@Preview(name = "MINUTE · how was it", widthDp = 380, heightDp = 500, showBackground = true)
@Composable
private fun PreviewSelfCheck() {
    FoniaTheme(shell = FoniaShell.Grown) { ScreenColumn(SpeakingStrings.MINUTE_TITLE) { SelfCheckPane {} } }
}
