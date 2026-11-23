package tech.fonia.mouth3d

import tech.fonia.mouth3d.style.Blend
import tech.fonia.mouth3d.style.Geo
import tech.fonia.mouth3d.style.Mat4
import tech.fonia.mouth3d.style.MeshBuilder
import tech.fonia.mouth3d.style.Pass
import tech.fonia.mouth3d.style.StyleCamera
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A software copy of `render/StyleGl` (same vertex layout, same fragment maths, premultiplied alpha, batches in order,
 * the cut plane, the stencil parity pass and the stencil-tested caps), supersampled [ss]× for anti-aliasing. Used by
 * the offline previews and the contact sheets. Keep it identical to the GL shader.
 */
object StylePreview {
    fun render(mb: MeshBuilder, cam: StyleCamera, w: Int, h: Int, glow: FloatArray, rim: FloatArray, lips: FloatArray, bg: Int, ss: Int = 3): BufferedImage {
        val W = w * ss; val H = h * ss
        val color = FloatArray(W * H * 4)
        val depth = FloatArray(W * H) { Float.MAX_VALUE }
        val stencil = ByteArray(W * H)
        val n = mb.vertexCount
        val d = mb.data
        val S = MeshBuilder.STRIDE
        val sx = FloatArray(n); val sy = FloatArray(n); val sz = FloatArray(n)
        val ex = FloatArray(n * 3); val en = FloatArray(n * 3)
        val mvp = Mat4.mul(cam.proj, cam.mv)
        val t4 = FloatArray(4)
        for (v in 0 until n) {
            val o = v * S
            Mat4.apply(mvp, d[o], d[o + 1], d[o + 2], t4)
            val iw = 1f / t4[3]
            sx[v] = (t4[0] * iw * 0.5f + 0.5f) * W; sy[v] = (1f - (t4[1] * iw * 0.5f + 0.5f)) * H; sz[v] = t4[2] * iw
            Mat4.apply(cam.mv, d[o], d[o + 1], d[o + 2], t4)
            ex[3 * v] = -t4[0]; ex[3 * v + 1] = -t4[1]; ex[3 * v + 2] = -t4[2]
            val m = cam.mv
            for (r in 0 until 3) en[3 * v + r] = m[r] * d[o + 3] + m[4 + r] * d[o + 4] + m[8 + r] * d[o + 5]
        }
        val idx = mb.indices
        val frag = FloatArray(S)
        val nv = FloatArray(3); val vv = FloatArray(3); val out = FloatArray(4)
        val cp = cam.clip
        for (b in mb.batches) {
            if (b.pass == Pass.Stencil && b.clearStencil) stencil.fill(0)
            val writeDepth = b.blend != Blend.AlphaNoDepthWrite
            var t = b.first
            while (t < b.first + b.count) {
                val i0 = idx[t]; val i1 = idx[t + 1]; val i2 = idx[t + 2]
                t += 3
                val area = (sx[i1] - sx[i0]) * (sy[i2] - sy[i0]) - (sx[i2] - sx[i0]) * (sy[i1] - sy[i0])
                if (abs(area) < 1e-9f) continue
                val x0 = max(0, min(sx[i0], min(sx[i1], sx[i2])).toInt()); val x1 = min(W - 1, max(sx[i0], max(sx[i1], sx[i2])).toInt() + 1)
                val y0 = max(0, min(sy[i0], min(sy[i1], sy[i2])).toInt()); val y1 = min(H - 1, max(sy[i0], max(sy[i1], sy[i2])).toInt() + 1)
                if (x0 > x1 || y0 > y1) continue
                for (py in y0..y1) for (px in x0..x1) {
                    val fx = px + 0.5f; val fy = py + 0.5f
                    val w0 = ((sx[i1] - fx) * (sy[i2] - fy) - (sx[i2] - fx) * (sy[i1] - fy)) / area
                    val w1 = ((sx[i2] - fx) * (sy[i0] - fy) - (sx[i0] - fx) * (sy[i2] - fy)) / area
                    val w2 = 1 - w0 - w1
                    if (w0 < 0 || w1 < 0 || w2 < 0) continue
                    val z = w0 * sz[i0] + w1 * sz[i1] + w2 * sz[i2]
                    if (z < -1f || z > 1f) continue
                    val pi = py * W + px
                    if (b.clip) {
                        val mx = w0 * d[i0 * S] + w1 * d[i1 * S] + w2 * d[i2 * S]
                        val my = w0 * d[i0 * S + 1] + w1 * d[i1 * S + 1] + w2 * d[i2 * S + 1]
                        val mz = w0 * d[i0 * S + 2] + w1 * d[i1 * S + 2] + w2 * d[i2 * S + 2]
                        if (cp[0] * mx + cp[1] * my + cp[2] * mz + cp[3] < 0f) continue
                    }
                    if (b.pass == Pass.Stencil) { stencil[pi] = (stencil[pi].toInt() xor 0xFF).toByte(); continue }
                    if (b.pass == Pass.Cap && (stencil[pi].toInt() and 1) == 0) continue
                    if (z > depth[pi] + 1e-6f) continue
                    if (b.dissolve < 0.999f) {
                        val q = 0.06711056f * (px / ss + 0.5f) + 0.00583715f * (py / ss + 0.5f)
                        if ((52.9829189f * (q - kotlin.math.floor(q))).let { it - kotlin.math.floor(it) } > b.dissolve) continue
                    }
                    for (k in 0 until S) frag[k] = w0 * d[i0 * S + k] + w1 * d[i1 * S + k] + w2 * d[i2 * S + k]
                    for (k in 0 until 3) { nv[k] = w0 * en[3 * i0 + k] + w1 * en[3 * i1 + k] + w2 * en[3 * i2 + k]; vv[k] = w0 * ex[3 * i0 + k] + w1 * ex[3 * i1 + k] + w2 * ex[3 * i2 + k] }
                    // kind is constant per triangle (flat in GL): the provoking vertex's
                    frag[10] = d[i0 * S + 10]
                    shade(frag, nv, vv, cam, glow, rim, lips, out)
                    val a = out[3]
                    if (a <= 0.001f && b.blend != Blend.Opaque) continue
                    val c = pi * 4
                    for (k in 0 until 3) color[c + k] = out[k] + color[c + k] * (1 - a)
                    color[c + 3] = a + color[c + 3] * (1 - a)
                    if (writeDepth) depth[pi] = z
                }
            }
        }
        val bgr = ((bg shr 16) and 255) / 255f; val bgg = ((bg shr 8) and 255) / 255f; val bgb = (bg and 255) / 255f
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) for (x in 0 until w) {
            var r = 0f; var g = 0f; var bb = 0f
            for (j in 0 until ss) for (i in 0 until ss) {
                val c = ((y * ss + j) * W + x * ss + i) * 4
                val a = color[c + 3]
                r += color[c] + bgr * (1 - a); g += color[c + 1] + bgg * (1 - a); bb += color[c + 2] + bgb * (1 - a)
            }
            val k = 1f / (ss * ss)
            img.setRGB(x, y, (to8(r * k) shl 16) or (to8(g * k) shl 8) or to8(bb * k))
        }
        return img
    }

    private fun to8(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()

    /** Mirror of the GL fragment shader in render/StyleGl. */
    fun shade(f: FloatArray, nIn: FloatArray, vIn: FloatArray, cam: StyleCamera, glowCol: FloatArray, rim: FloatArray, lips: FloatArray, out: FloatArray) {
        var kind = f[10]
        var vig = 1f
        if (kind > 7.5f) {
            kind -= 8f
            val dy = (f[1] - cam.vignette[0]) / cam.vignette[2]; val dz = (f[2] - cam.vignette[1]) / cam.vignette[3]
            vig = 1f - Geo.smoothstep(0.86f, 1f, sqrt(dy * dy + dz * dz))
        }
        var r = f[6]; var g = f[7]; var b = f[8]
        var a = f[9] * vig
        val glow = f[12]
        if (kind < 0.5f || (kind > 2.5f && kind < 3.5f)) {
            var nl = sqrt(nIn[0] * nIn[0] + nIn[1] * nIn[1] + nIn[2] * nIn[2]).coerceAtLeast(1e-6f)
            val n = floatArrayOf(nIn[0] / nl, nIn[1] / nl, nIn[2] / nl)
            nl = sqrt(vIn[0] * vIn[0] + vIn[1] * vIn[1] + vIn[2] * vIn[2]).coerceAtLeast(1e-6f)
            val v = floatArrayOf(vIn[0] / nl, vIn[1] / nl, vIn[2] / nl)
            if (n[0] * v[0] + n[1] * v[1] + n[2] * v[2] < 0f) { n[0] = -n[0]; n[1] = -n[1]; n[2] = -n[2] }
            val l = cam.light
            val skin = kind > 2.5f
            val amb = if (skin) 0.965f else 0.58f
            val dd = (n[0] * l[0] + n[1] * l[1] + n[2] * l[2]) * 0.5f + 0.5f
            val k = amb + (1 - amb) * Geo.smoothstep(0.12f, 0.95f, dd)
            r *= k; g *= k; b *= k
            val hx = l[0] + v[0]; val hy = l[1] + v[1]; val hz = l[2] + v[2]
            val hl = sqrt(hx * hx + hy * hy + hz * hz)
            val nh = max(0f, (n[0] * hx + n[1] * hy + n[2] * hz) / hl)
            val gl = f[11]
            val sp = gl * 0.65f * nh.pow(14f + 90f * gl)
            val nvv = max(0f, n[0] * v[0] + n[1] * v[1] + n[2] * v[2])
            val rimK = (1 - nvv).pow(3f) * (if (skin) 0.05f else 0.16f)
            val ge = glow * (0.22f + 0.7f * (1 - nvv).pow(2f))
            r += sp + rim[0] * rimK + glowCol[0] * ge; g += sp + rim[1] * rimK + glowCol[1] * ge; b += sp + rim[2] * rimK + glowCol[2] * ge
        } else if (kind < 1.5f) {
            val m = glow * 0.16f
            r += (glowCol[0] - r) * m; g += (glowCol[1] - g) * m; b += (glowCol[2] - b) * m
        } else if (kind > 3.5f) {
            val z = cam.lipZone
            val lip = Geo.smoothstep(z[0] - 0.6f, z[0] + 0.6f, f[2]) * Geo.smoothstep(z[1] - 0.8f, z[1] + 0.8f, f[1]) * (1f - Geo.smoothstep(z[2] - 0.8f, z[2] + 0.8f, f[1]))
            r += (lips[0] - r) * lip; g += (lips[1] - g) * lip; b += (lips[2] - b) * lip
        } else {
            val du = f[13] * 2 - 1; val dv = f[14] * 2 - 1
            a *= 1f - Geo.smoothstep(0.55f, 1f, sqrt(du * du + dv * dv))
        }
        out[0] = min(1f, r) * a; out[1] = min(1f, g) * a; out[2] = min(1f, b) * a; out[3] = a
    }
}
