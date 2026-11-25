package tech.fonia.design

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
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
import kotlin.math.sin
import kotlinx.coroutines.delay

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
 * Whether slow, endless motion runs here: drifting clouds, a calm bird, the start button's breath. A page under a
 * cover, or a tab out of view, stays composed but stands still, so it doesn't repaint the screen under a lesson.
 */
val LocalAmbientMotion = compositionLocalOf { true }

/** Stops slow, endless motion in [content] while [running] is false; it never restarts what an outer page stopped. */
@Composable
fun AmbientMotion(running: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAmbientMotion provides (running && LocalAmbientMotion.current), content = content)
}

/**
 * The beat slow, endless motion steps on. Every loop reads the same frame time, so they all land on the same
 * frames: the screen repaints a dozen times a second for all of them together, not once for each at its own
 * moment. Home left untouched used most of a core while the clouds, the bird and the start button each kept
 * their own clock.
 */
object AmbientBeat {
    const val PER_SECOND = 12
    const val NANOS: Long = 1_000_000_000L / PER_SECOND

    fun of(frameNanos: Long): Long = frameNanos / NANOS
}

/**
 * Calls [onBeat] on the first frame of every beat and sleeps in between. It runs on frames, so it stops with
 * the app in the background.
 */
suspend fun ambientBeats(onBeat: (frameNanos: Long) -> Unit): Nothing {
    var last = Long.MIN_VALUE
    while (true) {
        val now = withFrameNanos { now ->
            val beat = AmbientBeat.of(now)
            if (beat != last) {
                last = beat
                onBeat(now)
            }
            now
        }
        // Wake two frames before the next beat, so every loop is waiting on the frame that starts it.
        val wait = ((last + 1) * AmbientBeat.NANOS - now) / 1_000_000L - 34
        if (wait > 0) delay(wait)
    }
}

/**
 * Pale clouds crossing a scene very slowly, or stars breathing at night. It's what keeps a page from looking
 * like a poster, and it's slow enough that nobody watches it. Both step on the ambient beat and are read only
 * while drawing, so the page doesn't recompose while they play.
 */
@Composable
fun SkyLife(isNight: Boolean = false, modifier: Modifier = Modifier) {
    if (LocalReduceMotion.current) {
        Box(modifier)
        return
    }
    val running = LocalAmbientMotion.current
    val born = remember { System.nanoTime() }
    val clock = remember { mutableLongStateOf(0L) }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        ambientBeats { now -> clock.longValue = (now - born) / 1_000_000L }
    }
    if (isNight) {
        Canvas(modifier) {
            val seconds = clock.longValue / 1000.0
            platform { canvas ->
                for (index in 0 until 14) {
                    val seed = index.toDouble()
                    val radius = (1 + SkyNoise.noise(seed + 90) * 1.5) * density
                    val x = SkyNoise.noise(seed) * size.width
                    val y = SkyNoise.noise(seed + 30) * size.height * 0.42
                    canvas.drawCircle(x.toFloat(), y.toFloat(), radius.toFloat(), fillPaint(Color.hex(0xFFF6CF), starGlow(seed, seconds)))
                }
            }
        }
    } else {
        // The clouds cross the screen in minutes.
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

/** A star breathing between 0.35 and 1, each at its own pace and after its own pause, eased both ways. */
private fun starGlow(seed: Double, seconds: Double): Double {
    val pause = SkyNoise.noise(seed + 20) * 2
    val span = 2 + SkyNoise.noise(seed + 60) * 2.5
    if (seconds <= pause) return 0.35
    val run = (seconds - pause) / span
    val leg = floor(run)
    val along = (run - leg).toFloat()
    val eased = EaseInOutCurve.transform(if (leg.toLong() % 2 == 0L) along else 1 - along)
    return 0.35 + 0.65 * eased
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
