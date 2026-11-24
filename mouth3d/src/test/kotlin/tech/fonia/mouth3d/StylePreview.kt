package tech.fonia.mouth3d

import tech.fonia.mouth3d.style.Blend
import tech.fonia.mouth3d.style.Geo
import tech.fonia.mouth3d.style.Mat4
import tech.fonia.mouth3d.style.MeshBuilder
import tech.fonia.mouth3d.style.Pass
import tech.fonia.mouth3d.style.Kind
import tech.fonia.mouth3d.style.ScannedFace
import tech.fonia.mouth3d.style.StyleCamera
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
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
    private class Texture(name: String) {
        private val image = ScannedFace.resource(name).use { ImageIO.read(it) }
        private val w = image.width
        private val h = image.height
        private val pixels = image.getRGB(0, 0, w, h, null, 0, w)

        fun sample(u: Float, v: Float, out: FloatArray) {
            val x = (u * w - .5f).coerceIn(0f, w - 1.001f)
            val y = (v * h - .5f).coerceIn(0f, h - 1.001f)
            val i = y.toInt() * w + x.toInt()
            val dx = x - x.toInt(); val dy = y - y.toInt()
            for (k in 0..2) {
                val shift = 16 - 8 * k
                fun channel(index: Int) = ((pixels[index] ushr shift) and 255).toFloat()
                out[k] = Geo.lerp(Geo.lerp(channel(i), channel(i + 1), dx),
                    Geo.lerp(channel(i + w), channel(i + w + 1), dx), dy) / 255f
            }
        }
    }

    private val pigment by lazy { Texture("pigment.jpg") }
    private val relief by lazy { Texture("relief.png") }

    fun render(mb: MeshBuilder, cam: StyleCamera, w: Int, h: Int, glow: FloatArray, rim: FloatArray, lips: FloatArray, bg: Int, ss: Int = 3): BufferedImage {
        val W = w * ss; val H = h * ss
        val color = FloatArray(W * H * 4)
        val depth = FloatArray(W * H) { Float.MAX_VALUE }
        val stencil = ByteArray(W * H)
        val n = mb.vertexCount
        val d = mb.data
        val S = MeshBuilder.STRIDE
        val sx = FloatArray(n); val sy = FloatArray(n); val sz = FloatArray(n)
        val invW = FloatArray(n)
        val ex = FloatArray(n * 3); val en = FloatArray(n * 3)
        val mvp = Mat4.mul(cam.proj, cam.mv)
        val t4 = FloatArray(4)
        for (v in 0 until n) {
            val o = v * S
            Mat4.apply(mvp, d[o], d[o + 1], d[o + 2], t4)
            val iw = 1f / t4[3]
            invW[v] = iw
            sx[v] = (t4[0] * iw * 0.5f + 0.5f) * W; sy[v] = (1f - (t4[1] * iw * 0.5f + 0.5f)) * H; sz[v] = t4[2] * iw
            Mat4.apply(cam.mv, d[o], d[o + 1], d[o + 2], t4)
            ex[3 * v] = -t4[0]; ex[3 * v + 1] = -t4[1]; ex[3 * v + 2] = -t4[2]
            val m = cam.mv
            for (r in 0 until 3) en[3 * v + r] = m[r] * d[o + 3] + m[4 + r] * d[o + 4] + m[8 + r] * d[o + 5]
        }
        val idx = mb.indices
        val frag = FloatArray(S)
        val tangent = FloatArray(6)
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
                if (d[i0 * S + 10] == Kind.SCAN) {
                    val u1 = d[i1*S+13] - d[i0*S+13]; val v1 = d[i1*S+14] - d[i0*S+14]
                    val u2 = d[i2*S+13] - d[i0*S+13]; val v2 = d[i2*S+14] - d[i0*S+14]
                    val sign = kotlin.math.sign(u1*v2-u2*v1)
                    for (k in 0..2) {
                        val e1 = ex[3*i0+k] - ex[3*i1+k]; val e2 = ex[3*i0+k] - ex[3*i2+k]
                        tangent[k] = (e1*v2-e2*v1)*sign
                        tangent[3+k] = (e2*u1-e1*u2)*sign
                    }
                    for (j in 0..3 step 3) {
                        val len = sqrt(tangent[j].pow(2) + tangent[j+1].pow(2) + tangent[j+2].pow(2)).coerceAtLeast(1e-10f)
                        for (k in j..j+2) tangent[k] /= len
                    }
                }
                for (py in y0..y1) for (px in x0..x1) {
                    val fx = px + 0.5f; val fy = py + 0.5f
                    var w0 = ((sx[i1] - fx) * (sy[i2] - fy) - (sx[i2] - fx) * (sy[i1] - fy)) / area
                    var w1 = ((sx[i2] - fx) * (sy[i0] - fy) - (sx[i0] - fx) * (sy[i2] - fy)) / area
                    var w2 = 1 - w0 - w1
                    if (w0 < 0 || w1 < 0 || w2 < 0) continue
                    val z = w0 * sz[i0] + w1 * sz[i1] + w2 * sz[i2]
                    if (z < -1f || z > 1f) continue
                    val denom = w0 * invW[i0] + w1 * invW[i1] + w2 * invW[i2]
                    w0 *= invW[i0] / denom; w1 *= invW[i1] / denom; w2 *= invW[i2] / denom
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
                    shade(frag, nv, vv, cam, glow, rim, lips, out, tangent)
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
    private fun shade(f: FloatArray, nIn: FloatArray, vIn: FloatArray, cam: StyleCamera, glowCol: FloatArray, rim: FloatArray, lips: FloatArray, out: FloatArray, tangent: FloatArray) {
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
        if (kind == Kind.SCAN) {
            val nl = sqrt(nIn[0].pow(2) + nIn[1].pow(2) + nIn[2].pow(2)).coerceAtLeast(1e-8f)
            val vl = sqrt(vIn[0].pow(2) + vIn[1].pow(2) + vIn[2].pow(2)).coerceAtLeast(1e-8f)
            val side = if (nIn[0]*vIn[0] + nIn[1]*vIn[1] + nIn[2]*vIn[2] < 0f) -1f else 1f
            relief.sample(f[13], f[14], out)
            val tx = (2 * out[0] - 1) * .72f; val ty = (2 * out[1] - 1) * .72f
            var nx = nIn[0] * side/nl + tx*tangent[0] + ty*tangent[3]
            var ny = nIn[1] * side/nl + tx*tangent[1] + ty*tangent[4]
            var nz = nIn[2] * side/nl + tx*tangent[2] + ty*tangent[5]
            val len = sqrt(nx*nx + ny*ny + nz*nz).coerceAtLeast(1e-8f)
            nx /= len; ny /= len; nz /= len
            val l = cam.light
            val dl = nx*l[0] + ny*l[1] + nz*l[2]
            val key = max(0f, dl); val wrap = max(0f, .28f + .72f*dl)
            val hx = l[0] + vIn[0]/vl; val hy = l[1] + vIn[1]/vl; val hz = l[2] + vIn[2]/vl
            val nh = max(0f, (nx*hx + ny*hy + nz*hz) / sqrt(hx*hx + hy*hy + hz*hz))
            val nv = max(0f, (nx*vIn[0] + ny*vIn[1] + nz*vIn[2])/vl)
            val sp = (.036f + f[11]*.070f)*nh.pow(13) + (.020f + f[11]*.17f)*nh.pow(74)
            val ge = glow * (.15f + .25f*(1f-nv).pow(2))
            pigment.sample(f[13], f[14], out)
            r = r*out[0]*sqrt(.35f + .61f*key + .19f*wrap) + sp + glowCol[0]*ge
            g = g*out[1]*sqrt(.365f + .65f*key + .115f*wrap) + .94f*sp + glowCol[1]*ge
            b = b*out[2]*sqrt(.39f + .65f*key + .075f*wrap) + .89f*sp + glowCol[2]*ge
        } else if (kind < 0.5f || kind == Kind.SKIN || kind == Kind.ENAMEL) {
            var nl = sqrt(nIn[0] * nIn[0] + nIn[1] * nIn[1] + nIn[2] * nIn[2]).coerceAtLeast(1e-6f)
            val n = floatArrayOf(nIn[0] / nl, nIn[1] / nl, nIn[2] / nl)
            nl = sqrt(vIn[0] * vIn[0] + vIn[1] * vIn[1] + vIn[2] * vIn[2]).coerceAtLeast(1e-6f)
            val v = floatArrayOf(vIn[0] / nl, vIn[1] / nl, vIn[2] / nl)
            if (n[0] * v[0] + n[1] * v[1] + n[2] * v[2] < 0f) { n[0] = -n[0]; n[1] = -n[1]; n[2] = -n[2] }
            val l = cam.light
            val skin = max(max(f[13], 0f), if (kind > 2.5f) 1f else 0f)
            val lip = f[14]
            val amb = Geo.lerp(0.48f, 0.72f, skin)
            val dd = (n[0] * l[0] + n[1] * l[1] + n[2] * l[2]) * 0.5f + 0.5f
            val k = amb + (1 - amb) * Geo.smoothstep(0.12f, 0.95f, dd)
            r *= k; g *= k; b *= k
            val hx = l[0] + v[0]; val hy = l[1] + v[1]; val hz = l[2] + v[2]
            val hl = sqrt(hx * hx + hy * hy + hz * hz)
            val nh = max(0f, (n[0] * hx + n[1] * hy + n[2] * hz) / hl)
            val gl = f[11]
            val sp = gl * (0.19f * nh.pow(11f) + 0.33f * nh.pow(37f))
            val nvv = max(0f, n[0] * v[0] + n[1] * v[1] + n[2] * v[2])
            val rimK = (1 - nvv).pow(3f) * Geo.lerp(0.065f, 0.035f, skin)
            if (skin + lip > 0.005f) {
                val grain = kotlin.math.sin(f[0] * 9.7f + f[1] * 4.1f) * kotlin.math.sin(f[1] * 8.3f - f[2] * 5.3f)
                val wrinkle = kotlin.math.sin(f[0] * 1.71f + f[1] * 0.86f) + 0.65f * kotlin.math.sin(f[0] * 3.08f - f[1] * 0.4f)
                val fold = kotlin.math.sin(f[0] * 6.4f + 1.53f * wrinkle + 1.48f * kotlin.math.sin(f[1] * 0.38f + f[2] * 0.47f))
                val surface = 1f + skin * 0.009f * grain - lip * 0.027f * Geo.smoothstep(0.48f, 0.98f, fold)
                r *= surface; g *= surface; b *= surface
            } else if (f[13] < 0f) {
                val papillae = kotlin.math.sin(f[0] * 7.4f + f[2] * 3f) * kotlin.math.sin(f[2] * 8.1f - f[1] * 2.9f)
                val surface = 1f - f[13] * 0.025f * papillae
                r *= surface; g *= surface; b *= surface
            }
            val ge = glow * (0.22f + 0.7f * (1 - nvv).pow(2f))
            val depth = Geo.smoothstep(0f, 38f, -f[2]); val corner = Geo.smoothstep(10f, 26f, abs(f[0]))
            val shade = if (kind == Kind.ENAMEL) .98f-.37f*depth-.22f*corner else .80f-.55f*depth-.14f*corner
            val light = Geo.lerp(shade, .97f, Geo.smoothstep(.25f, .95f, cam.cutAmount))
            r = (r + sp + rim[0]*rimK)*light + glowCol[0]*ge
            g = (g + sp + rim[1]*rimK)*light + glowCol[1]*ge
            b = (b + sp + rim[2]*rimK)*light + glowCol[2]*ge
        } else if (kind < 1.5f) {
            val m = glow * 0.16f
            r += (glowCol[0] - r) * m; g += (glowCol[1] - g) * m; b += (glowCol[2] - b) * m
        } else if (kind > 4.5f) {
            val fibre = kotlin.math.sin(f[1] * 0.53f + 1.1f * kotlin.math.sin(f[2] * 0.19f)) * 0.018f
            val k = 0.94f + 0.11f * Geo.smoothstep(-22f, 20f, f[1]) + fibre
            r *= k; g *= k; b *= k
        } else if (kind > 3.5f) {
            fun section(q: FloatArray): Float {
                val y = (f[1] - q[0]) / q[2]
                val z = (f[2] - q[1]) / q[3]
                return 1f - Geo.smoothstep(0.67f, 1.15f, sqrt(y * y + z * z))
            }
            val lip = 0.8f * max(section(cam.upperLipCut), section(cam.lowerLipCut))
            r += (lips[0] - r) * lip; g += (lips[1] - g) * lip; b += (lips[2] - b) * lip
        } else {
            val du = f[13] * 2 - 1; val dv = f[14] * 2 - 1
            a *= 1f - Geo.smoothstep(0.55f, 1f, sqrt(du * du + dv * dv))
        }
        out[0] = min(1f, r) * a; out[1] = min(1f, g) * a; out[2] = min(1f, b) * a; out[3] = a
    }
}
