package tech.fonia.designsystem.motion

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlinx.coroutines.isActive
import tech.fonia.designsystem.letter.Particles
import tech.fonia.designsystem.theme.FoniaTheme
import tech.fonia.designsystem.tokens.FoniaMotion
import tech.fonia.designsystem.tokens.FoniaPalette
import tech.fonia.designsystem.tokens.FoniaSticker
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** What a [CelebrationBurst] throws. */
enum class BurstStyle {
    /** A fountain of confetti and stars that falls back (a level done, a reward). */
    Confetti,
    /** A ring of sun stars, little gravity (a correct answer). */
    Stars,
    /** A small quick ring of dots (a tap, a node unlocked). */
    Sparkle,
}

/** Something drawn in a [FoniaOverlayHost], above every sibling and never clipped by them. */
fun interface OverlayDrawer {
    /** Draw on [canvas], whose origin is the host's top-left; [host] gives you `localPositionOf` for your own coords. */
    fun draw(canvas: Canvas, host: LayoutCoordinates)
}

/**
 * The unclipped top layer of a screen: a [CelebrationBurst] and the POP burst of a [tech.fonia.designsystem.letter.LiveLetter]
 * inside a [FoniaOverlayHost] draw here instead of inside their own (possibly clipped) boxes.
 */
@Stable
class FoniaOverlay internal constructor() {
    internal val drawers = ArrayList<OverlayDrawer>()
    internal val tick = mutableLongStateOf(0L)
    internal var coords: LayoutCoordinates? = null
    internal val particles = Particles(FoniaMotion.PARTICLES_MAX)
    internal var awake by mutableStateOf(false)
    internal var reduceMotion = false

    fun add(d: OverlayDrawer) { drawers += d; invalidate() }
    fun remove(d: OverlayDrawer) { drawers -= d; invalidate() }

    /** Redraws the overlay on the next frame (call it every frame while you have something moving). */
    fun invalidate() { tick.longValue++ }

    /** Throws a burst from [atInRoot] (root coordinates). Pooled: at most 150 particles live at once. */
    fun burst(atInRoot: Offset, style: BurstStyle = BurstStyle.Confetti, power: Float = 1f) {
        val origin = coords?.takeIf { it.isAttached }?.positionInRoot() ?: Offset.Zero
        Celebrations.spawn(particles, atInRoot.x - origin.x, atInRoot.y - origin.y, style, power, reduceMotion, unit)
        awake = true
        invalidate()
    }

    internal var unit = 3f
}

/** The overlay of the nearest [FoniaOverlayHost], or null. */
val LocalFoniaOverlay = staticCompositionLocalOf<FoniaOverlay?> { null }

/**
 * Wrap a screen (or the whole app) in this to give it an unclipped celebration layer. The overlay is a sibling drawn
 * after [content], so redrawing it never redraws the content, and it takes no touches.
 */
@Composable
fun FoniaOverlayHost(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val overlay = remember { FoniaOverlay() }
    overlay.reduceMotion = FoniaTheme.reduceMotion
    val painter = remember { CelebrationPainter() }
    LaunchedEffect(overlay.awake) {
        if (!overlay.awake) return@LaunchedEffect
        var last = 0L
        while (isActive && overlay.particles.alive) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else (now - last) / 1e9f
                last = now
                overlay.particles.update(dt.coerceAtMost(0.05f))
                overlay.invalidate()
            }
        }
        overlay.invalidate()
        overlay.awake = false
    }
    Box(modifier.onGloballyPositioned { overlay.coords = it; overlay.unit = it.size.width / 400f }) {
        CompositionLocalProvider(LocalFoniaOverlay provides overlay) { content() }
        Spacer(
            Modifier.matchParentSize().clearAndSetSemantics { }.drawBehind {
                overlay.tick.longValue
                val host = overlay.coords ?: return@drawBehind
                drawIntoCanvas { cv ->
                    val n = cv.nativeCanvas
                    for (i in overlay.drawers.indices) overlay.drawers[i].draw(n, host)
                    if (overlay.particles.alive) painter.draw(n, overlay.particles)
                }
            },
        )
    }
}

/**
 * Throws confetti / stars / sparkles from [origin] (a fraction of this composable's bounds; centre by default) every
 * time [trigger] changes (not on first composition). Inside a [FoniaOverlayHost] the particles fly over everything,
 * unclipped; without one they are drawn in this composable's own box. Give it the bounds of the thing that celebrates
 * (`Modifier.matchParentSize()` over a button, a node, a card). Reduced motion: a few stars fade in place.
 */
@Composable
fun CelebrationBurst(
    trigger: Any?,
    modifier: Modifier = Modifier,
    style: BurstStyle = BurstStyle.Confetti,
    origin: Offset = Offset(0.5f, 0.5f),
    power: Float = 1f,
) {
    val overlay = LocalFoniaOverlay.current
    val reduce = FoniaTheme.reduceMotion
    val local = remember { LocalBurst() }
    val tick = remember { mutableLongStateOf(0L) }
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(trigger) {
        if (first[0]) { first[0] = false; return@LaunchedEffect }
        val c = local.coords ?: return@LaunchedEffect
        val at = Offset(c.size.width * origin.x, c.size.height * origin.y)
        if (overlay != null) {
            overlay.burst(c.positionInRoot() + at, style, power)
        } else {
            Celebrations.spawn(local.particles, at.x, at.y, style, power, reduce, c.size.width / 200f)
            var last = 0L
            while (isActive && local.particles.alive) withFrameNanos { now ->
                val dt = if (last == 0L) 0f else (now - last) / 1e9f
                last = now
                local.particles.update(dt.coerceAtMost(0.05f))
                tick.longValue = now
            }
            tick.longValue++
        }
    }
    Spacer(
        modifier.clearAndSetSemantics { }.onGloballyPositioned { local.coords = it }.drawBehind {
            tick.longValue
            if (local.particles.alive) drawIntoCanvas { local.painter.draw(it.nativeCanvas, local.particles) }
        },
    )
}

private class LocalBurst {
    var coords: LayoutCoordinates? = null
    val particles = Particles(90)
    val painter = CelebrationPainter()
}

/** The emitters (pure maths on a [Particles] pool, sizes in px from [unit] ≈ 1 dp). */
internal object Celebrations {
    private val CONFETTI_KINDS = intArrayOf(Particles.KIND_CONFETTI, Particles.KIND_CONFETTI, Particles.KIND_STAR, Particles.KIND_CONFETTI, Particles.KIND_DOT)
    private val STAR_KINDS = intArrayOf(Particles.KIND_STAR, Particles.KIND_STAR, Particles.KIND_DOT)
    private val ALL_COLORS = intArrayOf(0, 1, 2, 3, 4)
    private val SUN = intArrayOf(0, 0, 2)

    fun spawn(ps: Particles, x: Float, y: Float, style: BurstStyle, power: Float, reduce: Boolean, unit: Float) {
        val u = unit.coerceAtLeast(1f) * power.coerceIn(0.3f, 2f)
        if (reduce) {
            // no flight: five stars fade in place around the point
            for (i in 0 until 5) {
                val a = i * 2f * PI.toFloat() / 5f - PI.toFloat() / 2f
                ps.spawn(x + cos(a) * 28f * u, y + sin(a) * 28f * u, 0f, 0f, 14f * u, 0.7f, Particles.KIND_STAR, 0, 0f, 0f, 0f)
            }
            return
        }
        when (style) {
            BurstStyle.Confetti -> {
                // a fountain: mostly upwards, wide, then gravity
                ps.burst(x, y, 46, -PI.toFloat() * 0.92f, -PI.toFloat() * 0.08f, 520f * u, 13f * u, 1.15f, CONFETTI_KINDS, ALL_COLORS, 900f * u)
                ps.burst(x, y, 14, 0f, 2f * PI.toFloat(), 260f * u, 9f * u, 0.8f, STAR_KINDS, SUN, 300f * u)
            }
            BurstStyle.Stars -> ps.burst(x, y, 18, 0f, 2f * PI.toFloat(), 340f * u, 15f * u, 0.85f, STAR_KINDS, SUN, 160f * u)
            BurstStyle.Sparkle -> ps.burst(x, y, 12, 0f, 2f * PI.toFloat(), 200f * u, 7f * u, 0.5f, intArrayOf(Particles.KIND_DOT), ALL_COLORS, 0f)
        }
    }
}

/** Draws a [Particles] pool as sticker confetti: a rim and a face, the Fonia palette only. */
internal class CelebrationPainter {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeJoin = Paint.Join.ROUND }
    private val star = Path()
    // 0 sun, 1 voice, 2 parrot, 3 accent, 4 sky
    private val faces = intArrayOf(
        FoniaSticker.Sun.edge.toArgb(), FoniaSticker.Voice.edge.toArgb(), FoniaSticker.Parrot.edge.toArgb(),
        FoniaPalette.accent.color.toArgb(), FoniaPalette.skyEdge.color.toArgb(),
    )
    private val rims = intArrayOf(
        FoniaSticker.Sun.rim.toArgb(), FoniaSticker.Voice.rim.toArgb(), FoniaSticker.Parrot.rim.toArgb(),
        FoniaPalette.accentDeep.color.toArgb(), FoniaPalette.accentDeep.color.toArgb(),
    )

    init {
        for (i in 0 until 10) {
            val a = -PI / 2 + i * PI / 5
            val r = if (i % 2 == 0) 1f else 0.5f
            val x = (cos(a) * r).toFloat(); val y = (sin(a) * r).toFloat()
            if (i == 0) star.moveTo(x, y) else star.lineTo(x, y)
        }
        star.close()
    }

    fun draw(c: Canvas, ps: Particles) {
        for (i in 0 until ps.count) {
            val u = ps.age[i] / ps.life[i]
            val a = 1f - Motion.progress(u, 0.6f, 1f)
            val size = ps.size[i] * Motion.easeOutCubic(Motion.progress(u, 0f, 0.1f))
            if (size < 0.5f) continue
            val face = fade(faces[ps.color[i] % faces.size], a)
            val rim = fade(rims[ps.color[i] % rims.size], a)
            when (ps.kind[i]) {
                Particles.KIND_DOT -> {
                    paint.style = Paint.Style.FILL
                    paint.color = rim; c.drawCircle(ps.x[i], ps.y[i], size * 0.62f, paint)
                    paint.color = face; c.drawCircle(ps.x[i], ps.y[i], size * 0.45f, paint)
                }
                Particles.KIND_CONFETTI -> {
                    // a strip that flutters: its width follows the rotation, like paper turning in the air
                    val flutter = 0.35f + 0.65f * kotlin.math.abs(cos(ps.rot[i] * 0.05f))
                    c.save(); c.translate(ps.x[i], ps.y[i]); c.rotate(ps.rot[i]); c.scale(1f, flutter)
                    paint.style = Paint.Style.FILL
                    paint.color = rim; c.drawRoundRect(-size * 0.6f, -size * 0.28f, size * 0.6f, size * 0.28f, size * 0.2f, size * 0.2f, paint)
                    paint.color = face; c.drawRoundRect(-size * 0.48f, -size * 0.16f, size * 0.48f, size * 0.16f, size * 0.12f, size * 0.12f, paint)
                    c.restore()
                }
                else -> {
                    val r = size * 0.62f
                    c.save(); c.translate(ps.x[i], ps.y[i]); c.rotate(ps.rot[i] * 0.3f); c.scale(r, r)
                    paint.style = Paint.Style.FILL_AND_STROKE
                    paint.strokeWidth = 0.42f
                    paint.color = rim; c.drawPath(star, paint)
                    paint.style = Paint.Style.FILL
                    paint.color = face; c.drawPath(star, paint)
                    c.restore()
                }
            }
        }
    }

    private fun fade(argb: Int, a: Float): Int = ((((argb ushr 24) and 0xFF) * a.coerceIn(0f, 1f)).toInt() shl 24) or (argb and 0xFFFFFF)
}
