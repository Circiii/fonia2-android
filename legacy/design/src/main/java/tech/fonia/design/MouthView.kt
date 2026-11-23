package tech.fonia.design

import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private object Ink {
    val lipUpper = Color.hex(0xD9566F)
    val lipUpperLight = Color.hex(0xEC7A8C)
    val lipLower = Color.hex(0xF28C9A)
    val lipLowerDark = Color.hex(0xDD6379)
    val toothShade = Color.hex(0xEFECE4)
    val inside = Color.hex(0x571C2E)
    val insideDeep = Color.hex(0x3A0F1E)
    val tongue = Color.hex(0xFA9CA8)
    val tongueShade = Color.hex(0xEE7C8D)
    val tooth = Color.hex(0xFFFFFF)
}

/**
 * A soft piece of cheek and chin behind the mouth. A mouth floating on white reads as a sticker; on skin it
 * reads as a mouth, without the stare of a whole face. The caller clips it.
 */
@Composable
fun FacePatch(modifier: Modifier = Modifier) {
    Canvas(modifier.clearAndSetSemantics {}) {
        val w = size.width
        val h = size.height
        drawRect(Brush.verticalGradient(listOf(Color.hex(0xFCDDC4), Color.hex(0xF7C8A6))))
        platform { canvas ->
            // Nose, just a shadow at the top.
            val nose = fillPaint(Color.hex(0xE9A98A), 0.5)
            nose.maskFilter = blurFilter(8.0 * density)
            canvas.drawOval(RectF(w / 2 - w * 0.1f, h * 0.03f - h * 0.05f, w / 2 + w * 0.1f, h * 0.03f + h * 0.05f), nose)
            // Cheeks: a circle as wide as 0.32 of the patch (no taller than the patch).
            val cheek = fillPaint(Color.hex(0xF29A8C), 0.28)
            cheek.maskFilter = blurFilter(18.0 * density)
            val diameter = min(w * 0.32f, h)
            for (x in listOf(0.12f, 0.88f)) canvas.drawCircle(w * x, h * 0.4f, diameter / 2, cheek)
        }
    }
}

/** Pull-based inputs read once per frame, so a voice moving the mouth never touches observed state. */
class MouthDriver(
    val talk: () -> Double,
    val mirror: (() -> MouthShape?)? = null,
)

/**
 * A soft mouth, drawn from pose numbers. A change of [shape] moves the whole 16-number vector with one
 * spring, `spring(duration: 0.34, bounce: 0.12)` (`easeOut(0.15)` with Reduce Motion); [driver] adds what is
 * live: the voice's loudness or the learner's own face. 200 : 150, hidden from accessibility.
 */
@Composable
fun MouthView(
    shape: MouthShape,
    modifier: Modifier = Modifier,
    driver: MouthDriver? = null,
    isLive: Boolean = true,
    zoom: Double = 1.0,
) {
    val reduceMotion = LocalReduceMotion.current
    val target = remember(shape) { MouthVector(shape) }
    val vector = remember { AnimatedVector(target.v) }
    var from by remember { mutableStateOf(shape) }
    LaunchedEffect(target) {
        vector.animateTo(
            target.v,
            if (reduceMotion) VectorMotion.Tween(0.15, EaseOutCurve) else VectorMotion.Spring(0.34, 0.12),
        )
        from = shape
    }
    // While it moves, the vector still carries the trill of where it came from (SwiftUI ORs the flag).
    val vibrate = shape.vibrate || (vector.isRunning && from.vibrate)
    val needsClock = driver != null || (vibrate && !reduceMotion)
    val clock = rememberFrameClock(needsClock && isLive, 1.0 / 60)
    Canvas(modifier.aspectRatio(200f / 150f).clearAndSetSemantics {}) {
        val time = if (needsClock) clock.value / 1e9 else 0.0
        var pose = MouthVector(vector.value, vibrate)
        val mirrored = driver?.mirror?.invoke()
        if (mirrored != null) {
            pose = MouthVector(mirrored)
        } else if (driver != null) {
            pose = pose.talking(driver.talk())
        }
        val wobble = if (vibrate && !reduceMotion) sin(time * 2 * PI * 9) else 0.0
        val painter = MouthPainter(pose, wobble, zoom)
        platform { canvas -> painter.draw(canvas, size.width.toDouble(), size.height.toDouble()) }
    }
}

internal class MouthPainter(val pose: MouthVector, val wobble: Double, val zoom: Double = 1.0) {
    fun draw(context: android.graphics.Canvas, width: Double, height: Double) {
        val scale = min(width / 200, height / 150) * zoom
        context.save()
        context.translate((width / 2 - 100 * scale).toFloat(), (height / 2 - MouthGeometry.my * scale).toFloat())
        context.scale(scale.toFloat(), scale.toFloat())

        val g = MouthGeometry(pose)
        val cx = MouthGeometry.cx
        val hoE = g.yb - g.yt
        var gap = hoE * (1 - 0.92 * pose.teeth)
        if (pose.tongueWeight(TonguePose.between) > 0.5) gap = max(gap, 6.5)
        val vis = max(0.0, hoE - gap)
        val share = 0.62 + 0.25 * g.sm
        val bite = max(0.0, min(1.0, pose.bite))
        val yU = g.yt + vis * share + (g.yb + 2.2 - (g.yt + vis * share)) * bite
        val yL = g.yt + vis * share + gap
        val follow = max(0.0, g.lift) * 0.35
        val upperEdge: (Double) -> Double = { x ->
            val d = (x - cx) / 30
            yU - d * d * (1.6 + follow * 0.5)
        }
        val lowerEdge: (Double) -> Double = { x ->
            val d = (x - cx) / 30
            yL - d * d * (1.2 + follow * 0.5)
        }

        val inside = g.opening.boundingRect
        context.drawPath(g.opening, gradientPaint(listOf(Ink.insideDeep, Ink.inside), CGPoint(cx, inside.minY), CGPoint(cx, inside.maxY)))

        // Everything behind the lips, clipped to the opening.
        context.save()
        context.clipPath(g.opening.android())
        for (pos in listOf(TonguePose.rest, TonguePose.out, TonguePose.outUp, TonguePose.outLeft, TonguePose.outRight, TonguePose.up, TonguePose.tipUp)) {
            val weight = pose.tongueWeight(pos)
            if (weight > 0.01) drawTongueInside(pos, g, yU, hoE, context, opacity = min(1.0, weight))
        }
        drawTeeth(lowerEdge, -1.0, context)
        val between = pose.tongueWeight(TonguePose.between)
        if (between > 0.01) drawTongueBetween(g, yL, context, opacity = min(1.0, between))
        drawTeeth(upperEdge, 1.0, context)
        for (pos in listOf(TonguePose.out, TonguePose.outUp, TonguePose.outLeft, TonguePose.outRight)) {
            val weight = pose.tongueWeight(pos)
            if (weight > 0.01) drawTongueOut(pos, g, hoE, context, opacity = min(1.0, weight))
        }

        // The lips throw a soft shadow on what's behind them.
        val upperShade = strokePaint(Ink.inside, 4.0, opacity = 0.22)
        upperShade.maskFilter = blurFilter(2.5)
        context.drawPath(g.upper, upperShade)
        val lowerShade = strokePaint(Ink.inside, 3.0, opacity = 0.14)
        lowerShade.maskFilter = blurFilter(2.5)
        context.drawPath(g.lower, lowerShade)
        context.restore()

        val upperBox = g.upper.boundingRect
        context.drawPath(g.upper, gradientPaint(listOf(Ink.lipUpperLight, Ink.lipUpper), CGPoint(cx, upperBox.minY), CGPoint(cx, upperBox.maxY)))
        val lowerBox = g.lower.boundingRect
        context.drawPath(g.lower, gradientPaint(listOf(Ink.lipLower, Ink.lipLowerDark), CGPoint(cx, lowerBox.minY), CGPoint(cx, lowerBox.maxY)))
        context.save()
        context.clipPath(g.lower.android())
        val shine = fillPaint(Color.White, 0.42)
        shine.maskFilter = blurFilter(3.0)
        context.drawPath(
            CGPath.ellipse(CGRect(cx - lowerBox.width * 0.22, lowerBox.midY - 3, lowerBox.width * 0.44, 6.0)),
            shine,
        )
        context.restore()

        if (bite > 0.02) {
            context.save()
            context.clipPath(g.biteClip.android())
            drawTeeth(upperEdge, 1.0, context, opacity = min(1.0, bite * 1.5))
            context.restore()
        }

        for (pos in listOf(TonguePose.out, TonguePose.outUp, TonguePose.outLeft, TonguePose.outRight)) {
            val weight = pose.tongueWeight(pos)
            if (weight > 0.01) {
                context.save()
                // iOS clips to `outside(of: opening)`: a big rectangle plus the opening, with the default
                // non-zero rule. Both run the same way round, so the opening is not cut out and the tongue
                // is drawn over everything; the same path and rule here give the same result.
                context.clipPath(outside(g.opening).android())
                drawTongueOut(pos, g, hoE, context, opacity = min(1.0, weight))
                context.restore()
            }
        }
        context.restore()
    }

    private fun outside(path: CGPath): CGPath {
        val p = CGPath.rect(CGRect(-100, -100, 400, 350))
        p.addPath(path)
        return p
    }

    /** One white strip per arch: reads as teeth at any size and stays clean when the rows nearly touch. */
    private fun drawTeeth(edge: (Double) -> Double, dir: Double, context: android.graphics.Canvas, opacity: Double = 1.0) {
        val cx = MouthGeometry.cx
        val strip = CGPath()
        val reach = 62.0
        strip.move(CGPoint(cx - reach, edge(cx - reach)))
        // `stride(from: -reach, through: reach, by: 6)`: −62, −56, …, 58.
        var step = -reach
        while (step <= reach) {
            strip.addLine(CGPoint(cx + step, edge(cx + step)))
            step += 6
        }
        strip.addLine(CGPoint(cx + reach, edge(cx + reach) - dir * 60))
        strip.addLine(CGPoint(cx - reach, edge(cx - reach) - dir * 60))
        strip.closeSubpath()
        val far = edge(cx) - dir * 16
        context.drawPath(strip, gradientPaint(listOf(Ink.tooth, Ink.toothShade), CGPoint(cx, edge(cx)), CGPoint(cx, far), opacity))
    }

    private fun dome(cx: Double, w: Double, top: Double, bottom: Double, shoulder: Double = 3.0): CGPath {
        val p = CGPath()
        p.move(CGPoint(cx - w, bottom))
        p.addCurve(CGPoint(cx, top), CGPoint(cx - w, top + shoulder), CGPoint(cx - w * 0.55, top))
        p.addCurve(CGPoint(cx + w, bottom), CGPoint(cx + w * 0.55, top), CGPoint(cx + w, top + shoulder))
        p.closeSubpath()
        return p
    }

    private fun tongueFill(path: CGPath, context: android.graphics.Canvas, opacity: Double) {
        val box = path.boundingRect
        context.drawPath(path, gradientPaint(listOf(Ink.tongue, Ink.tongueShade), CGPoint(box.midX, box.minY), CGPoint(box.midX, box.maxY), opacity))
    }

    private fun drawTongueInside(pos: TonguePose, g: MouthGeometry, yU: Double, hoE: Double, context: android.graphics.Canvas, opacity: Double) {
        val cx = MouthGeometry.cx
        val w = min(g.ai * 0.86, 34.0)
        when (pos) {
            TonguePose.rest, TonguePose.out, TonguePose.outUp, TonguePose.outLeft, TonguePose.outRight -> {
                val top = g.yb - min(16.0, hoE * 0.4) + 1
                tongueFill(dome(cx, w, top, g.yb + 12), context, opacity)
            }
            TonguePose.up -> {
                val top = max(g.yt + 2, yU + 2.5)
                val ww = min(g.ai * 0.95, 38.0)
                tongueFill(dome(cx, ww, top, g.yb + 12, shoulder = 6.0), context, opacity)
            }
            TonguePose.tipUp -> {
                val base = g.yb - min(10.0, hoE * 0.22)
                val tw = min(g.ai * 0.42, 13.0)
                val amp = max(1.2, hoE * 0.08)
                val tipY = yU - 3 + wobble * amp
                context.drawPath(dome(cx, w, base - 1, g.yb + 12, shoulder = 1.0), fillPaint(Ink.tongueShade, opacity))
                val tip = CGPath()
                tip.move(CGPoint(cx - tw * 1.25, g.yb + 8))
                tip.addCurve(CGPoint(cx, tipY), CGPoint(cx - tw, tipY + (g.yb - tipY) * 0.5), CGPoint(cx - tw * 0.85, tipY))
                tip.addCurve(CGPoint(cx + tw * 1.25, g.yb + 8), CGPoint(cx + tw * 0.85, tipY), CGPoint(cx + tw, tipY + (g.yb - tipY) * 0.5))
                tip.closeSubpath()
                tongueFill(tip, context, opacity)
            }
            TonguePose.between, TonguePose.hidden -> Unit
        }
    }

    private fun drawTongueBetween(g: MouthGeometry, yL: Double, context: android.graphics.Canvas, opacity: Double) {
        val cx = MouthGeometry.cx
        val rx = min(g.ai * 0.55, 16.0)
        val cy = yL + 1.5
        val p = CGPath()
        p.move(CGPoint(cx - rx, cy + 6))
        p.addCurve(CGPoint(cx, cy - 5.5), CGPoint(cx - rx, cy - 5), CGPoint(cx - rx * 0.5, cy - 5.5))
        p.addCurve(CGPoint(cx + rx, cy + 6), CGPoint(cx + rx * 0.5, cy - 5.5), CGPoint(cx + rx, cy - 5))
        p.closeSubpath()
        tongueFill(p, context, opacity)
    }

    private fun drawTongueOut(pos: TonguePose, g: MouthGeometry, hoE: Double, context: android.graphics.Canvas, opacity: Double) {
        val cx = MouthGeometry.cx
        val tw = 14.0
        val base = g.yb - min(16.0, hoE * 0.4) + 4
        var p = CGPath()
        when (pos) {
            TonguePose.out -> {
                val end = g.ybot + 13
                p.move(CGPoint(cx - tw, base))
                p.addCurve(CGPoint(cx, end), CGPoint(cx - tw * 1.1, g.ybot - 2), CGPoint(cx - tw, end))
                p.addCurve(CGPoint(cx + tw, base), CGPoint(cx + tw, end), CGPoint(cx + tw * 1.1, g.ybot - 2))
            }
            TonguePose.outUp -> {
                val end = g.ypk - 15
                p.move(CGPoint(cx - tw, base + 8))
                p.addCurve(CGPoint(cx, end), CGPoint(cx - tw * 1.1, g.ypk + 2), CGPoint(cx - tw, end))
                p.addCurve(CGPoint(cx + tw, base + 8), CGPoint(cx + tw, end), CGPoint(cx + tw * 1.1, g.ypk + 2))
            }
            TonguePose.outLeft, TonguePose.outRight -> {
                val top = base - 4
                val x0 = cx - 12
                val x1 = cx + g.a + 20
                val yz = g.yc + 1.5
                p.move(CGPoint(x0, top + 2))
                p.addCurve(CGPoint(x1 - 7, yz - 8), CGPoint(x0 + 16, top), CGPoint(x1 - 26, yz - 9))
                p.addCurve(CGPoint(x1 - 7, yz + 8), CGPoint(x1 + 2, yz - 7.5), CGPoint(x1 + 2, yz + 7.5))
                p.addCurve(CGPoint(x0, top + 14), CGPoint(x1 - 24, yz + 9), CGPoint(x0 + 22, top + 14))
                if (pos == TonguePose.outLeft) {
                    // Mirrored across the middle of the mouth.
                    p = p.applying(CGAffineTransform(a = -1.0, tx = 2 * cx))
                }
            }
            else -> return
        }
        p.closeSubpath()
        tongueFill(p, context, opacity)
    }
}
