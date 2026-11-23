package tech.fonia.app.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import tech.fonia.designsystem.theme.FoniaTheme

/**
 * Press feedback for the child's cards: the sticker sinks to 94 % while pressed and springs back with a little overshoot
 * (bouncy spring). No ripple (stickers do not ripple); reduced motion keeps it still.
 */
fun Modifier.pressBounce(enabled: Boolean = true, role: Role = Role.Button, onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val reduce = FoniaTheme.reduceMotion
    val scale by animateFloatAsState(
        targetValue = if (pressed && !reduce) 0.94f else 1f,
        animationSpec = spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = source, indication = null, enabled = enabled, role = role, onClick = onClick)
}
