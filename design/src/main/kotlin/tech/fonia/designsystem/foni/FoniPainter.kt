package tech.fonia.designsystem.foni

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.ui.graphics.toArgb
import tech.fonia.designsystem.art.FoniArt
import tech.fonia.designsystem.motion.Motion
import tech.fonia.designsystem.tokens.FoniaPalette
import tech.fonia.designsystem.tokens.FoniaSticker
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Stacks Foni's layers the way the legacy `ParrotPainter` does (same pivots in the 35 × 35 grid, same order), plus what
 * v2 adds: a ground shadow that shrinks as he hops, a squashing blink, eyes that move, the turn-around squish, thought
 * bubbles, twinkling stars and the Zzz. Owns its Paints and Path; draws with canvas transforms, no allocation per frame.
 */
internal class FoniPainter {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dst = RectF()
    private val star = Path()

    private val shadowArgb = FoniaPalette.sheetShadow.color.toArgb()
    private val bubbleFace = FoniaSticker.Sky.face.toArgb()
    private val bubbleEdge = FoniaSticker.Sky.edge.toArgb()
    private val bubbleRim = FoniaSticker.Sky.rim.toArgb()
    private val starFace = FoniaSticker.Sun.face.toArgb()
    private val starEdge = FoniaSticker.Sun.edge.toArgb()
    private val starRim = FoniaSticker.Sun.rim.toArgb()

    init {
        // a five-pointed star of radius 1 with rounded joins (stroked under the fill)
        for (i in 0 until 10) {
            val a = -Math.PI / 2 + i * Math.PI / 5
            val r = if (i % 2 == 0) 1f else 0.48f
            val x = (cos(a) * r).toFloat()
            val y = (sin(a) * r).toFloat()
            if (i == 0) star.moveTo(x, y) else star.lineTo(x, y)
        }
        star.close()
        fill.strokeJoin = Paint.Join.ROUND
    }

    fun draw(c: Canvas, p: FoniPose, art: FoniArt, width: Float, height: Float) {
        val side = min(width, height)
        val u = side / 35f
        val k = side / 720f
        c.save()
        c.translate((width - side) / 2f, (height - side) / 2f)
        c.scale(p.scale, p.scale, side / 2f, side * 0.95f)

        // the ground shadow (stays on the ground, shrinks and fades as he rises)
        val lift = Motion.clamp01(p.rise * 9f)
        val sw = 6.4f * u * (1f - 0.35f * lift) * (1f + p.squash * 0.8f)
        fill.style = Paint.Style.FILL
        fill.color = fade(shadowArgb, 1f - 0.5f * lift)
        dst.set(side * 0.5f - sw, 33.0f * u - 0.75f * u, side * 0.5f + sw, 33.0f * u + 0.75f * u)
        c.drawOval(dst, fill)

        // facing: mirror around the middle; near zero he is a narrow sliver (the turn)
        val f = p.facing
        val sx = (if (f >= 0f) 1f else -1f) * max(0.12f, abs(f))
        c.save()
        c.scale(sx, 1f, side / 2f, 0f)

        c.save()
        c.translate(0f, -p.rise * side)
        layer(c, art, FoniArt.LEG_FAR, k)

        // the body: lean around the feet, squash and breathe around the feet
        c.save()
        c.rotate(p.lean, FEET_X * u, FEET_Y * u)
        c.scale(1f + p.squash * 0.7f - p.breath * 0.004f, 1f - p.squash + p.breath * 0.009f, FEET_X * u, FEET_Y * u)

        c.save(); c.rotate(p.tail, TAIL_X * u, TAIL_Y * u); layer(c, art, FoniArt.TAIL, k); c.restore()
        c.restore() // the near leg is not squashed with the body
        layer(c, art, FoniArt.LEG_NEAR, k)
        c.save()
        c.rotate(p.lean, FEET_X * u, FEET_Y * u)
        c.scale(1f + p.squash * 0.7f - p.breath * 0.004f, 1f - p.squash + p.breath * 0.009f, FEET_X * u, FEET_Y * u)
        layer(c, art, FoniArt.BODY, k)

        // the head with its eyes and beak
        c.save()
        c.rotate(p.head, NECK_X * u, NECK_Y * u)
        c.translate(0f, -p.breath * 0.09f * u)
        layer(c, art, FoniArt.HEAD, k)
        drawEyes(c, p, art, u, k)
        val open = p.beak
        if (open > 0.5f) {
            c.save(); c.translate(0f, open * 0.018f * u)
            layer(c, art, FoniArt.MOUTH, k, min(1f, open / 8f))
            c.restore()
        }
        c.save()
        c.rotate(-open, HINGE_X * u, HINGE_Y * u)
        c.translate(0f, open * 0.03f * u)
        layer(c, art, FoniArt.BEAK_LOW, k)
        c.restore()
        c.save(); c.rotate(open * 0.4f, HINGE_X * u, HINGE_Y * u); layer(c, art, FoniArt.BEAK_UP, k); c.restore()
        c.restore()

        // the wing over the neck; its soft shadow fades as it lifts off the body
        c.save()
        c.rotate(p.wing, SHOULDER_X * u, SHOULDER_Y * u)
        val shadow = max(0f, 1f - abs(p.wing) / 14f) * (1f - p.wingLift)
        if (shadow > 0.01f) layer(c, art, FoniArt.WING_SHADOW, k, shadow)
        c.translate(p.wingLift * 1.6f * u, p.wingLift * 1.4f * u)
        layer(c, art, FoniArt.WING, k)
        c.restore()

        c.restore() // body
        c.restore() // rise

        if (p.dots >= 1f) drawBubbles(c, p.dots - 1f, u)
        if (p.sparkle > 0f) drawStars(c, p, u)
        c.restore() // facing

        if (p.zzz > 0f) art.zzz?.let { z ->
            val drift = (p.breath + 1f) * 0.5f
            val x0 = if (f >= 0f) 23.5f * u else 4.5f * u
            dst.set(x0, (2.2f - drift) * u, x0 + 7f * u, (2.2f - drift) * u + 7f * u)
            paint.alpha = ((0.55f + 0.45f * drift) * p.zzz * 255).toInt().coerceIn(0, 255)
            c.drawBitmap(z, null, dst, paint)
            paint.alpha = 255
        }
        c.restore()
    }

    private fun drawEyes(c: Canvas, p: FoniPose, art: FoniArt, u: Float, k: Float) {
        val ex = EYE_X * u
        val ey = EYE_Y * u
        val open = (1f - p.closed) * (1f - p.smile)
        if (open > 0.01f) {
            val squeeze = 1f - 0.9f * p.blink
            c.save()
            c.translate(p.eyeDx * u, p.eyeDy * u)
            c.scale(p.eyeScale, p.eyeScale * squeeze, ex, ey)
            layer(c, art, FoniArt.EYE_OPEN, k, open)
            c.restore()
        }
        val shut = max(p.closed, if (p.blink > 0.55f) (p.blink - 0.55f) / 0.45f * (1f - p.smile) else 0f)
        if (shut > 0.01f) layer(c, art, FoniArt.EYE_CALM, k, shut)
        if (p.smile > 0.01f) layer(c, art, FoniArt.EYE_SMILE, k, p.smile * (1f - p.closed))
    }

    /** Three thought bubbles rising in front of his head, one after another. */
    private fun drawBubbles(c: Canvas, phase: Float, u: Float) {
        for (i in 0 until 3) {
            val appear = Motion.step(phase * 2.4f - i * 0.28f, 0.5f, 300f)
            if (appear <= 0.01f) continue
            val bob = sin((phase * 6.283f + i * 1.3f)) * 0.25f * u
            val x = (9.2f - i * 2.9f) * u
            val y = (5.2f - i * 1.9f) * u + bob
            val r = (0.7f + i * 0.42f) * u * appear
            fill.style = Paint.Style.FILL
            fill.color = bubbleRim; c.drawCircle(x, y, r + 0.32f * u, fill)
            fill.color = bubbleEdge; c.drawCircle(x, y, r + 0.16f * u, fill)
            fill.color = bubbleFace; c.drawCircle(x, y, r, fill)
        }
    }

    /** Four little sun stars around him that twinkle in turn. */
    private fun drawStars(c: Canvas, p: FoniPose, u: Float) {
        for (i in 0 until 4) {
            val ph = (p.sparklePhase * 1.6f + i * 0.27f) % 1f
            val s = Motion.bell(ph) * p.sparkle
            if (s < 0.05f) continue
            val x = STARS[i * 2] * u
            val y = STARS[i * 2 + 1] * u
            val r = (1.15f + 0.45f * (i % 2)) * u * s
            c.save(); c.translate(x, y); c.rotate(ph * 50f); c.scale(r, r)
            fill.style = Paint.Style.FILL_AND_STROKE
            fill.strokeWidth = 0.55f
            fill.color = starRim; c.drawPath(star, fill)
            fill.strokeWidth = 0.28f
            fill.color = starEdge; c.drawPath(star, fill)
            fill.style = Paint.Style.FILL
            fill.color = starFace; c.drawPath(star, fill)
            c.restore()
        }
    }

    private fun layer(c: Canvas, art: FoniArt, i: Int, k: Float, alpha: Float = 1f) {
        val r = art.rects[i]
        dst.set(r[0] * k, r[1] * k, r[2] * k, r[3] * k)
        paint.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
        c.drawBitmap(art.layers[i], null, dst, paint)
        paint.alpha = 255
    }

    private fun fade(argb: Int, a: Float): Int {
        val al = (((argb ushr 24) and 0xFF) * a.coerceIn(0f, 1f)).toInt()
        return (al shl 24) or (argb and 0xFFFFFF)
    }

    private companion object {
        // pivots in the 35 × 35 grid (legacy tools/art/parrot.py)
        const val NECK_X = 17.7f; const val NECK_Y = 14.4f
        const val SHOULDER_X = 17.7f; const val SHOULDER_Y = 15.6f
        const val TAIL_X = 22.5f; const val TAIL_Y = 26.0f
        const val HINGE_X = 11.4f; const val HINGE_Y = 11.7f
        const val FEET_X = 18.0f; const val FEET_Y = 33.0f
        const val EYE_X = 13.42f; const val EYE_Y = 10.99f
        val STARS = floatArrayOf(4.2f, 6.0f, 28.5f, 4.5f, 31.0f, 14.5f, 3.2f, 16.5f)
    }
}
