package tech.fonia.glyphs

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Geometric effects on a [QuadOutline] for the living letter. All write into caller-owned buffers
 * and allocate nothing once those are big enough. TrueType winding: ink is on the right of the
 * direction of travel, so the left normal points away from the ink ("outward").
 *
 * The axis morphs themselves are not here: "sharpen / round" (S <-> Ș) is the ROND axis and
 * "inflate while held" is wght + ROND + scale, both through [VariationInterpolator.setAxes]; the
 * comma drop is [GlyphOutline.translateComponent]. These ops add what axes cannot.
 */
object OutlineOps {

    /** Splits every curve so no piece is longer than [maxLen] (font units); lines are split too. */
    fun subdivide(src: QuadOutline, dst: QuadOutline, maxLen: Float) {
        require(src !== dst) { "subdivide needs two buffers" }
        require(maxLen > 0f)
        dst.reset()
        val s = src.seg
        for (c in 0 until src.contourCount) {
            val a = src.contourStart(c)
            val e = src.contourEnd[c]
            for (j in a until e) {
                val n = if (j + 1 == e) a else j + 1
                val p0x = s[4 * j]; val p0y = s[4 * j + 1]
                val cx = s[4 * j + 2]; val cy = s[4 * j + 3]
                val p1x = s[4 * n]; val p1y = s[4 * n + 1]
                val len = approxLength(p0x, p0y, cx, cy, p1x, p1y)
                val pieces = maxOf(1, ceil(len / maxLen).toInt())
                val isLine = src.line[j]
                for (k in 0 until pieces) {
                    val t0 = k.toFloat() / pieces
                    val t1 = (k + 1).toFloat() / pieces
                    val u0 = 1f - t0; val u1 = 1f - t1
                    // start point B(t0) and the control point of the sub-curve [t0, t1]
                    val bx = u0 * u0 * p0x + 2f * u0 * t0 * cx + t0 * t0 * p1x
                    val by = u0 * u0 * p0y + 2f * u0 * t0 * cy + t0 * t0 * p1y
                    val m = u0 * t1 + t0 * u1
                    val qx = u0 * u1 * p0x + m * cx + t0 * t1 * p1x
                    val qy = u0 * u1 * p0y + m * cy + t0 * t1 * p1y
                    dst.add(bx, by, qx, qy, isLine)
                }
            }
            dst.endContour()
        }
    }

    /** Horizontal shear: `x += k * (y - pivotY)` (Almost: k = tan 9°). In place is allowed. */
    fun shear(src: QuadOutline, dst: QuadOutline, k: Float, pivotY: Float = 0f) {
        if (src !== dst) dst.copyFrom(src)
        val s = dst.seg
        for (j in 0 until dst.segmentCount) {
            s[4 * j] += k * (s[4 * j + 1] - pivotY)
            s[4 * j + 2] += k * (s[4 * j + 3] - pivotY)
        }
    }

    /** `dst = a + (b - a) * t`; a and b must have the same structure (e.g. two subdivisions of one glyph). */
    fun lerp(a: QuadOutline, b: QuadOutline, t: Float, dst: QuadOutline) {
        require(a.segmentCount == b.segmentCount && a.contourCount == b.contourCount) { "lerp needs matching outlines" }
        if (dst !== a) dst.copyFrom(a)
        val d = dst.seg; val bs = b.seg
        for (i in 0 until a.segmentCount * 4) d[i] += (bs[i] - d[i]) * t
    }

    /**
     * Moves every point along its outward normal by [distance] font units (negative = thinner).
     * An approximate offset for small distances (a few % of the em); corners are not mitred.
     */
    fun offsetAlongNormals(src: QuadOutline, dst: QuadOutline, distance: Float) {
        require(src !== dst) { "offsetAlongNormals needs two buffers" }
        dst.copyFrom(src)
        val s = src.seg; val d = dst.seg
        for (c in 0 until src.contourCount) {
            val a = src.contourStart(c)
            val e = src.contourEnd[c]
            for (j in a until e) {
                val p = if (j == a) e - 1 else j - 1
                val n = if (j + 1 == e) a else j + 1
                // on-curve point: tangent from the previous control to its own control
                offsetPoint(d, 4 * j, s[4 * j + 2] - s[4 * p + 2], s[4 * j + 3] - s[4 * p + 3], distance)
                // control point: tangent along the chord
                offsetPoint(d, 4 * j + 2, s[4 * n] - s[4 * j], s[4 * n + 1] - s[4 * j + 1], distance)
            }
        }
    }

    private fun offsetPoint(d: FloatArray, k: Int, tx: Float, ty: Float, distance: Float) {
        val l = sqrt(tx * tx + ty * ty)
        if (l < 1e-6f) return
        d[k] += -ty / l * distance
        d[k + 1] += tx / l * distance
    }

    /**
     * Displaces every point (on-curve and control) by coherent value noise of amplitude [amp]
     * (font units) along the contour's arc length, wavelength [wavelength]; seamless around each
     * contour. Same [seed] = same shape (Unsure's 4 precomputed frames use seeds 0..3).
     * Subdivide first (plan: 0.08 em) so the noise has points to move.
     */
    fun noiseDisplace(src: QuadOutline, dst: QuadOutline, seed: Int, amp: Float, wavelength: Float) {
        require(src !== dst) { "noiseDisplace needs two buffers" }
        require(wavelength > 0f)
        dst.copyFrom(src)
        val s = src.seg; val d = dst.seg
        for (c in 0 until src.contourCount) {
            val a = src.contourStart(c)
            val e = src.contourEnd[c]
            var total = 0f
            for (j in a until e) total += segLength(s, j, if (j + 1 == e) a else j + 1)
            if (total <= 0f) continue
            val cells = maxOf(1, (total / wavelength).roundToInt())
            val sx = seed + c * CONTOUR_SALT
            val sy = sx xor Y_SALT
            var at = 0f
            for (j in a until e) {
                val len = segLength(s, j, if (j + 1 == e) a else j + 1)
                val xp = at / total * cells
                val xc = (at + 0.5f * len) / total * cells
                d[4 * j] += amp * OutlineNoise.value(sx, xp, cells)
                d[4 * j + 1] += amp * OutlineNoise.value(sy, xp, cells)
                d[4 * j + 2] += amp * OutlineNoise.value(sx, xc, cells)
                d[4 * j + 3] += amp * OutlineNoise.value(sy, xc, cells)
                at += len
            }
        }
    }

    /**
     * Places [count] samples at equal arc-length steps over all contours (POP particles), writing
     * x, y into [outXY] and the unit outward normal into [outNormal] (both >= 2 * count). Returns
     * the number written (0 for an empty outline).
     */
    fun sampleEvenly(src: QuadOutline, count: Int, outXY: FloatArray, outNormal: FloatArray): Int {
        if (count <= 0) return 0
        var total = 0f
        for (c in 0 until src.contourCount) {
            val a = src.contourStart(c); val e = src.contourEnd[c]
            for (j in a until e) total += flatLength(src, j, if (j + 1 == e) a else j + 1)
        }
        if (total <= 0f) return 0
        val step = total / count
        var next = step * 0.5f
        var walked = 0f
        var written = 0
        val s = src.seg
        for (c in 0 until src.contourCount) {
            val a = src.contourStart(c); val e = src.contourEnd[c]
            for (j in a until e) {
                val n = if (j + 1 == e) a else j + 1
                val p0x = s[4 * j]; val p0y = s[4 * j + 1]
                val cx = s[4 * j + 2]; val cy = s[4 * j + 3]
                val p1x = s[4 * n]; val p1y = s[4 * n + 1]
                var lx = p0x; var ly = p0y
                for (k in 1..FLAT_STEPS) {
                    val t = k.toFloat() / FLAT_STEPS
                    val u = 1f - t
                    val x = u * u * p0x + 2f * u * t * cx + t * t * p1x
                    val y = u * u * p0y + 2f * u * t * cy + t * t * p1y
                    val dl = dist(lx, ly, x, y)
                    while (written < count && next <= walked + dl && dl > 0f) {
                        val f = (next - walked) / dl
                        val tt = (k - 1 + f) / FLAT_STEPS
                        val uu = 1f - tt
                        outXY[2 * written] = uu * uu * p0x + 2f * uu * tt * cx + tt * tt * p1x
                        outXY[2 * written + 1] = uu * uu * p0y + 2f * uu * tt * cy + tt * tt * p1y
                        var dx = 2f * uu * (cx - p0x) + 2f * tt * (p1x - cx)
                        var dy = 2f * uu * (cy - p0y) + 2f * tt * (p1y - cy)
                        if (dx == 0f && dy == 0f) { dx = p1x - p0x; dy = p1y - p0y }
                        val l = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-6f)
                        outNormal[2 * written] = -dy / l
                        outNormal[2 * written + 1] = dx / l
                        written++
                        next += step
                    }
                    walked += dl
                    lx = x; ly = y
                }
            }
        }
        return written
    }

    private fun segLength(s: FloatArray, j: Int, n: Int): Float =
        approxLength(s[4 * j], s[4 * j + 1], s[4 * j + 2], s[4 * j + 3], s[4 * n], s[4 * n + 1])

    /** Length of a quad, (2 * chord + control polygon) / 3: within a few % for font curves. */
    private fun approxLength(p0x: Float, p0y: Float, cx: Float, cy: Float, p1x: Float, p1y: Float): Float {
        val chord = dist(p0x, p0y, p1x, p1y)
        val poly = dist(p0x, p0y, cx, cy) + dist(cx, cy, p1x, p1y)
        return (2f * chord + poly) / 3f
    }

    private fun flatLength(q: QuadOutline, j: Int, n: Int): Float {
        val s = q.seg
        val p0x = s[4 * j]; val p0y = s[4 * j + 1]; val cx = s[4 * j + 2]; val cy = s[4 * j + 3]
        val p1x = s[4 * n]; val p1y = s[4 * n + 1]
        var lx = p0x; var ly = p0y; var len = 0f
        for (k in 1..FLAT_STEPS) {
            val t = k.toFloat() / FLAT_STEPS; val u = 1f - t
            val x = u * u * p0x + 2f * u * t * cx + t * t * p1x
            val y = u * u * p0y + 2f * u * t * cy + t * t * p1y
            len += dist(lx, ly, x, y); lx = x; ly = y
        }
        return len
    }

    private fun dist(x0: Float, y0: Float, x1: Float, y1: Float): Float {
        val dx = x1 - x0; val dy = y1 - y0
        return sqrt(dx * dx + dy * dy)
    }

    private const val FLAT_STEPS = 8
    internal const val CONTOUR_SALT = 0x632BE5AB
    internal const val Y_SALT = 0x5BD1E995
}

/**
 * Deterministic 1-D value noise, periodic, identical on iOS (32-bit wrapping integer math):
 *
 *     h = i * 0x9E3779B9 + seed * 0x85EBCA6B   (wrapping)
 *     h ^= h >>> 16; h *= 0x7FEB352D; h ^= h >>> 15; h *= 0x846CA68B; h ^= h >>> 16
 *     lattice(i) = (h >>> 8) / 2^24 * 2 - 1                 in [-1, 1)
 *     value(x)   = lerp(lattice(i0 mod p), lattice((i0 + 1) mod p), smoothstep(x - i0)), i0 = floor(x)
 */
object OutlineNoise {
    fun lattice(seed: Int, i: Int): Float {
        var h = i * -0x61C88647 + seed * -0x7A143595
        h = h xor (h ushr 16)
        h *= 0x7FEB352D
        h = h xor (h ushr 15)
        h *= -0x7B935975
        h = h xor (h ushr 16)
        return (h ushr 8) / 16777216f * 2f - 1f
    }

    fun value(seed: Int, x: Float, period: Int): Float {
        val fl = floor(x)
        val i0 = fl.toInt()
        val f = x - fl
        val u = f * f * (3f - 2f * f)
        val a = lattice(seed, Math.floorMod(i0, period))
        val b = lattice(seed, Math.floorMod(i0 + 1, period))
        return a + (b - a) * u
    }
}
