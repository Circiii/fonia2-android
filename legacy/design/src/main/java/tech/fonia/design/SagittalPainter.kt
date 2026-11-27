package tech.fonia.design

import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private class SagittalFrame(val pose: SagittalVector, wobble: Double) {
    val upperLip: List<CGPoint>
    val lowerLip: List<CGPoint>
    val protrusion: Double
    val tip: CGPoint
    val tongue: List<CGPoint>
    val velum: List<CGPoint>

    init {
        val jaw = pose.jaw
        val bite = pose.bite
        val pro = pose.round * 7

        // Rounded lips push forward; the gap and F/V lift the upper lip a little. Spread lips only pull the
        // outside back, because the inner face stays on the teeth.
        val upper = SagittalAnatomy.upperLip.mapIndexed { i, q ->
            val w = max(0.0, min(1.0, (q.y - 92) / 30))
            val k = if (pro < 0 && i > 7) 0.0 else 1.0
            CGPoint(q.x + pro * w * k * (if (q.y > 104) 1.0 else 0.6), q.y - w * (pose.lips * 0.3 + bite * 1.5))
        }
        // The lower lip turns with the jaw, then its free edge is placed so the gap matches the pose.
        // For F/V it tucks up and back under the upper incisors.
        val stomion = upper[7]
        val turned = SagittalAnatomy.lowerLip.map { SagittalAnatomy.turned(it, jaw) }
        val freeEdge = turned[0]
        val gapFix = stomion.y + pose.lips - freeEdge.y
        val lower = turned.mapIndexed { i, q ->
            val w = max(0.0, min(1.0, (147 - SagittalAnatomy.lowerLip[i].y) / 24))
            val x = q.x + pro * w * (if (pro < 0 && i > 3) 0.0 else 1.0)
            val y = q.y + gapFix * w * (1 - bite)
            CGPoint(x + bite * w * -8.5, y + bite * w * (SagittalAnatomy.upperEdge.y + 1.2 - freeEdge.y))
        }
        upperLip = upper
        lowerLip = lower
        protrusion = pro

        // One closed curve with its root on the floor of the mouth and the tip free. The underside starts
        // just behind the tip, so the blade ends thin instead of in a round nose.
        val tip = pose.tip.moved(0.0, wobble)
        this.tip = tip
        val length = max(hypot(tip.x - pose.blade.x, tip.y - pose.blade.y), 1.0)
        val ux = (tip.x - pose.blade.x) / length
        val uy = (tip.y - pose.blade.y) / length
        tongue = listOf(
            CGPoint(84, 162), CGPoint(pose.root.x + 4, 146.0), pose.root, pose.back, pose.dorsum, pose.front, pose.blade, tip,
            CGPoint(tip.x - ux * 3 - uy * 6.5, tip.y - uy * 3 + ux * 6.5),
            pose.under.moved(0.0, wobble * 0.5),
            SagittalAnatomy.turned(CGPoint(176, 149), jaw),
            SagittalAnatomy.turned(CGPoint(186, 158), jaw),
            CGPoint(112, 172),
        )
        velum = SagittalAnatomy.velumDown.zip(SagittalAnatomy.velumUp).map { (down, up) -> down.toward(up, pose.velum) }
    }

    fun rot(p: CGPoint) = SagittalAnatomy.turned(p, pose.jaw)

    val stomionUpper: CGPoint get() = upperLip[7]
    val stomionLower: CGPoint get() = lowerLip[0]

    val upperLipPath: CGPath get() = Trace.smooth(upperLip)
    val lowerLipPath: CGPath get() = Trace.smooth(lowerLip)

    val neckCurveStart: CGPoint get() = lowerLip[12]

    fun addNeck(p: CGPath) {
        val top = neckCurveStart
        p.addCurve(CGPoint(124, 196), top.moved(-22.0, 2.0), CGPoint(134.0, 190 + (top.y - 191) * 0.4))
        p.addCurve(CGPoint(116, 240), CGPoint(120, 200), CGPoint(118, 206))
    }

    val silhouette: CGPath
        get() {
            val p = CGPath()
            p.move(CGPoint(-30, -30))
            p.addLine(CGPoint(194, -30))
            p.addLine(CGPoint(197, 0))
            SagittalAnatomy.addProfile(p, upperLip[0])
            Trace.addCurves(p, upperLip.subList(0, 8), closed = false)
            p.addLine(lowerLip[0])
            Trace.addCurves(p, lowerLip.subList(0, 13), closed = false)
            addNeck(p)
            p.addLine(CGPoint(-30, 240))
            p.closeSubpath()
            return p
        }

    val airSpace: CGPath
        get() = Trace.polygon(
            listOf(
                stomionUpper, stomionLower, rot(CGPoint(208, 136)), rot(CGPoint(199, 140.5)), rot(CGPoint(189, 146.5)),
                CGPoint(168, 153), CGPoint(138, 160), CGPoint(110, 166), CGPoint(90, 168), CGPoint(86, 214), CGPoint(52, 214), CGPoint(47, 170),
                CGPoint(44.5, 120), CGPoint(44.5, 80), CGPoint(50, 64), CGPoint(70, 70), CGPoint(97, 74), CGPoint(130, 71), CGPoint(170, 72),
                CGPoint(196, 82), CGPoint(208, 100), CGPoint(212, 112),
            ),
        )

    fun airPath(stop: Boolean, lateral: Boolean): List<CGPoint> {
        val air = mutableListOf(CGPoint(70, 214), CGPoint(70, 190), CGPoint(64, 162), CGPoint(59, 132), CGPoint(66, 104))
        val roof = (velum.subList(5, velum.size) + SagittalAnatomy.palate).sortedBy { it.x }
        val surface = Trace.sample(tongue, from = 2, to = 7, steps = 12)
        val front = if (stop) tip.x - 16 else (if (lateral) pose.blade.x - 12 else tip.x - 6)
        var x = 96.0
        while (x < front) {
            val top = Trace.y(roof, x)
            val bottom = Trace.y(surface, x)
            if (top != null && bottom != null) air += CGPoint(x, (top + bottom) / 2)
            x += 16
        }
        // L: past the tip the air goes round it, across the side of the blade.
        if (lateral) air += pose.blade.moved(2.0, 9.0)
        if (!stop) {
            // Over the tip, not through it: a raised tip would otherwise sit across a straight run to the teeth.
            if (!lateral && pose.tip.x < pose.gate.x) {
                val top = Trace.y(roof, pose.tip.x + 1)
                if (top != null) air += CGPoint(pose.tip.x + 1, (top + pose.tip.y) / 2)
            }
            val lips = stomionUpper.toward(stomionLower, 0.5)
            air += pose.gate
            air += lips
            air += lips.moved(20 + protrusion * 0.5, 1.0)
        }
        return air
    }
}

internal class SagittalPainter(
    val pose: SagittalVector,
    val stop: Boolean,
    val lateral: Boolean,
    val airColor: Color,
    val voiceColor: Color,
    val casing: Color,
    /** Seconds, while something moves on its own; null draws the still pose. */
    val clock: Double?,
) {
    companion object {
        /**
         * Only the mouth: the tongue, the teeth and the roof, with room on the right for the air to leave.
         * The rest of the head adds nothing a learner needs and makes the picture look clinical.
         */
        val frame = CGRect(118, 60, 142, 110)
    }

    fun draw(context: android.graphics.Canvas, width: Double, height: Double) {
        val frame = frame
        val scale = min(width / frame.width, height / frame.height)
        context.save()
        context.translate(
            ((width - frame.width * scale) / 2 - frame.minX * scale).toFloat(),
            ((height - frame.height * scale) / 2 - frame.minY * scale).toFloat(),
        )
        context.scale(scale.toFloat(), scale.toFloat())
        context.clipPath(CGPath.continuous(frame, 12.0).android())

        val wobble = clock?.let { sin(it * 2 * PI * 5) * 3.2 } ?: 0.0
        val f = SagittalFrame(pose, wobble * pose.trill)

        drawHead(f, context)
        drawThroat(f, context)
        drawTongue(f, context)
        drawPalate(f, context)
        drawJaw(f, context)
        drawLips(f, context)
        drawVoice(context)
        drawAir(f, context)
        drawTrill(f, context)
        context.restore()
    }

    private fun fill(context: android.graphics.Canvas, path: CGPath, color: Color, opacity: Double = 1.0) =
        context.drawPath(path, fillPaint(color, opacity))

    private fun stroke(context: android.graphics.Canvas, path: CGPath, color: Color, width: Double, cap: Paint.Cap = Paint.Cap.BUTT, join: Paint.Join = Paint.Join.MITER, opacity: Double = 1.0) =
        context.drawPath(path, strokePaint(color, width, cap, join, opacity))

    private fun drawHead(f: SagittalFrame, context: android.graphics.Canvas) {
        fill(context, f.silhouette, SagittalInk.tissue)
        fill(context, SagittalAnatomy.noseCavity, SagittalInk.inside, 0.08)
        for (concha in SagittalAnatomy.conchae) {
            fill(context, concha, SagittalInk.tissue)
            stroke(context, concha, SagittalInk.tissueLine, 0.5)
        }
        fill(context, f.airSpace, SagittalInk.inside)
    }

    private fun drawThroat(f: SagittalFrame, context: android.graphics.Canvas) {
        val floor = SagittalAnatomy.floor.mapIndexed { i, q -> if (i in 4..7) q else f.rot(q) }
        fill(context, Trace.smooth(floor), SagittalInk.tissue)

        fill(context, SagittalAnatomy.esophagus, SagittalInk.tissue)
        fill(context, SagittalAnatomy.larynx, SagittalInk.tissue)
        for (cartilage in listOf(SagittalAnatomy.thyroid, SagittalAnatomy.hyoid)) {
            fill(context, cartilage, SagittalInk.bone)
            stroke(context, cartilage, SagittalInk.boneLine, 0.8)
        }

        // The vocal folds come together when the voice is on.
        val reach = 9 + 4 * pose.voice
        val half = 4 - pose.voice
        val folds = CGPath()
        folds.addLines(listOf(CGPoint(56.0, 191 - half), CGPoint(56 + reach, 191.0), CGPoint(56.0, 191 + half)))
        folds.closeSubpath()
        folds.addLines(listOf(CGPoint(84.0, 191 - half), CGPoint(84 - reach, 191.0), CGPoint(84.0, 191 + half)))
        folds.closeSubpath()
        fill(context, folds, SagittalInk.tongue)
        stroke(context, folds, SagittalInk.tongueLine, 0.6)
    }

    private fun drawTongue(f: SagittalFrame, context: android.graphics.Canvas) {
        val tongue = Trace.smooth(f.tongue)
        fill(context, tongue, SagittalInk.tongue)

        // Muscle fibres fanning from the jaw into the body (genioglossus) make it read as a tongue, not a balloon.
        val fan = f.rot(CGPoint(184, 157))
        val fibres = CGPath()
        for (q in listOf(pose.root, pose.back, pose.dorsum, pose.front, pose.blade)) {
            val mid = fan.toward(q, 0.5)
            fibres.move(fan)
            fibres.addQuadCurve(fan.toward(q, 0.86), mid.moved(-6.0, 4.0))
        }
        context.save()
        context.clipPath(tongue.android())
        stroke(context, fibres, SagittalInk.tongueLine, 0.8, cap = Paint.Cap.ROUND, opacity = 0.22)
        context.restore()

        // Only the free edge gets a line; the root grows out of the floor of the mouth.
        val edge = Trace.polyline(Trace.sample(f.tongue, from = 1, to = 10, steps = 12))
        stroke(context, edge, SagittalInk.tongueLine, 1.0, cap = Paint.Cap.ROUND, join = Paint.Join.ROUND)

        fill(context, SagittalAnatomy.epiglottis, SagittalInk.tissue)
        stroke(context, SagittalAnatomy.epiglottis, SagittalInk.tissueLine, 0.7)
    }

    private fun drawPalate(f: SagittalFrame, context: android.graphics.Canvas) {
        val velum = Trace.smooth(f.velum)
        fill(context, velum, SagittalInk.tissue)
        stroke(context, velum, SagittalInk.tissueLine, 0.8)

        fill(context, SagittalAnatomy.palateBone, SagittalInk.bone)
        stroke(context, SagittalAnatomy.palateBone, SagittalInk.boneLine, 0.8)
        fill(context, SagittalAnatomy.upperRoot, SagittalInk.tissue)
        stroke(context, SagittalAnatomy.upperRoot, SagittalInk.toothLine, 0.6)
        fill(context, SagittalAnatomy.palateLining, SagittalInk.tissue)
        stroke(context, SagittalAnatomy.palateLining, SagittalInk.tissueLine, 0.5)
        fill(context, SagittalAnatomy.upperGum, SagittalInk.tissue)
    }

    private fun drawJaw(f: SagittalFrame, context: android.graphics.Canvas) {
        fill(context, f.lowerLipPath, SagittalInk.tissue)

        val mandible = Trace.smooth(SagittalAnatomy.mandible.map(f::rot))
        fill(context, mandible, SagittalInk.bone)
        stroke(context, mandible, SagittalInk.boneLine, 0.8)

        val root = Trace.smooth(SagittalAnatomy.lowerRoot.map(f::rot))
        fill(context, root, SagittalInk.tissue)
        stroke(context, root, SagittalInk.toothLine, 0.6)
        fill(context, Trace.polygon(SagittalAnatomy.lowerGum.map(f::rot)), SagittalInk.tissue)

        val crown = Trace.smooth(SagittalAnatomy.lowerCrown.map(f::rot))
        fill(context, crown, SagittalInk.tooth)
        stroke(context, crown, SagittalInk.toothLine, 0.8)
    }

    private fun drawLips(f: SagittalFrame, context: android.graphics.Canvas) {
        val ul = f.upperLip
        val ll = f.lowerLip
        val upper = f.upperLipPath
        fill(context, upper, SagittalInk.tissue)
        context.save()
        context.clipPath(upper.android())
        fill(
            context,
            Trace.polygon(listOf(ul[3], ul[3].moved(30.0, -8.0), ul[3].moved(30.0, 40.0), ul[11].moved(-6.0, 30.0), ul[11].moved(-6.0, 0.0))),
            SagittalInk.lipUpper,
        )
        context.restore()
        stroke(context, Trace.smooth(ul.subList(0, 9), closed = false), SagittalInk.tissueLine, 1.0, cap = Paint.Cap.ROUND)

        fill(context, SagittalAnatomy.upperCrown, SagittalInk.tooth)
        stroke(context, SagittalAnatomy.upperCrown, SagittalInk.toothLine, 0.8)

        val lower = f.lowerLipPath
        context.save()
        context.clipPath(lower.android())
        fill(
            context,
            Trace.polygon(listOf(ll[4], ll[4].moved(30.0, 6.0), ll[4].moved(30.0, -50.0), ll[17].moved(-8.0, -50.0), ll[17].moved(-8.0, 0.0), ll[17])),
            SagittalInk.lipLower,
        )
        context.restore()
        stroke(context, Trace.smooth(listOf(ll[19]) + ll.subList(0, 13), closed = false), SagittalInk.tissueLine, 1.0, cap = Paint.Cap.ROUND)

        val profile = CGPath()
        profile.move(CGPoint(197, 0))
        SagittalAnatomy.addProfile(profile, ul[0])
        stroke(context, profile, SagittalInk.tissueLine, 1.0, cap = Paint.Cap.ROUND)

        val neck = CGPath()
        neck.move(f.neckCurveStart)
        f.addNeck(neck)
        stroke(context, neck, SagittalInk.tissueLine, 1.0, cap = Paint.Cap.ROUND)

        stroke(context, SagittalAnatomy.noseWing, SagittalInk.tissueLine, 1.1, cap = Paint.Cap.ROUND)
        fill(context, SagittalAnatomy.nostril, SagittalInk.tissueLine)
    }

    /** Where you feel the voice: on the front of the throat. */
    private fun drawVoice(context: android.graphics.Canvas) {
        if (pose.voice <= 0.01) return
        val pulse = clock?.let { 0.55 + 0.45 * abs(sin(it * PI * 4)) } ?: 1.0
        val arcs = CGPath()
        val center = CGPoint(124, 193)
        for (radius in listOf(5.0, 9.5, 14.0)) {
            arcs.move(center.moved(radius * cos(-0.7), radius * sin(-0.7)))
            arcs.addArc(center, radius, -0.7, 0.85, clockwise = false)
        }
        stroke(context, arcs, voiceColor, 1.8, cap = Paint.Cap.ROUND, opacity = pose.voice * pulse)
    }

    private fun drawAir(f: SagittalFrame, context: android.graphics.Canvas) {
        if (pose.air <= 0.01) return
        val points = f.airPath(stop, lateral)
        if (points.size <= 1) return
        val path = Trace.smooth(points, closed = false)
        val dash = clock?.let { (it * 40) % 12 } ?: 0.0
        val end = points[points.size - 1]
        val before = points[points.size - 2]
        val angle = atan2(end.y - before.y, end.x - before.x)
        fun corner(turn: Double) = end.moved(cos(angle + turn) * 5, sin(angle + turn) * 5)
        val head = Trace.polygon(listOf(corner(0.0), corner(2.4), corner(-2.4)))

        // One layer at the air's opacity, so the casing and the dashes don't add up where they overlap.
        val bounds = RectF(frame.minX.toFloat(), frame.minY.toFloat(), frame.maxX.toFloat(), frame.maxY.toFloat())
        context.saveLayerAlpha(bounds, (pose.air * 255).toInt().coerceIn(0, 255))
        // A paper casing keeps the dashes readable over both the dark mouth and the pale face.
        val phase = 12 - dash
        val pattern = floatArrayOf(6f, 6f)
        context.drawPath(path, strokePaint(casing.opacity(0.9), 4.2, Paint.Cap.ROUND, dash = pattern, dashPhase = phase))
        context.drawPath(path, strokePaint(airColor, 2.4, Paint.Cap.ROUND, dash = pattern, dashPhase = phase))
        context.drawPath(head, fillPaint(airColor))
        context.drawPath(head, strokePaint(casing, 1.2, join = Paint.Join.ROUND))
        context.restore()
    }

    private fun drawTrill(f: SagittalFrame, context: android.graphics.Canvas) {
        if (pose.trill <= 0.01) return
        val ticks = CGPath()
        ticks.move(f.tip.moved(-7.0, -1.0))
        ticks.addQuadCurve(f.tip.moved(-7.0, 5.0), f.tip.moved(-9.0, 2.0))
        ticks.move(f.tip.moved(-11.0, -2.0))
        ticks.addQuadCurve(f.tip.moved(-11.0, 6.0), f.tip.moved(-14.0, 2.0))
        stroke(context, ticks, casing, 1.0, cap = Paint.Cap.ROUND, opacity = pose.trill)
    }
}
