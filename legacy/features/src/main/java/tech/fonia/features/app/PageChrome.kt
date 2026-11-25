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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tech.fonia.design.FloatingPanel
import tech.fonia.design.FoniaIcon
import tech.fonia.design.FoniaIconView
import tech.fonia.design.FoniaImage
import tech.fonia.design.FoniaText
import tech.fonia.design.LocalReduceMotion
import tech.fonia.design.Palette
import tech.fonia.design.SceneName
import tech.fonia.design.SkyLife
import tech.fonia.design.StyledButton
import tech.fonia.design.ToolButtonStyle
import tech.fonia.design.fTitle
import tech.fonia.design.opacity

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

/** A page that opens on a painted scene, says one sentence about itself, then lists what's in it. */
@Composable
fun ScenePage(
    scene: SceneName,
    title: String,
    sentence: String,
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
                if (!isNight) {
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
            // Every scene page carries its sentence the same way: a white card floating over the bottom of the scene.
            FloatingPanel(
                sentence,
                Modifier
                    .padding(horizontal = 16.dp)
                    .pullUp(46.dp),
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(top = 18.dp, bottom = 32.dp),
                content = content,
            )
        }
        // Set once the scene has scrolled up under the buttons: a bar in the page colour then holds them and the title,
        // so they never float over the rows and the status bar never sits on text. It comes in just before the
        // sentence under the scene reaches the buttons.
        val reduceMotion = LocalReduceMotion.current
        val threshold = with(LocalDensity.current) { (heroHeight - 110.dp).toPx() }
        val showsBar by remember(threshold) { derivedStateOf { scroll.value > threshold } }
        val barAlpha by animateFloatAsState(if (showsBar) 1f else 0f, if (reduceMotion) snap() else tween(180, easing = LinearOutSlowInEasing), label = "scene bar")
        Box(
            Modifier
                .fillMaxWidth()
                .background(pageColor.copy(alpha = pageColor.alpha * barAlpha))
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (barAlpha > 0f) {
                FoniaText(
                    title,
                    modifier = Modifier
                        .padding(horizontal = 44.dp)
                        .graphicsLayer { alpha = barAlpha }
                        .clearAndSetSemantics {},
                    style = fTitle,
                    color = titleColor,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                HeaderButton()
                Spacer(Modifier.weight(1f))
                if (onHelp != null) HelpButton(onHelp)
            }
        }
    }
}
