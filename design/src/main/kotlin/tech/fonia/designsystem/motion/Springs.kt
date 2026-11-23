package tech.fonia.designsystem.motion

import tech.fonia.designsystem.tokens.SpringToken
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Pure-Kotlin motion maths for the living letter (no Android, no allocation). Cue animations are closed-form functions
 * of the time since the cue (deterministic, testable, and "jump to the last frame" for reduced motion is just t = ∞);
 * continuous voice input goes through [Spring1D], integrated with the frame time.
 */
object Motion {
    /**
     * Step response of a unit-mass spring from 0 to 1 after [t] seconds (the curve Compose's `spring()` follows from rest).
     * Under-damped springs overshoot: `bounce` (0.55 / 500) peaks at ~1.13, `reward` (0.45 / 400) at ~1.20.
     */
    fun step(t: Float, dampingRatio: Float, stiffness: Float): Float {
        if (t <= 0f) return 0f
        val w0 = sqrt(stiffness)
        val z = dampingRatio
        return if (z < 1f) {
            val wd = w0 * sqrt(1f - z * z)
            val e = exp(-z * w0 * t)
            1f - e * (cos(wd * t) + (z * w0 / wd) * sin(wd * t))
        } else {
            // critically damped (good enough for z >= 1)
            val e = exp(-w0 * t)
            1f - e * (1f + w0 * t)
        }
    }

    fun step(t: Float, s: SpringToken): Float = step(t, s.dampingRatio, s.stiffness)

    /** Impulse-like "kick" of a spring: starts at 0, swings to 1 and rings back to 0. Peak ≈ 1 near t = π/(2ωd). */
    fun kick(t: Float, dampingRatio: Float, stiffness: Float): Float {
        if (t <= 0f) return 0f
        val w0 = sqrt(stiffness)
        val z = min(dampingRatio, 0.99f)
        val wd = w0 * sqrt(1f - z * z)
        val peakT = (kotlin.math.atan(wd / (z * w0))) / wd
        val norm = exp(-z * w0 * peakT) * sin(wd * peakT)
        return exp(-z * w0 * t) * sin(wd * t) / norm
    }

    fun kick(t: Float, s: SpringToken): Float = kick(t, s.dampingRatio, s.stiffness)

    /** Seconds until a spring step stays within [eps] of its target (envelope bound). */
    fun settleTime(s: SpringToken, eps: Float = 0.002f): Float {
        val w0 = sqrt(s.stiffness)
        val z = min(s.dampingRatio, 1f)
        return (-kotlin.math.ln(eps) / (z * w0)).toFloat()
    }

    /** CubicBezier(x1, y1, x2, y2) easing evaluated at [x] in 0..1 (Newton + bisection, no allocation). */
    fun cubic(x: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
        if (x <= 0f) return 0f
        if (x >= 1f) return 1f
        var t = x
        repeat(6) {
            val cx = bez(t, x1, x2) - x
            val d = bezD(t, x1, x2)
            if (kotlin.math.abs(cx) < 1e-4f) return bez(t, y1, y2)
            if (kotlin.math.abs(d) < 1e-6f) return@repeat
            t -= cx / d
        }
        var lo = 0f; var hi = 1f; t = x
        repeat(20) {
            val v = bez(t, x1, x2)
            if (kotlin.math.abs(v - x) < 1e-4f) return bez(t, y1, y2)
            if (v < x) lo = t else hi = t
            t = (lo + hi) * 0.5f
        }
        return bez(t, y1, y2)
    }

    private fun bez(t: Float, p1: Float, p2: Float): Float {
        val u = 1f - t
        return 3f * u * u * t * p1 + 3f * u * t * t * p2 + t * t * t
    }

    private fun bezD(t: Float, p1: Float, p2: Float): Float {
        val u = 1f - t
        return 3f * u * u * p1 + 6f * u * t * (p2 - p1) + 3f * t * t * (1f - p2)
    }

    /** The `out` token: CubicBezier(0.2, 0.8, 0.3, 1). */
    fun out(x: Float): Float = cubic(x, 0.2f, 0.8f, 0.3f, 1f)

    fun easeInOut(x: Float): Float { val c = clamp01(x); return c * c * (3f - 2f * c) }
    fun easeIn(x: Float): Float { val c = clamp01(x); return c * c * c }
    fun easeOutCubic(x: Float): Float { val c = 1f - clamp01(x); return 1f - c * c * c }

    fun clamp01(x: Float): Float = if (x < 0f) 0f else if (x > 1f) 1f else x
    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
    /** 0 before [a], 1 after [b], linear in between. */
    fun progress(t: Float, a: Float, b: Float): Float = clamp01((t - a) / (b - a))
    /** Bell 0 → 1 → 0 over 0..1 (sine). */
    fun bell(x: Float): Float = if (x <= 0f || x >= 1f) 0f else sin(PI.toFloat() * x)

    /** Smooth deterministic 1-D value noise in -1..1 (for hand-drawn wobble). */
    fun noise1(x: Float, seed: Int): Float {
        val i = kotlin.math.floor(x).toInt()
        val f = x - i
        val a = hash(i, seed)
        val b = hash(i + 1, seed)
        val u = f * f * (3f - 2f * f)
        return a + (b - a) * u
    }

    /** Hash to -1..1. */
    fun hash(i: Int, seed: Int): Float {
        var h = i * 374761393 + seed * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFF) / 32767.5f - 1f
    }

    const val TAU = (2 * PI).toFloat()
}

/**
 * A spring that chases a target, integrated per frame (semi-implicit Euler in ≤ 4 ms sub-steps, stable for any frame
 * time). Used for everything continuous: inflation, roundness, level, the listening lean.
 */
class Spring1D(var dampingRatio: Float, var stiffness: Float, initial: Float = 0f) {
    constructor(s: SpringToken, initial: Float = 0f) : this(s.dampingRatio, s.stiffness, initial)

    var value = initial
    var velocity = 0f
    var target = initial

    fun snap(v: Float) { value = v; target = v; velocity = 0f }

    fun update(dt: Float): Float {
        var left = min(dt, 0.1f)
        val w0 = sqrt(stiffness)
        val c = 2f * dampingRatio * w0
        while (left > 0f) {
            val h = min(left, 0.004f)
            val a = -stiffness * (value - target) - c * velocity
            velocity += a * h
            value += velocity * h
            left -= h
        }
        return value
    }

    val settled: Boolean get() = kotlin.math.abs(value - target) < 1e-3f && kotlin.math.abs(velocity) < 1e-2f
}

/** Utility: `max(a, b)` that reads well in pose code. */
internal fun maxf(a: Float, b: Float) = max(a, b)
