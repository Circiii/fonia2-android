package tech.fonia.designsystem.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import tech.fonia.designsystem.motion.pressBounce
import tech.fonia.designsystem.motion.rememberFoniaHaptics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.style.TextOverflow
import tech.fonia.designsystem.theme.FoniaTheme
import tech.fonia.designsystem.tokens.FoniaSpace
import tech.fonia.designsystem.tokens.FoniaTarget

/** Look of a [ContinueButton]: the one filled thing on the screen, or its outlined twin. */
enum class ContinueStyle { Filled, Outlined }

/**
 * THE main action (D2 §7): 72 dp tall, full width minus the page margins, filled accent on an `accentDeep` edge that
 * the face sinks into (3 dp) while pressed. Sits in the content, never glued to the bottom edge. The → glyph is Allo's.
 */
@Composable
fun ContinueButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ContinueStyle = ContinueStyle.Filled,
    enabled: Boolean = true,
    arrow: Boolean = true,
    contentDescription: String = text,
) {
    val c = FoniaTheme.colors
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val haptics = rememberFoniaHaptics()
    val filled = style == ContinueStyle.Filled
    val edge = 4.dp
    val sink = if (pressed && enabled) 3.dp else 0.dp
    val face = when {
        !enabled -> c.fill
        filled -> c.accent
        else -> c.surface
    }
    val rim = when {
        !enabled -> c.separator
        filled -> c.accentDeep
        else -> c.inkSecondary
    }
    val ink = when {
        !enabled -> c.inkSecondary
        filled -> c.surface
        else -> c.ink
    }
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = FoniaTarget.continueButtonHeight)
            .semantics(mergeDescendants = true) { this.contentDescription = contentDescription }
            .pressBounce(source, pressedScale = 0.97f, enabled = enabled)
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button) {
                haptics.tap()
                onClick()
            }
            .drawBehind {
                val r = CornerRadius(size.height / 2f)
                val e = edge.toPx()
                val s = sink.toPx()
                val h = size.height - e
                // the edge under the face (the "depth" of the sticker button)
                drawRoundRect(rim, Offset(0f, e), Size(size.width, h), r)
                drawRoundRect(face, Offset(0f, s), Size(size.width, h), r)
                if (!filled || !enabled) {
                    val w = 2.dp.toPx()
                    drawRoundRect(rim, Offset(w / 2, s + w / 2), Size(size.width - w, h - w), CornerRadius(r.x - w / 2), style = Stroke(w))
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = if (arrow) "$text  →" else text,
            style = FoniaTheme.type.title2.copy(color = ink, textAlign = TextAlign.Center),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.offset(y = sink - edge / 2).padding(horizontal = FoniaSpace.l, vertical = FoniaSpace.s),
        )
    }
}

/**
 * A quiet secondary control (≥ 48 dp): surface pill with a thin outline, ink-secondary label. For the corners of a
 * screen ("Pentru adult", "Oglinda") and the adult band; never competes with the [ContinueButton].
 */
@Composable
fun QuietButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glyph: String? = null,
    emphasised: Boolean = false,
    contentDescription: String = text,
) {
    val c = FoniaTheme.colors
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Row(
        modifier
            .defaultMinSize(minWidth = FoniaTarget.minimum, minHeight = FoniaTarget.minimum)
            .semantics(mergeDescendants = true) { this.contentDescription = contentDescription }
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .drawBehind {
                val r = CornerRadius(size.height / 2f)
                drawRoundRect(if (pressed) c.fill else c.surface, cornerRadius = r)
                val w = (if (emphasised) 2.dp else 1.5.dp).toPx()
                drawRoundRect(
                    if (emphasised) c.accent else c.separator, Offset(w / 2, w / 2), Size(size.width - w, size.height - w),
                    CornerRadius(r.x - w / 2), style = Stroke(w),
                )
            }
            .padding(horizontal = FoniaSpace.m, vertical = FoniaSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FoniaSpace.xs, Alignment.CenterHorizontally),
    ) {
        if (glyph != null) BasicText(glyph, style = FoniaTheme.type.headline.copy(color = if (emphasised) c.accentDeep else c.inkSecondary))
        BasicText(text, style = FoniaTheme.type.label.copy(color = if (emphasised) c.accentDeep else c.ink), maxLines = 2)
    }
}

/** A row of [QuietButton]s spaced by the touch-target gap. */
@Composable
fun QuietRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(FoniaTarget.gap), verticalAlignment = Alignment.CenterVertically) {
        content()
    }
}
