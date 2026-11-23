package tech.fonia.designsystem.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import tech.fonia.designsystem.motion.pressBounce
import tech.fonia.designsystem.motion.rememberFoniaHaptics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.fonia.designsystem.letter.StickerText
import tech.fonia.designsystem.theme.FoniaTheme
import tech.fonia.designsystem.theme.alloFamily
import tech.fonia.designsystem.tokens.FoniaSticker

/** States of a node on PATH (D2 §7). */
enum class PathNodeState { Available, Current, Done, Locked }

/**
 * A sound on the road: the letter itself (not an icon) on a round sticker disc. Available = white disc, ink letter;
 * Current = the letter as a `Voice` sticker inside a `voice` ring, breathing twice when it appears; Done = a `Parrot`
 * ✓ badge; Locked = outlined disc with a drawn lock. [progress] (0–3) shows as filled vs hollow dots under the disc
 * (shape, not colour alone). Visual 72 dp, touch target 88 dp.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PathNode(
    letter: String,
    state: PathNodeState,
    progress: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    onLongClick: (() -> Unit)? = null,
    contentDescription: String = letter,
) {
    val c = FoniaTheme.colors
    val reduce = FoniaTheme.reduceMotion
    val source = remember { MutableInteractionSource() }
    val haptics = rememberFoniaHaptics()
    val breathe = remember { Animatable(1f) }
    LaunchedEffect(state, reduce) {
        if (state == PathNodeState.Current && !reduce) repeat(2) {
            breathe.animateTo(1.07f, tween(700, easing = FastOutSlowInEasing))
            breathe.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
        }
    }
    Column(
        modifier
            .size(width = size + 16.dp, height = size + 30.dp)
            .semantics(mergeDescendants = true) {
                this.contentDescription = contentDescription
                role = Role.Button
            }
            .combinedClickable(interactionSource = source, indication = null, onClick = { haptics.tap(); onClick() }, onLongClick = onLongClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        Box(
            Modifier
                .size(size + 8.dp)
                .pressBounce(source, pressedScale = 0.9f, enabled = state != PathNodeState.Locked)
                .graphicsLayer {
                    val s = breathe.value
                    scaleX = s; scaleY = s
                }
                .drawBehind {
                    val r = (size.toPx()) / 2f
                    val center = Offset(this.size.width / 2, this.size.height / 2)
                    // the sticker's own shadow (part of the drawing)
                    drawCircle(c.stickerShadow, r, center + Offset(0f, 4.dp.toPx()))
                    when (state) {
                        PathNodeState.Locked -> {
                            drawCircle(c.bg, r, center)
                            drawCircle(c.separator, r - 1.dp.toPx(), center, style = Stroke(2.dp.toPx()))
                        }
                        PathNodeState.Current -> {
                            drawCircle(c.surface, r, center)
                            drawCircle(c.voice, r - 2.5.dp.toPx(), center, style = Stroke(5.dp.toPx()))
                        }
                        PathNodeState.Done -> {
                            drawCircle(c.surface, r, center)
                            drawCircle(c.parrot, r - 1.5.dp.toPx(), center, style = Stroke(3.dp.toPx()))
                        }
                        PathNodeState.Available -> {
                            drawCircle(c.surface, r, center)
                            drawCircle(c.separator, r - 1.dp.toPx(), center, style = Stroke(2.dp.toPx()))
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            val fs = if (letter.length > 1) 24.sp else 34.sp
            when (state) {
                PathNodeState.Current -> StickerText(letter, FoniaSticker.Voice, fontSize = if (letter.length > 1) 22.sp else 30.sp, contentDescription = "")
                else -> BasicText(
                    letter,
                    style = TextStyle(
                        fontFamily = alloFamily(820f, 100f, fs.value), fontSize = fs, textAlign = TextAlign.Center,
                        color = if (state == PathNodeState.Locked) c.inkSecondary else c.ink,
                    ),
                )
            }
            if (state == PathNodeState.Done) {
                StickerText("✓", FoniaSticker.Parrot, fontSize = 16.sp, contentDescription = "", modifier = Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-6).dp))
            }
        }
        Row(Modifier.offset(y = 4.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(3) { i ->
                Box(
                    Modifier.size(9.dp).drawBehind {
                        if (i < progress) drawCircle(c.sunDeep) else drawCircle(c.separator, style = Stroke(1.5.dp.toPx()))
                    },
                )
            }
        }
    }
}
