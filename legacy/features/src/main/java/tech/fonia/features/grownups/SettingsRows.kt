package tech.fonia.features.grownups

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import tech.fonia.design.ContinuousRoundedRectangle
import tech.fonia.design.FoniaIcon
import tech.fonia.design.FoniaIconView
import tech.fonia.design.FoniaText
import tech.fonia.design.LocalReduceMotion
import tech.fonia.design.MiniPillStyle
import tech.fonia.design.Palette
import tech.fonia.design.Radius
import tech.fonia.design.StyledButton
import tech.fonia.design.TemplateImage
import tech.fonia.design.TextRole
import tech.fonia.design.UIWeight
import tech.fonia.design.fBodyStrong
import tech.fonia.design.fCaption
import tech.fonia.design.softShadow
import tech.fonia.design.swiftSpring
import tech.fonia.design.ui
import tech.fonia.features.app.FoniaSwitch

// MARK: The pieces

/** The colour of a group's icons, so each part of the page is told apart at a glance (iOS `SettingsTint`). */
internal enum class SettingsTint(val ink: Color, val soft: Color) {
    Green(Palette.accentText, Palette.accentSoft),
    Blue(Palette.cold, Palette.coldSoft),
    Gold(Palette.tipDeep, Palette.tipSoft),
    Rose(Palette.warm, Palette.warmSoft),
    Grey(Palette.notYet, Palette.notYetSoft),
    Danger(Palette.danger, Palette.dangerSoft),
}

/**
 * The icons iOS added for this page (`FoniaIcon.chart`, `.note`, `.share`, `.trash`), by their file in `images/icons`.
 * They stay here until `FoniaIcon` has them.
 */
internal object SettingsIcons {
    const val CHART = "chart-line-up-duotone"
    const val NOTE = "note-pencil-duotone"
    const val SHARE = "export-duotone"
    const val TRASH = "trash-duotone"
}

/** `FoniaIconView` by file name: grows with the text, no semantics of its own. */
@Composable
internal fun SettingsIcon(name: String, size: Float, tint: Color, modifier: Modifier = Modifier) {
    val side = size * LocalDensity.current.fontScale
    TemplateImage(name, modifier.size(side.dp), tint)
}

internal val LocalSettingsTint = compositionLocalOf { SettingsTint.Green }

/** A group's name over its card. */
@Composable
internal fun GroupTitle(title: String) {
    FoniaText(title, Modifier.padding(start = 6.dp).semantics { heading() }, style = ui(14, UIWeight.bold, TextRole.footnote), color = Palette.inkSoft)
}

/** A title over one white card of rows. The danger group has a red edge. */
@Composable
internal fun SettingsGroup(title: String, tint: SettingsTint, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = ContinuousRoundedRectangle(Radius.card)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        GroupTitle(title)
        CompositionLocalProvider(LocalSettingsTint provides tint) {
            Column(
                Modifier
                    .fillMaxWidth()
                    // The shadow sits under the card's fill, outside the clip.
                    .softShadow(shape)
                    .background(Palette.sheet, shape)
                    .then(if (tint == SettingsTint.Danger) Modifier.border(1.dp, Palette.danger.copy(alpha = 0.25f), shape) else Modifier)
                    .clip(shape),
                content = content,
            )
        }
    }
}

/** The icon of a row, on its soft tile. [pulse] rings it once in the accent when it turns true. */
@Composable
internal fun SettingsIconTile(icon: String, destructive: Boolean = false, pulse: Boolean = false) {
    val tint = if (destructive) SettingsTint.Danger else LocalSettingsTint.current
    val reduceMotion = LocalReduceMotion.current
    val ring = remember { Animatable(1f) }
    var last by remember { mutableStateOf(pulse) }
    LaunchedEffect(pulse) {
        val turnedOn = pulse && !last
        last = pulse
        if (!turnedOn || reduceMotion) return@LaunchedEffect
        ring.snapTo(0f)
        ring.animateTo(1f, tween(700))
    }
    val accent = Palette.accent
    Box(
        Modifier
            .size(38.dp)
            .drawWithContent {
                drawContent()
                val t = ring.value
                if (t < 1f) {
                    // Pow's pulse: a ring that grows out of the tile and fades.
                    val grow = 10.dp.toPx() * t
                    val radius = 11.dp.toPx() + grow
                    drawRoundRect(
                        accent.copy(alpha = (1f - t) * 0.9f),
                        topLeft = Offset(-grow, -grow),
                        size = Size(size.width + 2 * grow, size.height + 2 * grow),
                        cornerRadius = CornerRadius(radius),
                        style = Stroke(2.dp.toPx()),
                    )
                }
            }
            .background(tint.soft, ContinuousRoundedRectangle(11.dp)),
        contentAlignment = Alignment.Center,
    ) {
        SettingsIcon(icon, 21f, tint.ink)
    }
}

/** An icon on a soft tile, a name, and a line saying what's inside; top-aligned so a long line keeps the tile by the name. */
@Composable
private fun SettingsRowLabel(icon: String, title: String, detail: String?, destructive: Boolean, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        SettingsIconTile(icon, destructive)
        Column(Modifier.weight(1f).heightIn(min = 38.dp), verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) {
            FoniaText(title, style = fBodyStrong, color = if (destructive) Palette.danger else Palette.ink)
            if (detail != null) FoniaText(detail, style = fCaption, color = Palette.inkSoft)
        }
    }
}

/** A row that opens something or does it. It lights up under the finger, the way a system list does. */
@Composable
internal fun SettingsRow(
    icon: String,
    title: String,
    detail: String? = null,
    chevron: Boolean = false,
    destructive: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (pressed) Palette.cloud else Color.Transparent)
            .clickable(interactionSource = interactions, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 60.dp)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsRowLabel(icon, title, detail, destructive, Modifier.weight(1f))
        if (chevron) FoniaIconView(FoniaIcon.forward, size = 13f, tint = Palette.inkSoft)
    }
}

/** A row that explains something, with its action under the words (iOS `soundsModelRow`). */
@Composable
internal fun ActionRow(icon: String, title: String, detail: String, action: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SettingsRowLabel(icon, title, detail, destructive = false, modifier = Modifier.semantics(mergeDescendants = true) {})
        Box(Modifier.padding(start = 50.dp)) { action() }
    }
}

/**
 * A switch on the line of its name, with what it does underneath: beside the switch, a long explanation would be
 * squeezed into a narrow column. The icon rings once when it is turned on.
 */
@Composable
internal fun SwitchRow(
    icon: String,
    title: String,
    checked: Boolean,
    detail: String? = null,
    /** False when the row sits inside a padded block of its own (the reminder). */
    inset: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().then(if (inset) Modifier.padding(horizontal = 14.dp, vertical = 11.dp) else Modifier),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        SettingsIconTile(icon, pulse = checked)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // The switch says the name itself; the line underneath is read on its own.
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 38.dp)
                    .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FoniaText(title, Modifier.weight(1f), style = fBodyStrong, color = Palette.ink)
                FoniaSwitch(checked)
            }
            if (detail != null) FoniaText(detail, style = fCaption, color = Palette.inkSoft)
        }
    }
}

/** The rule between two rows, from under the text. */
@Composable
internal fun RowDivider() {
    Box(Modifier.padding(start = 64.dp).fillMaxWidth().height(1.dp).background(Palette.cloud))
}

/** A small action under a row's words. */
@Composable
internal fun Pill(title: String, onClick: () -> Unit) {
    StyledButton(onClick = onClick, style = MiniPillStyle(), modifier = Modifier.heightIn(min = 44.dp)) { FoniaText(title) }
}

/** `ProgressView(value:)` under "Se descarcă…". */
@Composable
internal fun DownloadBar(fraction: Double) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FoniaText("Se descarcă…", style = fCaption, color = Palette.inkSoft)
        val track = Palette.progressTrack
        val fill = Palette.accent
        Canvas(
            Modifier
                .widthIn(max = 240.dp)
                .fillMaxWidth()
                .height(4.dp)
                .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction.toFloat().coerceIn(0f, 1f), 0f..1f) },
        ) {
            val radius = CornerRadius(size.height / 2)
            drawRoundRect(track, cornerRadius = radius)
            drawRoundRect(fill, size = Size(size.width * fraction.toFloat().coerceIn(0f, 1f), size.height), cornerRadius = radius)
        }
    }
}

/**
 * Each part of the page settles into place a beat after the one above it: it fades in and comes up 14 dp on a soft
 * spring, 40 ms after the part above. With Reduce Motion it is simply there.
 */
@Composable
internal fun Modifier.arriving(order: Int, step: Long = 40, duration: Double = 0.45, bounce: Double = 0.12): Modifier {
    val reduceMotion = LocalReduceMotion.current
    val progress = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (progress.value >= 1f) return@LaunchedEffect
        delay(order * step)
        progress.animateTo(1f, swiftSpring(duration, bounce))
    }
    return graphicsLayer {
        alpha = progress.value.coerceIn(0f, 1f)
        translationY = (1f - progress.value) * 14.dp.toPx()
    }
}
