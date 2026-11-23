package tech.fonia.glyphs

/**
 * Where outlines go. The module knows no `Path`; the Compose adapter (in :core:designsystem)
 * forwards to `androidx.compose.ui.graphics.Path` after `rewind()`. Coordinates are whatever the
 * emitter's transform produced (font units, y up, unless a scale/flip is passed to `emit`).
 */
interface PathSink {
    fun moveTo(x: Float, y: Float)
    fun lineTo(x: Float, y: Float)
    fun quadTo(x1: Float, y1: Float, x2: Float, y2: Float)
    fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float)
    fun close()
}

/**
 * Reusable result of [VariationInterpolator.interpolate], in TrueType form (on/off-curve points,
 * font units, y up). A composite is flattened, but each top-level component keeps its point and
 * contour range and its interpolated offset, so an animation can move it (the comma of Ș).
 *
 * Size it once per font ([AlloOutlines.maxPoints] etc. are the defaults) and reuse every frame.
 */
class GlyphOutline(
    pointCapacity: Int,
    contourCapacity: Int,
    componentCapacity: Int,
) {
    constructor(font: AlloOutlines) : this(font.maxPoints, font.maxContours, font.maxComponents)

    var glyphId: Int = -1
        internal set
    /** Interpolated advance width, font units. */
    var advance: Float = 0f
        internal set
    var pointCount: Int = 0
        internal set
    var contourCount: Int = 0
        internal set
    var componentCount: Int = 0
        internal set

    /** x0, y0, x1, y1, ... for [pointCount] points. Writable: ops may displace points in place. */
    val xy = FloatArray(pointCapacity * 2)
    val on = BooleanArray(pointCapacity)
    /** Index of the last point of each contour. */
    val contourEnds = IntArray(contourCapacity)

    internal val compGlyph = IntArray(componentCapacity)
    internal val compDx = FloatArray(componentCapacity)
    internal val compDy = FloatArray(componentCapacity)
    internal val compPointEnd = IntArray(componentCapacity)
    internal val compContourEnd = IntArray(componentCapacity)

    /** Glyph id of component [i] (a simple glyph is its own single component). */
    fun componentGlyph(i: Int): Int = compGlyph[i]
    /** Current offset of component [i], font units (interpolated, plus any [translateComponent]). */
    fun componentDx(i: Int): Float = compDx[i]
    fun componentDy(i: Int): Float = compDy[i]
    fun componentPointStart(i: Int): Int = if (i == 0) 0 else compPointEnd[i - 1]
    fun componentPointEnd(i: Int): Int = compPointEnd[i]
    fun componentContourStart(i: Int): Int = if (i == 0) 0 else compContourEnd[i - 1]
    fun componentContourEnd(i: Int): Int = compContourEnd[i]

    /** Index of the first component drawn from [glyphId] (e.g. `commaaccent`), or -1. */
    fun componentIndexOf(glyphId: Int): Int {
        for (i in 0 until componentCount) if (compGlyph[i] == glyphId) return i
        return -1
    }

    /** Moves component [i] by (dx, dy) font units on top of its interpolated offset. */
    fun translateComponent(i: Int, dx: Float, dy: Float) {
        compDx[i] += dx
        compDy[i] += dy
        var k = 2 * componentPointStart(i)
        val end = 2 * compPointEnd[i]
        while (k < end) { xy[k] += dx; xy[k + 1] += dy; k += 2 }
    }

    /** Bounding box of the points (control points included) into out = [xMin, yMin, xMax, yMax]. */
    fun bounds(out: FloatArray) {
        if (pointCount == 0) { out[0] = 0f; out[1] = 0f; out[2] = 0f; out[3] = 0f; return }
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        var k = 0
        while (k < 2 * pointCount) {
            val x = xy[k]; val y = xy[k + 1]
            if (x < x0) x0 = x; if (x > x1) x1 = x
            if (y < y0) y0 = y; if (y > y1) y1 = y
            k += 2
        }
        out[0] = x0; out[1] = y0; out[2] = x1; out[3] = y1
    }

    /** Emits every contour; `x' = x * sx + tx`, `y' = y * sy + ty` (pass a negative sy to flip y). */
    fun emit(sink: PathSink, sx: Float = 1f, sy: Float = 1f, tx: Float = 0f, ty: Float = 0f) =
        emitContours(sink, 0, contourCount, sx, sy, tx, ty)

    /** Emits only component [i] (e.g. to draw the comma on its own layer). */
    fun emitComponent(i: Int, sink: PathSink, sx: Float = 1f, sy: Float = 1f, tx: Float = 0f, ty: Float = 0f) =
        emitContours(sink, componentContourStart(i), compContourEnd[i], sx, sy, tx, ty)

    /**
     * TrueType quadratic rules: between two consecutive off-curve points lies an implied on-curve
     * point at their midpoint; a contour may start with off-curve points.
     */
    fun emitContours(sink: PathSink, fromContour: Int, toContour: Int, sx: Float, sy: Float, tx: Float, ty: Float) {
        for (c in fromContour until toContour) {
            val s = if (c == 0) 0 else contourEnds[c - 1] + 1
            val e = contourEnds[c]
            val n = e - s + 1
            if (n <= 0) continue
            var first = -1
            for (k in 0 until n) if (on[s + k]) { first = k; break }
            val startX: Float
            val startY: Float
            val k0: Int
            val count: Int
            if (first >= 0) {
                startX = xy[2 * (s + first)]; startY = xy[2 * (s + first) + 1]
                k0 = first + 1; count = n
            } else {
                // all off-curve: start at the midpoint of the last and first points
                val l = 2 * e; val f = 2 * s
                startX = (xy[l] + xy[f]) * 0.5f; startY = (xy[l + 1] + xy[f + 1]) * 0.5f
                k0 = 0; count = n
            }
            sink.moveTo(startX * sx + tx, startY * sy + ty)
            var hasCtrl = false
            var cx = 0f; var cy = 0f
            for (j in 0 until count) {
                var k = k0 + j
                if (k >= n) k -= n
                val p = s + k
                val x = xy[2 * p]; val y = xy[2 * p + 1]
                val isLast = first >= 0 && j == count - 1
                if (on[p]) {
                    if (hasCtrl) sink.quadTo(cx * sx + tx, cy * sy + ty, x * sx + tx, y * sy + ty)
                    else if (!isLast) sink.lineTo(x * sx + tx, y * sy + ty)
                    hasCtrl = false
                } else {
                    if (hasCtrl) {
                        val mx = (cx + x) * 0.5f; val my = (cy + y) * 0.5f
                        sink.quadTo(cx * sx + tx, cy * sy + ty, mx * sx + tx, my * sy + ty)
                    }
                    cx = x; cy = y; hasCtrl = true
                }
            }
            if (hasCtrl) sink.quadTo(cx * sx + tx, cy * sy + ty, startX * sx + tx, startY * sy + ty)
            sink.close()
        }
    }
}
