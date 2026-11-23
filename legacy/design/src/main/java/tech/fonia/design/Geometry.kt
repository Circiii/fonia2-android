package tech.fonia.design

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import android.graphics.Path as AndroidPath

// The drawings (mouth, profile, parrot, sparkles) are ported line by line from SwiftUI's `Path` and
// CoreGraphics. These few types keep the same names and the same conventions, so the Kotlin reads like the
// Swift: coordinates are Doubles (CGFloat on a 64-bit iPhone), y grows downward, angles are radians.

/** CGPoint. */
data class CGPoint(val x: Double, val y: Double) {
    constructor(x: Number, y: Number) : this(x.toDouble(), y.toDouble())

    fun moved(dx: Double, dy: Double) = CGPoint(x + dx, y + dy)

    fun toward(other: CGPoint, k: Double) = CGPoint(x + (other.x - x) * k, y + (other.y - y) * k)

    companion object {
        val zero = CGPoint(0.0, 0.0)
    }
}

/** CGRect. */
data class CGRect(val x: Double, val y: Double, val width: Double, val height: Double) {
    constructor(x: Number, y: Number, width: Number, height: Number) :
        this(x.toDouble(), y.toDouble(), width.toDouble(), height.toDouble())

    val minX get() = x
    val minY get() = y
    val maxX get() = x + width
    val maxY get() = y + height
    val midX get() = x + width / 2
    val midY get() = y + height / 2
}

/** CGAffineTransform: x' = a·x + c·y + tx, y' = b·x + d·y + ty. */
data class CGAffineTransform(
    val a: Double = 1.0,
    val b: Double = 0.0,
    val c: Double = 0.0,
    val d: Double = 1.0,
    val tx: Double = 0.0,
    val ty: Double = 0.0,
) {
    fun apply(p: CGPoint) = CGPoint(a * p.x + c * p.y + tx, b * p.x + d * p.y + ty)

    /** This transform first, then [t]. */
    fun concatenating(t: CGAffineTransform) = CGAffineTransform(
        a = a * t.a + b * t.c,
        b = a * t.b + b * t.d,
        c = c * t.a + d * t.c,
        d = c * t.b + d * t.d,
        tx = tx * t.a + ty * t.c + t.tx,
        ty = tx * t.b + ty * t.d + t.ty,
    )

    companion object {
        fun rotation(angle: Double) = CGAffineTransform(cos(angle), sin(angle), -sin(angle), cos(angle))
        fun translation(x: Double, y: Double) = CGAffineTransform(tx = x, ty = y)
        fun scale(x: Double, y: Double) = CGAffineTransform(a = x, d = y)
    }
}

/**
 * A path built the way SwiftUI's `Path` is: moves, lines, quadratic and cubic curves, arcs turned into cubic
 * curves, ellipses and rectangles. It keeps its own segments in Doubles, so it can be transformed exactly
 * and can give the tight bounding box `Path.boundingRect` gives (control points left out).
 *
 * Every closed shape runs in the same direction as CoreGraphics' rectangle (along the top from left to
 * right, as y grows downward). With the default non-zero fill, overlapping ellipses and rectangles in one
 * path then add up to their union, as they do on iOS (the clouds, the start button's skirt).
 */
class CGPath {
    private val ops = ArrayList<DoubleArray>()
    private var current: CGPoint? = null
    private var subpathStart: CGPoint? = null
    private var cached: AndroidPath? = null

    val isEmpty get() = ops.isEmpty()
    val currentPoint get() = current

    fun copy(): CGPath = CGPath().also { it.addPath(this) }

    fun move(to: CGPoint) {
        ops += doubleArrayOf(MOVE, to.x, to.y)
        current = to
        subpathStart = to
        cached = null
    }

    fun addLine(to: CGPoint) {
        if (current == null) return move(to)
        ops += doubleArrayOf(LINE, to.x, to.y)
        current = to
        cached = null
    }

    /** SwiftUI `addLines`: starts a new subpath at the first point. */
    fun addLines(points: List<CGPoint>) {
        if (points.isEmpty()) return
        move(points[0])
        for (i in 1 until points.size) addLine(points[i])
    }

    fun addQuadCurve(to: CGPoint, control: CGPoint) {
        if (current == null) move(control)
        ops += doubleArrayOf(QUAD, control.x, control.y, to.x, to.y)
        current = to
        cached = null
    }

    fun addCurve(to: CGPoint, control1: CGPoint, control2: CGPoint) {
        if (current == null) move(control1)
        ops += doubleArrayOf(CUBIC, control1.x, control1.y, control2.x, control2.y, to.x, to.y)
        current = to
        cached = null
    }

    fun closeSubpath() {
        if (current == null) return
        ops += doubleArrayOf(CLOSE)
        current = subpathStart
        cached = null
    }

    /**
     * `addArc(center:radius:startAngle:endAngle:clockwise:)`. As in SwiftUI, `clockwise = false` makes the
     * angle grow from start to end, which on a y-down screen turns clockwise.
     */
    fun addArc(center: CGPoint, radius: Double, startAngle: Double, endAngle: Double, clockwise: Boolean) {
        var sweep = endAngle - startAngle
        if (!clockwise) {
            if (sweep >= 2 * PI) sweep = 2 * PI else while (sweep < 0) sweep += 2 * PI
        } else {
            if (sweep <= -2 * PI) sweep = -2 * PI else while (sweep > 0) sweep -= 2 * PI
        }
        val start = CGPoint(center.x + radius * cos(startAngle), center.y + radius * sin(startAngle))
        if (current == null) move(start) else addLine(start)
        appendArc(center, radius, radius, startAngle, sweep)
    }

    /** `addArc(tangent1End:tangent2End:radius:)`: a line to the first tangent point, then the arc. */
    fun addArc(tangent1End: CGPoint, tangent2End: CGPoint, radius: Double) {
        val p0 = current ?: return move(tangent1End)
        val p1 = tangent1End
        val p2 = tangent2End
        val v1x = p0.x - p1.x
        val v1y = p0.y - p1.y
        val v2x = p2.x - p1.x
        val v2y = p2.y - p1.y
        val l1 = sqrt(v1x * v1x + v1y * v1y)
        val l2 = sqrt(v2x * v2x + v2y * v2y)
        if (l1 < 1e-9 || l2 < 1e-9 || radius <= 0) return addLine(p1)
        val u1x = v1x / l1
        val u1y = v1y / l1
        val u2x = v2x / l2
        val u2y = v2y / l2
        val cosTheta = (u1x * u2x + u1y * u2y).coerceIn(-1.0, 1.0)
        val theta = acos(cosTheta)
        if (theta < 1e-9 || abs(theta - PI) < 1e-9) return addLine(p1)
        val dist = radius / tan(theta / 2)
        val t1 = CGPoint(p1.x + u1x * dist, p1.y + u1y * dist)
        val t2 = CGPoint(p1.x + u2x * dist, p1.y + u2y * dist)
        val bx = u1x + u2x
        val by = u1y + u2y
        val bl = sqrt(bx * bx + by * by)
        val centreDistance = radius / sin(theta / 2)
        val centre = CGPoint(p1.x + bx / bl * centreDistance, p1.y + by / bl * centreDistance)
        addLine(t1)
        val a1 = atan2(t1.y - centre.y, t1.x - centre.x)
        val a2 = atan2(t2.y - centre.y, t2.x - centre.x)
        var sweep = a2 - a1
        while (sweep > PI) sweep -= 2 * PI
        while (sweep < -PI) sweep += 2 * PI
        appendArc(centre, radius, radius, a1, sweep)
    }

    fun addRect(rect: CGRect) {
        move(CGPoint(rect.minX, rect.minY))
        addLine(CGPoint(rect.maxX, rect.minY))
        addLine(CGPoint(rect.maxX, rect.maxY))
        addLine(CGPoint(rect.minX, rect.maxY))
        closeSubpath()
    }

    fun addEllipse(rect: CGRect) {
        val rx = rect.width / 2
        val ry = rect.height / 2
        val centre = CGPoint(rect.midX, rect.midY)
        move(CGPoint(rect.maxX, rect.midY))
        appendArc(centre, rx, ry, 0.0, 2 * PI)
        closeSubpath()
    }

    /** `addRoundedRect(in:cornerSize:)` with circular corners. */
    fun addRoundedRect(rect: CGRect, cornerWidth: Double, cornerHeight: Double) {
        val rx = min(cornerWidth, rect.width / 2)
        val ry = min(cornerHeight, rect.height / 2)
        if (rx <= 0 || ry <= 0) return addRect(rect)
        move(CGPoint(rect.minX + rx, rect.minY))
        addLine(CGPoint(rect.maxX - rx, rect.minY))
        appendArc(CGPoint(rect.maxX - rx, rect.minY + ry), rx, ry, -PI / 2, PI / 2)
        addLine(CGPoint(rect.maxX, rect.maxY - ry))
        appendArc(CGPoint(rect.maxX - rx, rect.maxY - ry), rx, ry, 0.0, PI / 2)
        addLine(CGPoint(rect.minX + rx, rect.maxY))
        appendArc(CGPoint(rect.minX + rx, rect.maxY - ry), rx, ry, PI / 2, PI / 2)
        addLine(CGPoint(rect.minX, rect.minY + ry))
        appendArc(CGPoint(rect.minX + rx, rect.minY + ry), rx, ry, PI, PI / 2)
        closeSubpath()
    }

    /**
     * `RoundedRectangle(cornerRadius:style: .continuous)`: Apple's smooth corner. Each corner starts 1.528665 ×
     * radius from the vertex and is three cubic curves; the numbers are the widely used reverse-engineered
     * iOS 7+ outline. When a side is too short for the smooth corner to fit, the corner turns circular with
     * the same radius, which is how a short iOS card still reads.
     */
    fun addContinuousRoundedRect(
        rect: CGRect,
        topLeft: Double,
        topRight: Double,
        bottomRight: Double,
        bottomLeft: Double,
    ) {
        val half = min(rect.width, rect.height) / 2
        fun corner(radius: Double): Pair<Double, Boolean> {
            val r = min(max(radius, 0.0), half)
            return r to (r * SMOOTH_EXTENT <= half)
        }
        val (rTL, sTL) = corner(topLeft)
        val (rTR, sTR) = corner(topRight)
        val (rBR, sBR) = corner(bottomRight)
        val (rBL, sBL) = corner(bottomLeft)
        // Each corner is described in its own frame: `along` runs from the vertex along the incoming edge,
        // `across` from the vertex along the outgoing edge.
        fun cornerPoint(vertex: CGPoint, inDir: CGPoint, outDir: CGPoint, r: Double, along: Double, across: Double) =
            CGPoint(vertex.x + inDir.x * along * r + outDir.x * across * r, vertex.y + inDir.y * along * r + outDir.y * across * r)

        fun drawCorner(vertex: CGPoint, inDir: CGPoint, outDir: CGPoint, r: Double, smooth: Boolean) {
            if (r <= 0) {
                addLine(vertex)
                return
            }
            fun p(along: Double, across: Double) = cornerPoint(vertex, inDir, outDir, r, along, across)
            if (!smooth) {
                addLine(p(1.0, 0.0))
                addCurve(p(0.0, 1.0), p(1 - KAPPA, 0.0), p(0.0, 1 - KAPPA))
                return
            }
            addLine(p(SMOOTH_EXTENT, 0.0))
            addCurve(p(0.66993427, 0.06549600), p(1.08849323, 0.0), p(0.86840689, 0.0))
            addLine(p(0.63149399, 0.07491100))
            addCurve(p(0.07491100, 0.63149399), p(0.37282392, 0.16905899), p(0.16905899, 0.37282392))
            addCurve(p(0.0, SMOOTH_EXTENT), p(0.0, 0.86840689), p(0.0, 1.08849323))
        }
        val tl = CGPoint(rect.minX, rect.minY)
        val tr = CGPoint(rect.maxX, rect.minY)
        val br = CGPoint(rect.maxX, rect.maxY)
        val bl = CGPoint(rect.minX, rect.maxY)
        val left = CGPoint(-1.0, 0.0)
        val right = CGPoint(1.0, 0.0)
        val up = CGPoint(0.0, -1.0)
        val down = CGPoint(0.0, 1.0)
        // Start on the top edge just after the top-left corner, then go round clockwise (on screen).
        val startAlong = if (rTL <= 0) 0.0 else if (sTL) SMOOTH_EXTENT else 1.0
        move(CGPoint(rect.minX + startAlong * rTL, rect.minY))
        // Top-right corner: arriving along the top edge (incoming direction from the vertex: left), leaving down.
        drawCorner(tr, left, down, rTR, sTR)
        drawCorner(br, up, left, rBR, sBR)
        drawCorner(bl, right, up, rBL, sBL)
        drawCorner(tl, down, right, rTL, sTL)
        closeSubpath()
    }

    fun addPath(other: CGPath) {
        for (op in other.ops) ops += op.copyOf()
        if (other.current != null) {
            current = other.current
            subpathStart = other.subpathStart
        }
        cached = null
    }

    fun applying(t: CGAffineTransform): CGPath {
        val out = CGPath()
        for (op in ops) {
            val copy = op.copyOf()
            var i = 1
            while (i + 1 < copy.size) {
                val p = t.apply(CGPoint(copy[i], copy[i + 1]))
                copy[i] = p.x
                copy[i + 1] = p.y
                i += 2
            }
            out.ops += copy
        }
        out.current = current?.let(t::apply)
        out.subpathStart = subpathStart?.let(t::apply)
        return out
    }

    /** The tight box around the outline, without control points, like `Path.boundingRect`. */
    val boundingRect: CGRect
        get() {
            var minX = Double.POSITIVE_INFINITY
            var minY = Double.POSITIVE_INFINITY
            var maxX = Double.NEGATIVE_INFINITY
            var maxY = Double.NEGATIVE_INFINITY
            fun include(x: Double, y: Double) {
                minX = min(minX, x)
                minY = min(minY, y)
                maxX = max(maxX, x)
                maxY = max(maxY, y)
            }
            var cx = 0.0
            var cy = 0.0
            var sx = 0.0
            var sy = 0.0
            for (op in ops) {
                when (op[0]) {
                    MOVE -> {
                        cx = op[1]; cy = op[2]; sx = cx; sy = cy
                        include(cx, cy)
                    }
                    LINE -> {
                        cx = op[1]; cy = op[2]
                        include(cx, cy)
                    }
                    QUAD -> {
                        for (t in quadExtrema(cx, op[1], op[3]) + quadExtrema(cy, op[2], op[4])) {
                            val u = 1 - t
                            include(u * u * cx + 2 * u * t * op[1] + t * t * op[3], u * u * cy + 2 * u * t * op[2] + t * t * op[4])
                        }
                        cx = op[3]; cy = op[4]
                        include(cx, cy)
                    }
                    CUBIC -> {
                        for (t in cubicExtrema(cx, op[1], op[3], op[5]) + cubicExtrema(cy, op[2], op[4], op[6])) {
                            include(cubic(cx, op[1], op[3], op[5], t), cubic(cy, op[2], op[4], op[6], t))
                        }
                        cx = op[5]; cy = op[6]
                        include(cx, cy)
                    }
                    CLOSE -> {
                        cx = sx; cy = sy
                    }
                }
            }
            if (minX > maxX) return CGRect(0.0, 0.0, 0.0, 0.0)
            return CGRect(minX, minY, maxX - minX, maxY - minY)
        }

    /** The same outline as an Android path, non-zero fill. Built once and kept until the path changes. */
    fun android(): AndroidPath {
        cached?.let { return it }
        val p = AndroidPath()
        p.fillType = AndroidPath.FillType.WINDING
        for (op in ops) {
            when (op[0]) {
                MOVE -> p.moveTo(op[1].toFloat(), op[2].toFloat())
                LINE -> p.lineTo(op[1].toFloat(), op[2].toFloat())
                QUAD -> p.quadTo(op[1].toFloat(), op[2].toFloat(), op[3].toFloat(), op[4].toFloat())
                CUBIC -> p.cubicTo(
                    op[1].toFloat(), op[2].toFloat(), op[3].toFloat(), op[4].toFloat(), op[5].toFloat(), op[6].toFloat(),
                )
                CLOSE -> p.close()
            }
        }
        cached = p
        return p
    }

    /** An elliptical arc as cubic curves of at most 90° each, from the current point. */
    private fun appendArc(centre: CGPoint, rx: Double, ry: Double, start: Double, sweep: Double) {
        if (abs(sweep) < 1e-12) return
        val segments = max(1, ceil(abs(sweep) / (PI / 2) - 1e-9).toInt())
        val step = sweep / segments
        val k = 4.0 / 3.0 * tan(step / 4)
        var a = start
        repeat(segments) {
            val b = a + step
            val p0 = CGPoint(centre.x + rx * cos(a), centre.y + ry * sin(a))
            val p3 = CGPoint(centre.x + rx * cos(b), centre.y + ry * sin(b))
            val c1 = CGPoint(p0.x - k * rx * sin(a), p0.y + k * ry * cos(a))
            val c2 = CGPoint(p3.x + k * rx * sin(b), p3.y - k * ry * cos(b))
            addCurve(p3, c1, c2)
            a = b
        }
    }

    companion object {
        private const val MOVE = 0.0
        private const val LINE = 1.0
        private const val QUAD = 2.0
        private const val CUBIC = 3.0
        private const val CLOSE = 4.0

        /** 4/3·(√2 − 1): a quarter circle as one cubic curve. */
        const val KAPPA = 0.5522847498307936
        const val SMOOTH_EXTENT = 1.52866483

        private fun cubic(p0: Double, p1: Double, p2: Double, p3: Double, t: Double): Double {
            val u = 1 - t
            return u * u * u * p0 + 3 * u * u * t * p1 + 3 * u * t * t * p2 + t * t * t * p3
        }

        private fun quadExtrema(p0: Double, p1: Double, p2: Double): List<Double> {
            val d = p0 - 2 * p1 + p2
            if (abs(d) < 1e-12) return emptyList()
            val t = (p0 - p1) / d
            return if (t > 0 && t < 1) listOf(t) else emptyList()
        }

        private fun cubicExtrema(p0: Double, p1: Double, p2: Double, p3: Double): List<Double> {
            val a = -p0 + 3 * p1 - 3 * p2 + p3
            val b = 2 * (p0 - 2 * p1 + p2)
            val c = p1 - p0
            val roots = ArrayList<Double>(2)
            if (abs(a) < 1e-12) {
                if (abs(b) > 1e-12) roots += -c / b
            } else {
                val disc = b * b - 4 * a * c
                if (disc >= 0) {
                    val s = sqrt(disc)
                    roots += (-b + s) / (2 * a)
                    roots += (-b - s) / (2 * a)
                }
            }
            return roots.filter { it > 0 && it < 1 }
        }

        fun ellipse(rect: CGRect) = CGPath().also { it.addEllipse(rect) }

        fun rect(rect: CGRect) = CGPath().also { it.addRect(rect) }

        /** `Path(roundedRect:cornerRadius:style: .continuous)`. */
        fun continuous(rect: CGRect, radius: Double) =
            CGPath().also { it.addContinuousRoundedRect(rect, radius, radius, radius, radius) }

        /** `Path(roundedRect:cornerRadius:)` with circular corners. */
        fun roundedRect(rect: CGRect, radius: Double) = CGPath().also { it.addRoundedRect(rect, radius, radius) }
    }
}

/** `RoundedRectangle(cornerRadius:style: .continuous)` as a Compose shape. */
class ContinuousRoundedRectangle(
    private val cornerRadius: Dp,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = with(density) { cornerRadius.toPx() }.toDouble()
        val path = CGPath.continuous(CGRect(0.0, 0.0, size.width.toDouble(), size.height.toDouble()), r)
        return Outline.Generic(path.android().asComposePath())
    }

    override fun equals(other: Any?) = other is ContinuousRoundedRectangle && other.cornerRadius == cornerRadius

    override fun hashCode() = cornerRadius.hashCode()
}

/** `UnevenRoundedRectangle(topLeadingRadius:topTrailingRadius:style: .continuous)`: rounded on top only. */
class ContinuousTopRoundedRectangle(
    private val radius: Dp,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = with(density) { radius.toPx() }.toDouble()
        val path = CGPath()
        path.addContinuousRoundedRect(CGRect(0.0, 0.0, size.width.toDouble(), size.height.toDouble()), r, r, 0.0, 0.0)
        return Outline.Generic(path.android().asComposePath())
    }

    override fun equals(other: Any?) = other is ContinuousTopRoundedRectangle && other.radius == radius

    override fun hashCode() = radius.hashCode()
}
