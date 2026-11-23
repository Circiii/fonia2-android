package tech.fonia.mouth3d.style

import tech.fonia.mouth3d.AirStream
import tech.fonia.mouth3d.Pose
import tech.fonia.mouth3d.PoseChannel
import tech.fonia.mouth3d.PoseSpec
import tech.fonia.mouth3d.SceneFrame
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The lip opening of the front view as two closed contours around the mouth (inner = the wet line, outer = the
 * vermilion border), in mm, x right, y up. Shapes are exaggerated on purpose so a child tells the sounds apart:
 * spread = a wide smile with raised corners and a flat upper line, round = a small rounded funnel pushed forward.
 */
class LipContours(pose: Pose, lowerDrop: Float) {
    val spread = pose[PoseChannel.LipSpread]
    val round = pose[PoseChannel.LipRound]
    val part = pose[PoseChannel.LipPart]

    /** Half width of the opening (corner to middle). */
    val halfWidth = 24f + 9f * spread - 12f * round
    val smile = 5f * spread
    val upperLift = part * (3.4f + 2.6f * spread) + round * 4.2f + lowerDrop * 0.22f
    val lowerDrop = max(0f, lowerDrop * 1.15f - 1.2f) + part * (3.4f + 2.6f * spread) + round * 4.6f
    val upperThick = 7.2f - 2.2f * spread + 2.4f * round
    val lowerThick = 9.2f - 2.0f * spread + 3.0f * round
    val cornerThick = 1.0f + 10f * round
    val protrusion = 10f * round

    private val qU = 2f + 1.4f * spread
    private val kU = 1f - 0.5f * round
    private val qL = 2f
    private val kL = 1.05f - 0.55f * round
    private val qO = 2f + 0.5f * spread
    private val kO = 0.78f - 0.33f * round

    private fun prof(c: Float, q: Float, k: Float) = powf((1f - powf(abs(c), q)).coerceAtLeast(0f), k)

    /** Cupid's bow: a soft dip in the middle between two rounded peaks. */
    private fun bow(c: Float) = 1f - 0.16f * exp(-(c / 0.085f).pow2()) + 0.08f * exp(-((abs(c) - 0.24f) / 0.1f).pow2())

    fun mid(c: Float) = smile * c * c

    /** Inner contour at angle [t] (0 = right corner, π/2 = top middle). */
    fun inner(t: Float): P2 {
        val c = cos(t); val s = sin(t)
        val y = if (s >= 0) upperLift * prof(c, qU, kU) else -lowerDrop * prof(c, qL, kL)
        return P2(halfWidth * c, mid(c) + y)
    }

    fun outer(t: Float): P2 {
        val c = cos(t); val s = sin(t)
        val y = if (s >= 0) (upperLift + upperThick * bow(c)) * prof(c, qO, kO) else -(lowerDrop + lowerThick) * prof(c, qO, kO)
        return P2((halfWidth + cornerThick) * c, mid(c) + y)
    }

    /** The middle of the opening and the z of the wet line there (where the air leaves). */
    val openingY get() = mid(0f) + (upperLift - lowerDrop) / 2
}

/**
 * The mouth as a closed "bust slice" (units mm, x right, y up, z forward, the upper incisal edge at the origin):
 *
 * - the **head**: one closed surface — a rounded shell (cheeks, nose tip, chin, jaw) whose skin runs over the lips,
 *   rolls into the mouth and lines the cavity (vestibule, gums, palate with the ridge, floor) back to the throat. The
 *   solid between skin and lining is the tissue the cut-away caps;
 * - the **teeth**: closed rounded crowns along two curved arches, necks sunk into the gums;
 * - the **tongue**: a closed body swept along the side profile ([SagittalShape]) with a centre groove, sitting in the
 *   floor of the mouth.
 *
 * All three are watertight, so the cut plane can be capped with the stencil buffer at any angle.
 */
object HeadModel {
    const val N = 96
    private val LIP_ROWS = floatArrayOf(1f, 0.96f, 0.88f, 0.75f, 0.6f, 0.45f, 0.3f, 0.16f, 0.06f, 0f, -0.5f, -1f)
    private val FACE = floatArrayOf(1f, 0.9f, 0.78f, 0.64f, 0.5f, 0.36f, 0.24f, 0.13f, 0.05f)
    private val BACK = floatArrayOf(1.3f, 1.0f, 0.7f, 0.42f, 0.16f)

    /** The head: an ellipsoid around the mouth (half widths, centre z, front and back half depths). */
    private const val RX = 62f
    private const val RY_UP = 54f
    private const val RY_DOWN = 60f
    private const val ZC = -28f
    private const val AF = 34f
    private const val AB = 52f

    /** Cavity stations behind the teeth: z, half width, roof (middle), floor (middle). */
    private val STATIONS = arrayOf(
        floatArrayOf(-15f, 20f, 20.5f, -11.5f),
        floatArrayOf(-23f, 22f, 24f, -14f),
        floatArrayOf(-33f, 22f, 26.2f, -16.5f),
        floatArrayOf(-45f, 20f, 26f, -19f),
        floatArrayOf(-55f, 16f, 23.5f, -20f),
        floatArrayOf(-61f, 11f, 18f, -17f),
    )
    private val ROWS = 1 + BACK.size + FACE.size + LIP_ROWS.size + 3 + STATIONS.size + 1

    /** The face's surface over the mouth (the front of the head ellipsoid). */
    fun skinZ(x: Float, y: Float) = ZC + AF * sqrt(max(0f, 1f - (x / RX).pow2() - (y / Geo.lerp(RY_DOWN, RY_UP, Geo.smoothstep(-20f, 20f, y))).pow2()))
    val SKIN_FRONT get() = ZC + AF

    class Glow(val lips: Float, val teeth: Float, val tongue: Float)

    /** Index ranges of the three closed meshes (for the cut's stencil caps). */
    class Ranges { var head0 = 0; var head1 = 0; var teeth0 = 0; var teeth1 = 0; var tongue0 = 0; var tongue1 = 0 }

    // per-frame scratch, reused
    private val pos = FloatArray(N * ROWS * 3)
    private val col = FloatArray(N * ROWS * 3)
    private val tint = FloatArray(N * ROWS * 3)
    private val tmp = FloatArray(3)

    /** Jaw: rotates (y, z) about the joint by [jaw] (0…1 of 22°), like [Anatomy.turned]; returns into [out]. */
    fun turnJaw(y: Float, z: Float, jaw: Float, out: FloatArray) {
        if (jaw == 0f) { out[1] = y; out[2] = z; return }
        // in side-drawing units: X forward, Y down
        val px = (z - 0.5f) / Anatomy.MM + Anatomy.UPPER_EDGE.x
        val py = Anatomy.UPPER_EDGE.y - y / Anatomy.MM
        val a = jaw * 22f * PI.toFloat() / 180f
        val dx = px - Anatomy.pivot.x; val dy = py - Anatomy.pivot.y
        val qx = Anatomy.pivot.x + dx * cos(a) - dy * sin(a)
        val qy = Anatomy.pivot.y + dx * sin(a) + dy * cos(a)
        out[1] = -(qy - Anatomy.UPPER_EDGE.y) * Anatomy.MM
        out[2] = (qx - Anatomy.UPPER_EDGE.x) * Anatomy.MM + 0.5f
    }

    /** The lower incisors' displacement (dy, dz) in mm for the side-view jaw angle. */
    fun jawOffset(jaw: Float): P2 {
        val a = SagittalShape.world(Anatomy.lowerEdge)
        val b = SagittalShape.world(Anatomy.turned(Anatomy.lowerEdge, jaw))
        return P2(b.first - a.first, b.second - a.second)
    }

    fun build(mb: MeshBuilder, pose: Pose, sag: SagittalShape, pal: StylePalette, glow: Glow, breath: Float, ranges: Ranges) {
        val off = jawOffset(sag.jaw)
        val lips = LipContours(pose, -off.x)
        mb.begin(Blend.Opaque)
        ranges.head0 = mb.mark()
        head(mb, lips, sag.jaw, pal, glow.lips, breath)
        ranges.head1 = mb.mark()
        ranges.teeth0 = ranges.head1
        teeth(mb, pal, sag.jaw, glow.teeth)
        ranges.teeth1 = mb.mark()
        ranges.tongue0 = ranges.teeth1
        tongue(mb, sag, pose, pal, glow.tongue)
        ranges.tongue1 = mb.mark()
    }

    private fun theta(i: Int) = (2.0 * PI * i / N).toFloat()

    private fun lipPoint(l: LipContours, t: Float, v: Float, out: FloatArray, o: Int, breath: Float) {
        val inn = l.inner(t); val ou = l.outer(t)
        val c = cos(t)
        val bulge = (3.8f + 2.8f * l.round) * powf((1f - c * c).coerceAtLeast(0f), 0.35f) + 0.8f
        val zInner = skinZ(inn.x, inn.y) + l.protrusion + 2.4f
        if (v < 0f) {
            var dx = ou.x - inn.x; var dy = ou.y - inn.y
            val len = sqrt(dx * dx + dy * dy)
            if (len < 1e-3f) { dx = 0f; dy = if (sin(t) >= 0) 1f else -1f } else { dx /= len; dy /= len }
            val k = -v
            out[o] = inn.x + dx * 1.9f * k; out[o + 1] = inn.y + dy * 1.9f * k; out[o + 2] = zInner - 4.2f * k
            return
        }
        val x = inn.x + (ou.x - inn.x) * v
        val y = inn.y + (ou.y - inn.y) * v
        val bump = powf(sin(PI.toFloat() * powf(v, 0.72f)).coerceAtLeast(0f), 0.75f)
        out[o] = x * (1f + 0.004f * breath); out[o + 1] = y
        out[o + 2] = skinZ(x, y) + bulge * bump + (l.protrusion + 2.4f) * powf(1f - v, 1.6f - 0.7f * l.round)
    }

    private fun set(o: Int, x: Float, y: Float, z: Float) { pos[o] = x; pos[o + 1] = y; pos[o + 2] = z }
    private fun setCol(o: Int, c: FloatArray, k: Float) { col[o] = c[0]; col[o + 1] = c[1]; col[o + 2] = c[2]; tint[o] = k; tint[o + 1] = k; tint[o + 2] = k }
    private fun setMix(o: Int, a: FloatArray, b: FloatArray, t: Float, k: Float) {
        for (q in 0 until 3) { col[o + q] = a[q] + (b[q] - a[q]) * t; tint[o + q] = k }
    }

    private fun head(mb: MeshBuilder, l: LipContours, jaw: Float, pal: StylePalette, glow: Float, breath: Float) {
        val nv = ROWS
        val pal2 = pal
        val mucosa = SlabMix.mix(pal.lips, pal.lining, 0.62f)
        val palate = SlabMix.mix(pal.gums, pal.lining, 0.25f)
        for (i in 0 until N) {
            val t = theta(i); val c = cos(t); val s = sin(t)
            val up = s >= 0
            var zPrev = 1e9f // behind the lips every row lies further back than the one before: no folds
            // lower half follows the jaw (blended across the corners)
            val wj = Geo.smoothstep(0.15f, -0.3f, s)
            var j = 0
            fun row(): Int = (i * nv + j++) * 3
            val o0 = l.outer(t)
            val ry = Geo.lerp(RY_DOWN, RY_UP, Geo.smoothstep(-0.35f, 0.35f, s))
            val rhoO = sqrt((o0.x / RX).pow2() + (o0.y / ry).pow2()).coerceIn(0.05f, 0.95f)
            // back pole and the back of the head (an ellipsoid), then the face from its widest ring to the lips
            run { val o = row(); set(o, 0f, 0f, ZC - AB); setCol(o, pal2.patch, 0.9f) }
            for (psi in BACK) {
                val o = row()
                val rho = cos(psi)
                var y = ry * s * rho; var z = ZC - AB * sin(psi)
                val wb = wj * (1f - psi / 1.3f).coerceAtLeast(0f)
                if (wb > 0f) { turnJaw(y, z, jaw * wb, tmp); y = tmp[1]; z = tmp[2] }
                set(o, RX * c * rho, y, z); setCol(o, pal2.patch, 0.9f + 0.1f * (1f - psi / 1.6f))
            }
            for (kk in FACE) {
                val o = row()
                // from the lips' border out to the head's widest ring along the angle θ (smooth rows, no crease)
                val rhoT = rhoO + (1f - rhoO) * powf(kk, 1.3f)
                val g = powf(kk, 0.8f)
                var x = o0.x + (RX * c * rhoT - o0.x) * g; var y = o0.y + (ry * s * rhoT - o0.y) * g
                val rho = sqrt((x / RX).pow2() + (y / ry).pow2()).coerceAtMost(1f)
                var z = ZC + AF * sqrt(max(0f, 1f - rho * rho))
                // nose tip above, chin below
                val nose = 10f * exp(-((t - PI.toFloat() / 2) / 0.24f).pow2()) * exp(-((rho - 0.6f) / 0.13f).pow2())
                val chin = 4.5f * exp(-((t - 1.5f * PI.toFloat()) / 0.6f).pow2()) * exp(-((rho - 0.62f) / 0.16f).pow2())
                z += nose + chin
                val wf = wj * Geo.smoothstep(0.03f, 0.4f, kk)
                if (wf > 0f) { turnJaw(y, z, jaw * wf, tmp); y = tmp[1]; z = tmp[2] }
                x *= 1f + 0.003f * breath * kk
                set(o, x, y, z)
                // a soft contact shade along the lips and under the lower lip
                val ao = 1f - 0.1f * (1f - Geo.smoothstep(0f, 0.2f, kk)) - (if (!up) 0.05f * (1f - Geo.smoothstep(0.1f, 0.45f, kk)) else 0f)
                // a soft blush on the cheeks (a toy's face, not a medical one)
                val blush = 0.16f * exp(-(((abs(x) - 42f) / 11f).pow2() + ((y + 4f) / 11f).pow2()))
                setMix(o, pal2.patch, pal2.lips, blush, ao)
            }
            // the lips: border → wet line → inner roll
            for (v in LIP_ROWS) {
                val o = row()
                lipPoint(l, t, v, pos, o, breath)
                val k = (if (up) 0.97f else 1.07f)
                if (v < 0f) setMix(o, pal2.lips, mucosa, -v, 0.85f) else setCol(o, pal2.lips, k)
            }
            // vestibule (behind the lips, in front of the teeth)
            run {
                val o = row()
                val x = 25f * c; var y = (if (up) 12f else -12.5f) * powf(abs(s), 0.45f); var z = min(2f - 3f * c * c, skinZ(x, y) - 4.5f)
                zPrev = z
                if (wj > 0f) { turnJaw(y, z, jaw * wj, tmp); y = tmp[1]; z = tmp[2] }
                set(o, x, y, z); setCol(o, mucosa, 0.8f)
            }
            // the gum crest over the teeth's necks, along each arch
            run {
                val o = row()
                val x = 25f * c
                val sy = powf(abs(s), 0.15f)
                var y = (if (up) 9.7f else -9.4f) * sy
                var z = min(min((if (up) upperArchZ(x) else lowerArchZ(x, P2(0f, 0f))) - 3.2f, zPrev - 2f), skinZ(x, y) - 6f)
                zPrev = z
                if (wj > 0f) { turnJaw(y, z, jaw * wj, tmp); y = tmp[1]; z = tmp[2] }
                set(o, x, y, z); setCol(o, pal2.gums, 0.95f)
            }
            // lingual gum: up to the ridge behind the incisors, down behind the lower ones
            run {
                val o = row()
                val x = 17f * c
                var y = (if (up) 11.8f else -10.5f) * powf(abs(s), 0.2f)
                var z = min((if (up) upperArchZ(x * 1.25f) else lowerArchZ(x * 1.25f, P2(0f, 0f))) - 8f, zPrev - 2.5f)
                zPrev = z
                if (wj > 0f) { turnJaw(y, z, jaw * wj, tmp); y = tmp[1]; z = tmp[2] }
                set(o, x, y, z); setCol(o, pal2.gums, 0.88f)
            }
            // palate, cheeks and floor back to the throat
            for (st in STATIONS) {
                val o = row()
                val z0 = st[0]; val w = st[1]
                val x = w * c
                val xr = (x / w).pow2()
                val roofSide = if (z0 > -30f) 10f else st[2] * 0.62f
                val top = roofSide + (st[2] - roofSide) * (1f - xr)
                val bot = -9f + (st[3] + 9f) * (1f - xr)
                var y = (if (up) top else bot) * powf(abs(s), 0.22f)
                var z = min(z0, zPrev - 2.5f)
                zPrev = z
                if (wj > 0f) { turnJaw(y, z, jaw * wj, tmp); y = tmp[1]; z = tmp[2] }
                set(o, x, y, z)
                val depth = Geo.smoothstep(-12f, -60f, z0)
                if (up && abs(c) < 0.8f) setMix(o, palate, pal2.lining, depth * 0.5f + (1f - abs(s)) * 0.4f, 1f - 0.35f * depth)
                else setCol(o, pal2.lining, 1f - 0.3f * depth)
            }
            // the throat: one point for every column, so the surface is closed
            run { val o = row(); set(o, 0f, 4f, -68f); setCol(o, pal2.lining, 0.6f) }
        }
        mb.kind = Kind.LIT; mb.gloss = 0.12f; mb.glow = 0f
        mb.color(pal.patch)
        // lips glow when highlighted: glow is per vertex through the current attribute, so draw with the lips' glow;
        // the skin takes it too but faintly through the rim term only where the lips are (see shader)
        mb.glow = 0f
        val g0 = mb.vertexCount
        mb.grid(N, nv, true, pos, tint = tint, colors = col)
        // gloss and glow per row: lips glossy (and glowing when highlighted), wet inside, matte skin
        val lipStart = 1 + BACK.size + FACE.size
        for (i in 0 until N) for (jj in 0 until nv) {
            val vi = g0 + i * nv + jj
            val isLip = jj in lipStart until lipStart + LIP_ROWS.size
            val inside = jj >= lipStart + LIP_ROWS.size - 2
            mb.data[vi * MeshBuilder.STRIDE + 11] = when { isLip -> 0.45f; inside -> 0.3f; else -> 0.06f }
            mb.data[vi * MeshBuilder.STRIDE + 12] = if (isLip) glow else 0f
        }
    }

    private class ToothSpec(val width: Float, val height: Float, val edge: Float, val depth: Float)

    private val UPPER = listOf(ToothSpec(8.8f, 10.8f, 0f, 6.4f), ToothSpec(6.8f, 9.6f, 0.7f, 5.6f), ToothSpec(7.6f, 10.4f, 0.2f, 7f), ToothSpec(6.8f, 9.4f, 0.9f, 8f), ToothSpec(7f, 9.4f, 1.1f, 9f))
    private val LOWER = listOf(ToothSpec(5.6f, 9.4f, 0f, 5.2f), ToothSpec(6f, 9.4f, 0.2f, 5.6f), ToothSpec(6.8f, 10f, 0.3f, 6.6f), ToothSpec(7f, 9.4f, 0.8f, 7.6f), ToothSpec(7.2f, 9.4f, 1f, 8.5f))

    fun upperArchZ(x: Float) = 0.5f - x * x / 32f
    fun lowerArchZ(x: Float, off: P2) = 0.5f - 1.9f - x * x / 28f + off.y

    private fun teeth(mb: MeshBuilder, pal: StylePalette, jaw: Float, glow: Float) {
        mb.kind = Kind.LIT; mb.gloss = 0.55f; mb.glow = glow
        mb.color(pal.teeth)
        row(mb, UPPER, { upperArchZ(it) }, 0f, up = true, jaw = 0f)
        row(mb, LOWER, { lowerArchZ(it, P2(0f, 0f)) }, -0.9f, up = false, jaw = jaw)
        mb.glow = 0f
    }

    /** One arch of teeth along z = arch(x), incisal edges at [edgeY]; upper teeth grow up, lower ones down. */
    private fun row(mb: MeshBuilder, specs: List<ToothSpec>, arch: (Float) -> Float, edgeY: Float, up: Boolean, jaw: Float) {
        for (side in intArrayOf(-1, 1)) {
            var s = 0f
            for (t in specs) {
                val sc = s + t.width / 2
                var x = 0f; var acc = 0f
                while (acc < sc) { val nx = x + 0.1f; acc += sqrt(0.01f + (arch(nx) - arch(x)).pow2()); x = nx }
                val dz = (arch(x + 0.05f) - arch(x - 0.05f)) / 0.1f
                val tl = sqrt(1 + dz * dz)
                tooth(mb, x * side, arch(x), side / tl, dz / tl, side * dz / tl, -1f / tl, t, edgeY + (if (up) t.edge else -t.edge), up, jaw)
                s += t.width + 0.45f
            }
        }
    }

    private val TH = floatArrayOf(0f, 0.05f, 0.13f, 0.26f, 0.5f, 0.75f, 1f)
    private const val TPHI = 18
    private val toothPos = FloatArray(TPHI * TH.size * 3)
    private val toothTint = FloatArray(TPHI * TH.size * 3)

    /** A closed crown: superelliptic sections, a rounded chisel edge, sunk ~1.5 mm into the gum at the top. */
    private fun tooth(mb: MeshBuilder, lx: Float, lz: Float, tx: Float, tz: Float, inx: Float, inz: Float, t: ToothSpec, edgeY: Float, up: Boolean, jaw: Float) {
        val nv = TH.size
        val cx = lx + inx * t.depth / 2; val cz = lz + inz * t.depth / 2
        val dir = if (up) 1f else -1f
        for (i in 0 until TPHI) {
            val ph = (2.0 * PI * i / TPHI).toFloat()
            val cu = cos(ph); val su = sin(ph)
            val u = t.width / 2 * Math.signum(cu) * powf(abs(cu), 0.5f) * 0.94f
            val w0 = t.depth / 2 * Math.signum(su) * powf(abs(su), 0.5f)
            for (j in 0 until nv) {
                val h = TH[j]
                val round = if (h < 0.13f) 0.5f + 0.5f * sqrt(h / 0.13f) else 1f
                val chisel = 0.4f + 0.6f * Geo.smoothstep(0f, 0.45f, h)
                // the labial face bulges a little (curvature along the arch)
                val bulge = if (w0 < 0) 1f + 0.08f * (1f - (u / (t.width / 2)).pow2()) else 0.9f
                val uu = u * round
                val ww = w0 * chisel * bulge * round.coerceAtLeast(0.7f)
                val o = (i * nv + j) * 3
                var y = edgeY + dir * h * t.height
                var z = cz + tz * uu + inz * ww
                if (jaw != 0f) { turnJaw(y, z, jaw, tmp); y = tmp[1]; z = tmp[2] }
                toothPos[o] = cx + tx * uu + inx * ww
                toothPos[o + 1] = y
                toothPos[o + 2] = z
                // a touch of occlusion toward the gum
                val k = 1f - 0.18f * Geo.smoothstep(0.55f, 1f, h)
                toothTint[o] = k; toothTint[o + 1] = k; toothTint[o + 2] = k * 0.98f
            }
        }
        val first = mb.vertexCount
        mb.grid(TPHI, nv, true, toothPos, tint = toothTint)
        // close both ends (incisal edge and the buried top)
        for (end in intArrayOf(0, nv - 1)) {
            var sx = 0f; var sy = 0f; var sz = 0f
            for (i in 0 until TPHI) { val o = (i * nv + end) * 3; sx += toothPos[o]; sy += toothPos[o + 1]; sz += toothPos[o + 2] }
            val ny = if (end == 0) -dir else dir
            val cIdx = mb.vertex(sx / TPHI, sy / TPHI + (if (end == 0) -dir * 0.05f else 0f), sz / TPHI, 0f, ny, 0f)
            for (i in 0 until TPHI) {
                val a = first + i * nv + end; val b = first + ((i + 1) % TPHI) * nv + end
                if (end == 0) mb.tri(cIdx, b, a) else mb.tri(cIdx, a, b)
            }
        }
    }

    /** Floor of the mouth under the tongue (middle), before the jaw turns: z → y (kept for reference by tests). */
    private fun floorY(z: Float): Float = when {
        z > -5f -> -6f
        z > -11f -> Geo.lerp(-6f, -10.7f, (-5f - z) / 6f)
        z > -22f -> Geo.lerp(-10.7f, -14f, (-11f - z) / 11f)
        z > -37f -> Geo.lerp(-14f, -17.5f, (-22f - z) / 15f)
        else -> Geo.lerp(-17.5f, -20.5f, ((-37f - z) / 14f).coerceAtMost(1.3f))
    }

    private const val TK = 15 // slices across
    private val tPos = FloatArray(140 * TK * 3)
    private val tTint = FloatArray(140 * TK * 3)

    /**
     * The tongue: the side view's closed outline (exactly the cut's midline) inflated into a body — slices across
     * the tongue shrink the outline toward its centre, so the sides round off and the tip is narrow, and it can never
     * cross itself. A centre groove runs along the top (deeper for S), the cup raises the sides (Ș), sides-down lowers
     * them (L). Its lower part sinks into the floor of the mouth, so it never floats; it is closed at both sides.
     */
    fun tongue(mb: MeshBuilder, sag: SagittalShape, pose: Pose, pal: StylePalette, glow: Float) {
        val outline = sag.tongue
        val np = outline.size
        val steps = 8 // SagittalShape.of samples 8 points per control span; the top runs from the root (2) to the tip (7)
        val groove = pose[PoseChannel.TongueGroove]; val cup = pose[PoseChannel.TongueCup]; val sides = pose[PoseChannel.TongueSidesDown]
        // area centroid of the outline, in world (y, z)
        var a = 0f; var cy = 0f; var cz = 0f
        for (k in 0 until np) {
            val p = outline[k]; val q = outline[(k + 1) % np]
            val cr = p.x * q.y - q.x * p.y
            a += cr; cz += (p.x + q.x) * cr; cy += (p.y + q.y) * cr
        }
        val centre = SagittalShape.world(P2(cz / (3 * a), cy / (3 * a)))
        val w = 20f
        for (k in 0 until TK) {
            val phi = -PI.toFloat() / 2 + PI.toFloat() * k / (TK - 1)
            // L (sides down) is also narrower, so dark gaps show beside it from the front: the air goes round there
            val x = w * sin(phi) * (1f - 0.3f * sides)
            val sc = powf(cos(phi).coerceAtLeast(0f), 0.62f)
            val xr = abs(x) / w
            for (pIdx in 0 until np) {
                val (y0, z0) = SagittalShape.world(outline[pIdx])
                var y = centre.first + (y0 - centre.first) * sc
                val z = centre.second + (z0 - centre.second) * sc
                // the top surface: groove, cup, sides down — stronger toward the front
                val top = pIdx in 2 * steps..7 * steps
                if (top && xr > 0f) {
                    val front = Geo.smoothstep(3f * steps, 6.5f * steps, pIdx.toFloat())
                    val g = (0.9f + 1.5f * groove) * (1 - exp(-(xr / 0.22f).pow2()))
                    y += (g * (0.4f + 0.6f * front) + cup * 3.8f * xr * xr * front - sides * 6.5f * xr * xr * front) * sc
                }
                val o = (pIdx * TK + k) * 3
                tPos[o] = x; tPos[o + 1] = y; tPos[o + 2] = z
                // darker underneath and toward the floor, lighter on the top
                val sh = if (top) 1f else 0.8f
                tTint[o] = sh; tTint[o + 1] = sh; tTint[o + 2] = sh
            }
        }
        mb.kind = Kind.LIT; mb.gloss = 0.5f; mb.glow = glow
        mb.color(pal.tongue)
        mb.grid(np, TK, true, tPos, tint = tTint)
        mb.glow = 0f
    }

    /** R from the front: two pairs of little white arcs either side of the tip, beating with the flutter. */
    fun ticks(mb: MeshBuilder, frame: SceneFrame, sag: SagittalShape, alpha: Float) {
        if (alpha < 0.01f) return
        val l = LipContours(frame.pose, -jawOffset(sag.jaw).x)
        val z = skinZ(0f, 0f) + l.protrusion + 3.5f
        val y = l.openingY + 1f
        val beat = 0.5f + 0.5f * sin(frame.timeSec * 2f * PI.toFloat() * 5f)
        mb.begin(Blend.AlphaNoDepthWrite, clip = false)
        mb.kind = Kind.FLAT; mb.glow = 0f
        mb.color(SlabMix.WHITE, alpha * (0.55f + 0.45f * beat))
        for (side in intArrayOf(-1, 1)) for (k in 0..1) {
            val r = 5f + 2.6f * k + beat * 0.8f
            val base = mb.vertexCount
            for (q in 0..8) {
                val a = (-0.55f + 1.1f * q / 8f)
                val cx = side * r * cos(a); val cy = y + r * sin(a) * 1.2f
                val nx = side * cos(a); val ny = sin(a)
                mb.vertex(cx + nx * 0.35f, cy + ny * 0.35f, z, 0f, 0f, 1f)
                mb.vertex(cx - nx * 0.35f, cy - ny * 0.35f, z, 0f, 0f, 1f)
            }
            for (q in 0 until 8) { val p = base + 2 * q; mb.tri(p, p + 1, p + 2); mb.tri(p + 2, p + 1, p + 3) }
        }
        mb.flush()
    }

    /**
     * The air leaving the mouth toward the child (front view, when the step is about the air): a thin fast jet for
     * S/Z/Ț, a wide warm cloud for Ș/J/CE/GE, puffs for R, two side streams for L; nothing while a closure holds it.
     */
    fun air(mb: MeshBuilder, frame: SceneFrame, spec: PoseSpec, sag: SagittalShape, pal: StylePalette, right: FloatArray, up: FloatArray, alpha: Float) {
        val a = spec.air ?: return
        if (a.stop && frame.closure) return
        val l = LipContours(frame.pose, -jawOffset(sag.jaw).x)
        val cy = l.openingY
        val cz = skinZ(0f, cy) + l.protrusion + 3f
        val t = frame.timeSec
        val wide = a.stream == AirStream.Wide
        val lateral = a.stream == AirStream.Lateral
        val colr = if (a.warm) pal.airWarm else pal.airCold
        val burst = if (a.stop) 1f + 1.5f * max(0f, 1f - frame.sinceRelease / 0.5f) else 1f
        val n = if (wide) 36 else 28
        mb.begin(Blend.AlphaNoDepthWrite, clip = false)
        mb.kind = Kind.DISC; mb.glow = 0f
        for (pass in 0..1) for (i in 0 until n) {
            val seed = ((sin(i * 12.9898f) * 43758.547f) % 1f + 1f) % 1f
            val seed2 = ((sin(i * 78.233f) * 12543.1f) % 1f + 1f) % 1f
            val ph = (((t * burst) / 1.15f + i.toFloat() / n) % 1f + 1f) % 1f
            val ang = seed2 * 2f * PI.toFloat()
            val r = if (wide) 3f + ph * 20f * seed else 1.2f + ph * 9f * seed
            var x = cos(ang) * r; val y = cy + sin(ang) * r * 0.6f - ph * 5f
            if (lateral) x += (if (i % 2 == 0) 1f else -1f) * (l.halfWidth * 0.45f + ph * 6f)
            var al = 0.8f * alpha * Geo.smoothstep(0.05f, 0.3f, ph) * (1f - ph)
            if (a.stream == AirStream.Pulse) al *= 0.2f + 0.8f * max(0f, sin(2f * PI.toFloat() * 2.5f * t - ph * 6f))
            val size = (if (wide) 1.1f else 0.75f) * (1f + 0.9f * ph)
            if (pass == 0) mb.color(SlabMix.WHITE, al * 0.4f) else mb.color(colr, al)
            mb.billboard(x, y, cz + ph * 30f, size * (if (pass == 0) 1.6f else 1f), right, up)
        }
        mb.flush()
    }
}

/** Colour helpers shared by the models. */
object SlabMix {
    val WHITE = floatArrayOf(1f, 1f, 1f)
    fun mix(a: FloatArray, b: FloatArray, t: Float) = FloatArray(3) { a[it] + (b[it] - a[it]) * t }
    fun scale(a: FloatArray, k: Float) = FloatArray(3) { min(1f, a[it] * k) }
}
