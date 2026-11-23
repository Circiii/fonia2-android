package tech.fonia.features.app

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tech.fonia.design.ContinuousTopRoundedRectangle
import tech.fonia.design.FloatingPanel
import tech.fonia.design.FoniaIcon
import tech.fonia.design.FoniaIconView
import tech.fonia.design.FoniaImage
import tech.fonia.design.FoniaText
import tech.fonia.design.Palette
import tech.fonia.design.SceneName
import tech.fonia.design.SkyLife
import tech.fonia.design.StyledButton
import tech.fonia.design.TextRole
import tech.fonia.design.ToolButtonStyle
import tech.fonia.design.UIWeight
import tech.fonia.design.fTitle
import tech.fonia.design.opacity
import tech.fonia.design.ui

/** SwiftUI's negative padding: the view moves up by [amount] and takes that much less room. */
fun Modifier.pullUp(amount: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val shift = amount.roundToPx()
    layout(placeable.width, (placeable.height - shift).coerceAtLeast(0)) { placeable.place(0, -shift) }
}

/** The round button at the top corner of a page: back, or close for a page that shuts a whole area. */
@Composable
fun HeaderButton(closes: Boolean = false, onClick: () -> Unit = LocalDismiss.current) {
    StyledButton(
        onClick = onClick,
        style = ToolButtonStyle(),
        modifier = Modifier.semantics { contentDescription = if (closes) "Închide" else "Înapoi" },
    ) {
        FoniaIconView(if (closes) FoniaIcon.close else FoniaIcon.back, size = 18f)
    }
}

/** "Ce e aici?": the help button opposite the back button. */
@Composable
fun HelpButton(onClick: () -> Unit) {
    StyledButton(onClick = onClick, style = ToolButtonStyle(), modifier = Modifier.semantics { contentDescription = "Ce e aici?" }) {
        FoniaIconView(FoniaIcon.help, size = 18f)
    }
}

/** The row at the top of a page: a round button on each side and the title between them. */
@Composable
fun PageHeader(
    title: String,
    modifier: Modifier = Modifier,
    closes: Boolean = false,
    titleColor: Color = Palette.ink,
    onHelp: (() -> Unit)? = null,
) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        FoniaText(
            title,
            modifier = Modifier
                .padding(horizontal = 58.dp)
                .semantics { heading() },
            style = fTitle,
            color = titleColor,
            maxLines = 1,
            minimumScaleFactor = 0.6f,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            HeaderButton(closes)
            Spacer(Modifier.weight(1f))
            if (onHelp != null) HelpButton(onHelp)
        }
    }
}

/**
 * `.pageHeader(_:)`: a plain page. The header stays put on the page colour and the content goes under it; the system
 * bars are the page's own, as when iOS hides its navigation bar.
 */
@Composable
fun HeaderPage(
    title: String,
    modifier: Modifier = Modifier,
    closes: Boolean = false,
    onHelp: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxSize().background(Palette.paper)) {
        PageHeader(
            title,
            Modifier
                .background(Palette.paper)
                .windowInsetsPadding(WindowInsets.statusBars),
            closes = closes,
            onHelp = onHelp,
        )
        content()
    }
}

/** How a page with a scene carries its one sentence. */
@Suppress("EnumEntryName")
enum class ScenePanel {
    /** A pale band joined to the bottom of the scene. */
    attached,

    /** A white card floating over the bottom of the scene. */
    floating,
}

/** A page that opens on a painted scene, says one sentence about itself, then lists what's in it. */
@Composable
fun ScenePage(
    scene: SceneName,
    title: String,
    sentence: String,
    panel: ScenePanel = ScenePanel.floating,
    isNight: Boolean = false,
    onPaper: Boolean = false,
    heroHeight: Dp = 340.dp,
    onHelp: (() -> Unit)? = null,
    art: @Composable BoxScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val pageColor = if (isNight) Palette.nightPage else if (onPaper) Palette.paper else Palette.sheet
    val titleColor = if (isNight) Palette.nightText else Palette.ink
    val top = with(LocalDensity.current) { WindowInsets.statusBars.getTop(this).toDp() }
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize().background(pageColor)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            // The scene, running up under the status bar.
            Box(Modifier.fillMaxWidth().height(heroHeight + top).clipToBounds()) {
                FoniaImage("scene-${scene.rawValue}-tall", Modifier.matchParentSize(), contentScale = ContentScale.Crop)
                if (scene.hasSky) SkyLife(isNight = isNight, modifier = Modifier.matchParentSize())
                Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center, content = art)
                if (panel == ScenePanel.floating && !isNight) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(90.dp)
                            .background(Brush.verticalGradient(listOf(pageColor.opacity(0.0), pageColor))),
                    )
                }
                FoniaText(
                    title,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = top + 12.dp, start = 60.dp, end = 60.dp)
                        .semantics { heading() },
                    style = fTitle,
                    color = titleColor,
                    textAlign = TextAlign.Center,
                )
            }
            when (panel) {
                ScenePanel.attached -> FoniaText(
                    sentence,
                    modifier = Modifier
                        .pullUp(20.dp)
                        .fillMaxWidth()
                        .background(Palette.cloud, ContinuousTopRoundedRectangle(20.dp))
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    style = ui(15, UIWeight.medium, TextRole.subheadline),
                    color = Palette.ink,
                    textAlign = TextAlign.Center,
                )
                ScenePanel.floating -> FloatingPanel(
                    sentence,
                    Modifier
                        .padding(horizontal = 16.dp)
                        .pullUp(46.dp),
                )
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(top = if (panel == ScenePanel.attached) 16.dp else 18.dp, bottom = 32.dp),
                content = content,
            )
        }
        // Once the list scrolls past the scene, the page colour veils the status bar and the back button's strip, so
        // rows no longer run under the clock (QA 2026-11-21; iOS's scroll-edge effect).
        Box(
            Modifier
                .fillMaxWidth()
                .height(top + 64.dp)
                .graphicsLayer {
                    val start = (heroHeight + top).toPx() * 0.55f
                    alpha = ((scroll.value - start) / 120.dp.toPx()).coerceIn(0f, 1f)
                }
                .background(Brush.verticalGradient(0f to pageColor, 0.7f to pageColor, 1f to pageColor.copy(alpha = 0f))),
        )
        Row(
            Modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeaderButton()
            Spacer(Modifier.weight(1f))
            if (onHelp != null) HelpButton(onHelp)
        }
    }
}
