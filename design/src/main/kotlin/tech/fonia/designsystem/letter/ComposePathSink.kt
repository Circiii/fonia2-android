package tech.fonia.designsystem.letter

import androidx.compose.ui.graphics.Path
import tech.fonia.glyphs.PathSink
import kotlin.math.cos
import kotlin.math.sin

/**
 * A 2-D affine transform `x' = a·x + c·y + e`, `y' = b·x + d·y + f`, mutable and allocation-free. The letter's whole
 * pose (scale, squash about the baseline, shear, rotation, jump, font units → pixels with the y flip) is baked into
 * one of these and applied while the outline is emitted, so every layer reuses the same path.
 */
class Affine {
    var a = 1f; var b = 0f; var c = 0f; var d = 1f; var e = 0f; var f = 0f

    fun reset(): Affine { a = 1f; b = 0f; c = 0f; d = 1f; e = 0f; f = 0f; return this }

    fun set(o: Affine): Affine { a = o.a; b = o.b; c = o.c; d = o.d; e = o.e; f = o.f; return this }

    /** this = M · this, where M is the given matrix (i.e. apply M after the current transform). */
    private fun post(ma: Float, mb: Float, mc: Float, md: Float, me: Float, mf: Float): Affine {
        val na = ma * a + mc * b
        val nb = mb * a + md * b
        val nc = ma * c + mc * d
        val nd = mb * c + md * d
        val ne = ma * e + mc * f + me
        val nf = mb * e + md * f + mf
        a = na; b = nb; c = nc; d = nd; e = ne; f = nf
        return this
    }

    fun postTranslate(tx: Float, ty: Float): Affine { e += tx; f += ty; return this }

    fun postScale(sx: Float, sy: Float, px: Float = 0f, py: Float = 0f): Affine =
        postTranslate(-px, -py).post(sx, 0f, 0f, sy, 0f, 0f).postTranslate(px, py)

    /** Rotation in degrees (positive = clockwise on screen, y down). */
    fun postRotate(deg: Float, px: Float = 0f, py: Float = 0f): Affine {
        if (deg == 0f) return this
        val r = deg * (Math.PI.toFloat() / 180f)
        val cs = cos(r); val sn = sin(r)
        return postTranslate(-px, -py).post(cs, sn, -sn, cs, 0f, 0f).postTranslate(px, py)
    }

    /** Horizontal shear about the line y = py: `x += k · (py − y)` (positive k leans the top to the right). */
    fun postShear(k: Float, py: Float = 0f): Affine {
        if (k == 0f) return this
        return postTranslate(0f, -py).post(1f, 0f, -k, 1f, 0f, 0f).postTranslate(0f, py)
    }

    fun mapX(x: Float, y: Float) = a * x + c * y + e
    fun mapY(x: Float, y: Float) = b * x + d * y + f
}

/**
 * The [PathSink] → Compose [Path] adapter: emits glyph outlines (font units, y up) through an [Affine] into a reused
 * Compose path. No allocation per call; call [Path.rewind] before a new frame's emission.
 */
class ComposePathSink(var path: Path, override val matrix: Affine = Affine()) : AffinePathSink {
    override fun moveTo(x: Float, y: Float) = path.moveTo(matrix.mapX(x, y), matrix.mapY(x, y))
    override fun lineTo(x: Float, y: Float) = path.lineTo(matrix.mapX(x, y), matrix.mapY(x, y))
    override fun quadTo(x1: Float, y1: Float, x2: Float, y2: Float) =
        path.quadraticTo(matrix.mapX(x1, y1), matrix.mapY(x1, y1), matrix.mapX(x2, y2), matrix.mapY(x2, y2))

    override fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) =
        path.cubicTo(
            matrix.mapX(x1, y1), matrix.mapY(x1, y1),
            matrix.mapX(x2, y2), matrix.mapY(x2, y2),
            matrix.mapX(x3, y3), matrix.mapY(x3, y3),
        )

    override fun close() = path.close()
}
