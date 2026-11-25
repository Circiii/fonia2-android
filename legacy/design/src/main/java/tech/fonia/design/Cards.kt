package tech.fonia.design

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/** The corners the app uses, and only these: a small tile or chip, a card or row, a panel that holds other things. */
object Radius {
    val small = 12.dp
    val card = 16.dp
    val panel = 22.dp
}

private val cardShape = ContinuousRoundedRectangle(Radius.card)

/** A door on the home page: a scene with a short label under it. */
@Composable
fun HomeTile(scene: SceneName, title: String, modifier: Modifier = Modifier, aspect: Float = 1.89f) {
    Column(modifier.clip(cardShape).clearAndSetSemantics { contentDescription = title }) {
        SceneArt(scene, aspect, Modifier.fillMaxWidth())
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 39.dp)
                .background(Palette.sheet)
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            FoniaText(
                title,
                style = ui(18, UIWeight.semibold, TextRole.headline),
                color = Palette.ink,
                maxLines = 1,
                minimumScaleFactor = 0.7f,
            )
        }
    }
}

/** A whole-width card: a wide scene, how much of it is finished, its name and a bar. [showsCount] false keeps the bar alone. */
@Composable
fun WideCard(
    scene: SceneName,
    title: String,
    done: Int,
    total: Int,
    modifier: Modifier = Modifier,
    showsCount: Boolean = true,
    detail: String? = null,
    art: @Composable BoxScope.() -> Unit = {},
) {
    Column(
        modifier
            .clip(cardShape)
            .clearAndSetSemantics {
                contentDescription = title
                if (showsCount) stateDescription = "$done din $total"
            },
    ) {
        Box {
            SceneArt(scene, 2.5f, Modifier.fillMaxWidth())
            Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center, content = art)
        }
        Column(
            Modifier
                .fillMaxWidth()
                .background(Palette.sheet)
                .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (showsCount) FoniaText("$done din $total", style = fCaption.monospacedDigit(), color = Palette.ink)
            FoniaText(title, style = fHeadline, color = Palette.ink, textAlign = TextAlign.Start)
            if (detail != null) {
                FoniaText(detail, style = fCaption, color = Palette.inkSoft, textAlign = TextAlign.Start, maxLines = 2)
            }
            ProgressPill(done, total, Modifier.padding(top = 2.dp))
        }
    }
}

/** A card in a two-column grid: a scene, a title and an optional line under it. */
@Composable
fun GridCard(
    scene: SceneName,
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    art: @Composable BoxScope.() -> Unit = {},
) {
    Column(
        modifier
            .clip(cardShape)
            .clearAndSetSemantics { contentDescription = if (detail != null) "$title, $detail" else title },
    ) {
        Box {
            SceneArt(scene, 1.515f, Modifier.fillMaxWidth())
            Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center, content = art)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 54.dp)
                .background(Palette.cloud)
                .padding(10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                FoniaText(
                    title,
                    style = ui(15, UIWeight.bold, TextRole.subheadline),
                    color = Palette.ink,
                    textAlign = TextAlign.Start,
                    maxLines = 2,
                    minimumScaleFactor = 0.85f,
                )
                if (detail != null) {
                    FoniaText(
                        detail,
                        style = ui(13, UIWeight.medium, TextRole.caption),
                        color = Palette.ink,
                        textAlign = TextAlign.Start,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

/** A pale track with a mint bar. Something always shows, so the very start doesn't read as empty. */
@Composable
fun ProgressPill(done: Int, total: Int, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(19.dp).clearAndSetSemantics {}) {
        val share = min(done, total).toFloat() / max(total, 1)
        val inset = 4.dp.toPx()
        val inner = size.width - 2 * inset
        drawRoundRect(Palette.progressTrack, cornerRadius = CornerRadius(size.height / 2))
        if (done > 0) {
            val bar = 11.dp.toPx()
            drawRoundRect(
                Palette.progress,
                topLeft = Offset(inset, (size.height - bar) / 2),
                size = Size(max(inner * 0.1f, inner * share), bar),
                cornerRadius = CornerRadius(bar / 2),
            )
        }
    }
}

/**
 * A count at the top of the home page: an icon and a number that only ever goes up, with the word after it when
 * [unit] is given ("3 zile").
 */
@Composable
fun CountPill(icon: FoniaIcon, tint: Color, value: Int, label: String, modifier: Modifier = Modifier, unit: String? = null) {
    Row(
        modifier
            .softShadow(Capsule)
            .background(Palette.sheet, Capsule)
            .heightIn(min = 36.dp)
            .padding(horizontal = 10.dp)
            .clearAndSetSemantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FoniaIconView(icon, size = 21f, relativeTo = TextRole.subheadline, tint = tint)
        NumericText(value, ui(15, UIWeight.bold, TextRole.subheadline).monospacedDigit(), Palette.ink)
        if (unit != null) FoniaText(unit, style = ui(15, UIWeight.bold, TextRole.subheadline), color = Palette.ink, maxLines = 1)
    }
}

/**
 * `.contentTransition(.numericText(value:))` with `.snappy`: each digit that changes rolls, up when the value
 * grows and down when it shrinks; the others stay put.
 */
@Composable
internal fun NumericText(value: Int, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    var previous by remember { mutableIntStateOf(value) }
    val up = value >= previous
    SideEffect { previous = value }
    val text = "$value"
    Row(modifier) {
        text.forEachIndexed { index, digit ->
            // Keyed by place value (ones, tens, …), so 9 → 10 rolls the ones and brings in a new tens digit.
            key(text.length - index) {
                AnimatedContent(
                    targetState = digit,
                    transitionSpec = {
                        val spec: ContentTransform = (
                            slideInVertically(snappySpring()) { if (up) it else -it } + fadeIn(snappySpring())
                            ) togetherWith (slideOutVertically(snappySpring()) { if (up) -it else it } + fadeOut(snappySpring()))
                        spec.using(SizeTransform(clip = true))
                    },
                    label = "digit",
                ) { shown ->
                    FoniaText("$shown", style = style, color = color)
                }
            }
        }
    }
}

/** A sheet that rises over a scene: rounded on top and running off the bottom of the page. */
@Composable
fun RisingPanel(
    modifier: Modifier = Modifier,
    color: Color = Palette.sage,
    radius: Dp = 25.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = remember(radius) { ContinuousTopRoundedRectangle(radius) }
    val below = WindowInsets.navigationBars.getBottom(LocalDensity.current)
    Box(
        modifier
            .fillMaxWidth()
            .drawBehind {
                // `.ignoresSafeArea(edges: .bottom)`: the sheet carries on under the system's bottom bar.
                val outline = shape.createOutline(Size(size.width, size.height + below), layoutDirection, this)
                drawOutline(outline, color)
            },
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** A white panel that floats over the bottom of a scene, holding one short sentence. */
@Composable
fun FloatingPanel(text: String, modifier: Modifier = Modifier) {
    // One or two lines read well centred. A paragraph (the English cues run to five lines) is set flush left, so every
    // line starts at the same edge.
    val isParagraph = text.length > if (isLargeText) 70 else 120
    Box(
        modifier
            .fillMaxWidth()
            .background(Palette.sheet, ContinuousRoundedRectangle(Radius.panel))
            .padding(horizontal = if (isParagraph) 18.dp else 16.dp, vertical = 16.dp),
        contentAlignment = if (isParagraph) Alignment.CenterStart else Alignment.Center,
    ) {
        FoniaText(
            text,
            Modifier.fillMaxWidth(),
            style = fBody,
            color = Palette.ink,
            textAlign = if (isParagraph) TextAlign.Start else TextAlign.Center,
        )
    }
}

/** A rounded white card on the progress page. */
@Composable
fun StatCard(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .background(Palette.sheet, ContinuousRoundedRectangle(Radius.panel)),
        contentAlignment = Alignment.CenterStart,
        content = content,
    )
}

/** One count on the progress page: an object on the left, its name and value on the right. */
@Composable
fun StatRow(art: String, label: String, value: String, modifier: Modifier = Modifier) {
    StatCard(
        modifier.clearAndSetSemantics {
            contentDescription = label
            stateDescription = value
        },
    ) {
        Row(
            Modifier
                .heightIn(min = 82.dp)
                .padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WordPicture(art, Modifier.padding(start = 16.dp).size(54.dp))
            Column(
                Modifier
                    .weight(1f)
                    .padding(end = 16.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                FoniaText(label, style = ui(15, UIWeight.regular, TextRole.subheadline), color = Palette.ink, textAlign = TextAlign.Center)
                FoniaText(value, style = fValue, color = Palette.accentText)
            }
        }
    }
}

/**
 * The faint lift under a pill: `shadow(color: Palette.pillShadow, radius: 6, y: 3)`, a Gaussian of 6 pt
 * moved 3 pt down. Nothing bigger than a pill gets this.
 */
fun Modifier.softShadow(shape: Shape = Capsule): Modifier = drawBehind {
    val outline = shape.createOutline(size, layoutDirection, this)
    val path = Path().apply { addOutline(outline) }.asAndroidPath()
    platform { canvas ->
        val paint = fillPaint(Palette.pillShadow)
        paint.maskFilter = blurFilter(6.0 * density)
        canvas.save()
        canvas.translate(0f, 3.dp.toPx())
        canvas.drawPath(path, paint)
        canvas.restore()
    }
}
