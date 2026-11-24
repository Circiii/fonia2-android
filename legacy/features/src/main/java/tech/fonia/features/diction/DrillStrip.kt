package tech.fonia.features.diction

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.fonia.design.LocalReduceMotion
import tech.fonia.design.Palette
import tech.fonia.designsystem.letter.StickerText
import tech.fonia.designsystem.tokens.FoniaClassicSticker
import tech.fonia.designsystem.tokens.StickerSet
import tech.fonia.store.Attempt

private val PendingSticker = StickerSet("DrillPending", Color(0xFFF4F6F8), Color(0xFFC3CAD4), Color(0xFF8A94A6))

/**
 * Above a drill's take: its items as a row of stickers that light one by one as he says them well (green), stay soft
 * when they were not yet right, and the one on screen stands up. A twister shows its tempo ladder instead: turtle,
 * hare walking, hare running (drawn, not emoji).
 */
@Composable
fun DrillStrip(drill: Drill, current: Int, outcomes: Map<Int, Attempt.Outcome>, modifier: Modifier = Modifier) {
    val tempos = drill.tempos
    if (tempos != null) {
        Row(
            modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "Tempo ${current + 1} din ${tempos.size}" },
            horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tempos.forEachIndexed { i, tempo ->
                val state = stateOf(i, current, outcomes)
                val lift by animateFloatAsState(if (i == current) 1.15f else 1f, spring(0.5f, 380f), label = "tempo")
                TempoIcon(tempo, state, Modifier.size(54.dp).graphicsLayer { scaleX = lift; scaleY = lift })
            }
        }
        return
    }
    val list = rememberLazyListState()
    val reduce = LocalReduceMotion.current
    LaunchedEffect(current) {
        if (reduce) list.scrollToItem((current - 1).coerceAtLeast(0)) else list.animateScrollToItem((current - 1).coerceAtLeast(0))
    }
    LazyRow(
        modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "${current + 1} din ${drill.chips.size}" },
        state = list,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(drill.chips) { i, chip ->
            val state = stateOf(i, current, outcomes)
            val lift by animateFloatAsState(if (state == ChipState.Current) 1.12f else 1f, spring(0.5f, 380f), label = "chip")
            val sticker = when (state) {
                ChipState.Good -> FoniaClassicSticker.Good
                ChipState.Tried -> FoniaClassicSticker.Almost
                ChipState.Current -> FoniaClassicSticker.Cold
                ChipState.Pending -> PendingSticker
            }
            Box(Modifier.graphicsLayer { scaleX = lift; scaleY = lift }) {
                StickerText(chip, sticker, fontSize = if (chip.length > 4) 16.sp else 22.sp, weight = 900f)
            }
        }
    }
}

private enum class ChipState { Good, Tried, Current, Pending }

private fun stateOf(i: Int, current: Int, outcomes: Map<Int, Attempt.Outcome>): ChipState = when {
    outcomes[i] == Attempt.Outcome.Good -> ChipState.Good
    outcomes[i] != null && i != current -> ChipState.Tried
    i == current -> ChipState.Current
    else -> ChipState.Pending
}

@Composable
private fun TempoIcon(tempo: Tempo, state: ChipState, modifier: Modifier) {
    val ink = when (state) {
        ChipState.Good -> Palette.progress
        ChipState.Current -> Palette.accentText
        ChipState.Tried -> Palette.ink.copy(alpha = 0.6f)
        ChipState.Pending -> Palette.ink.copy(alpha = 0.3f)
    }
    Canvas(modifier) {
        when (tempo) {
            Tempo.Slow -> drawTurtle(ink)
            Tempo.Medium -> drawHare(ink, running = false)
            Tempo.Fast -> drawHare(ink, running = true)
        }
    }
}

/** A turtle: a domed shell with a seam, a round head, two stubby legs. */
private fun DrawScope.drawTurtle(ink: Color) {
    val w = size.width
    val h = size.height
    val stroke = Stroke(w * 0.07f, cap = StrokeCap.Round)
    drawArc(ink, 180f, 180f, useCenter = true, topLeft = Offset(w * 0.12f, h * 0.3f), size = Size(w * 0.6f, h * 0.6f))
    drawLine(ink, Offset(w * 0.08f, h * 0.6f), Offset(w * 0.78f, h * 0.6f), strokeWidth = w * 0.07f, cap = StrokeCap.Round)
    drawCircle(ink, w * 0.1f, Offset(w * 0.84f, h * 0.52f))
    drawLine(ink, Offset(w * 0.24f, h * 0.62f), Offset(w * 0.22f, h * 0.76f), strokeWidth = w * 0.08f, cap = StrokeCap.Round)
    drawLine(ink, Offset(w * 0.6f, h * 0.62f), Offset(w * 0.62f, h * 0.76f), strokeWidth = w * 0.08f, cap = StrokeCap.Round)
    drawArc(Color.White.copy(alpha = 0.55f), 200f, 140f, useCenter = false, topLeft = Offset(w * 0.24f, h * 0.4f), size = Size(w * 0.36f, h * 0.36f), style = stroke)
}

/** A hare: an oval body, a head with two long ears; running, stretched out with speed lines behind. */
private fun DrawScope.drawHare(ink: Color, running: Boolean) {
    val w = size.width
    val h = size.height
    val shift = if (running) w * 0.08f else 0f
    drawOval(ink, Offset(w * 0.2f + shift, h * 0.48f), Size(w * (if (running) 0.5f else 0.42f), h * 0.3f))
    drawCircle(ink, w * 0.12f, Offset(w * 0.72f + shift, h * 0.46f))
    drawLine(ink, Offset(w * 0.7f + shift, h * 0.38f), Offset(w * (if (running) 0.6f else 0.66f) + shift, h * 0.08f), strokeWidth = w * 0.08f, cap = StrokeCap.Round)
    drawLine(ink, Offset(w * 0.78f + shift, h * 0.38f), Offset(w * (if (running) 0.72f else 0.8f) + shift, h * 0.1f), strokeWidth = w * 0.08f, cap = StrokeCap.Round)
    drawCircle(ink, w * 0.07f, Offset(w * 0.2f + shift, h * 0.56f))
    if (running) {
        for (k in 0..2) {
            val y = h * (0.5f + 0.1f * k)
            drawLine(ink.copy(alpha = 0.5f), Offset(w * 0.0f, y), Offset(w * 0.16f, y), strokeWidth = w * 0.05f, cap = StrokeCap.Round)
        }
    } else {
        drawLine(ink, Offset(w * 0.3f, h * 0.76f), Offset(w * 0.28f, h * 0.88f), strokeWidth = w * 0.07f, cap = StrokeCap.Round)
        drawLine(ink, Offset(w * 0.52f, h * 0.76f), Offset(w * 0.54f, h * 0.88f), strokeWidth = w * 0.07f, cap = StrokeCap.Round)
    }
}
