package tech.fonia.features.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tech.fonia.design.ContinuousRoundedRectangle
import tech.fonia.design.FoniaIcon
import tech.fonia.design.FoniaIconView
import tech.fonia.design.FoniaText
import tech.fonia.design.LocalFont
import tech.fonia.design.LocalForeground
import tech.fonia.design.Palette
import tech.fonia.design.StyledButton
import tech.fonia.design.ToolButtonStyle
import tech.fonia.design.UIWeight
import tech.fonia.design.fBody
import tech.fonia.design.fCaption
import tech.fonia.design.rowPress
import tech.fonia.design.swiftSpring
import tech.fonia.design.ui

// The system pieces of the adult pages: `List` in the inset-grouped style of iOS 26 (white rounded sections on the
// page colour, rows with separators, chevrons on the rows that open a page), `Toggle`, `LabeledContent`,
// `ProgressView(value:)` and the inline navigation bar of the settings sheet. They keep the iOS look, as the rest of
// the app does, instead of Material's.

private val sectionShape = ContinuousRoundedRectangle(26.dp)

/** `List { … }.scrollContentBackground(.hidden)` with the list font (`.font(.fBody)`). */
@Composable
fun InsetGroupedList(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    CompositionLocalProvider(LocalFont provides fBody, LocalForeground provides SystemColors.label) {
        Column(
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(36.dp),
            content = content,
        )
    }
}

/** The rows of one section, each drawn with its separator. */
class ListSectionScope internal constructor() {
    internal val rows = mutableListOf<@Composable () -> Unit>()

    fun row(content: @Composable () -> Unit) {
        rows += content
    }
}

/**
 * `Section(header) { … } footer: { … }`. [clear] is `listRowBackground(Color.clear)` with no insets, for a row that
 * draws its own card.
 */
@Composable
fun ListSection(header: String? = null, footer: String? = null, clear: Boolean = false, build: ListSectionScope.() -> Unit) {
    val rows = ListSectionScope().apply(build).rows
    Column(Modifier.fillMaxWidth()) {
        if (header != null) {
            FoniaText(
                header,
                Modifier
                    .padding(start = 16.dp, end = 16.dp, bottom = 11.dp)
                    .semantics { heading() },
                style = ui(15, UIWeight.regular),
                color = SystemColors.secondaryLabel,
            )
        }
        if (rows.isNotEmpty()) {
            Column(if (clear) Modifier.fillMaxWidth() else Modifier.fillMaxWidth().clip(sectionShape).background(Palette.sheet)) {
                rows.forEachIndexed { index, row ->
                    row()
                    if (index < rows.lastIndex && !clear) {
                        Box(
                            Modifier
                                .padding(start = 16.dp)
                                .fillMaxWidth()
                                .height(0.5.dp)
                                .background(SystemColors.separator),
                        )
                    }
                }
            }
        }
        if (footer != null) {
            FoniaText(
                footer,
                Modifier.padding(start = 16.dp, end = 16.dp, top = 9.dp),
                style = fCaption,
                color = SystemColors.secondaryLabel,
            )
        }
    }
}

/** A row's body: the touch highlight of a list row, the minimum height and the insets. */
@Composable
fun ListRow(
    modifier: Modifier = Modifier,
    onClick: (() -> kotlin.Unit)? = null,
    chevron: Boolean = false,
    enabled: Boolean = true,
    minHeight: Dp = 52.dp,
    padding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    content: @Composable RowScope.() -> kotlin.Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            // `RowPressStyle`: grey under the finger, a little smaller, one quiet tick.
            .then(if (onClick != null) Modifier.rowPress(enabled = enabled, onClick = onClick) else Modifier)
            .heightIn(min = minHeight)
            .padding(padding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            content()
        }
        if (chevron) FoniaIconView(FoniaIcon.forward, size = 13f, tint = Color(0xFFBDBDC2))
    }
}

/** `NavigationLink("Title") { Page() }`. */
@Composable
fun NavigationRow(title: String, onClick: () -> kotlin.Unit, color: Color = SystemColors.label) {
    ListRow(onClick = onClick, chevron = true) {
        FoniaText(title, Modifier.weight(1f), color = color)
    }
}

/** `NavigationLink { RowLabel(title, detail) }`, or a plain button with the same label when [chevron] is false. */
@Composable
fun DetailRow(title: String, detail: String, onClick: () -> kotlin.Unit, chevron: Boolean = true) {
    ListRow(onClick = onClick, chevron = chevron) {
        RowLabel(title, detail, Modifier.weight(1f))
    }
}

/** A row's name, with a line under it saying what's inside. */
@Composable
fun RowLabel(title: String, detail: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        FoniaText(title, style = fBody, color = Palette.ink)
        FoniaText(detail, style = fCaption, color = Palette.inkSoft)
    }
}

/** `Button(title)` in a list: the tint colour, or red for `role: .destructive`. */
@Composable
fun ButtonRow(title: String, onClick: () -> kotlin.Unit, destructive: Boolean = false, color: Color? = null, style: TextStyle? = null) {
    ListRow(onClick = onClick) {
        FoniaText(title, style = style, color = color ?: if (destructive) SystemColors.red else Palette.accentText)
    }
}

/** `LabeledContent(title, value:)`. */
@Composable
fun ValueRow(title: String, value: String, onClick: (() -> kotlin.Unit)? = null, color: Color = SystemColors.label) {
    ListRow(onClick = onClick) {
        FoniaText(title, Modifier.weight(1f), color = color)
        FoniaText(value, color = SystemColors.secondaryLabel)
    }
}

/** `Toggle(title, isOn:)`. */
@Composable
fun ToggleRow(title: String, checked: Boolean, detail: String? = null, onCheckedChange: (Boolean) -> kotlin.Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .heightIn(min = 52.dp)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (detail == null) FoniaText(title, Modifier.weight(1f)) else RowLabel(title, detail, Modifier.weight(1f))
        FoniaSwitch(checked)
    }
}

/** The iOS 26 switch, in the app's tint: a 64 × 28 track and a pill that slides. */
@Composable
fun FoniaSwitch(checked: Boolean) {
    val track by animateColorAsState(if (checked) Palette.accentText else Color(0xFFE9E9EA), label = "track")
    val knob by animateDpAsState(if (checked) 23.dp else 2.dp, swiftSpring(0.3, 0.15), label = "knob")
    Box(
        Modifier
            .size(64.dp, 28.dp)
            .background(track, RoundedCornerShape(50)),
    ) {
        Box(
            Modifier
                .offset(x = knob, y = 2.dp)
                .size(39.dp, 24.dp)
                .shadow(2.dp, RoundedCornerShape(50))
                .background(Color.White, RoundedCornerShape(50)),
        )
    }
}

/** `ProgressView(value:) { Text(label) }`: the label over a thin bar. */
@Composable
fun ProgressRow(label: String, value: Double) {
    ListRow {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FoniaText(label)
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .semantics { progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat().coerceIn(0f, 1f), 0f..1f) },
            ) {
                val radius = CornerRadius(size.height / 2)
                drawRoundRect(Color(0xFFE5E5EA), cornerRadius = radius)
                drawRoundRect(Palette.accentText, size = Size(size.width * value.toFloat().coerceIn(0f, 1f), size.height), cornerRadius = radius)
            }
        }
    }
}

/**
 * The inline navigation bar the settings sheet keeps (`navigationTitle` + `.inline`): the title in Baloo Bold 17 in
 * ink, as `RootView` styles `UINavigationBar`, a back button when the page was pushed, and "Gata" when the page closes
 * the sheet.
 */
@Composable
fun SystemNavigationBar(
    title: String,
    onBack: (() -> kotlin.Unit)? = null,
    onDone: (() -> kotlin.Unit)? = null,
    modifier: Modifier = Modifier,
    topInset: Boolean = false,
) {
    Box(
        modifier
            .fillMaxWidth()
            .then(if (topInset) Modifier.windowInsetsPadding(WindowInsets.statusBars) else Modifier)
            .padding(horizontal = 14.dp)
            .padding(top = 10.dp, bottom = 6.dp)
            .heightIn(min = 48.dp), // the back button with its raised edge, so hiding it moves nothing
        contentAlignment = Alignment.Center,
    ) {
        FoniaText(
            title,
            Modifier
                .padding(horizontal = 64.dp)
                .semantics { heading() },
            style = ui(17, UIWeight.semibold),
            color = Palette.ink,
            maxLines = 1,
            minimumScaleFactor = 0.8f,
        )
        if (onBack != null) {
            Box(Modifier.align(Alignment.CenterStart)) {
                StyledButton(onClick = onBack, style = ToolButtonStyle(), modifier = Modifier.semantics { contentDescription = "Înapoi" }) {
                    FoniaIconView(FoniaIcon.back, size = 18f)
                }
            }
        }
        if (onDone != null) {
            Box(Modifier.align(Alignment.CenterEnd)) { DoneButton(onDone) }
        }
    }
}

/** `ToolbarItem(placement: .confirmationAction) { Button("Gata") }`: a capsule in the tint colour. */
@Composable
fun DoneButton(onClick: () -> kotlin.Unit) {
    Box(
        Modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(50))
            .background(Palette.accentText)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        FoniaText("Gata", style = ui(17, UIWeight.semibold), color = Color.White)
    }
}

/** `Picker(…).pickerStyle(.segmented)`: a pale capsule with the chosen option on a white one. */
@Composable
fun SegmentedPicker(options: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> kotlin.Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Palette.cloud, RoundedCornerShape(50))
            .padding(3.dp),
    ) {
        options.forEachIndexed { index, title ->
            val isOn = index == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(if (isOn) Palette.sheet else Color.Transparent)
                    .toggleable(value = isOn, role = Role.Tab) { onSelect(index) }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                FoniaText(title, style = ui(13), color = SystemColors.label, tracking = androidx.compose.ui.unit.TextUnit.Unspecified)
            }
        }
    }
}
