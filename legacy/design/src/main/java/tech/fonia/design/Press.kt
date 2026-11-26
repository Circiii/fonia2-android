package tech.fonia.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.geometry.Rect
import tech.fonia.designsystem.motion.LocalFoniaHapticsEnabled

/** `Animation.easeOut(duration: 0.05)`: down at once under the finger. */
internal fun <T> pressDown(): AnimationSpec<T> = easeOut(0.05)

/** `Animation.spring(duration: 0.3, bounce: 0.42)`: back up with a little bounce, so the press reads as a real key. */
internal fun <T> pressUp(): AnimationSpec<T> = swiftSpring(0.3, 0.42)

/** `.animation(isPressed ? pressDown : pressUp, value: isPressed)`. */
internal fun <T> pressSpec(isPressed: Boolean): AnimationSpec<T> = if (isPressed) pressDown() else pressUp()

/** What the phone gives under the finger. */
enum class PressFeel {
    /** `.impact(flexibility: .soft, intensity: 0.5…0.6)`: a raised face or a card going down. */
    Soft,

    /** `.selection`: a quiet tick, for words, rows and choices. */
    Tick,
}

private fun PressFeel.type(): HapticFeedbackType = when (this) {
    PressFeel.Soft -> HapticFeedbackType.ContextClick
    PressFeel.Tick -> HapticFeedbackType.SegmentTick
}

/** `.sensoryFeedback(feel, trigger: isPressed) { _, pressed in pressed }`: one haptic the moment the finger lands. */
@Composable
fun PressHaptic(isPressed: Boolean, feel: PressFeel = PressFeel.Soft) {
    val haptics = LocalHapticFeedback.current
    val on = LocalFoniaHapticsEnabled.current
    LaunchedEffect(isPressed) {
        if (isPressed && on) haptics.performHapticFeedback(feel.type())
    }
}

/**
 * `.brightness(amount)`: adds [amount] to each colour channel of what is drawn, through a colour matrix on a layer.
 * Nothing extra is drawn while it is 0.
 */
fun Modifier.brightness(amount: () -> Float): Modifier = drawWithContent {
    val a = amount()
    if (a == 0f) {
        drawContent()
        return@drawWithContent
    }
    val shift = a * 255f
    val paint = Paint().apply {
        colorFilter = ColorFilter.colorMatrix(
            ColorMatrix(
                floatArrayOf(
                    1f, 0f, 0f, 0f, shift,
                    0f, 1f, 0f, 0f, shift,
                    0f, 0f, 1f, 0f, shift,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )
    }
    drawIntoCanvas { canvas ->
        canvas.saveLayer(Rect(0f, 0f, size.width, size.height), paint)
        drawContent()
        canvas.restore()
    }
}

/**
 * [PressableStyle] for something that is not a [StyledButton]: the same shrink, the same 3% darker and the same soft
 * haptic, around a plain click. No sound, like the plain buttons it replaces.
 */
fun Modifier.pressable(
    enabled: Boolean = true,
    role: Role? = Role.Button,
    onClickLabel: String? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val interactions = remember { MutableInteractionSource() }
    val isPressed by interactions.collectIsPressedAsState()
    val pressed = isPressed && enabled
    PressHaptic(pressed)
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, pressSpec(pressed), label = "pressable")
    val dim by animateFloatAsState(if (pressed) -0.03f else 0f, pressSpec(pressed), label = "pressableDim")
    this
        .clickable(
            interactionSource = interactions,
            indication = null,
            enabled = enabled,
            onClickLabel = onClickLabel,
            role = role,
            onClick = onClick,
        )
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .brightness { dim }
}

/**
 * [RowPressStyle] for a row that handles its own click: grey under the finger, a little smaller, one quiet tick.
 * The grey sits under whatever the row draws next, so it goes before the row's own background and padding.
 */
fun Modifier.rowPress(
    enabled: Boolean = true,
    role: Role? = Role.Button,
    onClickLabel: String? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val interactions = remember { MutableInteractionSource() }
    val isPressed by interactions.collectIsPressedAsState()
    val pressed = isPressed && enabled
    PressHaptic(pressed, PressFeel.Tick)
    // `.animation(isPressed ? pressDown : .easeOut(duration: 0.2), value: isPressed)`.
    val scale by animateFloatAsState(if (pressed) 0.985f else 1f, if (pressed) pressDown() else easeOut(0.2), label = "row")
    val grey by animateColorAsState(if (pressed) Palette.cloud else Palette.cloud.copy(alpha = 0f), if (pressed) pressDown() else easeOut(0.2), label = "rowGrey")
    this
        .clickable(
            interactionSource = interactions,
            indication = null,
            enabled = enabled,
            onClickLabel = onClickLabel,
            role = role,
            onClick = onClick,
        )
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .drawWithContent {
            if (grey.alpha > 0f) drawRect(grey)
            drawContent()
        }
}
