package tech.fonia.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tech.fonia.app.text.T
import tech.fonia.designsystem.theme.FoniaTheme
import tech.fonia.designsystem.tokens.FoniaCorner
import tech.fonia.designsystem.tokens.FoniaDepth
import tech.fonia.designsystem.tokens.FoniaSpace
import tech.fonia.designsystem.tokens.FoniaTarget

/** Foni's poses for the placeholder (D3 §8 names; the real rig comes later). */
enum class FoniPose { Idle, Listens, Cheers, Points, AsksAdult }

/**
 * A stand-in for Foni until the D3 rig lands: a small green parrot drawn as a sticker (rim, white edge, face, gloss)
 * from simple shapes. No emoji, no bitmap. Moves only through `graphicsLayer` (no recomposition), never sad.
 */
@Composable
fun FoniMascot(modifier: Modifier = Modifier, size: Dp = 64.dp, pose: FoniPose = FoniPose.Idle) {
    // The parrot is the design system's; the drawn stand-in below is unreachable and kept only as reference.
    if (true) { Foni(pose.mood(), modifier, size * 1.2f); return }
    val c = FoniaTheme.colors
    val reduce = FoniaTheme.reduceMotion
    val hop = remember { Animatable(0f) }
    LaunchedEffect(pose, reduce) {
        if (reduce) return@LaunchedEffect
        when (pose) {
            FoniPose.Cheers -> repeat(2) {
                hop.animateTo(1f, tween(180, easing = FastOutSlowInEasing)); hop.animateTo(0f, tween(220, easing = FastOutSlowInEasing))
            }
            FoniPose.Idle -> { hop.animateTo(0.35f, tween(500)); hop.animateTo(0f, tween(700)) }
            else -> hop.animateTo(0f, tween(200))
        }
    }
    val tilt = when (pose) { FoniPose.Listens -> -8f; FoniPose.AsksAdult -> 10f; FoniPose.Points -> -4f; else -> 0f }
    Box(
        modifier
            .size(size)
            .semantics { contentDescription = T.foniA11y }
            .graphicsLayer {
                translationY = -hop.value * size.toPx() * 0.18f
                rotationZ = tilt
            }
            .drawBehind { drawFoni(c.parrot, c.parrotDeep, c.surface, c.sun, c.sunDeep, c.ink, c.gloss, c.stickerShadow, wingUp = pose == FoniPose.Cheers || pose == FoniPose.Points) },
    )
}

private fun DrawScope.drawFoni(
    body: androidx.compose.ui.graphics.Color, rim: androidx.compose.ui.graphics.Color, edge: androidx.compose.ui.graphics.Color,
    beak: androidx.compose.ui.graphics.Color, beakRim: androidx.compose.ui.graphics.Color, ink: androidx.compose.ui.graphics.Color,
    gloss: androidx.compose.ui.graphics.Color, shadow: androidx.compose.ui.graphics.Color, wingUp: Boolean,
) {
    val u = size.minDimension / 100f
    val cx = size.width / 2
    val bodyC = Offset(cx, 58 * u)
    val r = 34 * u
    fun tuft(angle: Float, len: Float) {
        rotate(angle, Offset(cx, 28 * u)) {
            drawOval(rim, Offset(cx - 7 * u, 28 * u - len - 2 * u), Size(14 * u, len + 4 * u))
            drawOval(body, Offset(cx - 5 * u, 28 * u - len), Size(10 * u, len))
        }
    }
    // shadow, then the sticker rim and white edge around the whole silhouette
    drawCircle(shadow, r + 4 * u, bodyC + Offset(2 * u, 6 * u))
    tuft(-22f, 18 * u); tuft(0f, 22 * u); tuft(22f, 16 * u)
    drawCircle(rim, r + 4.5f * u, bodyC)
    drawCircle(edge, r + 2.5f * u, bodyC)
    drawCircle(body, r, bodyC)
    // belly
    drawOval(edge.copy(alpha = 0.55f), Offset(cx - 18 * u, 62 * u), Size(36 * u, 26 * u))
    // wing
    if (wingUp) {
        // raised beside the body (a cheer): a broad rounded wing lifted 30° from the shoulder, tip at mid height with two
        // feather scallops — never a thin point above the head (it read as a horn)
        rotate(-30f, Offset(cx + 20 * u, 60 * u)) {
            drawOval(rim, Offset(cx + 14 * u, 51 * u), Size(34 * u, 18 * u))
            drawCircle(rim, 5.5f * u, Offset(cx + 44 * u, 55 * u))
            drawCircle(rim, 5f * u, Offset(cx + 42 * u, 64 * u))
            drawOval(body.copy(alpha = 0.35f), Offset(cx + 20 * u, 55 * u), Size(18 * u, 6 * u))
        }
    } else {
        val wing = Path().apply {
            moveTo(cx + 14 * u, 50 * u); quadraticTo(cx + 40 * u, 58 * u, cx + 30 * u, 86 * u); quadraticTo(cx + 16 * u, 74 * u, cx + 12 * u, 58 * u); close()
        }
        drawPath(wing, rim)
    }
    // eye
    drawCircle(edge, 10 * u, Offset(cx - 9 * u, 48 * u))
    drawCircle(ink, 5.5f * u, Offset(cx - 7.5f * u, 49 * u))
    drawCircle(edge, 1.8f * u, Offset(cx - 5.8f * u, 47 * u))
    // beak
    val b = Path().apply {
        moveTo(cx - 22 * u, 54 * u); quadraticTo(cx - 40 * u, 56 * u, cx - 34 * u, 70 * u); quadraticTo(cx - 28 * u, 62 * u, cx - 16 * u, 64 * u); close()
    }
    drawPath(b, beakRim, style = Stroke(3 * u, join = StrokeJoin.Round))
    drawPath(b, beak)
    // gloss crescent
    drawArc(gloss, 200f, 70f, false, Offset(cx - r + 6 * u, 58 * u - r + 6 * u), Size(2 * r - 12 * u, 2 * r - 12 * u), style = Stroke(5 * u, cap = StrokeCap.Round))
}

/** Foni's line, written (every voice line is also subtitled, §11). The tail points left, to Foni. */
@Composable
fun SpeechBubble(text: String, modifier: Modifier = Modifier, tailUp: Boolean = false) {
    val c = FoniaTheme.colors
    if (tailUp) return BubbleTailUp(text, modifier)
    Box(
        modifier
            .heightIn(min = 56.dp)
            .drawBehind {
                val tail = 10.dp.toPx()
                val r = CornerRadius(FoniaCorner.card.toPx())
                drawRoundRect(c.surface, Offset(tail, 0f), Size(size.width - tail, size.height), r)
                drawRoundRect(c.separator, Offset(tail, 0f), Size(size.width - tail, size.height), r, style = Stroke(1.5.dp.toPx()))
                val y = size.height / 2
                val p = Path().apply { moveTo(tail + 1.dp.toPx(), y - 8.dp.toPx()); lineTo(0f, y); lineTo(tail + 1.dp.toPx(), y + 8.dp.toPx()); close() }
                drawPath(p, c.surface)
                drawLine(c.separator, Offset(tail, y - 8.dp.toPx()), Offset(0f, y), 1.5.dp.toPx())
                drawLine(c.separator, Offset(0f, y), Offset(tail, y + 8.dp.toPx()), 1.5.dp.toPx())
            }
            .padding(start = 10.dp + FoniaSpace.m, end = FoniaSpace.m, top = FoniaSpace.s, bottom = FoniaSpace.s),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicText(text, style = FoniaTheme.type.title2.copy(color = c.ink))
    }
}

/** The bubble with its tail on top (Foni stands above it, on PATH). */
@Composable
private fun BubbleTailUp(text: String, modifier: Modifier) {
    val c = FoniaTheme.colors
    Box(
        modifier
            .drawBehind {
                val tail = 10.dp.toPx()
                val r = CornerRadius(FoniaCorner.card.toPx())
                drawRoundRect(c.surface, Offset(0f, tail), Size(size.width, size.height - tail), r)
                drawRoundRect(c.separator, Offset(0f, tail), Size(size.width, size.height - tail), r, style = Stroke(1.5.dp.toPx()))
                val x = 40.dp.toPx()
                val p = Path().apply { moveTo(x - 9.dp.toPx(), tail + 1.dp.toPx()); lineTo(x, 0f); lineTo(x + 9.dp.toPx(), tail + 1.dp.toPx()); close() }
                drawPath(p, c.surface)
                drawLine(c.separator, Offset(x - 9.dp.toPx(), tail), Offset(x, 0f), 1.5.dp.toPx())
                drawLine(c.separator, Offset(x, 0f), Offset(x + 9.dp.toPx(), tail), 1.5.dp.toPx())
            }
            .padding(start = FoniaSpace.m, end = FoniaSpace.m, top = 10.dp + FoniaSpace.s, bottom = FoniaSpace.s),
    ) {
        BasicText(text, style = FoniaTheme.type.title2.copy(color = c.ink))
    }
}

/** Lesson progress as dots (never a percentage): filled = done, hollow = to come. */
@Composable
fun StepDots(done: Int, total: Int, description: String, modifier: Modifier = Modifier) {
    val c = FoniaTheme.colors
    Row(
        modifier.semantics(mergeDescendants = true) { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(total) { i ->
            Box(
                Modifier.size(if (i == done) 14.dp else 12.dp).drawBehind {
                    when {
                        i < done -> drawCircle(c.accent)
                        i == done -> { drawCircle(c.surface); drawCircle(c.accent, style = Stroke(2.5.dp.toPx())) }
                        else -> drawCircle(c.separator, style = Stroke(2.dp.toPx()))
                    }
                },
            )
        }
    }
}

/** A round 48 dp control with an Allo glyph (✕ to leave the lesson). */
@Composable
fun RoundGlyphButton(glyph: String, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = FoniaTheme.colors
    Box(
        modifier
            .size(FoniaTarget.minimum)
            .semantics { contentDescription = description }
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick)
            .drawBehind {
                drawCircle(c.surface)
                drawCircle(c.separator, style = Stroke(1.5.dp.toPx()))
            },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(glyph, style = FoniaTheme.type.headline.copy(color = c.ink))
    }
}

/** The "gura" control: a drawn pair of lips (no emoji), opening MIRROR. */
@Composable
fun MouthButton(description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = FoniaTheme.colors
    Box(
        modifier
            .size(FoniaTarget.minimum)
            .semantics { contentDescription = description }
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick)
            .drawBehind {
                drawCircle(c.surface)
                drawCircle(c.separator, style = Stroke(1.5.dp.toPx()))
                val u = size.minDimension / 48f
                val cx = size.width / 2
                val cy = size.height / 2
                val upper = Path().apply {
                    moveTo(cx - 13 * u, cy); cubicTo(cx - 8 * u, cy - 8 * u, cx - 3 * u, cy - 6 * u, cx, cy - 3 * u)
                    cubicTo(cx + 3 * u, cy - 6 * u, cx + 8 * u, cy - 8 * u, cx + 13 * u, cy)
                    cubicTo(cx + 6 * u, cy - 1 * u, cx - 6 * u, cy - 1 * u, cx - 13 * u, cy); close()
                }
                val lower = Path().apply {
                    moveTo(cx - 13 * u, cy + 0.5f * u); cubicTo(cx - 6 * u, cy + 10 * u, cx + 6 * u, cy + 10 * u, cx + 13 * u, cy + 0.5f * u)
                    cubicTo(cx + 6 * u, cy + 2.5f * u, cx - 6 * u, cy + 2.5f * u, cx - 13 * u, cy + 0.5f * u); close()
                }
                drawPath(upper, c.voice)
                drawPath(lower, c.voice)
                drawPath(upper, c.voiceDeep, style = Stroke(1.4f * u, join = StrokeJoin.Round))
                drawPath(lower, c.voiceDeep, style = Stroke(1.4f * u, join = StrokeJoin.Round))
            },
    )
}

/**
 * The one sheet of the app (D2: the only shadow): scrim + a surface sheet rising from the bottom, corner 28 dp,
 * above the navigation bar. Back or a tap on the scrim closes it when [dismissible].
 */
@Composable
fun BoxScope.FoniaSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    dismissible: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = FoniaTheme.colors
    val reduce = FoniaTheme.reduceMotion
    if (visible) BackHandler { if (dismissible) onDismiss() }
    AnimatedVisibility(visible, enter = fadeIn(tween(200)), exit = fadeOut(tween(160))) {
        Box(
            Modifier
                .fillMaxSize()
                .background(c.ink.copy(alpha = 0.38f))
                .clickable(remember { MutableInteractionSource() }, indication = null) { if (dismissible) onDismiss() },
        )
    }
    AnimatedVisibility(
        visible,
        modifier = Modifier.align(Alignment.BottomCenter),
        enter = if (reduce) fadeIn(tween(150)) else slideInVertically(tween(320, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(120)),
        exit = if (reduce) fadeOut(tween(150)) else slideOutVertically(tween(220)) { it } + fadeOut(tween(160)),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .shadow(FoniaDepth.sheetBlur, RoundedCornerShape(topStart = FoniaCorner.sheet, topEnd = FoniaCorner.sheet), ambientColor = c.ink, spotColor = c.ink)
                .background(c.surface, RoundedCornerShape(topStart = FoniaCorner.sheet, topEnd = FoniaCorner.sheet))
                .clickable(remember { MutableInteractionSource() }, indication = null) {}
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = FoniaSpace.l, vertical = FoniaSpace.l),
            verticalArrangement = Arrangement.spacedBy(FoniaSpace.m),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(width = 40.dp, height = 5.dp).background(c.separator, RoundedCornerShape(3.dp)))
            content()
        }
    }
}

/** Sheet title (adult tone: sober type, no sticker). */
@Composable
fun SheetTitle(text: String) {
    BasicText(text, style = FoniaTheme.type.title.copy(color = FoniaTheme.colors.ink), modifier = Modifier.semantics { heading() })
}

@Composable
fun SheetBody(text: String) {
    BasicText(text, style = FoniaTheme.type.body.copy(color = FoniaTheme.colors.inkSecondary))
}
