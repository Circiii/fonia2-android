package tech.fonia.design

import kotlin.math.max
import kotlin.math.min

@Suppress("EnumEntryName")
enum class TonguePose(val rawValue: Int) {
    rest(0), up(1), tipUp(2), between(3), `out`(4), outUp(5), outLeft(6), outRight(7), hidden(8),
}

/** What the mouth is doing. Numbers run 0…1, except [smile], which runs −1…1. */
data class MouthShape(
    val open: Double = 0.1,
    val width: Double = 0.55,
    val round: Double = 0.0,
    val smile: Double = 0.6,
    val teeth: Double = 0.25,
    val puff: Double = 0.0,
    val tongue: TonguePose = TonguePose.rest,
    val vibrate: Boolean = false,
    val lipBite: Boolean = false,
) {
    companion object {
        val rest = MouthShape()
    }
}

/**
 * The animatable form of a mouth: six numbers, the bite and a weight for each tongue pose, so a change of
 * tongue position crossfades instead of jumping. Sixteen floats, like the `SIMD16<Float>` on iOS; the whole
 * vector moves with one spring ([AnimatedVector]).
 */
class MouthVector(val v: FloatArray, val vibrate: Boolean = false) {
    init {
        require(v.size == SIZE)
    }

    constructor(shape: MouthShape) : this(
        FloatArray(SIZE).also { v ->
            v[0] = shape.open.toFloat()
            v[1] = shape.width.toFloat()
            v[2] = shape.round.toFloat()
            v[3] = shape.smile.toFloat()
            v[4] = shape.teeth.toFloat()
            v[5] = shape.puff.toFloat()
            v[6] = if (shape.lipBite) 1f else 0f
            v[7 + shape.tongue.rawValue] = 1f
        },
        shape.vibrate,
    )

    fun tongueWeight(pose: TonguePose): Double = v[7 + pose.rawValue].toDouble()
    val open: Double get() = v[0].toDouble()
    val width: Double get() = v[1].toDouble()
    val round: Double get() = v[2].toDouble()
    val smile: Double get() = v[3].toDouble()
    val teeth: Double get() = v[4].toDouble()
    val puff: Double get() = v[5].toDouble()
    val bite: Double get() = v[6].toDouble()

    /**
     * While the voice sounds, the mouth holds the sound's shape and breathes a little. It must not open with
     * loudness: an S with the jaw dropping would teach the opposite of "teeth close".
     */
    fun talking(talk: Double): MouthVector {
        if (talk <= 0.01) return this
        val out = v.copyOf()
        out[0] = min(1f, v[0] + talk.toFloat() * 0.05f)
        val hidden = 7 + TonguePose.hidden.rawValue
        val rest = 7 + TonguePose.rest.rawValue
        out[rest] += v[hidden]
        out[hidden] = 0f
        return MouthVector(out, vibrate)
    }

    companion object {
        const val SIZE = 16
    }
}

/**
 * Lips and opening in a 200 × 150 box. The curves come from how lips actually move: smiling lifts the
 * corners into a crescent, opening drops the lower lip into a D, rounding closes the corners into an O.
 */
class MouthGeometry(p: MouthVector) {
    val a: Double
    val ai: Double
    val yc: Double
    val yt: Double
    val yb: Double
    val ypk: Double
    val ybot: Double
    val bw: Double
    val hu: Double
    val hl: Double
    val lift: Double
    val sm: Double
    val opening: CGPath
    val biteClip: CGPath
    val upper: CGPath
    val lower: CGPath
    val innerTop: CGPath

    init {
        val open = max(0.0, p.open)
        val bite = max(0.0, min(1.0, p.bite))
        val r = min(1.0, max(0.0, p.round))
        val sm = max(0.0, p.smile) * (1 - r)
        val spread = max(0.0, p.width - 0.6)
        val a = (24 + p.width * 30) * (1 - 0.36 * r) * (1 - 0.08 * p.puff)
        val ai = a * (1 - 0.55 * r)
        val ho = open * 44 * (1 - 0.12 * r) * (1 - bite) + 7 * bite
        val lift = p.smile * (5 + 0.07 * a) * (1 - r)
        val yt = my - ho * 0.2 + sm * 1.5 - bite * 3
        val yc = my - lift + ho * 0.22
        val crescent = sm * 9 * min(1.0, open * 6) * (1 - open * 0.6) * (1 - bite)
        val yb = yt + ho + crescent
        val press = (1 - min(1.0, open * 10)) * (1 - r)
        val hu = max(3.5, 8.5 + 3.5 * r - 2.2 * sm - spread * 5 - press * 1.2)
        val hl = max(5.0, (11 + 3 * r - 2.5 * sm - spread * 5 - press * 1.2) * (1 - 0.3 * bite))
        val bw = min(8.5, a * 0.4)
        val ypk = yt - hu
        val yn = ypk + 2.2 * (1 - 0.3 * r)
        val ybot = yb + hl

        val left = cx - a
        val right = cx + a
        val kt = min(0.92, 0.5 + 0.28 * r + 0.2 * open)
        val kb = min(0.92, 0.62 + 0.2 * r + 0.25 * open)
        val ct = (yt - 0.25 * yc) / 0.75
        val cb = (yb - 0.25 * yc) / 0.75

        val innerTop = CGPath()
        innerTop.move(CGPoint(cx - ai, yc))
        innerTop.addCurve(CGPoint(cx + ai, yc), CGPoint(cx - ai * kt, ct), CGPoint(cx + ai * kt, ct))

        val opening = innerTop.copy()
        opening.addCurve(CGPoint(cx - ai, yc), CGPoint(cx + ai * kb, cb), CGPoint(cx - ai * kb, cb))
        opening.closeSubpath()

        val cb2 = (yb + 5.5 - 0.25 * yc) / 0.75
        val biteClip = innerTop.copy()
        biteClip.addCurve(CGPoint(cx - ai, yc), CGPoint(cx + ai * kb, cb2), CGPoint(cx - ai * kb, cb2))
        biteClip.closeSubpath()

        val side = (a - bw) * 0.18 * (1 - 0.45 * r)
        val rise = yc - (yc - ypk) * (0.2 + 0.42 * r)
        val upper = CGPath()
        upper.move(CGPoint(left, yc))
        upper.addCurve(CGPoint(cx - bw, ypk), CGPoint(left + side, rise), CGPoint(cx - bw - (a - bw) * 0.45, ypk + 0.4))
        upper.addCurve(CGPoint(cx, yn), CGPoint(cx - bw * 0.6, ypk + 0.4), CGPoint(cx - bw * 0.25, yn))
        upper.addCurve(CGPoint(cx + bw, ypk), CGPoint(cx + bw * 0.25, yn), CGPoint(cx + bw * 0.6, ypk + 0.4))
        upper.addCurve(CGPoint(right, yc), CGPoint(cx + bw + (a - bw) * 0.45, ypk + 0.4), CGPoint(right - side, rise))
        upper.addLine(CGPoint(cx + ai, yc))
        upper.addCurve(CGPoint(cx - ai, yc), CGPoint(cx + ai * kt, ct), CGPoint(cx - ai * kt, ct))
        upper.closeSubpath()

        val drop = yc + (ybot - yc) * (0.3 + 0.34 * r)
        val corner = a * 0.1 * (1 - 0.4 * r)
        val lower = CGPath()
        lower.move(CGPoint(cx - ai, yc))
        lower.addCurve(CGPoint(cx + ai, yc), CGPoint(cx - ai * kb, cb), CGPoint(cx + ai * kb, cb))
        lower.addLine(CGPoint(right, yc))
        lower.addCurve(CGPoint(cx, ybot), CGPoint(right - corner, drop), CGPoint(cx + a * 0.55, ybot))
        lower.addCurve(CGPoint(left, yc), CGPoint(cx - a * 0.55, ybot), CGPoint(left + corner, drop))
        lower.closeSubpath()

        this.a = a
        this.ai = ai
        this.yc = yc
        this.yt = yt
        this.yb = yb
        this.ypk = ypk
        this.ybot = ybot
        this.bw = bw
        this.hu = hu
        this.hl = hl
        this.lift = lift
        this.sm = sm
        this.opening = opening
        this.biteClip = biteClip
        this.upper = upper
        this.lower = lower
        this.innerTop = innerTop
    }

    companion object {
        const val cx: Double = 100.0
        const val my: Double = 76.0
    }
}
