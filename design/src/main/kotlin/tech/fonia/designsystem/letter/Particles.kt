package tech.fonia.designsystem.letter

import tech.fonia.designsystem.motion.Motion
import tech.fonia.designsystem.tokens.FoniaMotion
import kotlin.math.cos
import kotlin.math.sin

/**
 * A fixed pool of particles (≤ 150, plan §10), in pixels, struct-of-arrays, no allocation after construction. The
 * emitter for the outline burst (POP), the little stars (Correct) and confetti; drawn by the painter.
 */
class Particles(val capacity: Int = FoniaMotion.PARTICLES_MAX) {
    val x = FloatArray(capacity)
    val y = FloatArray(capacity)
    val vx = FloatArray(capacity)
    val vy = FloatArray(capacity)
    val rot = FloatArray(capacity)
    val vrot = FloatArray(capacity)
    val age = FloatArray(capacity)
    val life = FloatArray(capacity)
    val size = FloatArray(capacity)
    val gravity = FloatArray(capacity)
    val drag = FloatArray(capacity)
    /** [KIND_DOT], [KIND_STAR], [KIND_HEART], [KIND_CONFETTI]. */
    val kind = IntArray(capacity)
    /** Index into the painter's particle colours: 0 sun, 1 voice, 2 parrot, 3 surface, 4 sky. */
    val color = IntArray(capacity)
    var count = 0
        private set

    private var seed = 1

    fun clear() { count = 0 }

    val alive: Boolean get() = count > 0

    /** Random in 0..1 (deterministic per pool, so the demo films the same every time). */
    fun rnd(): Float {
        seed = seed * 1103515245 + 12345
        return ((seed ushr 8) and 0xFFFF) / 65535f
    }

    fun spawn(
        px: Float, py: Float, pvx: Float, pvy: Float, psize: Float, plife: Float, pkind: Int, pcolor: Int,
        pgravity: Float, pdrag: Float = 1.2f, pvrot: Float = 0f,
    ) {
        if (count >= capacity) return
        val i = count++
        x[i] = px; y[i] = py; vx[i] = pvx; vy[i] = pvy; size[i] = psize; life[i] = plife; age[i] = 0f
        kind[i] = pkind; color[i] = pcolor; gravity[i] = pgravity; drag[i] = pdrag; rot[i] = rnd() * 360f; vrot[i] = pvrot
    }

    /** A ring of [n] particles flying out from ([cx], [cy]) between [a0] and [a1] radians (screen angles, y down). */
    fun burst(cx: Float, cy: Float, n: Int, a0: Float, a1: Float, speed: Float, sizePx: Float, lifeS: Float, kindMix: IntArray, colorMix: IntArray, gravityPx: Float) {
        for (k in 0 until n) {
            val a = a0 + (a1 - a0) * ((k + rnd() * 0.8f) / n)
            val sp = speed * (0.55f + 0.6f * rnd())
            spawn(
                cx, cy, cos(a) * sp, sin(a) * sp, sizePx * (0.7f + 0.6f * rnd()), lifeS * (0.8f + 0.4f * rnd()),
                kindMix[k % kindMix.size], colorMix[(k * 7 + 3) % colorMix.size], gravityPx, 1.6f, (rnd() - 0.5f) * 540f,
            )
        }
    }

    fun update(dt: Float) {
        var i = 0
        while (i < count) {
            age[i] += dt
            if (age[i] >= life[i]) {
                val last = --count
                if (i != last) copy(last, i)
                continue
            }
            val d = 1f - Motion.clamp01(drag[i] * dt)
            vx[i] *= d
            vy[i] = vy[i] * d + gravity[i] * dt
            x[i] += vx[i] * dt
            y[i] += vy[i] * dt
            rot[i] += vrot[i] * dt
            i++
        }
    }

    private fun copy(from: Int, to: Int) {
        x[to] = x[from]; y[to] = y[from]; vx[to] = vx[from]; vy[to] = vy[from]; rot[to] = rot[from]; vrot[to] = vrot[from]
        age[to] = age[from]; life[to] = life[from]; size[to] = size[from]; gravity[to] = gravity[from]; drag[to] = drag[from]
        kind[to] = kind[from]; color[to] = color[from]
    }

    companion object {
        const val KIND_DOT = 0
        const val KIND_STAR = 1
        const val KIND_HEART = 2
        const val KIND_CONFETTI = 3
    }
}
