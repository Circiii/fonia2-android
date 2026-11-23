package tech.fonia.collector.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tech.fonia.audio.LiveLevels

@Composable
fun Txt(text: String, style: TextStyle = T.body, modifier: Modifier = Modifier, color: Color = Color.Unspecified, align: TextAlign? = null) {
    val s = if (color != Color.Unspecified) style.copy(color = color) else style
    BasicText(text, modifier, style = if (align != null) s.copy(textAlign = align) else s)
}

/** A scrolling page with the system bars and the keyboard kept clear. */
@Composable
fun Page(modifier: Modifier = Modifier, bottom: (@Composable ColumnScope.() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().background(C.bg).safeDrawingPadding().imePadding(),
    ) {
        Column(
            modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = S.page, vertical = S.l),
            verticalArrangement = Arrangement.spacedBy(S.l),
            content = content,
        )
        if (bottom != null) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(C.separator))
            Column(
                Modifier.fillMaxWidth().background(C.bg).padding(horizontal = S.page, vertical = S.m),
                verticalArrangement = Arrangement.spacedBy(S.m),
                content = bottom,
            )
        }
    }
}

/** Caution is the destructive action: ink, with ⚠ in the text, never red (05 §2.3 rule 4). */
enum class ButtonKind { Primary, Secondary, Quiet, Caution }

@Composable
fun FButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Secondary,
    enabled: Boolean = true,
    height: Dp = 56.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(50)
    val (background, foreground, border) = when (kind) {
        ButtonKind.Primary -> Triple(if (pressed) C.accentDeep else C.accent, C.surface, null)
        ButtonKind.Secondary -> Triple(if (pressed) C.fill else C.surface, C.accentDeep, C.inkSecondary)
        ButtonKind.Quiet -> Triple(if (pressed) C.fill else Color.Transparent, C.accentDeep, null)
        ButtonKind.Caution -> Triple(if (pressed) C.fill else C.surface, C.ink, C.ink)
    }
    val alpha = if (enabled) 1f else 0.45f
    Box(
        modifier
            .heightIn(min = height)
            .clip(shape)
            .background(if (enabled) background else if (kind == ButtonKind.Primary) C.separator else background)
            .let { if (border != null) it.border(1.dp, border.copy(alpha = alpha), shape) else it }
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = S.xl, vertical = S.m),
        contentAlignment = Alignment.Center,
    ) {
        Txt(
            text, T.headline,
            color = if (!enabled && kind == ButtonKind.Primary) C.inkSecondary else foreground.copy(alpha = alpha),
            align = TextAlign.Center,
        )
    }
}

@Composable
fun FCard(modifier: Modifier = Modifier, background: Color = C.surface, border: Color? = C.separator, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(background)
            .let { if (border != null) it.border(1.dp, border, shape) else it }
            .padding(S.l),
        verticalArrangement = Arrangement.spacedBy(S.s),
        content = content,
    )
}

/** A selectable chip: selection is shown by a thick accent edge and a ✓, never by colour alone. */
@Composable
fun FChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, sub: String? = null) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(if (selected) C.fill else C.surface)
            .border(if (selected) 2.dp else 1.dp, if (selected) C.accent else C.inkSecondary, shape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = S.l, vertical = S.m),
        verticalArrangement = Arrangement.Center,
    ) {
        Txt(if (selected) "✓ $text" else text, T.label, color = if (selected) C.accentDeep else C.ink)
        if (sub != null) Txt(sub, T.caption)
    }
}

@Composable
fun FField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    help: String? = null,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Characters,
    keyboardType: KeyboardType = KeyboardType.Ascii,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(S.xs)) {
        Txt(label, T.label)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = T.headline,
            cursorBrush = SolidColor(C.accent),
            keyboardOptions = KeyboardOptions(capitalization = capitalization, autoCorrectEnabled = false, keyboardType = keyboardType),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
            decorationBox = { inner ->
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(8.dp)).background(C.fill)
                        .border(1.dp, C.inkSecondary, RoundedCornerShape(8.dp)).padding(horizontal = S.m, vertical = S.m),
                    contentAlignment = Alignment.CenterStart,
                ) { inner() }
            },
        )
        if (help != null) Txt(help, T.caption)
    }
}

@Composable
fun CheckRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Checkbox) { onChange(!checked) }.padding(vertical = S.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(S.m),
    ) {
        Box(
            Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(if (checked) C.fill else C.surface)
                .border(if (checked) 2.dp else 1.dp, if (checked) C.accent else C.inkSecondary, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Txt("✓", T.headline, color = C.accentDeep)
        }
        Txt(text, T.body, modifier = Modifier.weight(1f))
    }
}

@Composable
fun ProgressRail(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(C.fill)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(8.dp).clip(CircleShape).background(C.accentDeep))
    }
}

/**
 * The live level, read from `LiveLevels` once per frame (it is not observable on purpose). Pink is the speaker's
 * own sound (05 §2.3); grey when the microphone is closed.
 */
@Composable
fun LevelMeter(levels: LiveLevels, active: Boolean, modifier: Modifier = Modifier, threshold: Float? = null) {
    val level = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(active) {
        if (!active) {
            level.floatValue = 0f
            return@LaunchedEffect
        }
        var shown = 0f
        while (true) {
            withFrameNanos { }
            val target = levels.level
            shown = if (target > shown) target else shown * 0.9f + target * 0.1f
            level.floatValue = shown
        }
    }
    Box(
        modifier.fillMaxWidth().height(20.dp).clip(CircleShape).background(C.fill)
            .semantics { contentDescription = "Nivelul sunetului" },
    ) {
        Box(
            Modifier.fillMaxWidth(level.floatValue.coerceIn(0f, 1f)).height(20.dp).clip(CircleShape)
                .background(if (active) C.voice else C.separator),
        )
        if (threshold != null) {
            Row(Modifier.fillMaxWidth().height(20.dp)) {
                Spacer(Modifier.fillMaxWidth(threshold.coerceIn(0f, 1f)))
                Box(Modifier.width(2.dp).height(20.dp).background(C.ink.copy(alpha = 0.5f)))
            }
        }
    }
}

/** A small rounded tag: level, position, kind. */
@Composable
fun Tag(text: String, face: Color = C.fill, ink: Color = C.accentDeep, edge: Color? = null) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier.clip(shape).background(face).let { if (edge != null) it.border(1.5.dp, edge, shape) else it }
            .padding(horizontal = S.m, vertical = 6.dp),
    ) { Txt(text, T.label, color = ink) }
}

@Composable
fun ButtonRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(S.m), verticalAlignment = Alignment.CenterVertically, content = content)
}

@Composable
fun SectionTitle(text: String) = Txt(text, T.title2)

@Composable
fun KeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(S.m)) {
        Txt(key, T.label, color = C.inkSecondary, modifier = Modifier.width(120.dp))
        Txt(value, T.label, modifier = Modifier.weight(1f))
    }
}
