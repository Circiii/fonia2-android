package tech.fonia.design

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// SwiftUI gives springs as "duration + bounce"; Compose wants a damping ratio and a stiffness. The standard
// conversion (mass 1): stiffness = (2π / duration)², dampingRatio = 1 − bounce for bounce ≥ 0. A spring given
// as "response + dampingFraction" converts the same way, with response in place of duration.

/** `Animation.spring(duration:bounce:)`. */
fun <T> swiftSpring(duration: Double, bounce: Double = 0.0, visibilityThreshold: T? = null): SpringSpec<T> =
    spring(
        dampingRatio = (1.0 - bounce).toFloat(),
        stiffness = springStiffness(duration).toFloat(),
        visibilityThreshold = visibilityThreshold,
    )

/** `Animation.spring(response:dampingFraction:)`. */
fun <T> responseSpring(response: Double, dampingFraction: Double, visibilityThreshold: T? = null): SpringSpec<T> =
    spring(
        dampingRatio = dampingFraction.toFloat(),
        stiffness = springStiffness(response).toFloat(),
        visibilityThreshold = visibilityThreshold,
    )

/** `.snappy`: SwiftUI's preset is `spring(duration: 0.5, bounce: 0.15)`. */
fun <T> snappySpring(): SpringSpec<T> = swiftSpring(0.5, 0.15)

internal fun springStiffness(duration: Double): Double {
    val omega = 2 * PI / duration
    return omega * omega
}

/** CAMediaTimingFunction easeOut, which `Animation.easeOut` uses. */
val EaseOutCurve: Easing = CubicBezierEasing(0f, 0f, 0.58f, 1f)

/** CAMediaTimingFunction easeInEaseOut, which `Animation.easeInOut` uses. */
val EaseInOutCurve: Easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

/** `Animation.easeOut(duration:)`. */
fun <T> easeOut(seconds: Double): TweenSpec<T> = tween((seconds * 1000).roundToInt(), easing = EaseOutCurve)

/** `Animation.easeInOut(duration:)`. */
fun <T> easeInOut(seconds: Double): TweenSpec<T> = tween((seconds * 1000).roundToInt(), easing = EaseInOutCurve)

/** How a whole vector moves to its next value. */
sealed interface VectorMotion {
    /** A spring with the SwiftUI parameters; converted as above. */
    data class Spring(val duration: Double, val bounce: Double) : VectorMotion

    data class Tween(val seconds: Double, val easing: Easing) : VectorMotion
}

/**
 * A vector of floats animated as one value, the way SwiftUI animates a `VectorArithmetic` (`MouthVector`,
 * `SagittalVector`): every channel follows the same spring, an interrupted animation keeps its velocity, and
 * the curve is solved in closed form from the frame time, so it doesn't depend on the frame rate.
 *
 * [value] is state; read it while drawing so a frame only redraws, it doesn't recompose.
 */
@Stable
class AnimatedVector(initial: FloatArray) {
    var value: FloatArray by mutableStateOf(initial.copyOf(), neverEqualPolicy())
        private set

    /** True while an animation runs. State, so a view can switch its clock on and off. */
    var isRunning: Boolean by mutableStateOf(false)
        private set

    private var velocity = FloatArray(initial.size)

    fun snapTo(target: FloatArray) {
        value = target.copyOf()
        velocity = FloatArray(target.size)
    }

    suspend fun animateTo(target: FloatArray, motion: VectorMotion) {
        val start = value.copyOf()
        val v0 = velocity.copyOf()
        if (start.contentEquals(target) && v0.all { it == 0f }) return
        isRunning = true
        try {
            val began = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                val t = (now - began) / 1e9
                val done = when (motion) {
                    is VectorMotion.Spring -> stepSpring(start, v0, target, motion, t)
                    is VectorMotion.Tween -> stepTween(start, target, motion, t)
                }
                if (done) break
            }
            value = target.copyOf()
            velocity = FloatArray(target.size)
        } finally {
            isRunning = false
        }
    }

    private fun stepTween(start: FloatArray, target: FloatArray, motion: VectorMotion.Tween, t: Double): Boolean {
        val f = if (motion.seconds <= 0) 1f else motion.easing.transform((t / motion.seconds).toFloat().coerceIn(0f, 1f))
        value = FloatArray(target.size) { start[it] + (target[it] - start[it]) * f }
        velocity = FloatArray(target.size)
        return t >= motion.seconds
    }

    private fun stepSpring(start: FloatArray, v0: FloatArray, target: FloatArray, motion: VectorMotion.Spring, t: Double): Boolean {
        val omega = sqrt(springStiffness(motion.duration))
        val zeta = 1.0 - motion.bounce
        val out = FloatArray(target.size)
        val vel = FloatArray(target.size)
        var settled = true
        for (i in target.indices) {
            val x0 = (start[i] - target[i]).toDouble()
            val u0 = v0[i].toDouble()
            val x: Double
            val v: Double
            if (zeta < 1) {
                val wd = omega * sqrt(1 - zeta * zeta)
                val e = exp(-zeta * omega * t)
                val b = (u0 + zeta * omega * x0) / wd
                val c = cos(wd * t)
                val s = sin(wd * t)
                x = e * (x0 * c + b * s)
                v = -zeta * omega * x + e * (-x0 * wd * s + b * wd * c)
            } else if (zeta == 1.0) {
                val e = exp(-omega * t)
                val b = u0 + omega * x0
                x = (x0 + b * t) * e
                v = b * e - omega * x
            } else {
                val root = sqrt(zeta * zeta - 1)
                val r1 = -omega * (zeta - root)
                val r2 = -omega * (zeta + root)
                val c1 = (u0 - r2 * x0) / (r1 - r2)
                val c2 = x0 - c1
                x = c1 * exp(r1 * t) + c2 * exp(r2 * t)
                v = c1 * r1 * exp(r1 * t) + c2 * r2 * exp(r2 * t)
            }
            out[i] = (target[i] + x).toFloat()
            vel[i] = v.toFloat()
            val scale = max(1.0, abs(target[i].toDouble()))
            if (abs(x) > 1e-4 * scale || abs(v) > 1e-3 * scale) settled = false
        }
        value = out
        velocity = vel
        return settled || t > 5.0
    }
}
