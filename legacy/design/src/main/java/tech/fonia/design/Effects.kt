package tech.fonia.design

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/*
 * The iOS app takes these from Pow (`.shine`, `.jump`, `.pulse`/`.ping`, `.spray`, `.movingParts.pop`). Android has no
 * such library, so each is rebuilt here from Pow's own maths. Like Pow's `changeEffect`, an effect plays when its
 * trigger changes after the first composition, never when the view first appears, and only while it is enabled.
 * With Reduce Motion none of them play.
 */

/** Runs [play] each time [trigger] changes after the first composition, while [enabled] and motion is allowed. */
@Composable
private fun OnChange(trigger: Any?, enabled: Boolean, play: suspend () -> Unit) {
    val reduce = LocalReduceMotion.current
    val isEnabled by rememberUpdatedState(enabled && !reduce)
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(trigger) {
        if (first[0]) {
            first[0] = false
            return@LaunchedEffect
        }
        if (isEnabled) play()
    }
}

/**
 * `.changeEffect(.shine(angle:duration:).delay(…))`: a soft white light passes once across what is drawn, at [angle]
 * degrees, lighting only the drawn pixels (`.sourceAtop`). Its strength grows as it goes and it fades at the very end.
 */
fun Modifier.shine(
    trigger: Any?,
    enabled: Boolean = true,
    delayMillis: Int = 0,
    durationMillis: Int = 900,
    angle: Float = 20f,
): Modifier = composed {
    var fraction by remember { mutableFloatStateOf(-1f) }
    OnChange(trigger, enabled) {
        delay(delayMillis.toLong())
        try {
            Animatable(0f).animateTo(1f, tween(durationMillis, easing = EaseInOutCurve)) { fraction = value }
        } finally {
            fraction = -1f
        }
    }
    this
        .graphicsLayer { compositingStrategy = if (fraction >= 0f) CompositingStrategy.Offscreen else CompositingStrategy.Auto }
        .drawWithContent {
            drawContent()
            val f = fraction
            if (f < 0f) return@drawWithContent
            val a = angle / 180f * PI.toFloat()
            val u = Offset(cos(a), sin(a))
            // The frame's bounding box once turned by the angle: the gradient is twice as long and slides across it.
            val span = abs(size.width * u.x) + abs(size.height * u.y)
            val middle = -span + 2f * span * f
            val centre = Offset(size.width / 2f, size.height / 2f)
            val base = sin(f)
            val stops = (0..15).map { k ->
                val x = k * 0.2f
                (x / 3f) to Color.White.copy(alpha = (sin(x).pow(2) * 0.8f * base).coerceIn(0f, 1f))
            }.toTypedArray()
            drawRect(
                Brush.linearGradient(*stops, start = centre + u * (middle - span), end = centre + u * (middle + span)),
                alpha = (1f - f.pow(8)).coerceIn(0f, 1f),
                blendMode = BlendMode.SrcAtop,
            )
        }
}

/**
 * `.changeEffect(.jump(height:).delay(…))`: an underdamped spring (ζ = 1/3, stiffness 100) kicked up so that it peaks
 * at [height], with the squash on landing Pow draws: the view gets shorter and wider from its foot.
 */
fun Modifier.jump(trigger: Any?, height: Dp, enabled: Boolean = true, delayMillis: Int = 0): Modifier = composed {
    val displacement = remember { Animatable(0f) }
    OnChange(trigger, enabled) {
        delay(delayMillis.toLong())
        displacement.animateTo(
            0f,
            spring(dampingRatio = 1f / 3f, stiffness = 100f, visibilityThreshold = 0.04f),
            initialVelocity = -height.value / JUMP_PEAK_PER_VELOCITY,
        )
    }
    graphicsLayer {
        val d = displacement.value
        if (d < 0f) {
            translationY = d.dp.toPx()
        } else if (d > 0f && size.height > 0f) {
            val h = size.height
            val shorter = rubberClamp(h * 0.8f, h - d.dp.toPx() / 3f, h) / h
            scaleY = shorter
            scaleX = 1f / shorter
            transformOrigin = TransformOrigin(0.5f, 1f)
        }
    }
}

/** How high the spring above rises for a kick of 1 per second: e^(−ζω t*)·sin(ω_d t*)/ω_d at its first peak. */
private val JUMP_PEAK_PER_VELOCITY: Float = run {
    val omega = 10.0
    val zeta = 1.0 / 3.0
    val damped = omega * sqrt(1 - zeta * zeta)
    val peak = atan(damped / (zeta * omega)) / damped
    (kotlin.math.exp(-zeta * omega * peak) * sin(damped * peak) / damped).toFloat()
}

/** Pow's `rubberClamp`: past the limits the value still gives a little, as a rubber band would. */
private fun rubberClamp(low: Float, value: Float, high: Float, coefficient: Float = 0.55f): Float {
    val clamped = value.coerceIn(low, high)
    val delta = abs(clamped - value)
    if (delta == 0f) return value
    val sign = if (value > clamped) 1f else -1f
    val range = high - low
    if (range <= 0f) return clamped
    return clamped + sign * (1f - 1f / (delta * coefficient / range + 1f)) * range
}

/**
 * `.changeEffect(.pulse(shape: Circle(), style:, count:))` (Pow's `.ping`): [count] soft discs, 0.2 s apart, grow out
 * of the circle this is drawn behind and fade, the way Pow's fill pulse does.
 */
fun Modifier.ping(trigger: Any?, color: Color, enabled: Boolean = true, count: Int = 2): Modifier = composed {
    var elapsed by remember { mutableFloatStateOf(-1f) }
    OnChange(trigger, enabled) {
        // A pulse is visible for its first fifth (0.82 s of its 4 s run); the last one starts 0.2 s after the one before.
        val total = PULSE_VISIBLE + 0.2f * (count - 1)
        try {
            Animatable(0f).animateTo(total, tween((total * 1000).toInt(), easing = LinearEasing)) { elapsed = value }
        } finally {
            elapsed = -1f
        }
    }
    drawBehind {
        val t = elapsed
        if (t < 0f) return@drawBehind
        val reach = min(size.width, size.height) * 2f
        val r0 = min(size.width, size.height) / 2f
        for (i in 0 until count) {
            val p = (t - 0.2f * i) / 4f
            if (p <= 0f) continue
            val x = ((atan(sin(p * PI * 0.5) * 5.0) + PI / 2.0) / PI).toFloat()
            val arg = PI * p / 2.0
            val opacity = if (arg >= 1.0) 0f else (0.33 - asin(arg)).toFloat()
            if (opacity <= 0f) continue
            drawCircle(color.copy(alpha = (color.alpha * opacity).coerceIn(0f, 1f)), radius = r0 + (x - 0.5f) * reach)
        }
    }
}

private const val PULSE_VISIBLE = 0.83f

/**
 * `.transition(.movingParts.pop(color))`: when [visible] turns on, a ring in [ring] closes in round the spot, a few
 * sparks fly out, and the content grows in with a little overshoot. It leaves with a short fade. Shown from the first
 * composition, it is simply there. With Reduce Motion it fades in and out.
 */
@Composable
fun PopIn(visible: Boolean, ring: Color, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val reduce = LocalReduceMotion.current
    val progress = remember { Animatable(if (visible) 1f else 0f) }
    val fade = remember { Animatable(if (visible) 1f else 0f) }
    var shown by remember { mutableStateOf(visible) }
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(visible) {
        if (first[0]) {
            first[0] = false
            return@LaunchedEffect
        }
        if (visible) {
            shown = true
            if (reduce) {
                progress.snapTo(1f)
                fade.animateTo(1f, tween(200))
            } else {
                fade.snapTo(1f)
                progress.snapTo(0f)
                progress.animateTo(1f, tween(1200, easing = LinearEasing))
            }
        } else {
            fade.animateTo(0f, tween(150))
            shown = false
        }
    }
    if (!shown) {
        Box(modifier)
        return
    }
    Box(
        modifier.drawBehind {
            val p = progress.value
            if (p >= 1f || reduce) return@drawBehind
            val radius = min(size.width, size.height) / 2f + 8.dp.toPx()
            val t1 = (1.5f * p).coerceIn(0f, 1f)
            val t2 = (1.5f * (p - 0.15f)).coerceIn(0f, 1f)
            val outer = radius * (1f - 2f.pow(-14f * t1))
            val inner = radius * (1f - 2f.pow(-14f * t2))
            val ringAlpha = (1f - 1.3f.pow(-20f * (1f - t1))).coerceIn(0f, 1f)
            if (outer > inner && ringAlpha > 0f) {
                drawCircle(ring.copy(alpha = ring.alpha * ringAlpha), radius = (outer + inner) / 2f, style = Stroke(width = outer - inner))
            }
            val ts = (2f * (p - 1f / 3f)).coerceIn(0f, 1f)
            if (ts > 0f && ts < 1f) {
                val dot = 1.5.dp.toPx() * (1f - ts)
                val reach = radius + radius / 2f * (1f - 2f.pow(-10f * ts))
                for (i in 0 until 20) {
                    val a = i / 20f * 2f * PI.toFloat()
                    drawCircle(ring.copy(alpha = ring.alpha * (1f - ts)), radius = dot, center = center + Offset(cos(a), sin(a)) * reach)
                }
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.graphicsLayer {
                val p = progress.value
                val t = (2f * (p - 0.4f)).coerceIn(0f, 1f)
                val s = if (p >= 1f) 1f else 1f - 2f.pow(-20f * t)
                scaleX = s
                scaleY = s
                alpha = fade.value
            },
        ) { content() }
    }
}

/**
 * `.changeEffect(.spray(origin: .center) { particle })`: eleven copies of [particle] burst up and out of the middle of
 * what this is placed over, turn as they fly and shrink away, on a critically damped spring (stiffness 30). Put it in
 * a Box over the thing that sprays; [particleSize] is the particle's own size.
 */
@Composable
fun Spray(trigger: Any?, enabled: Boolean = true, particleSize: Dp = 14.dp, modifier: Modifier = Modifier, particle: @Composable () -> Unit) {
    val progress = remember { Animatable(0f) }
    var seed by remember { mutableIntStateOf(0) }
    var active by remember { mutableStateOf(false) }
    OnChange(trigger, enabled) {
        seed += 1
        progress.snapTo(0f)
        active = true
        try {
            progress.animateTo(1f, spring(dampingRatio = 1f, stiffness = 30f, visibilityThreshold = 0.002f))
        } finally {
            active = false
        }
    }
    if (!active) return
    val random = remember(seed) { Random(seed * 7919 + 17) }
    val jitter = remember(seed) { FloatArray(11) { random.nextFloat() } }
    Box(modifier, contentAlignment = Alignment.Center) {
        for (i in 0 until 11) {
            Box(
                Modifier
                    .size(particleSize)
                    .graphicsLayer {
                        val p = progress.value.coerceIn(0f, 1f)
                        val w = particleSize.toPx()
                        val h = particleSize.toPx()
                        val value = i / 10f
                        val adjusted = value - 0.5f
                        val angle = value * 45f - 22.5f
                        val value2 = jitter[i] + (i % 5) / 5f
                        val inset = cos(p) * p * -h * 2.5f
                        val scale = abs(sin((p * 0.75f + value2) * PI.toFloat())) * (1f - p.pow(8)) * p.pow(0.25f)
                        val x = adjusted * (sin(p * PI.toFloat()) * w * -2f)
                        val y = inset - (value2 * p) * h * 2.5f
                        // rotate(r1) → translate → scale → rotate(r2), as Pow draws each particle.
                        val r1 = p * -angle - angle * 0.25f
                        val r2 = sqrt(p * 2f) * angle - angle * 0.25f
                        val rad = r1 / 180f * PI.toFloat()
                        translationX = x * cos(rad) - y * sin(rad)
                        translationY = x * sin(rad) + y * cos(rad)
                        rotationZ = r1 + r2
                        scaleX = scale
                        scaleY = scale
                    },
            ) { particle() }
        }
    }
}

/**
 * The scroll area a [scrollSettle] card measures itself against: set by the scrolling container with
 * [scrollViewport], less what covers its foot (a bar drawn over it).
 */
@Stable
class ScrollViewport {
    internal var top by mutableFloatStateOf(Float.NaN)
    internal var bottom by mutableFloatStateOf(Float.NaN)
}

/** Marks this as the visible part of a scroll for [scrollSettle]; [coveredBottom] is what a bar hides at its foot. */
fun Modifier.scrollViewport(viewport: ScrollViewport, coveredBottom: Dp = 0.dp): Modifier = composed {
    val density = androidx.compose.ui.platform.LocalDensity.current
    onGloballyPositioned {
        val y = it.positionInRoot().y
        viewport.top = y
        viewport.bottom = y + it.size.height - with(density) { coveredBottom.toPx() }
    }
}

/**
 * `.scrollSettle()`: a card that is partly out of the visible part of a scroll gives a little (95%) and fades to 55%,
 * then settles with a spring (0.3 s) as it comes fully in. With Reduce Motion it only fades.
 */
fun Modifier.scrollSettle(viewport: ScrollViewport): Modifier = composed {
    val reduce = LocalReduceMotion.current
    var inside by remember { mutableStateOf(true) }
    val settle = remember { Animatable(1f) }
    LaunchedEffect(inside) { settle.animateTo(if (inside) 1f else 0f, swiftSpring(0.3, 0.0)) }
    this
        .onGloballyPositioned {
            val top = viewport.top
            val bottom = viewport.bottom
            if (top.isNaN() || bottom.isNaN()) return@onGloballyPositioned
            val y = it.positionInRoot().y
            inside = y >= top - 0.5f && y + it.size.height <= bottom + 0.5f
        }
        .graphicsLayer {
            val v = settle.value
            alpha = 0.55f + 0.45f * v
            if (!reduce) {
                val s = 0.95f + 0.05f * v
                scaleX = s
                scaleY = s
            }
        }
}

/**
 * `.contentTransition(.numericText())`: when the number changes, the old one slides out and the new one in, up for a
 * bigger number and down for a smaller one. The whole figure moves at once, not digit by digit. With Reduce Motion it
 * cross-fades.
 */
@Composable
fun NumericText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle? = null,
    color: Color = Color.Unspecified,
    maxLines: Int? = null,
    minimumScaleFactor: Float = 1f,
) {
    val reduce = LocalReduceMotion.current
    AnimatedContent(
        targetState = text,
        modifier = modifier,
        transitionSpec = { numericTransform(initialState, targetState, reduce) },
        contentAlignment = Alignment.CenterStart,
        label = "numeric",
    ) { shown ->
        FoniaText(shown, style = style, color = color, maxLines = maxLines, minimumScaleFactor = minimumScaleFactor)
    }
}

private fun numericTransform(from: String, to: String, reduce: Boolean): ContentTransform {
    val spec = swiftSpring<androidx.compose.ui.unit.IntOffset>(0.35, 0.0)
    if (reduce) return ContentTransform(fadeIn(tween(200)), fadeOut(tween(200)), sizeTransform = SizeTransform(clip = false))
    val up = (to.filter(Char::isDigit).toLongOrNull() ?: 0L) >= (from.filter(Char::isDigit).toLongOrNull() ?: 0L)
    val enter = slideInVertically(spec) { if (up) it / 2 else -it / 2 } + fadeIn(tween(200))
    val exit = slideOutVertically(spec) { if (up) -it / 2 else it / 2 } + fadeOut(tween(150))
    return ContentTransform(enter, exit, sizeTransform = SizeTransform(clip = false))
}
