package tech.fonia.features.session

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tech.fonia.audio.LiveLevels
import tech.fonia.design.Field
import tech.fonia.design.Foni
import tech.fonia.design.FoniMood
import tech.fonia.design.FoniTone
import tech.fonia.design.FoniaIcon
import tech.fonia.design.FoniaIconView
import tech.fonia.design.FoniaText
import tech.fonia.design.Palette
import tech.fonia.design.VoiceBars
import tech.fonia.design.VoiceRings
import tech.fonia.design.fButton
import tech.fonia.design.ping
import tech.fonia.features.app.AppModel
import tech.fonia.features.app.LocalStillMeters
import tech.fonia.features.app.isChild

/**
 * The waiting state under the prompt: Foni in the rings of the voice, then the sheet at the bottom with the live meters
 * right above one line that always says whose turn it is.
 */
@Composable
internal fun ListeningBar(
    sound: tech.fonia.domain.SoundID?,
    /** Set when this take shows no meter but the session's others do: its room stays, empty. */
    reservedMeter: tech.fonia.domain.SoundID?,
    drive: tech.fonia.designsystem.letter.LetterDrive,
    phase: SpeakPhase,
    field: Field,
    isChild: Boolean,
    showsHeardColour: Boolean,
    app: AppModel,
    line: String?,
    countdown: Int?,
    heard: HeardLevel,
    onStop: () -> Unit,
) {
    val levels = app.audio.levels
    val still = LocalStillMeters.current
    val level: () -> Float = if (phase == SpeakPhase.Model) ({ levels.talk }) else ({ levels.level })
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // The rings are wider than the row: they spread over the prompt above and the sheet below, under both.
        Box(Modifier.fillMaxWidth().height(124.dp), contentAlignment = Alignment.Center) {
            VoiceRings(
                color = FoniTone(field).glow,
                isLive = (phase == SpeakPhase.Listening || phase == SpeakPhase.Model) && !still,
                level = level,
                modifier = Modifier.requiredSize(180.dp),
                heard = if (showsHeardColour && phase == SpeakPhase.Listening) ({ levels.heardColour }) else null,
            )
            Foni(Modifier.size(124.dp), mood = if (phase == SpeakPhase.Listening) FoniMood.listening else FoniMood.idle, level = level)
            // Over Foni rather than under the line, so the sheet below keeps its size when it shows.
            NoMicCue(app, field, listening = phase == SpeakPhase.Listening, modifier = Modifier.align(Alignment.BottomCenter))
        }
        DockSheet {
            if (sound != null) {
                LiveMeters(sound, drive, listening = phase == SpeakPhase.Listening)
            } else if (reservedMeter != null) {
                LiveMeters(reservedMeter, remember { tech.fonia.designsystem.letter.LetterDrive() }, listening = false, modifier = Modifier.alpha(0f))
            }
            TurnLine(
                mode = when (phase) {
                    SpeakPhase.Listening -> TurnMode.Listening
                    SpeakPhase.Thinking -> TurnMode.Thinking
                    SpeakPhase.Heard -> TurnMode.Finished(HEARD, praised = false)
                    else -> TurnMode.Model
                },
                field = field,
                height = DockMetrics.rowHeight(isChild),
                heard = heard,
                level = { levels.level },
                line = line,
                countdown = countdown,
                onStop = onStop,
            )
        }
    }
}

/** What the turn line says: Foni talks, the phone listens, it works the take out, or a held sound has ended. */
internal sealed interface TurnMode {
    data object Model : TurnMode

    data object Listening : TurnMode

    data object Thinking : TurnMode

    data class Finished(val line: String, val praised: Boolean) : TurnMode
}

/** iOS `SessionVoice.thinking`. */
internal const val THINKING = "Mă gândesc"

/** iOS `SessionVoice.heard`: a take at automatizare whose verdict stays hidden. A recorded line. */
internal const val HEARD = "Te-am auzit"

/**
 * Whose turn it is, at the bottom of the sheet. Each state looks different without sound or reading: the speaker on a
 * pale disc while Foni talks, a solid disc with the microphone and a ring round the capsule while the phone listens,
 * the hourglass while it works the take out. The words change in place, the old ones gone before the new ones come.
 * [line] replaces "Mă gândesc" while thinking (a retake's line), with its [countdown].
 */
@Composable
internal fun TurnLine(
    mode: TurnMode,
    field: Field,
    height: Dp,
    heard: HeardLevel,
    level: () -> Float,
    line: String? = null,
    countdown: Int? = null,
    onStop: (() -> Unit)? = null,
) {
    val listening = mode == TurnMode.Listening
    val still = LocalStillMeters.current
    val reduceMotion = tech.fonia.design.LocalReduceMotion.current
    val density = LocalDensity.current
    val title = when (mode) {
        TurnMode.Model -> "Ascultă"
        TurnMode.Listening -> if (heard.isHeard) "Te aud" else "Acum tu"
        TurnMode.Thinking -> line ?: THINKING
        is TurnMode.Finished -> mode.line
    }
    val icon = when (mode) {
        TurnMode.Model -> FoniaIcon.speaker
        TurnMode.Listening -> FoniaIcon.microphone
        TurnMode.Thinking -> if (line != null) FoniaIcon.retry else FoniaIcon.hourglass
        is TurnMode.Finished -> if (mode.praised) FoniaIcon.star else FoniaIcon.ear
    }
    val edge by animateDpAsState(if (listening) 2.dp else 1.5.dp, tween(160), label = "edge")
    val edgeColor by animateColorAsState(if (listening) field.deep.copy(alpha = 0.85f) else Palette.line, tween(160), label = "edgeColor")
    val disc by animateColorAsState(if (listening) field.deep else Palette.cloud, tween(160), label = "disc")
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = height)
                .background(Palette.paper, RoundedCornerShape(50))
                .border(edge, edgeColor, RoundedCornerShape(50))
                .padding(start = 4.dp, end = 16.dp)
                .semantics(mergeDescendants = true) {},
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The ring grows inside this square and never past it, so it can't reach the words.
            Box(
                Modifier
                    .size(HEAR_REACH)
                    .drawBehind {
                        val e = if (listening) heard.value else 0f
                        if (e <= 0.005f) return@drawBehind
                        val r = 20.dp.toPx()
                        val most = HEAR_REACH.toPx() / 40.dp.toPx()
                        val k = (e * 1.8f).coerceAtMost(1f)
                        drawCircle(Palette.accent.copy(alpha = 0.22f + 0.4f * k), radius = r * (0.8f + (most - 0.8f) * k))
                        val stroke = 3.dp.toPx()
                        drawCircle(
                            Palette.accent.copy(alpha = 0.55f * k),
                            radius = r * (1f + (most - 1f) * k) - stroke / 2f,
                            style = Stroke(width = stroke),
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                // Two green rings leave the disc the moment the mic opens: it is listening now.
                Box(
                    Modifier
                        .size(40.dp)
                        .ping(trigger = listening, color = Palette.accent.copy(alpha = 0.5f), enabled = listening, count = 2)
                        .background(disc, CircleShape),
                )
                AnimatedContent(targetState = icon, transitionSpec = { DockMotion.swap(reduceMotion, density) }, label = "turnIcon") { shown ->
                    FoniaIconView(shown, size = 22f, tint = if (listening) Palette.buttonLabel else field.deep)
                }
            }
            // One line in one place: the old words leave before the new ones come in.
            AnimatedContent(
                targetState = title,
                modifier = Modifier.weight(1f),
                transitionSpec = { DockMotion.swap(reduceMotion, density) },
                contentAlignment = Alignment.CenterStart,
                label = "turnTitle",
            ) { shown ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FoniaText(shown, style = fButton, color = Palette.ink, maxLines = 2)
                    if (mode == TurnMode.Thinking && line != null && countdown != null) {
                        FoniaText("$countdown", style = fButton, color = field.deep)
                    }
                }
            }
            // Always laid out, shown only while listening: the line keeps its length in every state.
            VoiceBars(
                isLive = listening && !still,
                color = field.deep,
                level = level,
                modifier = Modifier.width(84.dp).height(28.dp).alpha(if (listening) 1f else 0f).clearAndSetSemantics {},
            )
        }
        if (listening && onStop != null) {
            Box(
                Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(50))
                    .clickable(role = Role.Button, onClick = onStop)
                    .semantics { contentDescription = "Am terminat" },
            )
        }
    }
}

/** The square the "Te aud" ring may fill at its loudest, round a 40 dp disc. */
private val HEAR_REACH = 56.dp

/** What the microphone hears, as the colour of the sound's family. Null while it isn't a hiss. */
val LiveLevels.heardColour: Color?
    get() {
        if (!isFricative) return null
        return if (sNess >= 0.5f) FoniTone.cold.glow else FoniTone.warm.glow
    }
