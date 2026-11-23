package tech.fonia.design

import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private object SagittalInk {
    val lipUpper = Color.hex(0xD9566F)
    val lipLower = Color.hex(0xF28C9A)
    val inside = Color.hex(0x571C2E)
    val tongue = Color.hex(0xFA9CA8)
    val tongueLine = Color.hex(0xEE7C8D)
    val tooth = Color.hex(0xFFFFFF)
    val toothLine = Color.hex(0xE8E3D6)
    val tissue = Color.hex(0xFCDDC4)
    val tissueLine = Color.hex(0xF2C4A2)
    val bone = Color.hex(0xFFF4E4)
    val boneLine = Color.hex(0xF2DFC4)
}

@Suppress("EnumEntryName")
enum class SagittalAir { cold, warm }

/**
 * One sound seen from the side, in the drawing's own coordinates: facing right, y growing downward.
 * [jaw] runs 0…1, [round] runs −1 (spread) … 1 (forward), [lips] is the gap in drawing units, [velum] is 1
 * raised and 0 lowered for breathing through the nose.
 */
data class SagittalPose(
    val jaw: Double = 0.1,
    val round: Double = 0.0,
    val lips: Double = 3.0,
    val velum: Double = 1.0,
    val tongue: Tongue = Tongue.relaxed,
    val air: SagittalAir? = null,
    val voiced: Boolean = false,
    val vibrate: Boolean = false,
    val lipBite: Boolean = false,
    /** The tongue closes the channel, so the air waits behind it. */
    val stop: Boolean = false,
    /** Name of the pose this one glides into and back from, as in Ț into S. */
    val release: String? = null,
    /** Where the air leaves the teeth, when not between the incisal edges. */
    val exit: CGPoint? = null,
    /** The air goes round the sides of the tongue, not over the middle. */
    val lateral: Boolean = false,
) {
    /** The tongue's profile, from the tip back to the root, plus the underside just behind the tip. */
    data class Tongue(
        val tip: CGPoint,
        val blade: CGPoint,
        val front: CGPoint,
        val dorsum: CGPoint,
        val back: CGPoint,
        val root: CGPoint,
        val under: CGPoint,
    ) {
        companion object {
            /** Low in the mouth, tip behind the lower incisors, clear air above. */
            val relaxed = Tongue(CGPoint(198.5, 125.5), CGPoint(187, 114), CGPoint(166, 105), CGPoint(134, 99), CGPoint(103, 104), CGPoint(80, 124), CGPoint(190, 134))

            // Tip down behind the lower incisors; only the blade comes close to the ridge, leaving a narrow groove.
            internal val groove = Tongue(CGPoint(200.5, 127.5), CGPoint(190, 97.5), CGPoint(172, 90), CGPoint(140, 88.5), CGPoint(105, 96), CGPoint(79, 123), CGPoint(189, 137))

            // Blade raised further back than for S, with a wider channel and a hollow under the tip.
            internal val cup = Tongue(CGPoint(195, 107), CGPoint(182, 93.5), CGPoint(163, 89), CGPoint(134, 89), CGPoint(103, 97), CGPoint(78, 123), CGPoint(186, 122))

            // Tip and blade pressed from the ridge back along the post-alveolar slope.
            internal val press = Tongue(CGPoint(196.5, 100.8), CGPoint(184, 87), CGPoint(164, 87), CGPoint(134, 89), CGPoint(103, 97), CGPoint(78, 123), CGPoint(189, 116))
        }
    }

    internal val released: SagittalPose get() = release?.let(::named) ?: this

    companion object {
        val rest = SagittalPose(jaw = 0.04, lips = 0.0, velum = 0.0)
        val s = SagittalPose(jaw = 0.08, round = -0.4, lips = 5.0, tongue = Tongue.groove, air = SagittalAir.cold)
        val z = SagittalPose(jaw = 0.08, round = -0.4, lips = 5.0, tongue = Tongue.groove, air = SagittalAir.cold, voiced = true)
        val ts = SagittalPose(
            jaw = 0.08, round = -0.4, lips = 5.0,
            tongue = Tongue(CGPoint(198.6, 103.4), CGPoint(190, 99), CGPoint(171, 92), CGPoint(140, 89), CGPoint(105, 96), CGPoint(79, 123), CGPoint(192, 116)),
            air = SagittalAir.cold, stop = true, release = "s",
        )
        val sh = SagittalPose(jaw = 0.14, round = 1.0, lips = 7.0, tongue = Tongue.cup, air = SagittalAir.warm)
        val zh = SagittalPose(jaw = 0.14, round = 1.0, lips = 7.0, tongue = Tongue.cup, air = SagittalAir.warm, voiced = true)
        val ce = SagittalPose(jaw = 0.12, round = 0.7, lips = 6.0, tongue = Tongue.press, air = SagittalAir.warm, stop = true, release = "ș")
        val ge = SagittalPose(jaw = 0.12, round = 0.7, lips = 6.0, tongue = Tongue.press, air = SagittalAir.warm, voiced = true, stop = true, release = "j")
        val r = SagittalPose(
            jaw = 0.3, lips = 9.0,
            tongue = Tongue(CGPoint(195, 100.8), CGPoint(183, 111), CGPoint(161, 112), CGPoint(130, 104), CGPoint(101, 100), CGPoint(79, 125), CGPoint(187, 120)),
            air = SagittalAir.cold, voiced = true, vibrate = true, exit = CGPoint(204, 118),
        )
        val l = SagittalPose(
            jaw = 0.3, lips = 9.0,
            tongue = Tongue(CGPoint(198, 102.6), CGPoint(187, 102), CGPoint(166, 106.5), CGPoint(134, 102), CGPoint(103, 103), CGPoint(79, 125), CGPoint(191, 117.5)),
            voiced = true, exit = CGPoint(205, 121), lateral = true,
        )

        // Tip sealed on the ridge with the body held higher than for L, so the air has nowhere to go until it lets go.
        val t = SagittalPose(
            jaw = 0.14, lips = 6.0,
            tongue = Tongue(CGPoint(198.4, 103.2), CGPoint(189, 99.5), CGPoint(170, 95), CGPoint(140, 92), CGPoint(105, 98), CGPoint(79, 124), CGPoint(191, 116.5)),
            air = SagittalAir.cold, stop = true,
        )

        // Contact only where the back of the tongue meets the soft palate; the front stays low.
        val k = SagittalPose(
            jaw = 0.24, lips = 8.0,
            tongue = Tongue(CGPoint(198, 136), CGPoint(185, 123), CGPoint(158, 110), CGPoint(124, 93), CGPoint(92, 80.5), CGPoint(74, 117), CGPoint(190, 143)),
        )
        val g = SagittalPose(jaw = 0.24, lips = 8.0, tongue = k.tongue, voiced = true)
        val th = SagittalPose(
            jaw = 0.12, lips = 8.0,
            tongue = Tongue(CGPoint(216, 125.5), CGPoint(203, 120), CGPoint(178, 109), CGPoint(142, 99), CGPoint(105, 101), CGPoint(79, 125), CGPoint(205, 131)),
            air = SagittalAir.cold, exit = CGPoint(214, 122),
        )
        val f = SagittalPose(jaw = 0.08, lips = 0.0, air = SagittalAir.cold, lipBite = true, exit = CGPoint(214, 125.5))
        val v = SagittalPose(jaw = 0.08, lips = 0.0, air = SagittalAir.cold, voiced = true, lipBite = true, exit = CGPoint(214, 125.5))

        /** The pose for a viseme name such as "ș" or "ce"; anything without a side view falls back to [rest]. */
        fun named(name: String): SagittalPose = when (name.lowercase()) {
            "s" -> s
            "z" -> z
            "ț" -> ts
            "ș" -> sh
            "j" -> zh
            "ce" -> ce
            "ge" -> ge
            "r" -> r
            "l" -> l
            "t" -> t
            "k", "c" -> k
            "g" -> g
            "th" -> th
            "f" -> f
            "v" -> v
            else -> rest
        }
    }
}

/**
 * The mouth in profile, the classic speech-therapy drawing in flat cut paper: nose and nasal cavity, hard
 * palate with the alveolar ridge, soft palate and uvula, pharynx and larynx, incisors, lips, jaw and one
 * smooth tongue. Each sound poses the tongue, jaw, lips and soft palate, and shows the air and the voice.
 * Labels belong to the caller.
 *
 * A change of pose moves the whole 25-channel vector with one spring, `spring(duration: 0.34, bounce: 0.12)`
 * (`easeOut(0.15)` with Reduce Motion). Ț, CE and GE alternate held (700 ms) and released (1100 ms) while live.
 */
@Composable
fun SagittalView(pose: SagittalPose, modifier: Modifier = Modifier, isLive: Boolean = true) {
    val reduceMotion = LocalReduceMotion.current
    var releasedFrom by remember { mutableStateOf<SagittalPose?>(null) }
    val shown = if (releasedFrom == pose) pose.released else pose
    val target = remember(shown) { SagittalVector.of(shown) }
    val vector = remember { AnimatedVector(target) }
    var lastPose by remember { mutableStateOf(pose) }
    LaunchedEffect(target) {
        // `.animation(…, value: pose)` follows Reduce Motion; `.animation(spring, value: releasedFrom)` doesn't.
        val byPose = lastPose != pose
        lastPose = pose
        val motion = if (byPose && reduceMotion) VectorMotion.Tween(0.15, EaseOutCurve) else VectorMotion.Spring(0.34, 0.12)
        vector.animateTo(target, motion)
    }
    // `.task(id: pose.release != nil && isLive && !reduceMotion ? pose : nil)`.
    val loop = if (pose.release != null && isLive && !reduceMotion) pose else null
    LaunchedEffect(loop) {
        releasedFrom = null
        if (loop == null) return@LaunchedEffect
        val held = loop
        var open = false
        while (true) {
            delay(if (open) 1100L else 700L)
            open = !open
            releasedFrom = if (open) held else null
        }
    }
    val targetMoves = SagittalVector(target).movesOnItsOwn
    val clockOn = isLive && !reduceMotion && (targetMoves || vector.isRunning)
    val clock = rememberFrameClock(clockOn, 1.0 / 60)
    val stop = shown.stop
    val lateral = shown.lateral
    Canvas(modifier.aspectRatio((SagittalPainter.frame.width / SagittalPainter.frame.height).toFloat()).clearAndSetSemantics {}) {
        val current = SagittalVector(vector.value)
        val ticking = clockOn && current.movesOnItsOwn
        val painter = SagittalPainter(
            pose = current,
            stop = stop,
            lateral = lateral,
            // `Palette.cold.mix(with: Palette.warm, by:)` mixes perceptually; Compose's `lerp` mixes in Oklab.
            airColor = lerp(Palette.cold, Palette.warm, max(0.0, min(1.0, current.warmth)).toFloat()),
            voiceColor = Palette.warm,
            casing = Palette.sheet,
            clock = if (ticking) clock.value / 1e9 else null,
        )
        platform { canvas -> painter.draw(canvas, size.width.toDouble(), size.height.toDouble()) }
    }
}

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

private object Trace {
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
        CGPoint(207.6, 135.6), CGPoint(205.6, 144), CGPoint(203, 152), CGPoint(201, 157), CGPoint(198.8, 156), CGPoint(199, 148), CGPoint(199.8, 141), CGPoint(200.6, 134.8),
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

private class SagittalFrame(val pose: SagittalVector, wobble: Double) {
    val upperLip: List<CGPoint>
    val lowerLip: List<CGPoint>
    val protrusion: Double
    val tip: CGPoint
    val tongue: List<CGPoint>
    val velum: List<CGPoint>

    init {
        val jaw = pose.jaw
        val bite = pose.bite
        val pro = pose.round * 7

        // Rounded lips push forward; the gap and F/V lift the upper lip a little. Spread lips only pull the
        // outside back, because the inner face stays on the teeth.
        val upper = SagittalAnatomy.upperLip.mapIndexed { i, q ->
            val w = max(0.0, min(1.0, (q.y - 92) / 30))
            val k = if (pro < 0 && i > 7) 0.0 else 1.0
            CGPoint(q.x + pro * w * k * (if (q.y > 104) 1.0 else 0.6), q.y - w * (pose.lips * 0.3 + bite * 1.5))
        }
        // The lower lip turns with the jaw, then its free edge is placed so the gap matches the pose.
        // For F/V it tucks up and back under the upper incisors.
        val stomion = upper[7]
        val turned = SagittalAnatomy.lowerLip.map { SagittalAnatomy.turned(it, jaw) }
        val freeEdge = turned[0]
        val gapFix = stomion.y + pose.lips - freeEdge.y
        val lower = turned.mapIndexed { i, q ->
            val w = max(0.0, min(1.0, (147 - SagittalAnatomy.lowerLip[i].y) / 24))
            val x = q.x + pro * w * (if (pro < 0 && i > 3) 0.0 else 1.0)
            val y = q.y + gapFix * w * (1 - bite)
            CGPoint(x + bite * w * -8.5, y + bite * w * (SagittalAnatomy.upperEdge.y + 1.2 - freeEdge.y))
        }
        upperLip = upper
        lowerLip = lower
        protrusion = pro

        // One closed curve with its root on the floor of the mouth and the tip free. The underside starts
        // just behind the tip, so the blade ends thin instead of in a round nose.
        val tip = pose.tip.moved(0.0, wobble)
        this.tip = tip
        val length = max(hypot(tip.x - pose.blade.x, tip.y - pose.blade.y), 1.0)
        val ux = (tip.x - pose.blade.x) / length
        val uy = (tip.y - pose.blade.y) / length
        tongue = listOf(
            CGPoint(84, 162), CGPoint(pose.root.x + 4, 146.0), pose.root, pose.back, pose.dorsum, pose.front, pose.blade, tip,
            CGPoint(tip.x - ux * 3 - uy * 6.5, tip.y - uy * 3 + ux * 6.5),
            pose.under.moved(0.0, wobble * 0.5),
            SagittalAnatomy.turned(CGPoint(176, 149), jaw),
            SagittalAnatomy.turned(CGPoint(186, 158), jaw),
            CGPoint(112, 172),
        )
        velum = SagittalAnatomy.velumDown.zip(SagittalAnatomy.velumUp).map { (down, up) -> down.toward(up, pose.velum) }
    }

    fun rot(p: CGPoint) = SagittalAnatomy.turned(p, pose.jaw)

    val stomionUpper: CGPoint get() = upperLip[7]
    val stomionLower: CGPoint get() = lowerLip[0]

    val upperLipPath: CGPath get() = Trace.smooth(upperLip)
    val lowerLipPath: CGPath get() = Trace.smooth(lowerLip)

    val neckCurveStart: CGPoint get() = lowerLip[12]

    fun addNeck(p: CGPath) {
        val top = neckCurveStart
        p.addCurve(CGPoint(124, 196), top.moved(-22.0, 2.0), CGPoint(134.0, 190 + (top.y - 191) * 0.4))
        p.addCurve(CGPoint(116, 240), CGPoint(120, 200), CGPoint(118, 206))
    }

    val silhouette: CGPath
        get() {
            val p = CGPath()
            p.move(CGPoint(-30, -30))
            p.addLine(CGPoint(194, -30))
            p.addLine(CGPoint(197, 0))
            SagittalAnatomy.addProfile(p, upperLip[0])
            Trace.addCurves(p, upperLip.subList(0, 8), closed = false)
            p.addLine(lowerLip[0])
            Trace.addCurves(p, lowerLip.subList(0, 13), closed = false)
            addNeck(p)
            p.addLine(CGPoint(-30, 240))
            p.closeSubpath()
            return p
        }

    val airSpace: CGPath
        get() = Trace.polygon(
            listOf(
                stomionUpper, stomionLower, rot(CGPoint(208, 136)), rot(CGPoint(199, 140.5)), rot(CGPoint(189, 146.5)),
                CGPoint(168, 153), CGPoint(138, 160), CGPoint(110, 166), CGPoint(90, 168), CGPoint(86, 214), CGPoint(52, 214), CGPoint(47, 170),
                CGPoint(44.5, 120), CGPoint(44.5, 80), CGPoint(50, 64), CGPoint(70, 70), CGPoint(97, 74), CGPoint(130, 71), CGPoint(170, 72),
                CGPoint(196, 82), CGPoint(208, 100), CGPoint(212, 112),
            ),
        )

    fun airPath(stop: Boolean, lateral: Boolean): List<CGPoint> {
        val air = mutableListOf(CGPoint(70, 214), CGPoint(70, 190), CGPoint(64, 162), CGPoint(59, 132), CGPoint(66, 104))
        val roof = (velum.subList(5, velum.size) + SagittalAnatomy.palate).sortedBy { it.x }
        val surface = Trace.sample(tongue, from = 2, to = 7, steps = 12)
        val front = if (stop) tip.x - 16 else (if (lateral) pose.blade.x - 12 else tip.x - 6)
        var x = 96.0
        while (x < front) {
            val top = Trace.y(roof, x)
            val bottom = Trace.y(surface, x)
            if (top != null && bottom != null) air += CGPoint(x, (top + bottom) / 2)
            x += 16
        }
        // L: past the tip the air goes round it, across the side of the blade.
        if (lateral) air += pose.blade.moved(2.0, 9.0)
        if (!stop) {
            // Over the tip, not through it: a raised tip would otherwise sit across a straight run to the teeth.
            if (!lateral && pose.tip.x < pose.gate.x) {
                val top = Trace.y(roof, pose.tip.x + 1)
                if (top != null) air += CGPoint(pose.tip.x + 1, (top + pose.tip.y) / 2)
            }
            val lips = stomionUpper.toward(stomionLower, 0.5)
            air += pose.gate
            air += lips
            air += lips.moved(20 + protrusion * 0.5, 1.0)
        }
        return air
    }
}

internal class SagittalPainter(
    val pose: SagittalVector,
    val stop: Boolean,
    val lateral: Boolean,
    val airColor: Color,
    val voiceColor: Color,
    val casing: Color,
    /** Seconds, while something moves on its own; null draws the still pose. */
    val clock: Double?,
) {
    companion object {
        /**
         * Only the mouth: the tongue, the teeth and the roof, with room on the right for the air to leave.
         * The rest of the head adds nothing a learner needs and makes the picture look clinical.
         */
        val frame = CGRect(118, 60, 142, 110)
    }

    fun draw(context: android.graphics.Canvas, width: Double, height: Double) {
        val frame = frame
        val scale = min(width / frame.width, height / frame.height)
        context.save()
        context.translate(
            ((width - frame.width * scale) / 2 - frame.minX * scale).toFloat(),
            ((height - frame.height * scale) / 2 - frame.minY * scale).toFloat(),
        )
        context.scale(scale.toFloat(), scale.toFloat())
        context.clipPath(CGPath.continuous(frame, 12.0).android())

        val wobble = clock?.let { sin(it * 2 * PI * 5) * 3.2 } ?: 0.0
        val f = SagittalFrame(pose, wobble * pose.trill)

        drawHead(f, context)
        drawThroat(f, context)
        drawTongue(f, context)
        drawPalate(f, context)
        drawJaw(f, context)
        drawLips(f, context)
        drawVoice(context)
        drawAir(f, context)
        drawTrill(f, context)
        context.restore()
    }

    private fun fill(context: android.graphics.Canvas, path: CGPath, color: Color, opacity: Double = 1.0) =
        context.drawPath(path, fillPaint(color, opacity))

    private fun stroke(context: android.graphics.Canvas, path: CGPath, color: Color, width: Double, cap: Paint.Cap = Paint.Cap.BUTT, join: Paint.Join = Paint.Join.MITER, opacity: Double = 1.0) =
        context.drawPath(path, strokePaint(color, width, cap, join, opacity))

    private fun drawHead(f: SagittalFrame, context: android.graphics.Canvas) {
        fill(context, f.silhouette, SagittalInk.tissue)
        fill(context, SagittalAnatomy.noseCavity, SagittalInk.inside, 0.08)
        for (concha in SagittalAnatomy.conchae) {
            fill(context, concha, SagittalInk.tissue)
            stroke(context, concha, SagittalInk.tissueLine, 0.5)
        }
        fill(context, f.airSpace, SagittalInk.inside)
    }

    private fun drawThroat(f: SagittalFrame, context: android.graphics.Canvas) {
        val floor = SagittalAnatomy.floor.mapIndexed { i, q -> if (i in 4..7) q else f.rot(q) }
        fill(context, Trace.smooth(floor), SagittalInk.tissue)

        fill(context, SagittalAnatomy.esophagus, SagittalInk.tissue)
        fill(context, SagittalAnatomy.larynx, SagittalInk.tissue)
        for (cartilage in listOf(SagittalAnatomy.thyroid, SagittalAnatomy.hyoid)) {
            fill(context, cartilage, SagittalInk.bone)
            stroke(context, cartilage, SagittalInk.boneLine, 0.8)
        }

        // The vocal folds come together when the voice is on.
        val reach = 9 + 4 * pose.voice
        val half = 4 - pose.voice
        val folds = CGPath()
        folds.addLines(listOf(CGPoint(56.0, 191 - half), CGPoint(56 + reach, 191.0), CGPoint(56.0, 191 + half)))
        folds.closeSubpath()
        folds.addLines(listOf(CGPoint(84.0, 191 - half), CGPoint(84 - reach, 191.0), CGPoint(84.0, 191 + half)))
        folds.closeSubpath()
        fill(context, folds, SagittalInk.tongue)
        stroke(context, folds, SagittalInk.tongueLine, 0.6)
    }

    private fun drawTongue(f: SagittalFrame, context: android.graphics.Canvas) {
        val tongue = Trace.smooth(f.tongue)
        fill(context, tongue, SagittalInk.tongue)

        // Muscle fibres fanning from the jaw into the body (genioglossus) make it read as a tongue, not a balloon.
        val fan = f.rot(CGPoint(184, 157))
        val fibres = CGPath()
        for (q in listOf(pose.root, pose.back, pose.dorsum, pose.front, pose.blade)) {
            val mid = fan.toward(q, 0.5)
            fibres.move(fan)
            fibres.addQuadCurve(fan.toward(q, 0.86), mid.moved(-6.0, 4.0))
        }
        context.save()
        context.clipPath(tongue.android())
        stroke(context, fibres, SagittalInk.tongueLine, 0.8, cap = Paint.Cap.ROUND, opacity = 0.22)
        context.restore()

        // Only the free edge gets a line; the root grows out of the floor of the mouth.
        val edge = Trace.polyline(Trace.sample(f.tongue, from = 1, to = 10, steps = 12))
        stroke(context, edge, SagittalInk.tongueLine, 1.0, cap = Paint.Cap.ROUND, join = Paint.Join.ROUND)

        fill(context, SagittalAnatomy.epiglottis, SagittalInk.tissue)
        stroke(context, SagittalAnatomy.epiglottis, SagittalInk.tissueLine, 0.7)
    }

    private fun drawPalate(f: SagittalFrame, context: android.graphics.Canvas) {
        val velum = Trace.smooth(f.velum)
        fill(context, velum, SagittalInk.tissue)
        stroke(context, velum, SagittalInk.tissueLine, 0.8)

        fill(context, SagittalAnatomy.palateBone, SagittalInk.bone)
        stroke(context, SagittalAnatomy.palateBone, SagittalInk.boneLine, 0.8)
        fill(context, SagittalAnatomy.upperRoot, SagittalInk.tissue)
        stroke(context, SagittalAnatomy.upperRoot, SagittalInk.toothLine, 0.6)
        fill(context, SagittalAnatomy.palateLining, SagittalInk.tissue)
        stroke(context, SagittalAnatomy.palateLining, SagittalInk.tissueLine, 0.5)
        fill(context, SagittalAnatomy.upperGum, SagittalInk.tissue)
    }

    private fun drawJaw(f: SagittalFrame, context: android.graphics.Canvas) {
        fill(context, f.lowerLipPath, SagittalInk.tissue)

        val mandible = Trace.smooth(SagittalAnatomy.mandible.map(f::rot))
        fill(context, mandible, SagittalInk.bone)
        stroke(context, mandible, SagittalInk.boneLine, 0.8)

        val root = Trace.smooth(SagittalAnatomy.lowerRoot.map(f::rot))
        fill(context, root, SagittalInk.tissue)
        stroke(context, root, SagittalInk.toothLine, 0.6)
        fill(context, Trace.polygon(SagittalAnatomy.lowerGum.map(f::rot)), SagittalInk.tissue)

        val crown = Trace.smooth(SagittalAnatomy.lowerCrown.map(f::rot))
        fill(context, crown, SagittalInk.tooth)
        stroke(context, crown, SagittalInk.toothLine, 0.8)
    }

    private fun drawLips(f: SagittalFrame, context: android.graphics.Canvas) {
        val ul = f.upperLip
        val ll = f.lowerLip
        val upper = f.upperLipPath
        fill(context, upper, SagittalInk.tissue)
        context.save()
        context.clipPath(upper.android())
        fill(
            context,
            Trace.polygon(listOf(ul[3], ul[3].moved(30.0, -8.0), ul[3].moved(30.0, 40.0), ul[11].moved(-6.0, 30.0), ul[11].moved(-6.0, 0.0))),
            SagittalInk.lipUpper,
        )
        context.restore()
        stroke(context, Trace.smooth(ul.subList(0, 9), closed = false), SagittalInk.tissueLine, 1.0, cap = Paint.Cap.ROUND)

        fill(context, SagittalAnatomy.upperCrown, SagittalInk.tooth)
        stroke(context, SagittalAnatomy.upperCrown, SagittalInk.toothLine, 0.8)

        val lower = f.lowerLipPath
        context.save()
        context.clipPath(lower.android())
        fill(
            context,
            Trace.polygon(listOf(ll[4], ll[4].moved(30.0, 6.0), ll[4].moved(30.0, -50.0), ll[17].moved(-8.0, -50.0), ll[17].moved(-8.0, 0.0), ll[17])),
            SagittalInk.lipLower,
        )
        context.restore()
        stroke(context, Trace.smooth(listOf(ll[19]) + ll.subList(0, 13), closed = false), SagittalInk.tissueLine, 1.0, cap = Paint.Cap.ROUND)

        val profile = CGPath()
        profile.move(CGPoint(197, 0))
        SagittalAnatomy.addProfile(profile, ul[0])
        stroke(context, profile, SagittalInk.tissueLine, 1.0, cap = Paint.Cap.ROUND)

        val neck = CGPath()
        neck.move(f.neckCurveStart)
        f.addNeck(neck)
        stroke(context, neck, SagittalInk.tissueLine, 1.0, cap = Paint.Cap.ROUND)

        stroke(context, SagittalAnatomy.noseWing, SagittalInk.tissueLine, 1.1, cap = Paint.Cap.ROUND)
        fill(context, SagittalAnatomy.nostril, SagittalInk.tissueLine)
    }

    /** Where you feel the voice: on the front of the throat. */
    private fun drawVoice(context: android.graphics.Canvas) {
        if (pose.voice <= 0.01) return
        val pulse = clock?.let { 0.55 + 0.45 * abs(sin(it * PI * 4)) } ?: 1.0
        val arcs = CGPath()
        val center = CGPoint(124, 193)
        for (radius in listOf(5.0, 9.5, 14.0)) {
            arcs.move(center.moved(radius * cos(-0.7), radius * sin(-0.7)))
            arcs.addArc(center, radius, -0.7, 0.85, clockwise = false)
        }
        stroke(context, arcs, voiceColor, 1.8, cap = Paint.Cap.ROUND, opacity = pose.voice * pulse)
    }

    private fun drawAir(f: SagittalFrame, context: android.graphics.Canvas) {
        if (pose.air <= 0.01) return
        val points = f.airPath(stop, lateral)
        if (points.size <= 1) return
        val path = Trace.smooth(points, closed = false)
        val dash = clock?.let { (it * 40) % 12 } ?: 0.0
        val end = points[points.size - 1]
        val before = points[points.size - 2]
        val angle = atan2(end.y - before.y, end.x - before.x)
        fun corner(turn: Double) = end.moved(cos(angle + turn) * 5, sin(angle + turn) * 5)
        val head = Trace.polygon(listOf(corner(0.0), corner(2.4), corner(-2.4)))

        // One layer at the air's opacity, so the casing and the dashes don't add up where they overlap.
        val bounds = RectF(frame.minX.toFloat(), frame.minY.toFloat(), frame.maxX.toFloat(), frame.maxY.toFloat())
        context.saveLayerAlpha(bounds, (pose.air * 255).toInt().coerceIn(0, 255))
        // A paper casing keeps the dashes readable over both the dark mouth and the pale face.
        val phase = 12 - dash
        val pattern = floatArrayOf(6f, 6f)
        context.drawPath(path, strokePaint(casing.opacity(0.9), 4.2, Paint.Cap.ROUND, dash = pattern, dashPhase = phase))
        context.drawPath(path, strokePaint(airColor, 2.4, Paint.Cap.ROUND, dash = pattern, dashPhase = phase))
        context.drawPath(head, fillPaint(airColor))
        context.drawPath(head, strokePaint(casing, 1.2, join = Paint.Join.ROUND))
        context.restore()
    }

    private fun drawTrill(f: SagittalFrame, context: android.graphics.Canvas) {
        if (pose.trill <= 0.01) return
        val ticks = CGPath()
        ticks.move(f.tip.moved(-7.0, -1.0))
        ticks.addQuadCurve(f.tip.moved(-7.0, 5.0), f.tip.moved(-9.0, 2.0))
        ticks.move(f.tip.moved(-11.0, -2.0))
        ticks.addQuadCurve(f.tip.moved(-11.0, 6.0), f.tip.moved(-14.0, 2.0))
        stroke(context, ticks, casing, 1.0, cap = Paint.Cap.ROUND, opacity = pose.trill)
    }
}
