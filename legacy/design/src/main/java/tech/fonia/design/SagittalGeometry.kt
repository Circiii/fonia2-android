package tech.fonia.design

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The animatable form of a pose. Air, warmth, voice and trill are channels too, so they fade with the spring
 * instead of switching; the exit point is resolved here because it depends on the jaw. 32 floats, like the
 * `SIMD32<Float>` on iOS.
 */
internal class SagittalVector(val v: FloatArray) {
    private fun point(i: Int) = CGPoint(v[i].toDouble(), v[i + 1].toDouble())

    val jaw: Double get() = v[0].toDouble()
    val round: Double get() = v[1].toDouble()
    val lips: Double get() = v[2].toDouble()
    val velum: Double get() = v[3].toDouble()
    val bite: Double get() = max(0f, min(1f, v[4])).toDouble()
    val tip: CGPoint get() = point(5)
    val blade: CGPoint get() = point(7)
    val front: CGPoint get() = point(9)
    val dorsum: CGPoint get() = point(11)
    val back: CGPoint get() = point(13)
    val root: CGPoint get() = point(15)
    val under: CGPoint get() = point(17)
    val air: Double get() = max(0f, min(1f, v[19])).toDouble()
    val warmth: Double get() = v[20].toDouble()
    val voice: Double get() = max(0f, min(1f, v[21])).toDouble()
    val trill: Double get() = max(0f, min(1f, v[22])).toDouble()
    val gate: CGPoint get() = point(23)

    val movesOnItsOwn: Boolean get() = air > 0.01 || voice > 0.01 || trill > 0.01

    companion object {
        const val SIZE = 32

        fun of(pose: SagittalPose): FloatArray {
            val v = FloatArray(SIZE)
            v[0] = pose.jaw.toFloat()
            v[1] = pose.round.toFloat()
            v[2] = pose.lips.toFloat()
            v[3] = pose.velum.toFloat()
            v[4] = if (pose.lipBite) 1f else 0f
            val t = pose.tongue
            listOf(t.tip, t.blade, t.front, t.dorsum, t.back, t.root, t.under).forEachIndexed { i, point ->
                v[5 + i * 2] = point.x.toFloat()
                v[6 + i * 2] = point.y.toFloat()
            }
            // L's air passes beside the tongue, behind the cut, so it is drawn lighter.
            v[19] = if (pose.lateral) 0.55f else (if (pose.air == null) 0f else 1f)
            v[20] = if (pose.air == SagittalAir.warm) 1f else 0f
            v[21] = if (pose.voiced) 1f else 0f
            v[22] = if (pose.vibrate) 1f else 0f
            val gate = pose.exit ?: SagittalAnatomy.upperEdge.toward(SagittalAnatomy.turned(SagittalAnatomy.lowerEdge, pose.jaw), 0.5)
            v[23] = gate.x.toFloat()
            v[24] = gate.y.toFloat()
            return v
        }
    }
}

internal object Trace {
    /** Catmull-Rom through the points, as cubic curves from the path's current point. */
    fun addCurves(path: CGPath, pts: List<CGPoint>, closed: Boolean) {
        val n = pts.size
        fun at(i: Int) = if (closed) pts[(i + n) % n] else pts[max(0, min(n - 1, i))]
        for (i in 0 until (if (closed) n else n - 1)) {
            val p0 = at(i - 1)
            val p1 = at(i)
            val p2 = at(i + 1)
            val p3 = at(i + 2)
            path.addCurve(
                p2,
                CGPoint(p1.x + (p2.x - p0.x) / 6, p1.y + (p2.y - p0.y) / 6),
                CGPoint(p2.x - (p3.x - p1.x) / 6, p2.y - (p3.y - p1.y) / 6),
            )
        }
    }

    fun smooth(pts: List<CGPoint>, closed: Boolean = true): CGPath {
        val path = CGPath()
        path.move(pts[0])
        addCurves(path, pts, closed)
        if (closed) path.closeSubpath()
        return path
    }

    /** Points along the same closed curve, for measuring the tongue surface. */
    fun sample(pts: List<CGPoint>, from: Int, to: Int, steps: Int = 10): List<CGPoint> {
        val n = pts.size
        fun at(i: Int) = pts[(i + n) % n]
        val out = ArrayList<CGPoint>((to - from) * steps + 1)
        for (i in from until to) {
            val p0 = at(i - 1)
            val p1 = at(i)
            val p2 = at(i + 1)
            val p3 = at(i + 2)
            for (s in 0 until steps) {
                val t = s.toDouble() / steps
                val t2 = t * t
                val t3 = t2 * t
                fun blend(a: Double, b: Double, c: Double, d: Double) =
                    0.5 * (2 * b + (c - a) * t + (2 * a - 5 * b + 4 * c - d) * t2 + (3 * b - a - 3 * c + d) * t3)
                out += CGPoint(blend(p0.x, p1.x, p2.x, p3.x), blend(p0.y, p1.y, p2.y, p3.y))
            }
        }
        out += at(to)
        return out
    }

    fun polygon(pts: List<CGPoint>): CGPath {
        val path = CGPath()
        path.addLines(pts)
        path.closeSubpath()
        return path
    }

    fun polyline(pts: List<CGPoint>): CGPath {
        val path = CGPath()
        path.addLines(pts)
        return path
    }

    /** y of a polyline at x, at its first crossing. */
    fun y(line: List<CGPoint>, x: Double): Double? {
        for (i in 0 until line.size - 1) {
            val a = line[i]
            val b = line[i + 1]
            if ((a.x - x) * (b.x - x) <= 0 && a.x != b.x) {
                return a.y + (b.y - a.y) * (x - a.x) / (b.x - a.x)
            }
        }
        return null
    }

    fun ellipse(c: CGPoint, rx: Double, ry: Double, degrees: Double): CGPath =
        CGPath.ellipse(CGRect(-rx, -ry, rx * 2, ry * 2))
            .applying(CGAffineTransform.rotation(degrees * PI / 180).concatenating(CGAffineTransform.translation(c.x, c.y)))
}

internal object SagittalAnatomy {
    /** Where the jaw turns; the joint sits in front of the ear, behind the frame of the drawing. */
    val pivot = CGPoint(52, 60)

    fun turned(p: CGPoint, jaw: Double): CGPoint {
        val angle = jaw * 22 * PI / 180
        val dx = p.x - pivot.x
        val dy = p.y - pivot.y
        return CGPoint(pivot.x + dx * cos(angle) - dy * sin(angle), pivot.y + dx * sin(angle) + dy * cos(angle))
    }

    /**
     * Oral surface of the palate, front to back: gum behind the upper incisor, alveolar ridge, post-alveolar
     * slope, the vault, the back edge of the hard palate.
     */
    val palate = listOf(
        CGPoint(201.6, 106.6), CGPoint(199, 103), CGPoint(195.5, 98), CGPoint(191, 93.5), CGPoint(183, 85),
        CGPoint(170, 77.5), CGPoint(152, 73), CGPoint(130, 72), CGPoint(110, 74.5), CGPoint(97, 77.5),
    )
    val nasalFloor = listOf(CGPoint(97, 70.5), CGPoint(130, 66.5), CGPoint(170, 67), CGPoint(196, 71.5), CGPoint(214, 79))
    val palateBone = Trace.smooth(nasalFloor + listOf(CGPoint(210, 90), CGPoint(208.5, 101), CGPoint(205, 105.5)) + palate.drop(1).map { it.moved(0.0, -3.0) })
    val palateLining = Trace.smooth(palate + palate.reversed().map { it.moved(0.0, -3.6) })
    val noseCavity = Trace.smooth(
        listOf(
            CGPoint(214, 79), CGPoint(196, 71.5), CGPoint(170, 67), CGPoint(130, 66.5), CGPoint(97, 70.5), CGPoint(70, 70), CGPoint(50, 64), CGPoint(56, 48),
            CGPoint(76, 36), CGPoint(102, 26), CGPoint(132, 20), CGPoint(162, 20), CGPoint(192, 28), CGPoint(212, 42), CGPoint(222, 56), CGPoint(221, 70),
        ),
    )
    val conchae = listOf(
        Trace.smooth(listOf(CGPoint(118, 60), CGPoint(150, 54), CGPoint(188, 56), CGPoint(196, 61), CGPoint(186, 63), CGPoint(150, 62))),
        Trace.smooth(listOf(CGPoint(120, 45), CGPoint(152, 39), CGPoint(182, 40), CGPoint(188, 45), CGPoint(176, 47), CGPoint(148, 47))),
        Trace.smooth(listOf(CGPoint(124, 31), CGPoint(146, 27), CGPoint(164, 29), CGPoint(156, 33), CGPoint(138, 34))),
    )

    val upperEdge = CGPoint(212.6, 125)

    /** Upper incisor: labial face, incisal edge and the shovel-shaped lingual face. */
    val upperCrown: CGPath = CGPath().apply {
        move(CGPoint(201.5, 105))
        addLine(CGPoint(210.6, 104))
        addCurve(CGPoint(213.9, 124), CGPoint(212.6, 111), CGPoint(214.2, 118.6))
        addCurve(CGPoint(209.8, 124.2), CGPoint(213.1, 125.6), CGPoint(211, 125.6))
        addCurve(CGPoint(201, 110.6), CGPoint(206.6, 119), CGPoint(202.2, 115.6))
        addCurve(CGPoint(201.5, 105), CGPoint(200.7, 108.2), CGPoint(200.9, 106.2))
        closeSubpath()
    }
    val upperRoot: CGPath = CGPath().apply {
        move(CGPoint(201.5, 105.4))
        addCurve(CGPoint(198.4, 80.4), CGPoint(199.4, 96), CGPoint(197.5, 86))
        addQuadCurve(CGPoint(202.8, 80.8), CGPoint(200.6, 78.2))
        addCurve(CGPoint(210.6, 104), CGPoint(206, 90), CGPoint(209, 98))
        closeSubpath()
    }
    val upperGum: CGPath = CGPath().apply {
        move(CGPoint(209.6, 104.6))
        addCurve(CGPoint(211, 90), CGPoint(209.2, 100), CGPoint(209.6, 94))
        addLine(CGPoint(213, 96))
        addCurve(CGPoint(211.4, 106), CGPoint(212, 100), CGPoint(211.6, 103))
        closeSubpath()
    }

    // Lower incisor, mandible and floor of the mouth, at jaw = 0; they turn with the jaw.
    val lowerEdge = CGPoint(206.8, 119.6)
    val lowerCrown = listOf(
        CGPoint(205, 120.8), CGPoint(207, 119.2), CGPoint(208.8, 120.6), CGPoint(209.2, 125.8), CGPoint(208.4, 131),
        CGPoint(207.6, 135.6), CGPoint(200.6, 134.8), CGPoint(201.6, 129.8), CGPoint(203.2, 124.8),
    )
    val lowerRoot = listOf(
        CGPoint(207.6, 135.6),
        CGPoint(205.6, 144),
        CGPoint(203, 152),
        CGPoint(201, 157),
        CGPoint(198.8, 156),
        CGPoint(199, 148),
        CGPoint(199.8, 141),
        CGPoint(200.6, 134.8),
    )
    val lowerGum = listOf(CGPoint(201.5, 136.2), CGPoint(203, 132), CGPoint(207.2, 132.5), CGPoint(208, 137.5), CGPoint(205, 140), CGPoint(200, 139))
    val mandible = listOf(
        CGPoint(207.6, 137), CGPoint(206, 147), CGPoint(210, 157), CGPoint(213.5, 166), CGPoint(210, 176), CGPoint(203, 181),
        CGPoint(192, 182), CGPoint(186, 172), CGPoint(187.5, 161), CGPoint(192, 150), CGPoint(198.5, 139), CGPoint(201.5, 136.5),
    )
    val floor = listOf(
        CGPoint(201.5, 137), CGPoint(197, 141.5), CGPoint(189, 146.5), CGPoint(168, 153), CGPoint(138, 160), CGPoint(110, 166),
        CGPoint(96, 178), CGPoint(104, 192), CGPoint(150, 193), CGPoint(186, 184), CGPoint(190, 168), CGPoint(193, 150),
    )

    // Upper lip: outer profile from below the nose to the stomion, then the inner face lying on the incisor back up to the vestibule.
    val upperLip = listOf(
        CGPoint(216, 86), CGPoint(218.5, 95), CGPoint(221.6, 101.6), CGPoint(224.8, 104.4), CGPoint(230.4, 110.2), CGPoint(231.6, 116), CGPoint(228.6, 120.8),
        CGPoint(222.5, 122.8), CGPoint(217, 122.4), CGPoint(214, 120), CGPoint(213.2, 114), CGPoint(211.6, 106), CGPoint(210.2, 98), CGPoint(210.6, 90),
    )
    val lowerLip = listOf(
        CGPoint(222.5, 123), CGPoint(229.6, 125.8), CGPoint(233, 131.2), CGPoint(231.4, 137.4), CGPoint(227.4, 141), CGPoint(221, 145.4), CGPoint(219, 148.5),
        CGPoint(222, 155.5), CGPoint(226, 164), CGPoint(224.6, 174), CGPoint(217, 182), CGPoint(204, 188), CGPoint(186, 191),
        CGPoint(184, 180), CGPoint(196, 158), CGPoint(204, 146), CGPoint(207.4, 140.6), CGPoint(209.5, 134), CGPoint(210.6, 128), CGPoint(214.6, 123.8),
    )

    val velumUp = listOf(
        CGPoint(97, 70.5), CGPoint(78, 66.5), CGPoint(58, 67.5), CGPoint(47.5, 73), CGPoint(46.5, 80),
        CGPoint(50, 88.5), CGPoint(54, 90), CGPoint(62, 85), CGPoint(80, 81.5), CGPoint(97, 78),
    )
    val velumDown = listOf(
        CGPoint(97, 70.5), CGPoint(82, 74), CGPoint(68, 84), CGPoint(60, 96), CGPoint(56, 104),
        CGPoint(57, 114), CGPoint(61, 114.5), CGPoint(65, 106), CGPoint(80, 91), CGPoint(97, 78),
    )

    val esophagus: CGPath = CGPath().apply {
        move(CGPoint(40, 170))
        addCurve(CGPoint(56, 174), CGPoint(46, 168), CGPoint(52, 170))
        addLine(CGPoint(56, 214))
        addLine(CGPoint(36, 214))
        closeSubpath()
    }
    val larynx: CGPath = CGPath().apply {
        move(CGPoint(84, 168))
        addCurve(CGPoint(104, 174), CGPoint(90, 166), CGPoint(100, 168))
        addLine(CGPoint(106, 214))
        addLine(CGPoint(82, 214))
        closeSubpath()
    }
    val thyroid: CGPath = CGPath().apply {
        move(CGPoint(86, 174))
        addCurve(CGPoint(101, 182), CGPoint(94, 172), CGPoint(100, 176))
        addLine(CGPoint(101, 206))
        addCurve(CGPoint(86, 206), CGPoint(96, 204), CGPoint(90, 204))
        closeSubpath()
    }
    val hyoid = Trace.ellipse(CGPoint(108, 176), 5.0, 3.4, -20.0)
    val epiglottis: CGPath = CGPath().apply {
        move(CGPoint(88, 170))
        addCurve(CGPoint(66.5, 140), CGPoint(82, 160), CGPoint(74, 150))
        addQuadCurve(CGPoint(64.4, 141.5), CGPoint(63.5, 137.5))
        addCurve(CGPoint(80, 175), CGPoint(67, 152), CGPoint(72, 163))
        closeSubpath()
    }
    val noseWing: CGPath = CGPath().apply {
        move(CGPoint(213, 60))
        addCurve(CGPoint(218, 76.5), CGPoint(208, 66), CGPoint(210, 74))
    }
    val nostril = Trace.ellipse(CGPoint(224, 75.5), 5.0, 1.8, -12.0)

    fun addProfile(p: CGPath, lipTop: CGPoint) {
        p.addCurve(CGPoint(235, 56), CGPoint(204, 22), CGPoint(222, 42))
        p.addCurve(CGPoint(234, 72), CGPoint(240, 61), CGPoint(240, 68))
        p.addCurve(CGPoint(219, 79), CGPoint(230, 75), CGPoint(223, 76))
        p.addCurve(lipTop, CGPoint(216.5, 81), CGPoint(216, 84))
    }
}
