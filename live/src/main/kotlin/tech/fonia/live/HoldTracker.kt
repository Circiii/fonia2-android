package tech.fonia.live

/**
 * How long the target has been held. The heir of the old `HeldTime` (legacy `SustainExercise`), which added 0.04 s
 * per drawn frame; this one adds real hop time on the analysis thread, so the frame rate does not change the score.
 *
 * A break shorter than [graceSeconds] only pauses the count (the first frames of a hiss often lean the wrong way).
 * A longer break restarts it when [resetsOnBreak], or keeps pausing it otherwise (the old cumulative behaviour).
 * Single owner: the analysis thread.
 */
class HoldTracker(
    private val graceSeconds: Double = 0.15,
    private val resetsOnBreak: Boolean = true,
) {
    /** The current hold. */
    var seconds = 0.0
        private set

    /** The longest hold since [reset]. */
    var bestSeconds = 0.0
        private set

    /** All time on target since [reset], breaks excluded. */
    var totalSeconds = 0.0
        private set

    private var gap = 0.0

    /** Adds [dt] seconds of audio in which the target was [active] or not. Returns the current hold. */
    fun push(active: Boolean, dt: Double): Double {
        if (dt <= 0.0) return seconds
        if (active) {
            gap = 0.0
            seconds += dt
            totalSeconds += dt
            if (seconds > bestSeconds) bestSeconds = seconds
        } else {
            gap += dt
            if (resetsOnBreak && gap > graceSeconds) seconds = 0.0
        }
        return seconds
    }

    fun reset() {
        seconds = 0.0
        bestSeconds = 0.0
        totalSeconds = 0.0
        gap = 0.0
    }
}
