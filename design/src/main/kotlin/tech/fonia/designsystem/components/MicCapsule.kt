package tech.fonia.designsystem.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import tech.fonia.designsystem.theme.FoniaTheme
import tech.fonia.designsystem.tokens.FoniaSpace
import kotlin.math.min

/** What the microphone is doing, said with a word and a shape, never with colour alone (D2 §7). */
enum class MicCapsuleState { Listen, YourTurn, Thinking, Off }

/**
 * The microphone's state, 64 dp tall: „Ascultă” (Foni shows; mic closed), „Acum tu” (open: a `voice` ring that fills
 * with the level, no pulse), „Mă gândesc” (three dots), off (slashed mic). [level] (0–1) and [nudge] (the 4-s silence
 * pulse) are read only in the draw phase, so a live level never recomposes anything.
 */
@Composable
fun MicCapsule(
    state: MicCapsuleState,
    label: String,
    modifier: Modifier = Modifier,
    level: () -> Float = { 0f },
    nudge: () -> Boolean = { false },
) {
    val c = FoniaTheme.colors
    val reduce = FoniaTheme.reduceMotion
    val t = rememberInfiniteTransition(label = "mic")
    val phase = if (reduce) null else t.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    val open = state == MicCapsuleState.YourTurn
    Row(
        modifier
            .heightIn(min = 64.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = label
                liveRegion = LiveRegionMode.Polite
            }
            .drawBehind {
                val r = CornerRadius(size.height / 2f)
                drawRoundRect(if (open) c.surface else c.fill, cornerRadius = r)
                val w = 2.dp.toPx()
                val pulse = open && nudge() && phase != null
                val ring = if (pulse) c.voice.copy(alpha = 0.35f + 0.65f * kotlin.math.abs(1f - 2f * (phase?.value ?: 0f))) else if (open) c.voice else c.separator
                drawRoundRect(ring, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), CornerRadius(r.x - w / 2), style = Stroke(w))
            }
            .padding(start = FoniaSpace.xs, end = FoniaSpace.l, top = FoniaSpace.xs, bottom = FoniaSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FoniaSpace.s),
    ) {
        Box(
            Modifier
                .size(48.dp)
                .drawBehind {
                    val cx = size.width / 2
                    val cy = size.height / 2
                    val radius = min(size.width, size.height) / 2
                    drawCircle(if (open) c.voice else c.surface, radius)
                    if (open) {
                        // the level ring: grows with the voice, never pulses by itself
                        val lv = level().coerceIn(0f, 1f)
                        drawCircle(c.voiceDeep, radius - 3.dp.toPx(), style = Stroke(3.dp.toPx()))
                        drawArc(
                            c.surface, -90f, 360f * lv, false, Offset(cx - radius + 3.dp.toPx(), cy - radius + 3.dp.toPx()),
                            Size(2 * (radius - 3.dp.toPx()), 2 * (radius - 3.dp.toPx())), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round),
                        )
                    }
                    when (state) {
                        MicCapsuleState.Thinking -> drawDots(c.accentDeep, phase?.value ?: 0f)
                        else -> drawMic(if (open) c.surface else if (state == MicCapsuleState.Off) c.inkSecondary else c.accentDeep, slashed = state == MicCapsuleState.Off, slashGap = if (open) c.voice else c.surface)
                    }
                },
        )
        BasicText(label, style = FoniaTheme.type.headline.copy(color = if (state == MicCapsuleState.Off) c.inkSecondary else c.ink))
    }
}

private fun DrawScope.drawMic(color: Color, slashed: Boolean, slashGap: Color) {
    val u = size.minDimension / 48f
    val cx = size.width / 2
    val top = size.height / 2 - 12 * u
    // capsule
    drawRoundRect(color, Offset(cx - 5 * u, top), Size(10 * u, 16 * u), CornerRadius(5 * u))
    // cradle
    val stroke = Stroke(2.4f * u, cap = StrokeCap.Round)
    drawArc(color, 0f, 180f, false, Offset(cx - 9 * u, top + 4 * u), Size(18 * u, 18 * u), style = stroke)
    drawLine(color, Offset(cx, top + 22 * u), Offset(cx, top + 26 * u), 2.4f * u, StrokeCap.Round)
    if (slashed) {
        drawLine(slashGap, Offset(cx - 11 * u, top - 1 * u), Offset(cx + 11 * u, top + 25 * u), 5f * u, StrokeCap.Round)
        drawLine(color, Offset(cx - 11 * u, top - 1 * u), Offset(cx + 11 * u, top + 25 * u), 2.4f * u, StrokeCap.Round)
    }
}

private fun DrawScope.drawDots(color: Color, phase: Float) {
    val u = size.minDimension / 48f
    val cy = size.height / 2
    for (i in 0..2) {
        val local = ((phase * 3f - i) % 3f + 3f) % 3f
        val lift = if (local < 1f) kotlin.math.sin(local * Math.PI).toFloat() else 0f
        drawCircle(color, 3.2f * u, Offset(size.width / 2 + (i - 1) * 10 * u, cy - 4 * u * lift))
    }
}
