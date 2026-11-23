package tech.fonia.glyphs

/**
 * Explicit form of an outline for geometric effects: every contour is a closed list of quadratic
 * segments `P[j] -> C[j] -> P[j+1]` (the last one returns to `P[0]`). Straight segments carry
 * `line[j] = true` and their control point at the midpoint. Font units, y up.
 *
 * Buffers grow when an op needs more room (an allocation only while warming up); size it with
 * enough [segmentCapacity] for the largest subdivision you use and nothing allocates per frame.
 */
class QuadOutline(segmentCapacity: Int = 256, contourCapacity: Int = 16) {
    var segmentCount: Int = 0
        internal set
    var contourCount: Int = 0
        internal set

    /** Per segment: Px, Py, Cx, Cy. */
    var seg = FloatArray(segmentCapacity * 4)
        private set
    var line = BooleanArray(segmentCapacity)
        private set
    /** Exclusive end segment of each contour. */
    var contourEnd = IntArray(contourCapacity)
        private set

    fun contourStart(c: Int): Int = if (c == 0) 0 else contourEnd[c - 1]

    internal fun reset() { segmentCount = 0; contourCount = 0 }

    internal fun ensure(segments: Int, contours: Int) {
        if (segments > line.size) {
            val n = maxOf(segments, line.size * 2)
            seg = seg.copyOf(n * 4); line = line.copyOf(n)
        }
        if (contours > contourEnd.size) contourEnd = contourEnd.copyOf(maxOf(contours, contourEnd.size * 2))
    }

    internal fun add(px: Float, py: Float, cx: Float, cy: Float, isLine: Boolean) {
        ensure(segmentCount + 1, contourCount + 1)
        val k = segmentCount * 4
        seg[k] = px; seg[k + 1] = py; seg[k + 2] = cx; seg[k + 3] = cy
        line[segmentCount] = isLine
        segmentCount++
    }

    internal fun endContour(): Boolean {
        val start = contourStart(contourCount)
        if (segmentCount == start) return false
        ensure(segmentCount, contourCount + 1)
        contourEnd[contourCount++] = segmentCount
        return true
    }

    fun copyFrom(src: QuadOutline) {
        ensure(src.segmentCount, src.contourCount)
        System.arraycopy(src.seg, 0, seg, 0, src.segmentCount * 4)
        System.arraycopy(src.line, 0, line, 0, src.segmentCount)
        System.arraycopy(src.contourEnd, 0, contourEnd, 0, src.contourCount)
        segmentCount = src.segmentCount
        contourCount = src.contourCount
    }

    /** `x' = x * sx + tx`, `y' = y * sy + ty`. Straight segments are emitted as lines. */
    fun emit(sink: PathSink, sx: Float = 1f, sy: Float = 1f, tx: Float = 0f, ty: Float = 0f) {
        for (c in 0 until contourCount) {
            val s = contourStart(c)
            val e = contourEnd[c]
            sink.moveTo(seg[4 * s] * sx + tx, seg[4 * s + 1] * sy + ty)
            for (j in s until e) {
                val n = if (j + 1 == e) s else j + 1
                val nx = seg[4 * n] * sx + tx
                val ny = seg[4 * n + 1] * sy + ty
                if (line[j]) {
                    if (j + 1 != e) sink.lineTo(nx, ny)
                } else {
                    sink.quadTo(seg[4 * j + 2] * sx + tx, seg[4 * j + 3] * sy + ty, nx, ny)
                }
            }
            sink.close()
        }
    }

    companion object {
        /** Converts the TrueType form (implied on-curve midpoints made explicit). Allocation-free once sized. */
        fun from(src: GlyphOutline, dst: QuadOutline) {
            dst.reset()
            val xy = src.xy
            for (c in 0 until src.contourCount) {
                val s = if (c == 0) 0 else src.contourEnds[c - 1] + 1
                val e = src.contourEnds[c]
                val n = e - s + 1
                if (n <= 0) continue
                var first = -1
                for (k in 0 until n) if (src.on[s + k]) { first = k; break }
                var px: Float; var py: Float
                val k0: Int
                if (first >= 0) {
                    px = xy[2 * (s + first)]; py = xy[2 * (s + first) + 1]; k0 = first + 1
                } else {
                    px = (xy[2 * e] + xy[2 * s]) * 0.5f; py = (xy[2 * e + 1] + xy[2 * s + 1]) * 0.5f; k0 = 0
                }
                val sx = px; val sy = py
                var hasCtrl = false
                var cx = 0f; var cy = 0f
                for (j in 0 until n) {
                    var k = k0 + j
                    if (k >= n) k -= n
                    val p = s + k
                    val x = xy[2 * p]; val y = xy[2 * p + 1]
                    if (src.on[p]) {
                        if (hasCtrl) dst.add(px, py, cx, cy, false)
                        else if (x != px || y != py) dst.add(px, py, (px + x) * 0.5f, (py + y) * 0.5f, true)
                        px = x; py = y; hasCtrl = false
                    } else {
                        if (hasCtrl) {
                            val mx = (cx + x) * 0.5f; val my = (cy + y) * 0.5f
                            dst.add(px, py, cx, cy, false)
                            px = mx; py = my
                        }
                        cx = x; cy = y; hasCtrl = true
                    }
                }
                if (hasCtrl) dst.add(px, py, cx, cy, false)
                else if (first < 0 && (px != sx || py != sy)) dst.add(px, py, (px + sx) * 0.5f, (py + sy) * 0.5f, true)
                dst.endContour()
            }
        }
    }
}
