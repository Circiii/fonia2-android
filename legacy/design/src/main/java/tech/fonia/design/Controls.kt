package tech.fonia.design

import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/** `Animation.spring(duration: 0.12, bounce: 0)`: every press. */
private val press = swiftSpring<Float>(0.12, 0.0)

/** What a button style draws with, like SwiftUI's `ButtonStyleConfiguration`. */
class ButtonConfiguration(
    val label: @Composable () -> Unit,
    val isPressed: Boolean,
    /** `@Environment(\.isEnabled)`. */
    val isEnabled: Boolean,
)

/** SwiftUI's `ButtonStyle`: one look, applied to any label by [StyledButton]. */
interface ButtonStyle {
    /** Draws the button; [modifier] carries the click handling and goes on the outermost layout. */
    @Composable
    fun MakeBody(configuration: ButtonConfiguration, modifier: Modifier)
}

/** `Button(action:label:).buttonStyle(style)`. */
@Composable
fun StyledButton(
    onClick: () -> Unit,
    style: ButtonStyle,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClickLabel: String? = null,
    label: @Composable () -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val reduceMotion = LocalReduceMotion.current
    val view = androidx.compose.ui.platform.LocalView.current
    // Press feedback (v2): the button gives under the finger and springs back, with a light tick; his look is unchanged.
    val scale by animateFloatAsState(
        if (pressed && enabled && !reduceMotion) 0.94f else 1f,
        androidx.compose.animation.core.spring(dampingRatio = 0.45f, stiffness = 700f),
        label = "press",
    )
    style.MakeBody(
        ButtonConfiguration(label = label, isPressed = pressed && enabled, isEnabled = enabled),
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(
                interactionSource = interactions,
                indication = null,
                enabled = enabled,
                onClickLabel = onClickLabel,
                role = Role.Button,
                onClick = {
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    FoniaSfx.play(SfxCue.Tap)
                    onClick()
                },
            ),
    )
}

/** `Label { Text(title) } icon: { … }` in the `.titleAndIcon` style: icon, 8 pt, title. */
@Composable
fun FoniaLabel(title: String, modifier: Modifier = Modifier, icon: (@Composable () -> Unit)? = null) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        icon?.invoke()
        FoniaText(title)
    }
}

/** `FoniaLabel` with one of the set's icons. */
@Composable
fun FoniaLabel(title: String, icon: FoniaIcon, iconSize: Float = 22f, modifier: Modifier = Modifier) =
    FoniaLabel(title, modifier) { FoniaIconView(icon, size = iconSize) }

/**
 * A solid piece on a darker edge. When it's pressed the face goes down over the edge. The edge is the same
 * shape moved down by [depth], and the piece keeps [depth] of room under it, so pressing never moves the
 * layout around it.
 */
@Composable
internal fun Modifier.raised(shape: Shape, face: Color, edge: Color, depth: Dp, isPressed: Boolean): Modifier {
    val pressed by animateFloatAsState(if (isPressed) 1f else 0f, press, label = "raised")
    return this
        .drawBehind {
            val d = depth.toPx()
            val outline = shape.createOutline(Size(size.width, size.height - d), layoutDirection, this)
            translate(top = d) { drawOutline(outline, edge) }
        }
        .padding(bottom = depth)
        .graphicsLayer { translationY = pressed * depth.toPx() }
        .background(face, shape)
}

internal val Capsule: Shape = RoundedCornerShape(percent = 50)

/** The main action: green, on a dark edge. There's one on a screen. */
class FoniButtonStyle(
    val field: Field = Field.paper,
    val size: Size = Size.large,
    val kind: Kind = Kind.primary,
    /** The least height, when a lesson asks for more than the size gives (64 for a child's main action). */
    val minHeight: Dp? = null,
) : ButtonStyle {
    @Suppress("EnumEntryName")
    enum class Size { regular, large }

    @Suppress("EnumEntryName")
    enum class Kind { primary, secondary }

    @Composable
    override fun MakeBody(configuration: ButtonConfiguration, modifier: Modifier) {
        val primary = kind == Kind.primary
        CompositionLocalProvider(
            LocalFont provides if (size == Size.large) fButton else ui(16, UIWeight.bold, TextRole.headline),
            LocalForeground provides if (primary) Palette.buttonLabel else Palette.ink,
            LocalTextAlign provides TextAlign.Center,
        ) {
            Box(
                modifier
                    .alpha(if (configuration.isEnabled) 1f else 0.5f)
                    .raised(
                        shape = Capsule,
                        face = if (primary) Palette.accent else Palette.sheet,
                        edge = if (primary) Palette.accentEdge else Palette.chipEdge,
                        depth = 3.3.dp,
                        isPressed = configuration.isPressed,
                    )
                    .fillMaxWidth()
                    .heightIn(min = minHeight ?: if (size == Size.large) 48.dp else 44.dp)
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center,
            ) { configuration.label() }
        }
    }
}

/** One of two choices side by side, in a strong colour of its own. Same build as the main action. */
class ChoicePillStyle(val tone: Tone) : ButtonStyle {
    @Suppress("EnumEntryName")
    enum class Tone { main, quiet }

    @Composable
    override fun MakeBody(configuration: ButtonConfiguration, modifier: Modifier) {
        val main = tone == Tone.main
        CompositionLocalProvider(
            LocalFont provides ui(15.5, UIWeight.bold, TextRole.headline),
            LocalForeground provides if (main) Palette.buttonLabel else Palette.ink,
            LocalTextAlign provides TextAlign.Center,
        ) {
            Box(
                modifier
                    .raised(
                        shape = Capsule,
                        face = if (main) Palette.accent else Palette.sheet,
                        edge = if (main) Palette.accentEdge else Palette.chipEdge,
                        depth = 3.3.dp,
                        isPressed = configuration.isPressed,
                    )
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) { configuration.label() }
        }
    }
}

/** The words of the one thing to do next. */
@Composable
fun CTALabel(title: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { FoniaText(title) }
}

/** A quiet action that stays on the page: no sheet, just the words. */
class QuietButtonStyle(val color: Color) : ButtonStyle {
    @Composable
    override fun MakeBody(configuration: ButtonConfiguration, modifier: Modifier) {
        CompositionLocalProvider(LocalFont provides fBodyStrong, LocalForeground provides color) {
            Box(
                modifier
                    .alpha(if (configuration.isPressed) 0.55f else 1f)
                    .padding(horizontal = 12.dp)
                    .heightIn(min = 44.dp),
                contentAlignment = Alignment.Center,
            ) { configuration.label() }
        }
    }
}

/** Round control for what the voice does: play, record, stop. */
class RoundButtonStyle(
    val fill: Color = Palette.accent,
    val ink: Color = Palette.buttonLabel,
    val diameter: Dp = 72.dp,
) : ButtonStyle {
    @Composable
    override fun MakeBody(configuration: ButtonConfiguration, modifier: Modifier) {
        val scale by animateFloatAsState(if (configuration.isPressed) 0.96f else 1f, press, label = "round")
        CompositionLocalProvider(LocalForeground provides ink) {
            Box(
                modifier
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .size(diameter)
                    .background(fill, CircleShape),
                contentAlignment = Alignment.Center,
            ) { configuration.label() }
        }
    }
}

/** A white tile for anything picked from a set: an answer, a sound, a profile. */
class TileButtonStyle(
    val face: Color = Palette.sheet,
    val border: Color? = null,
    val radius: Dp = 16.dp,
) : ButtonStyle {
    @Composable
    override fun MakeBody(configuration: ButtonConfiguration, modifier: Modifier) {
        val scale by animateFloatAsState(if (configuration.isPressed) 0.98f else 1f, press, label = "tile")
        val shape = remember(radius) { ContinuousRoundedRectangle(radius) }
        Box(
            modifier
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .background(face, shape)
                .then(if (border != null) Modifier.border(2.dp, border, shape) else Modifier),
            contentAlignment = Alignment.Center,
        ) { configuration.label() }
    }
}

/** Small rounded action with an icon, next to its neighbours. */
class PillButtonStyle(val minHeight: Dp = 44.dp) : ButtonStyle {
    @Composable
    override fun MakeBody(configuration: ButtonConfiguration, modifier: Modifier) {
        CompositionLocalProvider(
            LocalFont provides ui(15, UIWeight.semibold, TextRole.subheadline),
            LocalForeground provides Palette.ink,
        ) {
            Box(
                modifier
                    .raised(Capsule, Palette.sheet, Palette.chipEdge, 2.3.dp, configuration.isPressed)
                    .fillMaxWidth()
                    .heightIn(min = minHeight)
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) { configuration.label() }
        }
    }
}

/**
 * One of a row of equal small actions inside a card: the icon over a short word, on the raised white face. Give its
 * label as [IconOverTitle]. 44 is the least for an adult's finger; a child's controls ask for 56.
 */
class IconTileButtonStyle(val minHeight: Dp = 54.dp) : ButtonStyle {
    @Composable
    override fun MakeBody(configuration: ButtonConfiguration, modifier: Modifier) {
        val shape = RoundedCornerShape(18.dp)
        CompositionLocalProvider(
            LocalFont provides ui(14, UIWeight.semibold, TextRole.subheadline),
            LocalForeground provides Palette.ink,
            LocalTextAlign provides TextAlign.Center,
        ) {
            Box(
                modifier
                    .raised(shape, Palette.sheet, Palette.chipEdge, 2.3.dp, configuration.isPressed)
                    .border(1.5.dp, Palette.line, shape)
                    .fillMaxWidth()
                    .heightIn(min = maxOf(44.dp, minHeight))
                    .padding(6.dp),
                contentAlignment = Alignment.Center,
            ) { configuration.label() }
        }
    }
}

/** The label of an [IconTileButtonStyle]: the icon over the word, at most two lines. */
@Composable
fun IconOverTitle(title: String, icon: FoniaIcon, iconSize: Float = 20f, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FoniaIconView(icon, size = iconSize)
        FoniaText(title, maxLines = 2)
    }
}

/** A small helper inside a card, quieter than anything else that can be pressed. */
class MiniPillStyle : ButtonStyle {
    @Composable
    override fun MakeBody(configuration: ButtonConfiguration, modifier: Modifier) {
        val scale by animateFloatAsState(
            if (configuration.isPressed) 0.96f else 1f,
            swiftSpring(0.18, 0.2),
            label = "minipill",
        )
        CompositionLocalProvider(
            LocalFont provides ui(13.5, UIWeight.semibold, TextRole.footnote),
            LocalForeground provides Palette.accentText,
            LocalLineLimit provides 1,
        ) {
            Box(
                modifier
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .background(Palette.accentSoft, Capsule)
                    .heightIn(min = 34.dp)
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) { configuration.label() }
        }
    }
}

/** Round white button for the top of a screen: back, close, help, settings. */
class ToolButtonStyle(
    val field: Field = Field.paper,
    val diameter: Dp = 40.dp,
) : ButtonStyle {
    @Composable
    override fun MakeBody(configuration: ButtonConfiguration, modifier: Modifier) {
        CompositionLocalProvider(LocalForeground provides Palette.accent) {
            Box(
                modifier
                    .padding(2.dp)
                    .raised(CircleShape, Palette.sheet, Palette.chipEdge, 2.3.dp, configuration.isPressed)
                    .size(diameter),
                contentAlignment = Alignment.Center,
            ) { configuration.label() }
        }
    }
}

/** The round plus at the bottom of a page. */
class PlusButtonStyle : ButtonStyle {
    @Composable
    override fun MakeBody(configuration: ButtonConfiguration, modifier: Modifier) {
        val scale by animateFloatAsState(if (configuration.isPressed) 0.96f else 1f, press, label = "plus")
        CompositionLocalProvider(LocalForeground provides Palette.buttonLabel) {
            Box(
                modifier
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .size(56.dp)
                    .background(Palette.accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) { configuration.label() }
        }
    }
}

/**
 * What stays pinned above the system's navigation area. On iOS it goes in `safeAreaBar`, so the page fades
 * out behind it; the screen that holds it decides that here.
 */
@Composable
fun Dock(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 6.dp)) { content() }
}

/** The three answers an adult or a learner can give. Shape carries the meaning, not just colour. */
@Suppress("EnumEntryName")
enum class Rating {
    yes, nearly, notYet;

    val color: Color
        get() = when (this) {
            yes -> Palette.right
            nearly -> Palette.almostDeep
            notYet -> Palette.notYetDeep
        }

    val soft: Color
        get() = when (this) {
            yes -> Palette.rightSoft
            nearly -> Palette.almostSoft
            notYet -> Palette.notYetSoft
        }
}

/** A ring; full with the tick cut out of it for "yes", half full for "nearly", empty for "not yet". */
@Composable
fun RatingMark(rating: Rating, modifier: Modifier = Modifier, color: Color? = null) {
    val ink = color ?: rating.color
    Canvas(modifier) {
        platform { canvas ->
            canvas.save()
            canvas.scale(density, density)
            val w = size.width / density.toDouble()
            val h = size.height / density.toDouble()
            val d = min(w, h)
            val rect = CGRect((w - d) / 2 + 2, (h - d) / 2 + 2, d - 4, d - 4)
            val ring = CGPath.ellipse(rect)
            canvas.drawPath(ring, strokePaint(ink, 3.5))
            when (rating) {
                Rating.yes -> {
                    // `drawLayer` + `.destinationOut`: the tick cuts the disc, not what is behind it.
                    val bounds = RectF(rect.minX.toFloat() - 1, rect.minY.toFloat() - 1, rect.maxX.toFloat() + 1, rect.maxY.toFloat() + 1)
                    canvas.saveLayer(bounds, null)
                    canvas.drawPath(ring, fillPaint(ink))
                    val check = CGPath()
                    check.move(CGPoint(rect.minX + rect.width * 0.27, rect.minY + rect.height * 0.53))
                    check.addLine(CGPoint(rect.minX + rect.width * 0.44, rect.minY + rect.height * 0.69))
                    check.addLine(CGPoint(rect.minX + rect.width * 0.74, rect.minY + rect.height * 0.34))
                    val cut = strokePaint(Color.Black, rect.width * 0.13, Paint.Cap.ROUND, Paint.Join.ROUND)
                    cut.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
                    canvas.drawPath(check, cut)
                    canvas.restore()
                }
                Rating.nearly -> {
                    val half = CGPath()
                    half.addArc(CGPoint(rect.midX, rect.midY), rect.width / 2, Math.toRadians(90.0), Math.toRadians(270.0), clockwise = false)
                    half.closeSubpath()
                    canvas.drawPath(half, fillPaint(ink))
                }
                Rating.notYet -> Unit
            }
            canvas.restore()
        }
    }
}

data class RatingChoice(val rating: Rating, val title: String) {
    val id: Rating get() = rating
}

/** Three big answers in a row. The telephone's guess is outlined so the adult sees it and can overrule it. */
@Composable
fun RatingRow(
    choices: List<RatingChoice>,
    onPick: (Rating) -> Unit,
    modifier: Modifier = Modifier,
    suggested: Rating? = null,
    selected: Rating? = null,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for (choice in choices) {
            val isSelected = selected == choice.rating
            StyledButton(
                onClick = { onPick(choice.rating) },
                style = TileButtonStyle(
                    face = if (isSelected) choice.rating.soft else Palette.sheet,
                    border = if (isSelected) choice.rating.color else if (suggested == choice.rating) Palette.lineStrong else null,
                    radius = 18.dp,
                ),
                modifier = Modifier.weight(1f),
            ) {
                Column(
                    Modifier.fillMaxWidth().heightIn(min = 84.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    RatingMark(choice.rating, Modifier.size(30.dp))
                    FoniaText(
                        choice.title,
                        style = ui(15, UIWeight.semibold, TextRole.subheadline),
                        color = Palette.ink,
                        textAlign = TextAlign.Center,
                        minimumScaleFactor = 0.8f,
                    )
                }
            }
        }
    }
}

/** `PressableStyle`: anything that shrinks a little under the finger. */
class PressableStyle : ButtonStyle {
    @Composable
    override fun MakeBody(configuration: ButtonConfiguration, modifier: Modifier) {
        val scale by animateFloatAsState(if (configuration.isPressed) 0.97f else 1f, press, label = "pressable")
        Box(modifier.graphicsLayer { scaleX = scale; scaleY = scale }) { configuration.label() }
    }
}

/** How far along a sound has come. */
@Composable
fun RungStrip(total: Int, reached: Int, color: Color, track: Color, modifier: Modifier = Modifier) {
    // The fill grows in from the left when the strip appears and whenever it moves on (v2 polish); reduce motion: in place.
    val target = min(reached, total).toFloat() / max(total, 1)
    val reduceMotion = LocalReduceMotion.current
    val grown = remember { androidx.compose.animation.core.Animatable(if (reduceMotion) target else 0f) }
    androidx.compose.runtime.LaunchedEffect(target, reduceMotion) {
        if (reduceMotion) grown.snapTo(target) else grown.animateTo(target, androidx.compose.animation.core.spring(dampingRatio = 0.8f, stiffness = 120f))
    }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .semantics { contentDescription = "Pasul ${min(reached, total)} din $total" },
    ) {
        val radius = CornerRadius(size.height / 2)
        drawRoundRect(track, cornerRadius = radius)
        if (grown.value > 0f) drawRoundRect(color, size = Size(max(size.height, size.width * grown.value), size.height), cornerRadius = radius)
    }
}

/** Session progress with no numbers: one bar that fills. */
@Composable
fun SessionBar(value: Double, color: Color, track: Color, modifier: Modifier = Modifier, height: Dp = 8.dp) {
    val target = min(1.0, max(0.0, value)).toFloat()
    // `.animation(.spring(response: 0.45, dampingFraction: 0.8), value: value)` moves both the fill and its opacity.
    val share by animateFloatAsState(target, responseSpring(response = 0.45, dampingFraction = 0.8), label = "share")
    val shown by animateFloatAsState(if (value <= 0) 0f else 1f, responseSpring(response = 0.45, dampingFraction = 0.8), label = "shown")
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .semantics {
                contentDescription = "Cât am lucrat azi"
                stateDescription = "${(target * 100).toInt()} la sută"
            },
    ) {
        val radius = CornerRadius(size.height / 2)
        drawRoundRect(track, cornerRadius = radius)
        drawRoundRect(
            color,
            size = Size(max(size.height, size.width * share), size.height),
            cornerRadius = radius,
            alpha = shown.coerceIn(0f, 1f),
        )
    }
}
