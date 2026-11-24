package tech.fonia.mouth3d.style

import kotlin.math.sqrt

/**
 * Shading kinds, one float per vertex (the GL shader and the JVM preview read the same values). [VIGNETTE] is added to
 * a kind: the fragment fades out near the edge of the frame.
 */
object Kind {
    const val LIT = 0f
    const val FLAT = 1f
    const val DISC = 2f
    const val SKIN = 3f
    /** The cut's cap of the head: tissue colour, the lips' red in front of the incisors. */
    const val CAP_HEAD = 4f
    const val CAP_TONGUE = 5f
    const val SCAN = 6f
    const val ENAMEL = 7f
    const val VIGNETTE = 8f
}

/** Draw state of a run of triangles. */
enum class Blend { Opaque, Alpha, AlphaNoDepthWrite }

/**
 * What a run does. [Draw]: colour + depth. [Stencil]: no colour, no depth test, flips the stencil for every fragment
 * (after an optional stencil clear), so the cut plane's pixels that lie inside a closed mesh end up odd. [Cap]: the cut
 * plane's quad, drawn only where the stencil is odd.
 */
enum class Pass { Draw, Stencil, Cap }

/**
 * [dissolve] < 1 drops that share of the run's pixels in a fixed noise pattern. [clip] = the cut plane discards the
 * near half. [clearStencil]: clear the stencil before a [Pass.Stencil] run.
 */
class Batch(
    val first: Int,
    val count: Int,
    val blend: Blend,
    val dissolve: Float = 1f,
    val pass: Pass = Pass.Draw,
    val clip: Boolean = true,
    val clearStencil: Boolean = false,
)

/**
 * One frame's geometry: an interleaved vertex array ([STRIDE] floats: position, normal, rgba, kind, gloss, glow, u, v),
 * 32-bit indices and the batches in draw order. Rebuilt on the CPU each frame into the same arrays.
 */
class MeshBuilder(initialVertices: Int = 16384) {
    var data = FloatArray(initialVertices * STRIDE); private set
    var vertexCount = 0; private set
    var indices = IntArray(initialVertices * 6); private set
    var indexCount = 0; private set
    val batches = ArrayList<Batch>()
    private var batchStart = 0
    private var blend = Blend.Opaque
    private var batchDissolve = 1f
    private var batchClip = true

    /** Applies to the runs begun from now on. */
    var dissolve = 1f

    // current vertex attributes
    var r = 1f; var g = 1f; var b = 1f; var a = 1f
    var kind = Kind.LIT; var gloss = 0f; var glow = 0f

    private val du = FloatArray(3)
    private val dv = FloatArray(3)
    private val n = FloatArray(3)

    fun reset() {
        vertexCount = 0; indexCount = 0; batches.clear(); batchStart = 0; blend = Blend.Opaque
        dissolve = 1f; batchDissolve = 1f; batchClip = true
    }

    fun color(c: FloatArray, alpha: Float = 1f) { r = c[0]; g = c[1]; b = c[2]; a = alpha }

    /** Starts a new drawn run with [mode]; the previous run is closed. [clip]: cut by the cut plane. */
    fun begin(mode: Blend, clip: Boolean = true) { flush(); blend = mode; batchDissolve = dissolve; batchClip = clip }

    fun flush() {
        if (indexCount > batchStart) batches += Batch(batchStart, indexCount - batchStart, blend, batchDissolve, Pass.Draw, batchClip)
        batchStart = indexCount
    }

    /** The index position, to remember the start of a closed mesh for [stencil]. */
    fun mark(): Int { flush(); return indexCount }

    /** Re-draws the indices [from] … now as a stencil run (the mesh must be closed). */
    fun stencil(from: Int, to: Int, clear: Boolean) {
        flush()
        if (to > from) batches += Batch(from, to - from, Blend.Opaque, batchDissolve, Pass.Stencil, true, clear)
    }

    /** A quad in the plane x = [x] covering y, z ∈ [y0, y1] × [z0, z1], drawn where the stencil is odd. */
    fun cap(x: Float, y0: Float, y1: Float, z0: Float, z1: Float, normalX: Float) {
        flush()
        val s = indexCount
        val v0 = vertex(x, y0, z0, normalX, 0f, 0f); val v1 = vertex(x, y1, z0, normalX, 0f, 0f)
        val v2 = vertex(x, y1, z1, normalX, 0f, 0f); val v3 = vertex(x, y0, z1, normalX, 0f, 0f)
        tri(v0, v1, v2); tri(v0, v2, v3)
        batches += Batch(s, 6, Blend.Opaque, batchDissolve, Pass.Cap, false)
        batchStart = indexCount
    }

    fun vertex(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, u: Float = 0f, v: Float = 0f, alpha: Float = a): Int {
        if ((vertexCount + 1) * STRIDE > data.size) data = data.copyOf(data.size * 2)
        val o = vertexCount * STRIDE
        val d = data
        d[o] = x; d[o + 1] = y; d[o + 2] = z
        d[o + 3] = nx; d[o + 4] = ny; d[o + 5] = nz
        d[o + 6] = r; d[o + 7] = g; d[o + 8] = b; d[o + 9] = alpha
        d[o + 10] = kind; d[o + 11] = gloss; d[o + 12] = glow; d[o + 13] = u; d[o + 14] = v
        return vertexCount++
    }

    fun tri(i: Int, j: Int, k: Int) {
        if (indexCount + 3 > indices.size) indices = indices.copyOf(indices.size * 2)
        indices[indexCount++] = i; indices[indexCount++] = j; indices[indexCount++] = k
    }

    /**
     * A grid of [nu] × [nv] points ([pos] = xyz per point, u major), normals from neighbours (a row that collapses to
     * a point — a pole — borrows its neighbour row's direction). [wrapU] joins the last column to the first.
     * [alpha], [tint] (rgb multipliers) and [colors] (rgb, replacing the current colour) are optional per point.
     */
    fun grid(nu: Int, nv: Int, wrapU: Boolean, pos: FloatArray, alpha: FloatArray? = null, tint: FloatArray? = null, flip: Boolean = false, colors: FloatArray? = null) {
        val base = vertexCount
        for (i in 0 until nu) for (j in 0 until nv) {
            val ip = if (wrapU) (i + 1) % nu else (i + 1).coerceAtMost(nu - 1)
            val im = if (wrapU) (i - 1 + nu) % nu else (i - 1).coerceAtLeast(0)
            val jp = (j + 1).coerceAtMost(nv - 1); val jm = (j - 1).coerceAtLeast(0)
            var jj = j
            for (k in 0 until 3) du[k] = pos[(ip * nv + jj) * 3 + k] - pos[(im * nv + jj) * 3 + k]
            if (du[0] * du[0] + du[1] * du[1] + du[2] * du[2] < 1e-10f) {
                jj = if (j == 0) 1 else nv - 2
                for (k in 0 until 3) du[k] = pos[(ip * nv + jj) * 3 + k] - pos[(im * nv + jj) * 3 + k]
            }
            for (k in 0 until 3) dv[k] = pos[(i * nv + jp) * 3 + k] - pos[(i * nv + jm) * 3 + k]
            n[0] = du[1] * dv[2] - du[2] * dv[1]; n[1] = du[2] * dv[0] - du[0] * dv[2]; n[2] = du[0] * dv[1] - du[1] * dv[0]
            var l = sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2])
            if (l < 1e-9f) { n[0] = 0f; n[1] = 0f; n[2] = 1f; l = 1f }
            val s = if (flip) -1f / l else 1f / l
            val o = (i * nv + j) * 3
            val r0 = r; val g0 = g; val b0 = b
            if (colors != null) { r = colors[o]; g = colors[o + 1]; b = colors[o + 2] }
            if (tint != null) { r *= tint[o]; g *= tint[o + 1]; b *= tint[o + 2] }
            vertex(pos[o], pos[o + 1], pos[o + 2], n[0] * s, n[1] * s, n[2] * s, alpha = alpha?.get(i * nv + j) ?: a)
            r = r0; g = g0; b = b0
        }
        val cols = if (wrapU) nu else nu - 1
        for (i in 0 until cols) {
            val i2 = (i + 1) % nu
            for (j in 0 until nv - 1) {
                val p00 = base + i * nv + j; val p01 = p00 + 1
                val p10 = base + i2 * nv + j; val p11 = p10 + 1
                if (flip) { tri(p00, p01, p10); tri(p10, p01, p11) } else { tri(p00, p10, p01); tri(p10, p11, p01) }
            }
        }
    }

    /** A flat polygon in the plane x = [x] of the side view. */
    fun capSagittal(poly: List<P2>, x: Float, map: (P2) -> Pair<Float, Float>) {
        val tris = Geo.triangulate(poly)
        val base = vertexCount
        for (p in poly) { val (y, z) = map(p); vertex(x, y, z, -1f, 0f, 0f) }
        for (t in tris.indices step 3) tri(base + tris[t], base + tris[t + 1], base + tris[t + 2])
    }

    /** A camera-facing square of half-size [r] at (x, y, z), uv 0…1 (for [Kind.DISC]). */
    fun billboard(x: Float, y: Float, z: Float, r: Float, right: FloatArray, up: FloatArray) {
        val base = vertexCount
        for (k in 0 until 4) {
            val u = if (k == 1 || k == 2) 1f else 0f; val w = if (k >= 2) 1f else 0f
            val su = (u * 2 - 1) * r; val sv = (w * 2 - 1) * r
            vertex(x + right[0] * su + up[0] * sv, y + right[1] * su + up[1] * sv, z + right[2] * su + up[2] * sv, 0f, 0f, 1f, u, w)
        }
        tri(base, base + 1, base + 2); tri(base, base + 2, base + 3)
    }

    companion object {
        const val STRIDE = 15
    }
}
