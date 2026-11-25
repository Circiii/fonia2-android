package tech.fonia.features.app

import androidx.compose.foundation.background
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Constraints
import kotlin.math.max
import kotlin.math.roundToInt
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
    HeaderLine(
        modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 8.dp),
        title = {
            FoniaText(
                title,
                modifier = Modifier
                    .padding(horizontal = 58.dp)
                    .semantics { heading() },
                style = fTitle,
                color = titleColor,
                // A long title takes a second line rather than shrinking below its siblings.
                maxLines = 2,
                textAlign = TextAlign.Center,
                minimumScaleFactor = 0.85f,
            )
        },
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            HeaderButton(closes)
            Spacer(Modifier.weight(1f))
            if (onHelp != null) HelpButton(onHelp)
        }
    }
}

/**
 * A header's title over its row of round buttons. The buttons sit on the middle of the title's first line (Inter: about
 * 0.62 of the first baseline), so a title on two lines grows downwards and the buttons stay where they are on every
 * other page. The title is centred across the width; the buttons' row takes all of it.
 */
@Composable
private fun HeaderLine(modifier: Modifier = Modifier, title: @Composable () -> Unit, buttons: @Composable () -> Unit) {
    Layout(contents = listOf(title, buttons), modifier = modifier) { (titles, rows), constraints ->
        val width = constraints.maxWidth
        val loose = Constraints(maxWidth = width)
        val titlePlaceable = titles.firstOrNull()?.measure(loose)
        val rowPlaceable = rows.firstOrNull()?.measure(Constraints(minWidth = width, maxWidth = width))
        val titleLine = titlePlaceable?.let {
            val baseline = it[FirstBaseline]
            if (baseline == AlignmentLine.Unspecified) it.height / 2 else (baseline * 0.62f).roundToInt()
        } ?: 0
        val rowHalf = (rowPlaceable?.height ?: 0) / 2
        val line = max(titleLine, rowHalf)
        val titleY = line - titleLine
        val rowY = line - rowHalf
        val height = max(titleY + (titlePlaceable?.height ?: 0), rowY + (rowPlaceable?.height ?: 0))
            .coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            titlePlaceable?.place((width - titlePlaceable.width) / 2, titleY)
            rowPlaceable?.place(0, rowY)
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
    val density = LocalDensity.current
    val topPx = WindowInsets.statusBars.getTop(density)
    val top = with(density) { topPx.toDp() }
    val scroll = rememberScrollState()
    // The scene's real height under the status bar, in pixels: it grows past `heroHeight` when the title needs it.
    var heroExtent by remember { mutableStateOf<Int?>(null) }
    Box(Modifier.fillMaxSize().background(pageColor)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            // The scene, running up under the status bar. The title and the art each get their own band, so a title on
            // two lines, or at a large text size, pushes the art down (and the scene grows) instead of running into it.
            Box(
                Modifier
                    .fillMaxWidth()
                    .clipToBounds()
                    .onSizeChanged { heroExtent = it.height - topPx },
            ) {
                FoniaImage("scene-${scene.rawValue}-tall", Modifier.matchParentSize(), contentScale = ContentScale.Crop)
                if (scene.hasSky) SkyLife(isNight = isNight, modifier = Modifier.matchParentSize())
                SceneHero(
                    topInset = top,
                    minHeight = heroHeight + top,
                    title = {
                        FoniaText(
                            title,
                            modifier = Modifier.semantics { heading() },
                            style = fTitle,
                            color = titleColor,
                            textAlign = TextAlign.Center,
                        )
                    },
                    art = { Box(contentAlignment = Alignment.Center, content = art) },
                )
                // Not a decoration: the scene's lower edge melts into the page under the floating sentence, so the
                // picture has no hard cut behind the card.
                if (!isNight) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(90.dp)
                            .background(Brush.verticalGradient(listOf(pageColor.opacity(0.0), pageColor))),
                    )
                }
            }
            // Every scene page carries its sentence the same way: a white card floating over the bottom of the scene.
            FloatingPanel(
                sentence,
                Modifier
                    .padding(horizontal = 16.dp)
                    .pullUp(PanelOverlap),
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
        val defaultExtent = with(density) { heroHeight.roundToPx() }
        val margin = with(density) { 110.dp.roundToPx() }
        val showsBar by remember(defaultExtent, margin) { derivedStateOf { scroll.value > (heroExtent ?: defaultExtent) - margin } }
        val barAlpha by animateFloatAsState(if (showsBar) 1f else 0f, if (reduceMotion) snap() else tween(180, easing = LinearOutSlowInEasing), label = "scene bar")
        HeaderLine(
            Modifier
                .fillMaxWidth()
                .background(pageColor.copy(alpha = pageColor.alpha * barAlpha))
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 8.dp),
            title = {
                // Same rule as `PageHeader`: a long title takes a second line instead of being cut.
                if (barAlpha > 0f) {
                    FoniaText(
                        title,
                        modifier = Modifier
                            .padding(horizontal = 58.dp)
                            .graphicsLayer { alpha = barAlpha }
                            .clearAndSetSemantics {},
                        style = fTitle,
                        color = titleColor,
                        maxLines = 2,
                        textAlign = TextAlign.Center,
                        minimumScaleFactor = 0.85f,
                    )
                }
            },
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                HeaderButton()
                Spacer(Modifier.weight(1f))
                if (onHelp != null) HelpButton(onHelp)
            }
        }
    }
}

/** How far the sentence card climbs over the bottom of the scene. */
private val PanelOverlap = 46.dp

/**
 * Lays out a scene's title under the status bar and its art centred in the room left between the title and the sentence
 * card. The scene is at least [minHeight] tall and grows when the two would otherwise touch.
 */
@Composable
private fun SceneHero(topInset: Dp, minHeight: Dp, title: @Composable () -> Unit, art: @Composable () -> Unit) {
    Layout(contents = listOf(title, art), modifier = Modifier.fillMaxWidth()) { (titles, arts), constraints ->
        val width = constraints.maxWidth
        // Room for the round buttons on each side of the title.
        val side = 60.dp.roundToPx()
        val titleTop = topInset.roundToPx() + 12.dp.roundToPx()
        val gap = 12.dp.roundToPx()
        val overlap = PanelOverlap.roundToPx()
        val titlePlaceable = titles.firstOrNull()?.measure(Constraints(maxWidth = (width - side * 2).coerceAtLeast(0)))
        val artPlaceable = arts.firstOrNull()?.measure(Constraints(maxWidth = width))
        val titleHeight = titlePlaceable?.height ?: 0
        val artHeight = artPlaceable?.height ?: 0
        val artRoom = if (artHeight > 0) gap + artHeight + gap else 0
        val height = max(minHeight.roundToPx(), titleTop + titleHeight + artRoom + overlap)
        layout(width, height) {
            titlePlaceable?.place((width - titlePlaceable.width) / 2, titleTop)
            if (artPlaceable != null) {
                val bandTop = titleTop + titleHeight + gap
                val bandBottom = height - overlap - gap
                artPlaceable.place((width - artPlaceable.width) / 2, (bandTop + bandBottom - artHeight) / 2)
            }
        }
    }
}
