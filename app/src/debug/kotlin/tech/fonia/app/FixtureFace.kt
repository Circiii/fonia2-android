package tech.fonia.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import tech.fonia.app.mirror.FaceFixture
import tech.fonia.app.text.T
import tech.fonia.face.MirrorSnapshot
import tech.fonia.facecamera.CameraFaceTracker
import tech.fonia.facecamera.MouthTracker
import tech.fonia.facecamera.StillFrameFeed
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext

/**
 * `--es face fixture` (debug only): MIRROR fed from a still portrait instead of the camera. `small_smile.png` of
 * `facecamera/src/androidTest/assets/faces` (Kayla Barron, NASA, public domain; facecamera/MODELS.md; copied into the
 * debug assets under `faces/` by the `faceFixtureAssets` task) and frames derived from it (a crop, the lips drawn
 * together, a smaller face, a darkened copy) run through the REAL Face Landmarker pipeline ([StillFrameFeed]) at ~15 frames a second, so the rest, the quality
 * gate, the matcher, the advice and the overlay are all the real code. The camera never opens.
 *
 * The script ([SCRIPT]) walks through the screen's states: the calm face for the rest, then faces that ask for a
 * change, a match, a face too far, a face in the dark. Each frame's numbers are logged under `FoniaFaceFixture`.
 */
class FixtureFace(context: Context) : FaceFixture {
    private val app = context.applicationContext
    private val tracker = CameraFaceTracker(app)
    override val mouth = MouthTracker(tracker)
    private val feed = StillFrameFeed(app, tracker)
    private val shown = mutableStateOf<ImageBitmap?>(null)
    override val picture: State<ImageBitmap?> get() = shown
    override val label: String = T.mirrorFixture

    private class Frame(val name: String, val bitmap: ImageBitmap, val rgba: ByteArray, val w: Int, val h: Int)

    private class Segment(val frame: String, val seconds: Double)

    override suspend fun run() {
        val frames = withContext(Dispatchers.IO) { load() }
        val snap = MirrorSnapshot()
        while (coroutineContext.isActive) {
            for (seg in SCRIPT) {
                val f = frames[seg.frame] ?: continue
                shown.value = f.bitmap
                feed.reset() // a still jumps; a fresh detection, as when a face re-enters the camera
                val end = System.nanoTime() + (seg.seconds * 1e9).toLong()
                var lastLog = 0L
                while (System.nanoTime() < end) {
                    feed.submit(f.rgba, f.w, f.h)
                    val now = System.nanoTime()
                    if (now - lastLog > 500_000_000L && mouth.read(snap)) {
                        lastLog = now
                        val s = snap.state
                        val e = snap.evidence
                        Log.i(
                            TAG, "${seg.frame} face=${snap.hasFace} q=${s.quality} issue=${s.issue} rest=${s.rest} ap=%.2f w=%.2f open=%.2f round=%.2f spread=%.2f | ${e.status} m=%.2f adv=${e.advice?.key} abst=${e.abstain} restP=%.2f"
                                .format(s.aperture, s.width, s.open, s.round, s.spread, e.match, snap.restProgress),
                        )
                    }
                    delay(FRAME_MILLIS)
                }
            }
        }
    }

    private fun load(): Map<String, Frame> {
        fun decode(name: String): Bitmap = app.assets.open("faces/$name.png").use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888; inScaled = false })!!
        }
        val out = LinkedHashMap<String, Frame>()
        fun add(name: String, b: Bitmap) {
            val buf = ByteBuffer.allocate(b.width * b.height * 4)
            b.copyPixelsToBuffer(buf) // ARGB_8888 is R, G, B, A in memory: the RGBA the pipeline reads
            out[name] = Frame(name, b.asImageBitmap(), buf.array(), b.width, b.height)
        }
        // One face throughout (Kayla Barron's portrait, public domain), so the rest and every later frame belong to the
        // same person: the calm face, then the same face with the lips drawn together (a local horizontal squeeze of
        // the mouth region: narrower = rounder for the engine), then smaller and darker copies.
        val calm = crop(decode("small_smile"), Rect(108, 50, 348, 370))
        add("calm", calm)
        add("rounder", squeezeMouth(calm, 0.9f))
        add("round", squeezeMouth(calm, 0.72f))
        add("far", shrink(calm, 0.2f))
        add("dark", darken(calm, 0.1f))
        return out
    }

    private fun crop(src: Bitmap, r: Rect): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, r, Rect(0, 0, out.width, out.height), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /**
     * Draws the lips together: inside an ellipse around the mouth ([MOUTH_X], [MOUTH_Y] in the calm frame, from the
     * MediaPipe reference landmarks of `small_smile.json`), pixels are pulled toward the mouth's centre line by [k]
     * (1 at the ellipse's edge, so the face around stays put).
     */
    private fun squeezeMouth(src: Bitmap, k: Float): Bitmap {
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
                val weight = 1f - d * d
                val kEff = 1f - (1f - k) * weight
                val sx = (MOUTH_X + (x - MOUTH_X) / kEff).toInt().coerceIn(0, w - 1)
                out[y * w + x] = pixels[y * w + sx]
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun shrink(src: Bitmap, k: Float): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(src.getPixel(4, 4)) // the backdrop's colour: no second, bigger face behind
        val w = (src.width * k).toInt()
        val h = (src.height * k).toInt()
        val l = (out.width - w) / 2
        val t = (out.height - h) / 3
        c.drawBitmap(src, Rect(0, 0, src.width, src.height), Rect(l, t, l + w, t + h), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private fun darken(src: Bitmap, k: Float): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setScale(k, k, k, 1f) })
        })
        return out
    }

    companion object {
        private const val TAG = "FoniaFaceFixture"
        private const val FRAME_MILLIS = 66L

        /** The mouth's centre in the calm frame (crop 108,50 → 348,370 of small_smile.png, scaled ×2). */
        private const val MOUTH_X = 242f
        private const val MOUTH_Y = 376f

        /** ~21 s: the rest (taken from the calm face), advice, a held match, too far, too dark, calm again. */
        private val SCRIPT = listOf(
            Segment("calm", 5.0),
            Segment("rounder", 3.5),
            Segment("round", 4.5),
            Segment("far", 3.0),
            Segment("dark", 3.0),
            Segment("calm", 2.0),
        )
    }
}
