package tech.fonia.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.roundToInt

/** A picture from the word set, scaled to fit. Hidden from accessibility. */
@Composable
fun WordPicture(name: String, modifier: Modifier = Modifier) {
    FoniaImage(name, modifier)
}

/** A word with its target letters picked out. Colour plus weight, so it survives colour blindness. */
@Composable
fun TargetWord(
    word: String,
    graphemes: List<String>,
    target: Color,
    modifier: Modifier = Modifier,
    size: Float = 52f,
    ink: Color = Palette.ink,
) {
    val styled = remember(word, graphemes, target, ink) { styledWord(word, graphemes, target) }
    // A word shrinks before it breaks: one line per word, down to half size (`.minimumScaleFactor(0.5)`).
    val words = word.trim().split(Regex("\\s+")).size.coerceAtLeast(1)
    FoniaText(
        styled,
        modifier = modifier.clearAndSetSemantics { contentDescription = word },
        style = practice(size, PracticeWeight.regular, TextRole.largeTitle),
        color = ink,
        textAlign = TextAlign.Center,
        maxLines = words,
        minimumScaleFactor = 0.5f,
    )
}

internal fun styledWord(word: String, graphemes: List<String>, target: Color): AnnotatedString = buildAnnotatedString {
    append(word)
    val lowered = word.lowercase()
    // Longest first, so CE wins over a lone C.
    for (grapheme in graphemes.sortedByDescending { it.length }) {
        if (grapheme.isEmpty()) continue
        var searchStart = 0
        while (true) {
            val found = lowered.indexOf(grapheme, searchStart)
            if (found < 0) break
            val end = found + grapheme.length
            if (end <= word.length) {
                addStyle(SpanStyle(fontWeight = PracticeWeight.bold.fontWeight, color = target), found, end)
            }
            searchStart = end
        }
    }
}

/** A white card with one thing on it to say. There is only ever one on a screen. */
@Composable
fun PracticeSheet(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    CompositionLocalProvider(LocalTracking provides 0.sp) {
        Box(
            modifier
                .fillMaxWidth()
                .background(Palette.sheet, ContinuousRoundedRectangle(Radius.panel))
                .padding(20.dp),
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
}

/** A white card on the page. */
@Composable
fun Surface(
    modifier: Modifier = Modifier,
    padding: Dp = 16.dp,
    radius: Dp = 16.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = remember(radius) { ContinuousRoundedRectangle(radius) }
    Box(
        modifier
            .fillMaxWidth()
            .background(Palette.sheet, shape)
            .padding(padding),
        contentAlignment = Alignment.CenterStart,
        content = content,
    )
}

/** What marks a lesson: an object, or a letter in its family's colour. */
sealed interface ArtTileMark {
    data class Art(val name: String) : ArtTileMark

    data class Letter(val letter: String, val field: Field) : ArtTileMark
}

/** A small pale square with an object or a letter in it, the way each lesson is marked. */
@Composable
fun ArtTile(mark: ArtTileMark, modifier: Modifier = Modifier, side: Float = 56f) {
    // `@ScaledMetric(relativeTo: .body)`: the side grows with the font scale.
    val scaled = side * LocalDensity.current.fontScale
    val shape = remember(scaled) { ContinuousRoundedRectangle((scaled * 0.3f).dp) }
    Box(
        modifier
            .size(scaled.dp)
            .background(Palette.cloud, shape)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        when (mark) {
            is ArtTileMark.Art -> WordPicture(mark.name, Modifier.fillMaxSize().padding((scaled * 0.14f).dp))
            is ArtTileMark.Letter -> FoniaText(
                mark.letter,
                modifier = Modifier.padding(horizontal = 4.dp),
                style = practice(scaled * 0.46f, PracticeWeight.bold, TextRole.title2),
                color = mark.field.deep,
                maxLines = 1,
                minimumScaleFactor = 0.5f,
            )
        }
    }
}

@Suppress("EnumEntryName")
enum class WordMark { plain, good, weak, missed, extra, current }

data class MarkedWord(val id: Int, val text: String, val mark: WordMark = WordMark.plain)

/** A sentence broken into words that wrap like text. A marked word can be tapped to hear that bit of the take. */
@Composable
fun MarkedSentence(
    words: List<MarkedWord>,
    accent: Color,
    modifier: Modifier = Modifier,
    size: Float = 30f,
    ink: Color = Palette.ink,
    centred: Boolean = true,
    onTap: ((MarkedWord) -> Unit)? = null,
) {
    WrapLayout(modifier, spacing = (size * 0.28f).dp, lineSpacing = (-size * 0.24f).dp, centred = centred) {
        for (word in words) {
            if (onTap != null && word.mark != WordMark.plain && word.mark != WordMark.current) {
                StyledButton(onClick = { onTap(word) }, style = PressableStyle(), onClickLabel = "Ascultă bucata asta") {
                    MarkedWordLabel(word, size, ink, accent)
                }
            } else {
                MarkedWordLabel(word, size, ink, accent)
            }
        }
    }
}

@Composable
private fun MarkedWordLabel(word: MarkedWord, size: Float, ink: Color, accent: Color) {
    val mark = word.mark
    val hasBackground = mark == WordMark.missed || mark == WordMark.extra
    val spec = easeOut<Color>(0.1)
    val textColor by animateColorAsState(
        when (mark) {
            WordMark.plain, WordMark.good, WordMark.weak -> ink
            WordMark.current, WordMark.missed -> accent
            WordMark.extra -> ink.opacity(0.55)
        },
        spec,
        label = "word",
    )
    val fill by animateColorAsState(
        if (hasBackground) accent.opacity(if (mark == WordMark.extra) 0.12 else 0.2) else accent.copy(alpha = 0f),
        spec,
        label = "fill",
    )
    val underline by animateColorAsState(if (mark == WordMark.weak) accent else accent.copy(alpha = 0f), spec, label = "line")
    val shape = remember { ContinuousRoundedRectangle(7.dp) }
    FoniaText(
        word.text,
        modifier = Modifier
            .drawBehind {
                if (fill.alpha > 0f) drawOutline(shape.createOutline(this.size, layoutDirection, this), fill)
                if (underline.alpha > 0f) {
                    val h = 2.5.dp.toPx()
                    drawRect(underline, topLeft = Offset(0f, this.size.height - h + 1.dp.toPx()), size = Size(this.size.width, h))
                }
            }
            .padding(horizontal = if (hasBackground) 6.dp else 0.dp),
        style = practice(size, if (mark == WordMark.current || mark == WordMark.good) PracticeWeight.semibold else PracticeWeight.regular, TextRole.title),
        color = textColor,
    )
}

/** Wraps words like text, each line either centred or from the left edge. */
@Composable
fun WrapLayout(
    modifier: Modifier = Modifier,
    spacing: Dp = 8.dp,
    lineSpacing: Dp = 6.dp,
    centred: Boolean = false,
    content: @Composable () -> Unit,
) {
    Layout(content, modifier) { measurables, constraints ->
        // `sizeThatFits(.unspecified)`: every word at its ideal size.
        val placeables = measurables.map { it.measure(Constraints()) }
        val gap = spacing.toPx()
        val lineGap = lineSpacing.toPx()
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth.toFloat() else {
            placeables.sumOf { it.width.toDouble() }.toFloat() + gap * max(0, placeables.size - 1)
        }
        val xs = FloatArray(placeables.size)
        val ys = FloatArray(placeables.size)
        var lineStart = 0
        var x = 0f
        var y = 0f
        var lineHeight = 0f
        fun closeLine(end: Int) {
            if (!centred || end <= lineStart) return
            val last = end - 1
            val shift = (width - (xs[last] + placeables[last].width)) / 2
            for (index in lineStart until end) xs[index] += shift
        }
        placeables.forEachIndexed { index, placeable ->
            if (x > 0 && x + placeable.width > width) {
                closeLine(index)
                lineStart = index
                x = 0f
                y += lineHeight + lineGap
                lineHeight = 0f
            }
            xs[index] = x
            ys[index] = y
            x += placeable.width + gap
            lineHeight = max(lineHeight, placeable.height.toFloat())
        }
        closeLine(placeables.size)
        val height = max(0f, y + lineHeight)
        layout(
            width.roundToInt().coerceIn(constraints.minWidth, constraints.maxWidth),
            height.roundToInt().coerceIn(constraints.minHeight, constraints.maxHeight),
        ) {
            placeables.forEachIndexed { index, placeable ->
                placeable.place(xs[index].roundToInt(), ys[index].roundToInt())
            }
        }
    }
}
