package tech.fonia.audio

import kotlin.math.abs
import kotlin.math.floor

/**
 * Swift's `rounded()`: to the nearest integer, halves away from zero. Kotlin's `round` sends halves to
 * the even neighbour, which would make the hop at 22 050 Hz 220 samples instead of iOS's 221.
 */
internal fun Double.roundedAwayFromZero(): Double {
    if (isNaN() || isInfinite()) return this
    val magnitude = abs(this)
    val whole = floor(magnitude)
    val rounded = if (magnitude - whole >= 0.5) whole + 1 else whole
    return if (this < 0) -rounded else rounded
}
