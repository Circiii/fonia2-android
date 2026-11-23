package tech.fonia.features.guide

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import tech.fonia.facecamera.CameraFaceTracker
import tech.fonia.facecamera.MouthTracker
import tech.fonia.facecamera.StillFrameFeed
import java.nio.ByteBuffer

/**
 * Debug builds, `foniaFace=fixture`: the mouth coach's mirror fed from a still portrait through the REAL Face
 * Landmarker pipeline ([StillFrameFeed]) instead of the camera, so the rest, the quality gate, the matcher, the advice
 * and the lip guide are the real code. The portrait is `small_smile.png` of `facecamera/src/androidTest/assets/faces`
 * (Kayla Barron, NASA, public domain; facecamera/MODELS.md), as the lab app's `FixtureFace` uses it: the calm face for
 * the rest, then the same face with the lips drawn together (rounder), a match for the rounded sounds, too far, dark.
 */
internal class MirrorFixture private constructor(context: Context) {
    private val app = context.applicationContext
    private val tracker = CameraFaceTracker(app)
    val mouth = MouthTracker(tracker)
    private val feed = StillFrameFeed(app, tracker)
    private val shown = mutableStateOf<ImageBitmap?>(null)
    val picture: State<ImageBitmap?> get() = shown

    private class Frame(val bitmap: ImageBitmap, val rgba: ByteArray, val w: Int, val h: Int)

    suspend fun run() {
        val frames = withContext(Dispatchers.IO) { load() }
        while (currentCoroutineContext().isActive) {
            for ((name, seconds) in SCRIPT) {
                val f = frames[name] ?: continue
                shown.value = f.bitmap
                feed.reset()
                val end = System.nanoTime() + (seconds * 1e9).toLong()
                while (System.nanoTime() < end) {
                    feed.submit(f.rgba, f.w, f.h)
                    delay(66)
                }
            }
        }
    }

    private fun load(): Map<String, Frame> {
        val src = app.assets.open("guide/small_smile.png").use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888; inScaled = false })!!
        }
        val calm = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawBitmap(src, Rect(108, 50, 348, 370), Rect(0, 0, it.width, it.height), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        fun frame(b: Bitmap): Frame {
            val buf = ByteBuffer.allocate(b.width * b.height * 4)
            b.copyPixelsToBuffer(buf)
            return Frame(b.asImageBitmap(), buf.array(), b.width, b.height)
        }
        return mapOf(
            "calm" to frame(calm),
            "rounder" to frame(squeeze(calm, 0.9f)),
            "round" to frame(squeeze(calm, 0.72f)),
            "far" to frame(shrink(calm, 0.22f)),
            "dark" to frame(darken(calm, 0.1f)),
        )
    }

    /** Pulls the pixels around the mouth toward its centre line: narrower lips, which the engine reads as rounder. */
    private fun squeeze(src: Bitmap, k: Float): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = pixels.copyOf()
        val rx = 135f
        val ry = 78f
        for (y in (MOUTH_Y - ry).toInt().coerceAtLeast(0) until (MOUTH_Y + ry).toInt().coerceAtMost(h)) {
            for (x in (MOUTH_X - rx).toInt().coerceAtLeast(0) until (MOUTH_X + rx).toInt().coerceAtMost(w)) {
                val dx = (x - MOUTH_X) / rx
                val dy = (y - MOUTH_Y) / ry
                val d = dx * dx + dy * dy
                if (d >= 1f) continue
                val kEff = 1f - (1f - k) * (1f - d * d)
                val sx = (MOUTH_X + (x - MOUTH_X) / kEff).toInt().coerceIn(0, w - 1)
                out[y * w + x] = pixels[y * w + sx]
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun shrink(src: Bitmap, k: Float): Bitmap = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888).also {
        val c = Canvas(it)
        c.drawColor(src.getPixel(4, 4))
        val w = (src.width * k).toInt()
        val h = (src.height * k).toInt()
        val l = (src.width - w) / 2
        val t = (src.height - h) / 3
        c.drawBitmap(src, Rect(0, 0, src.width, src.height), Rect(l, t, l + w, t + h), Paint(Paint.FILTER_BITMAP_FLAG))
    }

    private fun darken(src: Bitmap, k: Float): Bitmap = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888).also {
        Canvas(it).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setScale(k, k, k, 1f) }) })
    }

    companion object {
        private const val MOUTH_X = 242f
        private const val MOUTH_Y = 376f
        private val SCRIPT = listOf("calm" to 5.0, "rounder" to 3.0, "round" to 6.0, "far" to 3.0, "dark" to 3.0, "calm" to 3.0)

        fun make(context: Context, face: String?): MirrorFixture? = if (face == "fixture") MirrorFixture(context) else null
    }
}
