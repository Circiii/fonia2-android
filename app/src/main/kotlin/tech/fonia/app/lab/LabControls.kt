package tech.fonia.app.lab

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import tech.fonia.designsystem.theme.FoniaTheme
import tech.fonia.designsystem.tokens.FoniaCorner
import tech.fonia.designsystem.tokens.FoniaSpace
import tech.fonia.designsystem.tokens.FoniaTarget

/** A selectable chip (letter / word picker). */
@Composable
fun LabChip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = FoniaTheme.colors
    Box(
        modifier
            .heightIn(min = FoniaTarget.minimum)
            .clip(RoundedCornerShape(FoniaCorner.chip))
            .background(if (selected) c.ink else c.surface)
            .border(1.dp, if (selected) c.ink else c.separator, RoundedCornerShape(FoniaCorner.chip))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = FoniaSpace.m, vertical = FoniaSpace.xs),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text, style = FoniaTheme.type.headline.copy(color = if (selected) c.surface else c.ink))
    }
}

/** A capsule button for an animation; [strong] = filled ink. */
@Composable
fun LabButton(text: String, modifier: Modifier = Modifier, strong: Boolean = false, onClick: () -> Unit) {
    val c = FoniaTheme.colors
    Box(
        modifier
            .heightIn(min = FoniaTarget.minimum)
            .clip(CircleShape)
            .background(if (strong) c.accent else c.fill)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = FoniaSpace.m, vertical = FoniaSpace.xs),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text, style = FoniaTheme.type.label.copy(color = if (strong) c.surface else c.accentDeep))
    }
}

/** A 0..1 slider drawn with the tokens (no Material). */
@Composable
fun LabSlider(label: String, value: Float, modifier: Modifier = Modifier, valueText: String? = null, onChange: (Float) -> Unit) {
    val c = FoniaTheme.colors
    Row(modifier.fillMaxWidth().heightIn(min = FoniaTarget.minimum), verticalAlignment = Alignment.CenterVertically) {
        BasicText(label, Modifier.width(112.dp), style = FoniaTheme.type.label.copy(color = c.ink))
        Box(
            Modifier
                .weight(1f)
                .height(FoniaTarget.minimum)
                .pointerInput(Unit) {
                    detectTapGestures { onChange((it.x / size.width).coerceIn(0f, 1f)) }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { change, _ -> onChange((change.position.x / size.width).coerceIn(0f, 1f)) }
                }
                .drawBehind {
                    val h = 8.dp.toPx()
                    val y = size.height / 2
                    drawRoundRect(c.fill, Offset(0f, y - h / 2), Size(size.width, h), CornerRadius(h / 2))
                    val v = value.coerceIn(0f, 1f)
                    drawRoundRect(c.accent, Offset(0f, y - h / 2), Size(size.width * v, h), CornerRadius(h / 2))
                    drawCircle(c.surface, 13.dp.toPx(), Offset(size.width * v, y))
                    drawCircle(c.accent, 10.dp.toPx(), Offset(size.width * v, y))
                },
        )
        Spacer(Modifier.width(FoniaSpace.xs))
        BasicText(valueText ?: "%.2f".format(value), Modifier.width(44.dp), style = FoniaTheme.type.caption.copy(color = c.inkSecondary))
    }
}

/** An on/off pill. */
@Composable
fun LabToggle(label: String, on: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    val c = FoniaTheme.colors
    Box(
        modifier
            .heightIn(min = FoniaTarget.minimum)
            .clip(CircleShape)
            .background(if (on) c.ink else c.surface)
            .border(1.dp, if (on) c.ink else c.inkSecondary, CircleShape)
            .clickable(role = Role.Switch) { onChange(!on) }
            .padding(horizontal = FoniaSpace.m, vertical = FoniaSpace.xs),
        contentAlignment = Alignment.Center,
    ) {
        BasicText((if (on) "● " else "○ ") + label, style = FoniaTheme.type.label.copy(color = if (on) c.surface else c.ink))
    }
}
