package tech.fonia.mouth3d.style

import tech.fonia.mouth3d.AirStream
import tech.fonia.mouth3d.Articulator
import tech.fonia.mouth3d.PoseSpec
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * What the cut-away explains on top of the cut 3D mouth, drawn just in front of the cut plane (x = [build]'s `x`):
 * the airflow (particles along the air's way), a pulsing ring where the tongue touches the roof, the voice mark at the
 * throat, motion ticks behind R's vibrating tip, and a soft glow along the tongue's top when the step is the tongue.
 * All positions come from [SagittalShape], whose outlines are the 3D model's midline.
 */
object CutOverlay {
    /** The air of one moment: where the particles are (side-drawing units), their size and opacity. */
    class Particle(val p: P2, val radius: Float, val alpha: Float)

    private fun map(p: P2) = SagittalShape.world(p)

    fun build(
        mb: MeshBuilder,
        sag: SagittalShape,
        lips: LipContours,
        spec: PoseSpec,
        pal: StylePalette,
        highlight: Articulator?,
        pulse: Float,
        alpha: Float,
        time: Float,
        closure: Boolean,
        sinceRelease: Float,
        x: Float,
        towardCamera: Float,
        right: FloatArray,
        up: FloatArray,
    ) {
        if (alpha < 0.01f) return
        mb.begin(Blend.AlphaNoDepthWrite, clip = false)
        fun at(k: Float) = x + towardCamera * k

        // the tongue's cut edge, so its shape reads at a glance
        line(mb, sag.tongue.subList(2 * 8, 9 * 8 + 4), at(0.1f), SlabMix.scale(pal.tongue, 0.52f), 0.75f, alpha, towardCamera)
        if (highlight == Articulator.Tongue) {
            val surf = sag.tongueSurface
            glowBand(mb, surf.subList(surf.size / 5, surf.size), at(0.2f), pal.highlight, alpha * pulse, towardCamera)
        }
        sag.contact?.let { c -> ring(mb, c, at(0.4f), 2.6f + 0.5f * pulse, 0.85f, pal.highlight, alpha * (0.55f + 0.45f * pulse), towardCamera) }
        if (spec.voiced) {
            for (k in 0 until 3) {
                val ph = ((time * 2.2f - k * 0.33f) % 1f + 1f) % 1f
                val a = alpha * (1f - ph) * Geo.smoothstep(0f, 0.15f, ph)
                val r = 4f + ph * 13f
                val c = P2(176f, 176f)
                val arc = (0..14).map { i -> val t = (-0.35f + 1.5f * i / 14f) * PI.toFloat() / 2; P2(c.x + r * cos(t) * 0.8f, c.y + r * sin(t)) }
                line(mb, arc, at(0.4f), pal.voice, 1.0f, a, towardCamera)
            }
            val c = P2(168f, 176f)
            val zig = (0..10).map { i -> P2(c.x - 9f + i * 1.8f, c.y + (if (i % 2 == 0) -1.6f else 1.6f) * (0.6f + 0.4f * sin(time * 40f))) }
            line(mb, zig, at(0.4f), pal.voice, 0.7f, alpha, towardCamera)
        }
        if (spec.flutter != null) {
            val tip = sag.tip
            for (k in 0..1) {
                val o = 6f + 4f * k
                val tick = (0..6).map { i -> val t = (i / 6f - 0.5f) * 1.6f; P2(tip.x - o + 2f * cos(t * 2f), tip.y - 2f + 6f * t) }
                line(mb, tick, at(0.4f), SlabMix.WHITE, 0.45f, alpha * 0.85f, towardCamera)
            }
        }
        val air = spec.air
        if (air != null) {
            val a = HeadModel.jawAngle(sag.jaw)
            val lowerIncisorY = HeadModel.jawY(-1.25f, -1f, cos(a), sin(a))
            val gate = Anatomy.UPPER_EDGE.moved(-0.5f, -lowerIncisorY * 0.5f / Anatomy.MM)
            val exit = Anatomy.UPPER_EDGE.moved((lips.frontZ - 0.5f) / Anatomy.MM, -lips.openingY / Anatomy.MM)
            val parts = particles(sag, air.stream, air.stop, time, closure, sinceRelease, gate, exit)
            val col = if (air.warm) pal.airWarm else pal.airCold
            val big = if (highlight == Articulator.Air) 1.35f else 1f
            mb.kind = Kind.DISC; mb.glow = 0f
            for (p in parts) disc(mb, p.p, at(0.6f), p.radius * big * 1.7f, SlabMix.WHITE, alpha * p.alpha * 0.45f, right, up)
            for (p in parts) disc(mb, p.p, at(0.7f), p.radius * big, col, alpha * p.alpha, right, up)
        }
        mb.flush()
    }

    /**
     * The particles of the air stream at [time]: they flow along [SagittalShape.airPath]; a closure (Ț, CE, GE)
     * holds them in a little crowd behind the tongue, and the release lets them out in a quick puff.
     */
    fun particles(sag: SagittalShape, stream: AirStream, stop: Boolean, time: Float, closure: Boolean, sinceRelease: Float, gate: P2? = null, exit: P2? = null): List<Particle> {
        val (path, hold) = sag.airPath(stop, stream == AirStream.Lateral, gate, exit)
        val len = Geo.length(path)
        val wide = stream == AirStream.Wide
        val n = if (wide) 34 else 28
        val speed = when (stream) { AirStream.Narrow -> 120f; AirStream.Wide -> 85f; AirStream.Pulse -> 100f; AirStream.Lateral -> 80f }
        val burst = if (stop && !closure) 1f + 1.2f * max(0f, 1f - sinceRelease / 0.6f) else 1f
        val out = ArrayList<Particle>(n)
        for (i in 0 until n) {
            val seed = frac(sin(i * 12.9898f) * 43758.547f)
            val ph = frac(time * speed * burst / len + i.toFloat() / n + seed * 0.02f)
            var s = ph * len
            var a = Geo.smoothstep(0f, 40f, s) * (1f - Geo.smoothstep(len - 22f, len, s))
            if (stop && closure && s > hold) {
                s = hold - 2f - (i % 6) * 2.4f + 0.8f * sin(time * 9f + i)
                a = 0.9f
            }
            val (p, t) = Geo.at(path, s)
            val nrm = P2(-t.y, t.x)
            val narrowing = if (stream == AirStream.Narrow) 1f - 0.7f * Geo.smoothstep(len * 0.6f, len * 0.8f, s) else 1f
            val spread = (if (wide) 4.2f else 1.4f) * narrowing
            val off = (seed * 2f - 1f) * spread
            if (stream == AirStream.Pulse) a *= 0.25f + 0.75f * max(0f, sin(2f * PI.toFloat() * 2.5f * time - s * 0.09f))
            // L: past the blade the air runs beside the tongue, out of the cut: drawn lighter
            if (stream == AirStream.Lateral && p.x > sag.blade.x - 14f) a *= 0.5f
            val r = if (wide) 2.9f else 2.1f
            out += Particle(p + nrm * off, r * (0.85f + 0.3f * seed), a)
        }
        return out
    }

    private fun frac(x: Float) = x - kotlin.math.floor(x)

    /** A ribbon of width [w] (mm) along a polyline in the plane x. */
    fun line(mb: MeshBuilder, pts: List<P2>, x: Float, color: FloatArray, w: Float, alpha: Float, nx: Float) {
        if (pts.size < 2 || alpha <= 0.003f) return
        mb.kind = Kind.FLAT; mb.glow = 0f
        mb.color(color, alpha)
        val wn = w / Anatomy.MM / 2
        val nrm = Geo.normals(pts, false)
        val base = mb.vertexCount
        for (i in pts.indices) {
            val a = map(pts[i] + nrm[i] * wn); val b = map(pts[i] - nrm[i] * wn)
            mb.vertex(x, a.first, a.second, nx, 0f, 0f)
            mb.vertex(x, b.first, b.second, nx, 0f, 0f)
        }
        for (i in 0 until pts.size - 1) {
            val p = base + 2 * i
            mb.tri(p, p + 1, p + 2); mb.tri(p + 2, p + 1, p + 3)
        }
    }

    /** A soft glow band along a line (on its upper side in the drawing): opaque at the line, gone 2.6 mm away. */
    private fun glowBand(mb: MeshBuilder, pts: List<P2>, x: Float, color: FloatArray, alpha: Float, nx: Float) {
        mb.kind = Kind.FLAT; mb.glow = 0f
        mb.color(color, alpha)
        val nrm = Geo.normals(pts, false)
        val base = mb.vertexCount
        val wn = 2.6f / Anatomy.MM
        for (i in pts.indices) {
            // the side away from the tongue's body (up in the drawing = −y)
            val n = if (nrm[i].y < 0) nrm[i] else P2(-nrm[i].x, -nrm[i].y)
            val a = map(pts[i] - n * 0.6f); val b = map(pts[i] + n * wn)
            mb.vertex(x, a.first, a.second, nx, 0f, 0f, alpha = alpha * 0.85f)
            mb.vertex(x, b.first, b.second, nx, 0f, 0f, alpha = 0f)
        }
        for (i in 0 until pts.size - 1) {
            val p = base + 2 * i
            mb.tri(p, p + 1, p + 2); mb.tri(p + 2, p + 1, p + 3)
        }
    }

    private fun ring(mb: MeshBuilder, c: P2, x: Float, radiusMm: Float, widthMm: Float, color: FloatArray, alpha: Float, nx: Float) {
        val n = 28
        val r = radiusMm / Anatomy.MM
        line(mb, (0..n).map { i -> val t = 2 * PI.toFloat() * i / n; P2(c.x + r * cos(t), c.y + r * sin(t)) }, x, color, widthMm, alpha, nx)
    }

    private fun disc(mb: MeshBuilder, p: P2, x: Float, radiusUnits: Float, color: FloatArray, alpha: Float, right: FloatArray, up: FloatArray) {
        if (alpha <= 0.003f) return
        val (y, z) = map(p)
        mb.color(color, alpha)
        mb.billboard(x, y, z, radiusUnits * Anatomy.MM, right, up)
    }
}
