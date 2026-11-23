package tech.fonia.dsp

/**
 * Adaptive noise floor: follows quiet moments quickly and creeps up slowly, so speech never becomes
 * the floor but a noisier room is learnt within a few seconds.
 */
internal class NoiseFloor(initial: Float, private val live: DSPThresholds.Live) {
    var value: Float = initial
        private set
    private var age = 0f

    fun update(levelDb: Float, seconds: Float): Float {
        if (!levelDb.isFinite()) return value
        age += seconds
        val rise = if (age < live.floorLearnSeconds) live.floorLearnRiseDbPerSecond else live.floorRiseDbPerSecond
        if (levelDb < value) {
            value += (levelDb - value) * min(1f, seconds * live.floorFallRate)
        } else {
            value += min(levelDb - value, rise * seconds)
        }
        return value
    }
}

/** Voice activity with hangover: stays on briefly after the level drops. */
internal class VoiceActivity(private val marginDb: Float, private val hangSeconds: Double) {
    var speaking = false
        private set
    private var lastOn = Double.NEGATIVE_INFINITY

    fun update(levelDb: Float, floorDb: Float, now: Double): Boolean {
        if (levelDb > floorDb + marginDb) {
            speaking = true
            lastOn = now
        } else if (now - lastOn > hangSeconds) {
            speaking = false
        }
        return speaking
    }
}
