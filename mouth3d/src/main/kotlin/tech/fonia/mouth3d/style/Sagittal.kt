package tech.fonia.mouth3d.style

import tech.fonia.mouth3d.Pose
import tech.fonia.mouth3d.PoseChannel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The mid-sagittal anatomy of the side view, in the units of the app's 2D side drawing (`SagittalView` in
 * :legacy:design, already reviewed for the 2D guide): x forward, y down, about 0.5 mm per unit. Copied, not shared, so
 * :core:mouth3d keeps no dependency on the design system.
 */
object Anatomy {
    /** World mm per drawing unit, and the drawing point that sits at the world's upper incisal edge. */
    const val MM = 0.5f
    val UPPER_EDGE = P2(212.6f, 125f)

    /** Where the jaw turns. */
    val pivot = P2(52f, 60f)

    fun turned(p: P2, jaw: Float): P2 {
        val a = jaw * 22f * PI.toFloat() / 180f
        val dx = p.x - pivot.x; val dy = p.y - pivot.y
        return P2(pivot.x + dx * cos(a) - dy * sin(a), pivot.y + dx * sin(a) + dy * cos(a))
    }

    private fun p(x: Number, y: Number) = P2(x.toFloat(), y.toFloat())

    /** Cubic Bézier path: start, then (c1, c2, end) triples; straight segments as (end, end, end). */
    fun bezier(start: P2, vararg seg: P2, steps: Int = 8): List<P2> {
        val out = arrayListOf(start)
        var a = start
        for (i in seg.indices step 3) {
            val c1 = seg[i]; val c2 = seg[i + 1]; val e = seg[i + 2]
            for (s in 1..steps) {
                val t = s.toFloat() / steps; val u = 1 - t
                out += P2(
                    u * u * u * a.x + 3 * u * u * t * c1.x + 3 * u * t * t * c2.x + t * t * t * e.x,
                    u * u * u * a.y + 3 * u * u * t * c1.y + 3 * u * t * t * c2.y + t * t * t * e.y,
                )
            }
            a = e
        }
        return out
    }

    /** Oral surface of the palate, front to back: gum behind the incisor, alveolar ridge, slope, vault, back edge. */
    val palate = listOf(p(201.6, 106.6), p(199, 103), p(195.5, 98), p(191, 93.5), p(183, 85), p(170, 77.5), p(152, 73), p(130, 72), p(110, 74.5), p(97, 77.5))
    val nasalFloor = listOf(p(97, 70.5), p(130, 66.5), p(170, 67), p(196, 71.5), p(214, 79))

    /** The ridge: the bump behind the upper teeth that R, L, Ț, CE touch. */
    val ridge = p(198.2, 101.8)

    val palateBone: List<P2> = Geo.catmull(nasalFloor + listOf(p(210, 90), p(208.5, 101), p(205, 105.5)) + palate.drop(1).map { it.moved(0f, -3f) }, true, 5)
    val palateLining: List<P2> = Geo.catmull(palate + palate.reversed().map { it.moved(0f, -3.6f) }, true, 4)

    val upperCrown: List<P2> = bezier(
        p(201.5, 105),
        p(210.6, 104), p(210.6, 104), p(210.6, 104),
        p(212.6, 111), p(214.2, 118.6), p(213.9, 124),
        p(213.1, 125.6), p(211, 125.6), p(209.8, 124.2),
        p(206.6, 119), p(202.2, 115.6), p(201, 110.6),
        p(200.7, 108.2), p(200.9, 106.2), p(201.5, 105),
    ).dropLast(1)
    val upperGum = bezier(p(209.6, 104.6), p(209.2, 100), p(209.6, 94), p(211, 90), p(213, 96), p(213, 96), p(213, 96), p(212, 100), p(211.6, 103), p(211.4, 106))

    val lowerEdge = p(206.8, 119.6)
    val lowerCrown = listOf(p(205, 120.8), p(207, 119.2), p(208.8, 120.6), p(209.2, 125.8), p(208.4, 131), p(207.6, 135.6), p(200.6, 134.8), p(201.6, 129.8), p(203.2, 124.8))
    val lowerGum = listOf(p(201.5, 136.2), p(203, 132), p(207.2, 132.5), p(208, 137.5), p(205, 140), p(200, 139))
    val mandible = listOf(p(207.6, 137), p(206, 147), p(210, 157), p(213.5, 166), p(210, 176), p(203, 181), p(192, 182), p(186, 172), p(187.5, 161), p(192, 150), p(198.5, 139), p(201.5, 136.5))

    val upperLip = listOf(p(216, 86), p(218.5, 95), p(221.6, 101.6), p(224.8, 104.4), p(230.4, 110.2), p(231.6, 116), p(228.6, 120.8), p(222.5, 122.8), p(217, 122.4), p(214, 120), p(213.2, 114), p(211.6, 106), p(210.2, 98), p(210.6, 90))
    val lowerLip = listOf(
        p(222.5, 123), p(229.6, 125.8), p(233, 131.2), p(231.4, 137.4), p(227.4, 141), p(221, 145.4), p(219, 148.5),
        p(222, 155.5), p(226, 164), p(224.6, 174), p(217, 182), p(204, 188), p(186, 191),
        p(184, 180), p(196, 158), p(204, 146), p(207.4, 140.6), p(209.5, 134), p(210.6, 128), p(214.6, 123.8),
    )
    val velumUp = listOf(p(97, 70.5), p(78, 66.5), p(58, 67.5), p(47.5, 73), p(46.5, 80), p(50, 88.5), p(54, 90), p(62, 85), p(80, 81.5), p(97, 78))
    val velumDown = listOf(p(97, 70.5), p(82, 74), p(68, 84), p(60, 96), p(56, 104), p(57, 114), p(61, 114.5), p(65, 106), p(80, 91), p(97, 78))

    /** Nose tip and the skin from the forehead down to the top of the upper lip (the profile line). */
    fun profile(lipTop: P2): List<P2> = bezier(
        p(197, 20),
        p(204, 30), p(222, 42), p(235, 56),
        p(240, 61), p(240, 68), p(234, 72),
        p(230, 75), p(223, 76), p(219, 79),
        p(216.5, 81), p(216, 84), lipTop,
    )

    /** The part of the side view the camera frames (drawing units). */
    const val FRAME_MIN_X = 114f
    const val FRAME_MAX_X = 258f
    const val FRAME_MIN_Y = 60f
    const val FRAME_MAX_Y = 180f
}

/** One tongue profile: tip, blade, front, dorsum, back, root and the underside just behind the tip. */
class TongueShape(val pts: List<P2>, val jaw: Float) {
    init { require(pts.size == 7) }
    val tip get() = pts[0]
    val blade get() = pts[1]

    companion object {
        private fun s(jaw: Float, vararg v: Number) = TongueShape(List(7) { P2(v[2 * it].toFloat(), v[2 * it + 1].toFloat()) }, jaw)

        val relaxed = s(0.04f, 198.5, 125.5, 187, 114, 166, 105, 134, 99, 103, 104, 80, 124, 190, 134)
        val groove = s(0.07f, 200.6, 129.5, 190.5, 99, 172, 90, 140, 88.5, 105, 96, 79, 123, 189, 137)
        val cup = s(0.12f, 194, 106, 181, 93, 163, 88.5, 134, 89, 103, 97, 78, 123, 186, 121)
        val press = s(0.12f, 196.8, 101.2, 184, 87.5, 164, 87, 134, 89, 103, 97, 78, 123, 189, 115.5)
        val ts = s(0.07f, 198.6, 103.0, 190, 99, 171, 92, 140, 89, 105, 96, 79, 123, 192, 115.5)
        val r = s(0.26f, 194.5, 102.5, 183.5, 112, 161, 113, 130, 104, 101, 100, 79, 125, 186, 121)
        val l = s(0.372f, 198, 102.4, 187, 101.5, 166, 106.5, 134, 102, 103, 103, 79, 125, 191, 117)
        val lRelease = s(0.372f, 199.5, 114, 188, 106.5, 166, 104.5, 134, 100, 103, 103, 79, 125, 190, 124)
    }
}

/**
 * The pose channels of the tongue mapped onto reviewed side-view shapes: each anchor is a channel vector from
 * spec/poses/mouth3d (a pose or an animation key) and the shape drawn for it. Between anchors the shapes blend by
 * inverse distance (exact at each anchor, never a shape that was not drawn); the tip channel then bends the tip
 * directly, so R's flutter moves only the tip.
 */
object TongueBlend {
    class Anchor(val tip: Float, val forward: Float, val raise: Float, val groove: Float, val cup: Float, val sides: Float, val shape: TongueShape)

    val anchors = listOf(
        Anchor(-0.1f, 0f, 0f, 0f, 0f, 0f, TongueShape.relaxed),
        Anchor(-0.5f, 0.15f, 0.45f, 1f, 0f, 0f, TongueShape.groove),
        Anchor(0.35f, -0.45f, 0.7f, 0f, 1f, 0f, TongueShape.cup),
        Anchor(0.3f, -0.3f, 0.7f, 0f, 0.8f, 0f, TongueShape.cup),
        Anchor(0.6f, -0.1f, 0.85f, 0f, 0.3f, 0f, TongueShape.press),
        Anchor(0.5f, 0.3f, 0.65f, 0f, 0f, 0f, TongueShape.ts),
        Anchor(0.6f, 0.15f, 0.55f, 0f, 0f, 0f, TongueShape.r),
        Anchor(0.65f, 0.25f, 0.55f, 0f, 0f, 1f, TongueShape.l),
        Anchor(0.15f, 0.25f, 0.55f, 0f, 0f, 0.3f, TongueShape.lRelease),
    )

    /** Weights of [anchors] for a pose (sum 1). */
    fun weights(pose: Pose): FloatArray {
        val tip = pose[PoseChannel.TongueTip]; val fw = pose[PoseChannel.TongueForward]; val ra = pose[PoseChannel.TongueRaise]
        val gr = pose[PoseChannel.TongueGroove]; val cu = pose[PoseChannel.TongueCup]; val sd = pose[PoseChannel.TongueSidesDown]
        val w = FloatArray(anchors.size)
        var sum = 0f
        for ((i, a) in anchors.withIndex()) {
            val d2 = sq(0.35f * (tip - a.tip)) + sq(fw - a.forward) + sq(ra - a.raise) + sq(1.4f * (gr - a.groove)) + sq(1.4f * (cu - a.cup)) + sq(1.4f * (sd - a.sides))
            if (d2 < 1e-8f) { w.fill(0f); w[i] = 1f; return w }
            w[i] = 1f / (d2 * sqrt(d2))
            sum += w[i]
        }
        for (i in w.indices) w[i] /= sum
        return w
    }

    /** The tongue's seven profile points for [pose] at side-view jaw angle [jaw]. */
    fun shape(pose: Pose, jaw: Float): List<P2> {
        val w = weights(pose)
        val xs = FloatArray(7); val ys = FloatArray(7)
        var aJaw = 0f; var aTip = 0f
        for ((i, a) in anchors.withIndex()) {
            if (w[i] == 0f) continue
            for (k in 0 until 7) { xs[k] += w[i] * a.shape.pts[k].x; ys[k] += w[i] * a.shape.pts[k].y }
            aJaw += w[i] * a.shape.jaw; aTip += w[i] * a.tip
        }
        // The tongue rides the jaw: the front more than the root.
        val dj = jaw - aJaw
        val pts = (0 until 7).map { k ->
            val p = P2(xs[k], ys[k])
            val ride = Geo.smoothstep(80f, 200f, p.x) * 0.85f
            val q = Anatomy.turned(p, dj * ride)
            q
        }.toMutableList()
        // The tip channel beyond the blend bends the tip about the blade (R's flutter, any in-between value).
        val dt = (pose[PoseChannel.TongueTip] - aTip)
        if (abs(dt) > 1e-4f) {
            pts[0] = pts[0].moved(-dt * 3f, -dt * 26f)
            pts[6] = pts[6].moved(0f, -dt * 10f)
            pts[1] = pts[1].moved(0f, -dt * 5f)
        }
        return pts
    }

    private fun sq(x: Float) = x * x
}

/** The side view of one pose: every outline of the diagram, in drawing units. */
class SagittalShape(
    val jaw: Float,
    val protrusion: Float,
    val upperLip: List<P2>,
    val lowerLip: List<P2>,
    /** The tongue's closed outline (smoothed) and its upper surface from the root to the tip. */
    val tongue: List<P2>,
    val tongueSurface: List<P2>,
    val tongueKey: List<P2>,
    val lowerCrown: List<P2>,
    val lowerGum: List<P2>,
    val mandible: List<P2>,
    val velum: List<P2>,
    val silhouette: List<P2>,
    val airSpace: List<P2>,
    val stomionUpper: P2,
    val stomionLower: P2,
    /** Where the tongue meets the roof, if it does (closest approach under ~1.3 mm). */
    val contact: P2?,
) {
    val tip: P2 get() = tongueKey[0]
    val blade: P2 get() = tongueKey[1]

    fun rot(p: P2) = Anatomy.turned(p, jaw)

    /**
     * The air's way through the mouth: up the throat, along the middle between the tongue and the roof, out between
     * the teeth and the lips. [stop]: it waits behind a closure. [lateral]: it leaves the middle before the tip (L).
     * Also returns the arc length where a closure holds it.
     */
    fun airPath(stop: Boolean, lateral: Boolean): Pair<List<P2>, Float> {
        // from the back of the mouth (the throat itself lies outside the 3D model)
        val air = mutableListOf(P2(86f, 94f))
        val roof = (velum.subList(5, velum.size) + Anatomy.palate).sortedBy { it.x }
        val front = if (lateral) blade.x - 12 else tip.x - 6
        var x = 96f
        while (x < front) {
            val top = Geo.yAt(roof, x); val bottom = Geo.yAt(tongueSurface, x)
            if (top != null && bottom != null) air += P2(x, (top + bottom) / 2)
            x += 12f
        }
        val holdAt = Geo.length(air) + 2f
        if (lateral) air += blade.moved(4f, 9f)
        val gate = Anatomy.UPPER_EDGE.toward(rot(Anatomy.lowerEdge), 0.5f).moved(-1f, -0.5f)
        if (!lateral && tip.x < gate.x - 1) {
            val top = Geo.yAt(roof, tip.x + 1)
            if (top != null) air += P2(tip.x + 1.5f, min((top + tip.y) / 2, gate.y - 1))
        }
        val lips = stomionUpper.toward(stomionLower, 0.5f)
        air += gate
        air += lips
        air += lips.moved(16 + protrusion * 0.6f, 1.5f)
        return Geo.catmull(air, false, 4) to (if (stop) holdAt else Float.MAX_VALUE)
    }

    companion object {
        /** Side-view jaw angle (0…1 of 22°) from the pose's jaw channel. */
        fun jawOf(pose: Pose) = pose[PoseChannel.JawOpen] * 0.62f

        fun of(pose: Pose): SagittalShape {
            val jaw = jawOf(pose)
            val round = pose[PoseChannel.LipRound] - 0.5f * pose[PoseChannel.LipSpread]
            val gap = max(0f, pose[PoseChannel.LipPart] * 5f + pose[PoseChannel.LipRound] * 2f + pose[PoseChannel.JawOpen] * 12f - 0.8f)
            val pro = round * 7.5f
            val upper = Anatomy.upperLip.mapIndexed { i, q ->
                val w = ((q.y - 92) / 30).coerceIn(0f, 1f)
                val k = if (pro < 0 && i > 7) 0f else 1f
                P2(q.x + pro * w * k * (if (q.y > 104) 1f else 0.6f), q.y - w * gap * 0.3f)
            }
            val stomion = upper[7]
            val turnedLower = Anatomy.lowerLip.map { Anatomy.turned(it, jaw) }
            val freeEdge = turnedLower[0]
            val gapFix = stomion.y + gap - freeEdge.y
            val lower = turnedLower.mapIndexed { i, q ->
                val w = ((147 - Anatomy.lowerLip[i].y) / 24).coerceIn(0f, 1f)
                P2(q.x + pro * w * (if (pro < 0 && i > 3) 0f else 1f), q.y + gapFix * w)
            }
            val key = TongueBlend.shape(pose, jaw)
            val tip = key[0]; val blade = key[1]
            val len = max(tip.dist(blade), 1f)
            val ux = (tip.x - blade.x) / len; val uy = (tip.y - blade.y) / len
            val ctrl = listOf(
                P2(84f, 162f), P2(key[5].x + 4, 146f), key[5], key[4], key[3], key[2], blade, tip,
                P2(tip.x - ux * 3 - uy * 6.5f, tip.y - uy * 3 + ux * 6.5f),
                key[6],
                Anatomy.turned(P2(176f, 149f), jaw),
                Anatomy.turned(P2(186f, 158f), jaw),
                P2(112f, 172f),
            )
            val steps = 8
            val outline = Geo.catmull(ctrl, true, steps)
            val surface = outline.subList(2 * steps, 7 * steps + 1)
            val roofPts = Geo.catmull(Anatomy.palate, false, 4)
            var best = Float.MAX_VALUE; var at: P2? = null
            for (p in outline.subList(5 * steps, 9 * steps)) for (q in roofPts) {
                val d = p.dist(q)
                if (d < best) { best = d; at = p.toward(q, 0.5f) }
            }
            val velumAmt = (abs(pose[PoseChannel.JawOpen] - 0.06f) * 6f + pose[PoseChannel.LipPart] + pose[PoseChannel.LipRound] + pose[PoseChannel.LipSpread] + abs(pose[PoseChannel.TongueRaise]) * 2f).coerceIn(0f, 1f)
            val velum = Anatomy.velumDown.zip(Anatomy.velumUp).map { (d, u) -> d.toward(u, velumAmt) }

            val sil = ArrayList<P2>()
            sil += P2(60f, 20f); sil += P2(197f, 20f)
            sil += Anatomy.profile(upper[0]).drop(1)
            sil += Geo.catmull(upper.subList(0, 8), false, 4).drop(1)
            sil += Geo.catmull(lower.subList(0, 13), false, 4)
            val top = lower[12]
            sil += Anatomy.bezier(top, top.moved(-22f, 2f), P2(134f, 190 + (top.y - 191) * 0.4f), P2(124f, 196f), P2(120f, 200f), P2(118f, 206f), P2(116f, 240f)).drop(1)
            sil += P2(60f, 240f)

            fun rot(p: P2) = Anatomy.turned(p, jaw)
            val su = upper[7]; val sl = lower[0]
            val air = Geo.catmull(
                listOf(
                    su, sl, rot(P2(208f, 136f)), rot(P2(199f, 140.5f)), rot(P2(189f, 146.5f)),
                    P2(168f, 153f), P2(138f, 160f), P2(110f, 166f), P2(90f, 168f), P2(86f, 214f), P2(52f, 214f), P2(47f, 170f),
                    P2(44.5f, 120f), P2(44.5f, 80f), P2(50f, 64f), P2(70f, 70f), P2(97f, 74f), P2(130f, 71f), P2(170f, 72f),
                    P2(196f, 82f), P2(208f, 100f), P2(212f, 112f),
                ),
                true, 3,
            )
            return SagittalShape(
                jaw = jaw, protrusion = pro, upperLip = upper, lowerLip = lower,
                tongue = outline, tongueSurface = surface, tongueKey = key,
                lowerCrown = Anatomy.lowerCrown.map(::rot), lowerGum = Anatomy.lowerGum.map(::rot), mandible = Anatomy.mandible.map(::rot),
                velum = velum, silhouette = sil, airSpace = air, stomionUpper = su, stomionLower = sl,
                contact = if (best < 2.6f) at else null,
            )
        }

        /** Side-view → world (mm): x across, y up, z forward; the upper incisal edge at (0, 0, 0.5). */
        fun world(p: P2): Pair<Float, Float> = (-(p.y - Anatomy.UPPER_EDGE.y) * Anatomy.MM) to ((p.x - Anatomy.UPPER_EDGE.x) * Anatomy.MM + 0.5f)
    }
}

internal fun Float.pow2() = this * this
internal fun powf(a: Float, b: Float) = a.toDouble().pow(b.toDouble()).toFloat()
