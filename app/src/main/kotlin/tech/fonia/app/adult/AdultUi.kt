package tech.fonia.app.adult

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import tech.fonia.app.text.T
import tech.fonia.app.ui.RoundGlyphButton
import tech.fonia.designsystem.components.QuietButton
import tech.fonia.designsystem.theme.FoniaShell
import tech.fonia.designsystem.theme.FoniaTheme
import tech.fonia.designsystem.tokens.FoniaCorner
import tech.fonia.designsystem.tokens.FoniaSpace
import tech.fonia.designsystem.tokens.FoniaTarget

/**
 * A page of the adult shell (chapter 01 §2): text, no Foni, „voi”, ROND ~30 (`FoniaShell.Grown`), 48 dp targets, a
 * back control and a scrolling column. [overlay] draws above the page (sheets).
 */
@Composable
fun AdultPage(
    title: String,
    onBack: () -> Unit,
    reduceMotion: Boolean,
    overlay: @Composable BoxScope.() -> Unit = {},
    /** False on a root page (TEEN-HOME): no back control. */
    showBack: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    FoniaTheme(shell = FoniaShell.Grown, reduceMotion = if (reduceMotion) true else null) {
        val c = FoniaTheme.colors
        Box(Modifier.fillMaxSize().background(c.bg)) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = FoniaSpace.pageGrown, vertical = FoniaSpace.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showBack) {
                        RoundGlyphButton("←", T.adultBack, onClick = onBack)
                        Spacer(Modifier.width(FoniaSpace.s))
                    } else {
                        Spacer(Modifier.height(FoniaTarget.minimum))
                    }
                    BasicText(title, style = FoniaTheme.type.title.copy(color = c.ink), modifier = Modifier.semantics { heading() })
                }
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = FoniaSpace.pageGrown),
                    verticalArrangement = Arrangement.spacedBy(FoniaSpace.s),
                ) {
                    Spacer(Modifier.height(FoniaSpace.xs))
                    content()
                    Spacer(Modifier.height(FoniaSpace.xl))
                }
            }
            overlay()
        }
    }
}

/** A white card (corner 20 dp), the adult shell's grouping. */
@Composable
fun AdultCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .background(FoniaTheme.colors.surface, RoundedCornerShape(FoniaCorner.card))
            .padding(FoniaSpace.m),
        verticalArrangement = Arrangement.spacedBy(FoniaSpace.xs),
        content = content,
    )
}

@Composable
fun AdultText(text: String, secondary: Boolean = false, modifier: Modifier = Modifier) {
    val c = FoniaTheme.colors
    BasicText(text, style = FoniaTheme.type.body.copy(color = if (secondary) c.inkSecondary else c.ink), modifier = modifier)
}

@Composable
fun AdultCaption(text: String, modifier: Modifier = Modifier) {
    BasicText(text, style = FoniaTheme.type.caption.copy(color = FoniaTheme.colors.inkSecondary), modifier = modifier)
}

@Composable
fun AdultHeading(text: String) {
    BasicText(text, style = FoniaTheme.type.headline.copy(color = FoniaTheme.colors.ink), modifier = Modifier.semantics { heading() })
}

/** A tappable row: title, optional help line, a chevron. */
@Composable
fun AdultRow(title: String, help: String? = null, onClick: () -> Unit) {
    val c = FoniaTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = FoniaTarget.minimum)
            .background(c.surface, RoundedCornerShape(FoniaCorner.card))
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = FoniaSpace.m, vertical = FoniaSpace.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(title, style = FoniaTheme.type.headline.copy(color = c.ink))
            if (help != null) AdultCaption(help)
        }
        BasicText("›", style = FoniaTheme.type.title2.copy(color = c.inkSecondary))
    }
}

/** One choice among a few: each option a quiet pill, the selected one emphasised. */
@Composable
fun <T> AdultChoice(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(FoniaSpace.xs)) {
        for ((value, label) in options) {
            QuietButton(
                (if (value == selected) "● " else "○ ") + label,
                onClick = { onSelect(value) },
                emphasised = value == selected,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * One on/off setting as a single control (the whole row toggles): title, help, and a switch whose state shows by the
 * thumb's side, the fill and the word („Pornit” / „Oprit”), never by colour alone.
 */
@Composable
fun AdultToggle(title: String, help: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = FoniaTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = FoniaTarget.minimum)
            .background(c.surface, RoundedCornerShape(FoniaCorner.card))
            .toggleable(checked, remember { MutableInteractionSource() }, indication = null, role = Role.Switch) { onChange(it) }
            .padding(horizontal = FoniaSpace.m, vertical = FoniaSpace.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(title, style = FoniaTheme.type.headline.copy(color = c.ink))
            if (help != null) AdultCaption(help)
        }
        Spacer(Modifier.width(FoniaSpace.s))
        BasicText(if (checked) T.on else T.off, style = FoniaTheme.type.label.copy(color = c.inkSecondary))
        Spacer(Modifier.width(FoniaSpace.xs))
        Box(
            Modifier.size(width = 52.dp, height = 32.dp).drawBehind {
                val r = size.height / 2
                drawRoundRect(if (checked) c.accent else c.fill, cornerRadius = androidx.compose.ui.geometry.CornerRadius(r))
                if (!checked) drawRoundRect(c.separator, cornerRadius = androidx.compose.ui.geometry.CornerRadius(r), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
                val k = 4.dp.toPx()
                val cx = if (checked) size.width - r else r
                drawCircle(c.surface, r - k, androidx.compose.ui.geometry.Offset(cx, r))
            },
        )
    }
}
