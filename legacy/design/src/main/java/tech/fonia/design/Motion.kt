package tech.fonia.design

import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.StartOffsetType
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Sparkles in the parrot's colours that burst once from the middle, fall a little and fade. Drawn in one
 * canvas, so it costs almost nothing, and it isn't there at all with Reduce Motion on.
 */
@Composable
fun CelebrationBurst(isActive: Boolean, modifier: Modifier = Modifier) {
    val reduceMotion = LocalReduceMotion.current
    var startedAt by remember { mutableStateOf<Long?>(null) }
    // `.onChange(of: isActive, initial: true)`: it starts once and is never reset.
    LaunchedEffect(isActive) {
        if (isActive && startedAt == null) startedAt = System.nanoTime()
    }
    val started = startedAt
    if (started == null || reduceMotion) {
        Box(modifier)
        return
    }
    var now by remember { mutableLongStateOf(started) }
    LaunchedEffect(started) {
        while (true) {
            val frame = withFrameNanos { it }
            now = frame
            if ((frame - started) / 1e9 >= CelebrationBurstSpec.DURATION) break
        }
    }
    Canvas(modifier) {
        val time = (now - started) / 1e9
        if (time >= CelebrationBurstSpec.DURATION) return@Canvas
        val scale = density.toDouble()
        platform { canvas ->
            canvas.save()
            canvas.scale(density, density)
            CelebrationBurstSpec.draw(canvas, size.width / scale, size.height / scale, time)
            canvas.restore()
        }
    }
}

internal object CelebrationBurstSpec {
    const val DURATION = 1.6
    private val colors = listOf(
        Color.hex(0xFDC436), Color.hex(0x4FC974), Color.hex(0x3A8DF2), Color.hex(0xF0803C), Color.hex(0xFFFFFF),
    )

    fun draw(canvas: android.graphics.Canvas, width: Double, height: Double, time: Double) {
        val centre = CGPoint(width / 2, height * 0.55)
        val reach = min(width, height) * 0.62
        for (index in 0 until 34) {
            val seed = index.toDouble()
            val angle = noise(seed) * 2 * PI
            val speed = 0.45 + noise(seed + 40) * 0.7
            val delay = noise(seed + 80) * 0.14
            val t = max(0.0, time - delay)
            val travelled = (1 - (1 - min(t / 1.1, 1.0)).pow(3)) * speed * reach
            val fall = t * t * 46
            val x = centre.x + cos(angle) * travelled
            val y = centre.y + sin(angle) * travelled + fall
            val radius = 4 + noise(seed + 120) * 6
            val fade = max(0.0, 1 - (time - delay) / (DURATION - delay))
            val paint = fillPaint(colors[index % colors.size], opacity = min(1.0, fade * 1.6))
            canvas.drawPath(sparkle(CGPoint(x, y), radius * (0.6 + 0.4 * fade)), paint)
        }
    }

    private fun sparkle(point: CGPoint, radius: Double): CGPath {
        val k = radius * 0.22
        val path = CGPath()
        path.move(CGPoint(point.x, point.y - radius))
        path.addQuadCurve(CGPoint(point.x + radius, point.y), CGPoint(point.x + k, point.y - k))
        path.addQuadCurve(CGPoint(point.x, point.y + radius), CGPoint(point.x + k, point.y + k))
        path.addQuadCurve(CGPoint(point.x - radius, point.y), CGPoint(point.x - k, point.y + k))
        path.addQuadCurve(CGPoint(point.x, point.y - radius), CGPoint(point.x - k, point.y - k))
        return path
    }

    fun noise(x: Double): Double {
        val value = sin(x * 12.9898 + 1.2345) * 43758.5453
        return value - floor(value)
    }
}

/**
 * Pale clouds crossing a scene very slowly, or stars breathing at night. It's what keeps a page from looking
 * like a poster, and it's slow enough that nobody watches it. The moves are infinite transitions read only
 * while drawing, so the page doesn't recompose while they play.
 */
@Composable
fun SkyLife(isNight: Boolean = false, modifier: Modifier = Modifier) {
    if (LocalReduceMotion.current) {
        Box(modifier)
        return
    }
    val transition = rememberInfiniteTransition(label = "sky")
    if (isNight) {
        val stars = (0 until 14).map { index ->
            val seed = index.toDouble()
            transition.animateFloat(
                initialValue = 0.35f,
                targetValue = 1f,
                animationSpec = InfiniteRepeatableSpec(
                    animation = easeInOut((2 + SkyNoise.noise(seed + 60) * 2.5)),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset((SkyNoise.noise(seed + 20) * 2 * 1000).roundToInt(), StartOffsetType.Delay),
                ),
                label = "star$index",
            )
        }
        Canvas(modifier) {
            platform { canvas ->
                for (index in 0 until 14) {
                    val seed = index.toDouble()
                    val radius = (1 + SkyNoise.noise(seed + 90) * 1.5) * density
                    val x = SkyNoise.noise(seed) * size.width
                    val y = SkyNoise.noise(seed + 30) * size.height * 0.42
                    canvas.drawCircle(x.toFloat(), y.toFloat(), radius.toFloat(), fillPaint(Color.hex(0xFFF6CF), stars[index].value.toDouble()))
                }
            }
        }
    } else {
        // The clouds cross the screen in minutes: 20 steps a second look the same as 120 and cost a sixth.
        val born = remember { System.nanoTime() }
        val clock = remember { androidx.compose.runtime.mutableLongStateOf(0L) }
        LaunchedEffect(Unit) {
            while (true) {
                clock.longValue = (System.nanoTime() - born) / 1_000_000L
                kotlinx.coroutines.delay(50)
            }
        }
        Canvas(modifier) {
            val d = density.toDouble()
            val ms = clock.longValue
            val near = (ms % 96_000L) / 96_000f
            val far = (ms % 150_000L) / 150_000f
            platform { canvas ->
                drawCloud(canvas, y = 0.11, scale = 1.0, progress = near, opacity = 0.55, start = 0.1, width = size.width / d, height = size.height / d, density = d)
                drawCloud(canvas, y = 0.2, scale = 0.68, progress = far, opacity = 0.4, start = 0.55, width = size.width / d, height = size.height / d, density = d)
            }
        }
    }
}

/** Two copies a full lap apart: when the lap ends, the second stands exactly where the first began. */
private fun drawCloud(
    canvas: android.graphics.Canvas,
    y: Double,
    scale: Double,
    progress: Float,
    opacity: Double,
    start: Double,
    width: Double,
    height: Double,
    density: Double,
) {
    val cloudWidth = 150 * scale
    val lap = width + cloudWidth
    val shift = progress * lap
    val paint = fillPaint(Color.White, opacity)
    for (x in listOf(start * lap + shift, start * lap - lap + shift)) {
        canvas.save()
        canvas.scale(density.toFloat(), density.toFloat())
        canvas.translate(x.toFloat(), (height * y).toFloat())
        canvas.drawPath(Cloud.path(cloudWidth, 50 * scale), paint)
        canvas.restore()
    }
}

private object SkyNoise {
    fun noise(x: Double): Double {
        val value = sin(x * 12.9898 + 7.77) * 43758.5453
        return value - floor(value)
    }
}

/** The cloud shape: three ellipses on a rounded bar, in a 150 × 50 box scaled to the frame. */
internal object Cloud {
    private val unit: CGPath = CGPath().apply {
        addEllipse(CGRect(0, 16, 62, 34))
        addEllipse(CGRect(30, 0, 66, 50))
        addEllipse(CGRect(78, 12, 72, 38))
        addRoundedRect(CGRect(14, 26, 124, 24), 12.0, 12.0)
    }

    private val scaled = HashMap<Pair<Double, Double>, CGPath>()

    fun path(width: Double, height: Double): CGPath = synchronized(scaled) {
        scaled.getOrPut(width to height) { unit.applying(CGAffineTransform.scale(width / 150, height / 50)) }
    }
}
