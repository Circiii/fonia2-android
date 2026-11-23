package tech.fonia.glyphs

/** User axis values -> normalised coordinates: default normalisation (min/def/max -> -1/0/1), then avar. */
class AxisMap internal constructor(private val axes: List<Axis>) {
    /** Axis index for a tag (0 = wght, 1 = ROND), or -1. */
    fun indexOf(tag: String): Int = axes.indexOfFirst { it.tag == tag }

    fun normalize(tag: String, user: Float): Float {
        val i = indexOf(tag)
        require(i >= 0) { "unknown axis $tag" }
        return normalize(i, user)
    }

    /** Allocation-free. Values outside the axis range are clamped. */
    fun normalize(axisIndex: Int, user: Float): Float {
        val a = axes[axisIndex]
        val v = when {
            user < a.default -> if (a.default == a.min) 0f else (maxOf(user, a.min) - a.default) / (a.default - a.min)
            user > a.default -> if (a.max == a.default) 0f else (minOf(user, a.max) - a.default) / (a.max - a.default)
            else -> 0f
        }
        return piecewise(v, a.avarFrom, a.avarTo)
    }

    private fun piecewise(v: Float, from: FloatArray, to: FloatArray): Float {
        if (from.size < 2) return v
        if (v <= from[0]) return to[0] + (v - from[0])
        val last = from.size - 1
        if (v >= from[last]) return to[last] + (v - from[last])
        var i = 1
        while (from[i] < v) i++
        val f0 = from[i - 1]; val f1 = from[i]
        if (v == f1) return to[i]
        return to[i - 1] + (to[i] - to[i - 1]) * (v - f0) / (f1 - f0)
    }
}

/**
 * Applies gvar-style variations to the exported glyphs. One instance per drawing thread:
 * it owns the per-frame [scalars]. Everything in the frame loop is allocation-free.
 *
 * Per frame: [setAxes] (cheap; recomputes the region scalars only when an axis moved by more
 * than 0.25 wght / 0.1 ROND units), then [interpolate] each glyph into a reused [GlyphOutline].
 */
class VariationInterpolator(val font: AlloOutlines) {
    /** Region scalars for the current axes; computed by [setAxes]. */
    val scalars = FloatArray(font.regionCount)

    var wght: Float = Float.NaN
        private set
    var rond: Float = Float.NaN
        private set

    init { setAxes(font.axes[0].default, font.axes[1].default) }

    /**
     * Sets the axes in user units (wght 100..900, ROND 0..100). Returns true when the scalars were
     * recomputed, i.e. when outlines interpolated before this call are stale.
     */
    fun setAxes(wghtUser: Float, rondUser: Float, force: Boolean = false): Boolean {
        if (!force && Math.abs(wghtUser - wght) < WGHT_EPS && Math.abs(rondUser - rond) < ROND_EPS) return false
        wght = wghtUser
        rond = rondUser
        scalars(font.axisMap.normalize(0, wghtUser), font.axisMap.normalize(1, rondUser), scalars)
        return true
    }

    /**
     * Region scalars for normalised coordinates: for each region the product over axes of the
     * tent (lo, peak, hi); an axis with peak 0 is skipped. Same rules as fontTools' supportScalar,
     * so intermediate masters work.
     */
    fun scalars(wghtN: Float, rondN: Float, out: FloatArray) {
        val reg = font.regions
        for (r in 0 until font.regionCount) {
            var s = 1f
            var k = r * 6
            for (axis in 0 until 2) {
                val v = if (axis == 0) wghtN else rondN
                val lo = reg[k]; val peak = reg[k + 1]; val hi = reg[k + 2]
                k += 3
                if (peak == 0f || lo > peak || peak > hi || (lo < 0f && hi > 0f) || v == peak) continue
                if (v <= lo || v >= hi) { s = 0f; break }
                s *= if (v < peak) (v - lo) / (peak - lo) else (hi - v) / (hi - peak)
            }
            out[r] = s
        }
    }

    /** Interpolated advance of a glyph, font units. */
    fun advance(glyphId: Int, scalars: FloatArray = this.scalars): Float {
        val g = font.glyphs[glyphId]
        var adv = g.advance
        val last = g.stride - 1
        for (t in g.tupleRegion.indices) {
            val s = scalars[g.tupleRegion[t]]
            if (s != 0f) adv += s * g.deltas[t * g.stride + last]
        }
        return adv
    }

    /**
     * `xy = default + sum(scalar[r] * delta[r])` into [out], unrounded. Composites: every
     * component is interpolated on its own and moved by its interpolated offset (see
     * [GlyphOutline.componentDx]); the offsets can be changed afterwards with
     * [GlyphOutline.translateComponent].
     */
    fun interpolate(glyphId: Int, out: GlyphOutline, scalars: FloatArray = this.scalars) {
        val g = font.glyphs[glyphId]
        out.glyphId = glyphId
        out.advance = advance(glyphId, scalars)
        out.pointCount = 0
        out.contourCount = 0
        val comps = g.comps
        if (comps == null) {
            out.componentCount = 1
            out.compGlyph[0] = glyphId
            out.compDx[0] = 0f
            out.compDy[0] = 0f
            appendSimple(g, out, 0f, 0f, scalars)
            out.compPointEnd[0] = out.pointCount
            out.compContourEnd[0] = out.contourCount
            return
        }
        out.componentCount = comps.size
        val cxy = g.compXY!!
        for (c in comps.indices) {
            var dx = cxy[2 * c]
            var dy = cxy[2 * c + 1]
            for (t in g.tupleRegion.indices) {
                val s = scalars[g.tupleRegion[t]]
                if (s != 0f) {
                    val b = t * g.stride + 2 * c
                    dx += s * g.deltas[b]
                    dy += s * g.deltas[b + 1]
                }
            }
            out.compGlyph[c] = comps[c]
            out.compDx[c] = dx
            out.compDy[c] = dy
            appendSimple(font.glyphs[comps[c]], out, dx, dy, scalars)
            out.compPointEnd[c] = out.pointCount
            out.compContourEnd[c] = out.contourCount
        }
    }

    private fun appendSimple(g: Glyph, out: GlyphOutline, dx: Float, dy: Float, scalars: FloatArray) {
        val n = g.pointCount
        val p0 = out.pointCount
        val xy = out.xy
        val base = 2 * p0
        val src = g.xy
        var i = 0
        while (i < 2 * n) {
            xy[base + i] = src[i] + dx
            xy[base + i + 1] = src[i + 1] + dy
            i += 2
        }
        val d = g.deltas
        val stride = g.stride
        for (t in g.tupleRegion.indices) {
            val s = scalars[g.tupleRegion[t]]
            if (s == 0f) continue
            val row = t * stride
            for (i in 0 until 2 * n) xy[base + i] += s * d[row + i]
        }
        System.arraycopy(g.on, 0, out.on, p0, n)
        val c0 = out.contourCount
        for (k in g.ends.indices) out.contourEnds[c0 + k] = g.ends[k] + p0
        out.contourCount = c0 + g.ends.size
        out.pointCount = p0 + n
    }

    companion object {
        /** Change thresholds below which [setAxes] keeps the old scalars (plan §3.3). */
        const val WGHT_EPS = 0.25f
        const val ROND_EPS = 0.1f
    }
}
