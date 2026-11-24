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
 * The mouth and lower face as closed, articulated surfaces (units mm, x right, y up, z forward, the upper incisal edge at the origin):
 *
 * - the **head**: one closed surface with sampled cheeks, philtrum and chin. The skin flows into the lips,
 *   rolls into the mouth and lines the cavity (vestibule, gums, palate with the ridge, floor) back to the throat. The
 *   solid between skin and lining is the tissue the cut-away caps;
 * - the **teeth**: closed rounded crowns along two curved arches, necks sunk into the gums;
 * - the **tongue**: a closed body swept along the side profile ([SagittalShape]) with a centre groove, sitting in the
 *   floor of the mouth.
 *
 * All three are watertight, so the cut plane can be capped with the stencil buffer at any angle.
 */
object HeadModel {
    private val N = ScannedFace.rim.size

    /** Cavity stations behind the teeth: z, half width, roof (middle), floor (middle). */
    private val STATIONS = arrayOf(
        floatArrayOf(-15f, 20f, 20.5f, -11.5f),
        floatArrayOf(-19f, 21f, 22.5f, -12.7f),
        floatArrayOf(-23f, 22f, 24f, -14f),
        floatArrayOf(-33f, 22f, 26.2f, -16.5f),
        floatArrayOf(-45f, 20f, 26f, -19f),
        floatArrayOf(-55f, 16f, 23.5f, -20f),
        floatArrayOf(-61f, 11f, 18f, -17f),
    )
    private val ROWS = 3 + 3 + STATIONS.size + 1

    class Glow(val lips: Float, val teeth: Float, val tongue: Float)

    /** Index ranges of the three closed meshes (for the cut's stencil caps). */
    class Ranges { var head0 = 0; var head1 = 0; var teeth0 = 0; var teeth1 = 0; var tongue0 = 0; var tongue1 = 0 }

    // per-frame scratch, reused
    private val pos = FloatArray(N * ROWS * 3)
    private val col = FloatArray(N * ROWS * 3)
    private val tint = FloatArray(N * ROWS * 3)
    private val tmp = FloatArray(3)

    private val jointY = (Anatomy.UPPER_EDGE.y - Anatomy.pivot.y) * Anatomy.MM
    private val jointZ = (Anatomy.pivot.x - Anatomy.UPPER_EDGE.x) * Anatomy.MM + 0.5f

    internal fun jawAngle(jaw: Float) = jaw * 22f * PI.toFloat() / 180f

    internal fun jawY(y: Float, z: Float, c: Float, s: Float) = jointY + (y - jointY) * c - (z - jointZ) * s

    internal fun jawZ(y: Float, z: Float, c: Float, s: Float) = jointZ + (y - jointY) * s + (z - jointZ) * c

    private fun turnJaw(y: Float, z: Float, jaw: Float, out: FloatArray) {
        val a = jawAngle(jaw)
        val c = cos(a)
        val s = sin(a)
        out[1] = jawY(y, z, c, s)
        out[2] = jawZ(y, z, c, s)
    }

    /** The lower incisors' displacement (dy, dz) in mm for the side-view jaw angle. */
    fun jawOffset(jaw: Float): P2 {
        val a = SagittalShape.world(Anatomy.lowerEdge)
        val b = SagittalShape.world(Anatomy.turned(Anatomy.lowerEdge, jaw))
        return P2(b.first - a.first, b.second - a.second)
    }

    @Synchronized
    fun build(mb: MeshBuilder, pose: Pose, sag: SagittalShape, pal: StylePalette, glow: Glow, breath: Float, ranges: Ranges) {
        val off = jawOffset(sag.jaw)
        val lips = LipContours(pose, -off.x)
        mb.begin(Blend.Opaque)
        ranges.head0 = mb.mark()
        head(mb, lips, sag.jaw, pal, glow.lips, breath)
        ranges.head1 = mb.mark()
        ranges.teeth0 = ranges.head1
        ReferenceAnatomy.dentition(mb, pal, sag.jaw, glow.teeth)
        ranges.teeth1 = mb.mark()
        ranges.tongue0 = ranges.teeth1
        tongue(mb, sag, pose, pal, glow.tongue)
        ranges.tongue1 = mb.mark()
    }

    private fun set(o: Int, x: Float, y: Float, z: Float) { pos[o] = x; pos[o + 1] = y; pos[o + 2] = z }
    private fun setCol(o: Int, c: FloatArray, k: Float) { col[o] = c[0]; col[o + 1] = c[1]; col[o + 2] = c[2]; tint[o] = k; tint[o + 1] = k; tint[o + 2] = k }
    private fun setMix(o: Int, a: FloatArray, b: FloatArray, t: Float, k: Float) {
        for (q in 0 until 3) { col[o + q] = a[q] + (b[q] - a[q]) * t; tint[o + q] = k }
    }

    private fun head(mb: MeshBuilder, l: LipContours, jaw: Float, pal: StylePalette, glow: Float, breath: Float) {
        val nv = ROWS
        val mucosa = SlabMix.mix(pal.lips, pal.lining, 0.62f)
        val palate = SlabMix.mix(pal.gums, pal.lining, 0.25f)
        val face = ScannedFace.build(mb, l, jaw, pal, glow, breath)
        for (i in 0 until N) {
            val c = ScannedFace.cosine[i]
            val s = ScannedFace.sine[i]
            val up = s >= 0f
            val wj = Geo.smoothstep(0.15f, -0.3f, s)
            var j = 0
            fun row(): Int = (i * nv + j++) * 3
            val edge = (face + ScannedFace.rim[i]) * MeshBuilder.STRIDE
            val xEdge = mb.data[edge]; val yEdge = mb.data[edge + 1]; val wetZ = mb.data[edge + 2]
            // Duplicate the scanned vermilion edge at the material seam, then roll into hydrated inner mucosa.
            set(row(), xEdge, yEdge, wetZ)
            setMix((i * nv) * 3, pal.lips, pal.lining, 0.26f, 0.86f)
            var o = row()
            set(o, xEdge - c * .25f, yEdge + s * .36f, wetZ - 1.15f)
            setMix(o, pal.lips, mucosa, 0.58f, 0.80f)
            o = row()
            set(o, xEdge - c * .85f, yEdge + s * 1.15f, wetZ - 3.4f)
            setCol(o, mucosa, 0.78f)
            var zPrev: Float
            // vestibule (behind the lips, in front of the teeth)
            run {
                val o = row()
                val x = min(22.5f, l.halfWidth - 1.2f) * c
                var y = (if (up) 12f else -12f) * powf(abs(s), 0.68f)
                var z = min(2f - 6.5f * c * c, wetZ - 5.8f)
                zPrev = z
                if (wj > 0f) { turnJaw(y, z, jaw * wj, tmp); y = tmp[1]; z = tmp[2] }
                set(o, x, y, z); setCol(o, mucosa, 0.8f)
            }
            // the gum crest over the teeth's necks, along each arch
            run {
                val o = row()
                val x = 22f * c
                val sy = powf(abs(s), 0.15f)
                var y = (if (up) 8.3f else -8.1f) * sy
                var z = min(-4.8f - x * x / 20f, zPrev - 1.8f)
                zPrev = z
                if (wj > 0f) { turnJaw(y, z, jaw * wj, tmp); y = tmp[1]; z = tmp[2] }
                set(o, x, y, z); setCol(o, pal.gums, 0.95f)
            }
            // lingual gum: up to the ridge behind the incisors, down behind the lower ones
            run {
                val o = row()
                val x = 17f * c
                var y = (if (up) 11.8f else -10.5f) * powf(abs(s), 0.2f)
                var z = min(-6.5f - x * x / 26f, zPrev - 2f)
                zPrev = z
                if (wj > 0f) { turnJaw(y, z, jaw * wj, tmp); y = tmp[1]; z = tmp[2] }
                set(o, x, y, z); setCol(o, pal.gums, 0.88f)
            }
            // Hard palate and ridge in front, softer tissue toward the back.
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
                if (up) {
                    val cheekBlend = Geo.smoothstep(0.22f, 0.98f, abs(c))
                    val softPalate = Geo.smoothstep(-39f, -62f, z0)
                    val fold = exp(-((z0 + 19f) / 5f).pow2()) * (1f - cheekBlend)
                    setMix(o, palate, pal.lining, (0.28f * softPalate + 0.65f * cheekBlend), 0.96f + 0.04f * fold - 0.27f * depth)
                } else {
                    setMix(o, pal.lining, mucosa, (1f - depth) * 0.35f * abs(s), 0.96f - 0.3f * depth)
                }
            }
            // the throat: one point for every column, so the surface is closed
            run { val o = row(); set(o, 0f, 4f, -68f); setCol(o, pal.lining, 0.6f) }
        }
        mb.kind = Kind.LIT; mb.gloss = 0.15f; mb.glow = 0f
        mb.color(pal.lining)
        mb.grid(N, nv, true, pos, tint = tint, colors = col)
    }

    private const val TK = 19 // an odd count preserves the central sagittal slice
    private val tPos = FloatArray(140 * TK * 3)
    private val tTint = FloatArray(140 * TK * 3)

    /**
     * The tongue: the side view's closed outline (exactly the cut's midline) inflated into a body — slices across
     * the tongue shrink the outline toward its centre, so the sides round off and the tip is narrow, and it can never
     * cross itself. A centre groove runs along the top (deeper for S), the cup raises the sides (Ș), sides-down lowers
     * them (L). Its lower part sinks into the floor of the mouth, so it never floats; it is closed at both sides.
     */
    private fun tongue(mb: MeshBuilder, sag: SagittalShape, pose: Pose, pal: StylePalette, glow: Float) {
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
                val root = Geo.smoothstep(centre.second - 24f, centre.second, z)
                val tip = Geo.smoothstep(centre.second + 1f, centre.second + 28f, z)
                val width = (0.85f + 0.20f * root) * (1f - 0.42f * tip)
                tPos[o] = x * width; tPos[o + 1] = y; tPos[o + 2] = z
                val dorsum = Geo.smoothstep(8f, 19f, pIdx.toFloat()) * (1f - Geo.smoothstep(52f, 74f, pIdx.toFloat()))
                val light = 1f - 0.22f * Geo.smoothstep(-6f, -39f, z)
                val sh = (0.79f + 0.18f * dorsum + 0.04f * tip) * light
                tTint[o] = sh + 0.025f * tip; tTint[o + 1] = sh; tTint[o + 2] = sh - 0.04f * root
            }
        }
        mb.kind = Kind.LIT; mb.gloss = 0.23f; mb.glow = glow
        mb.color(pal.tongue)
        val start = mb.vertexCount
        mb.grid(np, TK, true, tPos, tint = tTint)
        for (p in 2 * steps..7 * steps) for (k in 0 until TK) {
            // Distinct material detail weight for the papillae on the dorsal surface.
            mb.data[(start + p * TK + k) * MeshBuilder.STRIDE + 13] =
                -(1f - abs(2f * k / (TK - 1) - 1f)) * 0.8f
        }
        mb.glow = 0f
    }

    /** R from the front: two pairs of little white arcs either side of the tip, beating with the flutter. */
    fun ticks(mb: MeshBuilder, frame: SceneFrame, sag: SagittalShape, alpha: Float) {
        if (alpha < 0.01f) return
        val l = LipContours(frame.pose, -jawOffset(sag.jaw).x)
        val z = l.frontZ + 1.2f
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
        val cz = l.frontZ + 0.8f
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
