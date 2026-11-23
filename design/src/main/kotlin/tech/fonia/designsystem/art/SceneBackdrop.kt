package tech.fonia.designsystem.art

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.isActive
import tech.fonia.designsystem.motion.Motion
import tech.fonia.designsystem.theme.FoniaTheme
import tech.fonia.designsystem.tokens.FoniaMotion
import tech.fonia.designsystem.tokens.FoniaPalette
import kotlin.math.max
import kotlin.math.sin

/**
 * A painted scene of the old app behind a lesson or a card ([FoniaArtAssets.scenes]: `"lesson"`, `"cold"`, `"warm"`,
 * `"tip"`, `"night"`, …). The file is picked by the box's shape (page / tall / card / wide) and cropped to fill it.
 *
 * Ambient motion, all in the draw phase (no recomposition): a very slow breathing zoom and drift, a few soft motes of
 * light floating up (twinkling stars on `night` / `stories`), and [parallax] — a -1…1 offset per axis (a pager's
 * offset, a scroll fraction) that shifts the painting up to 3 % against the content. Reduced motion: the still painting.
 * It fades in when decoded; [content] is drawn on top.
 */
@Composable
fun SceneBackdrop(
    scene: String,
    modifier: Modifier = Modifier,
    parallax: () -> Offset = { Offset.Zero },
    ambient: Boolean = true,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val context = LocalContext.current
    val reduce = FoniaTheme.reduceMotion
    var box by remember { mutableStateOf(IntSize.Zero) }
    val file = if (box.width > 0 && box.height > 0) FoniaArtAssets.sceneFile(scene, box.width.toFloat() / box.height) else null
    var bitmap by remember(file) { mutableStateOf<Bitmap?>(file?.let { FoniaArtAssets.cached(it, max(box.width, box.height) / 2) }) }
    val fadeIn = remember(file) { Animatable(if (bitmap != null) 1f else 0f) }
    val frame = remember { mutableLongStateOf(0L) }
    val drift by rememberUpdatedState(parallax)
    val painter = remember { ScenePainter() }

    LaunchedEffect(file) {
        if (file == null) return@LaunchedEffect
        if (bitmap == null) bitmap = FoniaArtAssets.bitmap(context, file, max(box.width, box.height) / 2)
        fadeIn.animateTo(1f, tween(if (reduce) FoniaMotion.REDUCED_FADE_MS else FoniaMotion.SLOW_MS))
    }
    LaunchedEffect(reduce, ambient) {
        if (reduce || !ambient) return@LaunchedEffect
        var last = 0L
        while (isActive) withFrameNanos { now ->
            // the drift is slow: 30 frames a second are plenty and halve the cost of a full-screen bitmap
            if (now - last > 30_000_000L) { last = now; frame.longValue = now }
        }
    }

    Box(
        modifier
            .clipToBounds()
            .onSizeChanged { box = it }
            .clearAndSetSemantics { }
            .drawBehind {
                val now = frame.longValue
                val b = bitmap ?: return@drawBehind
                val t = if (reduce || !ambient) 0f else (now / 1e9f) % 10_000f
                val p = if (reduce) Offset.Zero else drift()
                drawIntoCanvas {
                    painter.draw(it.nativeCanvas, b, size.width, size.height, t, p, fadeIn.value, scene, ambient && !reduce)
                }
            },
        content = content,
    )
}

private class ScenePainter {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dst = RectF()
    private val moteArgb = FoniaPalette.surface.color.toArgb()
    private val starArgb = FoniaPalette.sunFace.color.toArgb()

    fun draw(c: android.graphics.Canvas, b: Bitmap, w: Float, h: Float, t: Float, parallax: Offset, alpha: Float, scene: String, motes: Boolean) {
        // crop to fill, with a small margin for the zoom and the parallax
        val zoom = 1.06f + 0.02f * sin(t * Motion.TAU / 18f)
        val scale = max(w / b.width, h / b.height) * zoom
        val dw = b.width * scale
        val dh = b.height * scale
        val room = (dw - w) / 2f
        val roomY = (dh - h) / 2f
        val dx = (sin(t * Motion.TAU / 23f) * 0.35f + parallax.x.coerceIn(-1f, 1f) * 0.6f) * room
        val dy = (sin(t * Motion.TAU / 29f) * 0.3f + parallax.y.coerceIn(-1f, 1f) * 0.6f) * roomY
        dst.set((w - dw) / 2f + dx, (h - dh) / 2f + dy, (w + dw) / 2f + dx, (h + dh) / 2f + dy)
        paint.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
        c.drawBitmap(b, null, dst, paint)
        if (!motes || alpha < 0.5f) return

        val night = scene.startsWith("night") || scene.startsWith("stories")
        val n = if (night) 22 else 12
        val unit = max(w, h) / 400f
        for (i in 0 until n) {
            val hx = Motion.hash(i, 17) * 0.5f + 0.5f
            val hy = Motion.hash(i, 31) * 0.5f + 0.5f
            val speed = 0.012f + 0.01f * (Motion.hash(i, 5) * 0.5f + 0.5f)
            if (night) {
                val tw = 0.5f + 0.5f * sin(t * (1.1f + hx) + i * 1.7f)
                dot.color = fade(starArgb, 0.25f + 0.55f * tw)
                c.drawCircle(hx * w, hy * h * 0.6f, (0.8f + 1.2f * tw) * unit, dot)
            } else {
                val y = ((hy - t * speed) % 1f + 1f) % 1f
                val x = hx + 0.02f * sin(t * 0.7f + i)
                val life = Motion.bell(y)
                dot.color = fade(moteArgb, 0.28f * life)
                c.drawCircle(x * w, y * h, (1.2f + 1.6f * (Motion.hash(i, 9) * 0.5f + 0.5f)) * unit, dot)
            }
        }
    }

    private fun fade(argb: Int, a: Float): Int = ((((argb ushr 24) and 0xFF) * a.coerceIn(0f, 1f)).toInt() shl 24) or (argb and 0xFFFFFF)
}
