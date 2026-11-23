package tech.fonia.face

import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.sqrt

/** One moment of a talk. `time` is in seconds, from any fixed starting point. */
data class PresenceSample(
    val time: Double,
    val isPresent: Boolean = true,
    val gazeYaw: Float = 0f,
    val gazePitch: Float = 0f,
    val headYaw: Float = 0f,
    val headPitch: Float = 0f,
) {
    constructor(levels: FaceLevels, time: Double) : this(
        time = time,
        isPresent = levels.isPresent,
        gazeYaw = levels.gazeYaw,
        gazePitch = levels.gazePitch,
        headYaw = levels.headYaw,
        headPitch = levels.headPitch,
    )
}

/**
 * Plain facts about where the speaker looked and how much the head moved.
 *
 * It says nothing about emotion, confidence or expressiveness and reads no smile or brow data:
 * EU law bans inferring emotions in education.
 */
data class PresenceReport(
    val seconds: Double = 0.0,
    /** Share of the time the face was visible. */
    val faceVisibleFraction: Double = 0.0,
    /** Share of the visible time spent looking at the camera. */
    val eyeContactFraction: Double = 0.0,
    val eyeContactSeconds: Double = 0.0,
    val longestLookAwaySeconds: Double = 0.0,
    /** Spread of the head's direction over the talk, in degrees. */
    val headMovementDegrees: Double = 0.0,
    /** Share of eye contact in each second of the talk. Null for a second with no face. */
    val timeline: List<Double?> = emptyList(),
) {
    /**
     * Sentences to show the speaker. Empty when there was nothing to measure.
     * @param eyeGazeAvailable false when the source only knows where the head points.
     */
    fun facts(eyeGazeAvailable: Boolean = true): List<String> {
        if (!(eyeContactSeconds > 0 || headMovementDegrees > 0)) return emptyList()
        val percent = SwiftMath.rounded(eyeContactFraction * 100).toInt()
        val verb = if (eyeGazeAvailable) "Ai privit" else "Ai stat cu fața"
        val facts = mutableListOf("$verb spre cameră $percent% din timp.")
        val longest = SwiftMath.rounded(longestLookAwaySeconds).toInt()
        if (longest > 0) {
            val duration = romanianCount(longest, one = "secundă", few = "secunde")
            facts.add("Te-ai uitat în altă parte cel mult $duration la rând.")
        }
        val movement = romanianCount(SwiftMath.rounded(headMovementDegrees).toInt(), one = "grad", few = "grade")
        facts.add("Ai mișcat capul cam $movement.")
        return facts
    }
}

/** Romanian plural: 1 secundă, 0 or 2 to 19 secunde, 20 de secunde, 101 secunde. */
internal fun romanianCount(count: Int, one: String, few: String): String {
    if (count == 1) return "1 $one"
    if (count == 0 || (count % 100) in 1..19) return "$count $few"
    return "$count de $few"
}

/**
 * Collects face readings during a talk and summarises them.
 *
 * Eye contact is measured against the first moments of the talk, when the speaker looks at the
 * camera: phones are held at very different angles, so an absolute "straight ahead" would be wrong
 * for most people.
 *
 * A struct with `mutating` methods on iOS; a class here, so a copy shares its samples.
 */
class PresenceTracker {
    private class Gaze(val yaw: Double, val pitch: Double)

    private val samples = mutableListOf<PresenceSample>()
    private var baseline: Gaze? = null
    private var calibrationDecided = false

    fun add(sample: PresenceSample) {
        samples.add(sample)
        val first = samples.firstOrNull()
        if (calibrationDecided || first == null) return
        if (!(sample.time - first.time >= CALIBRATION_SECONDS)) return

        calibrationDecided = true
        val early = samples.filter { it.isPresent && it.time - first.time <= CALIBRATION_SECONDS }
        if (early.size < MINIMUM_CALIBRATION_SAMPLES) return
        baseline = Gaze(
            yaw = median(early.map { it.gazeYaw.toDouble() }),
            pitch = median(early.map { it.gazePitch.toDouble() }),
        )
    }

    /** Live reading for the latest sample. Null while calibrating or when no face is visible. */
    val isLookingAtCameraNow: Boolean?
        get() {
            val last = samples.lastOrNull() ?: return null
            val baseline = baseline ?: return null
            if (!last.isPresent) return null
            return isContact(last, baseline)
        }

    fun report(): PresenceReport {
        if (samples.size < 2) return PresenceReport()
        val first = samples.first()
        val last = samples.last()
        val seconds = last.time - first.time

        var visibleSeconds = 0.0
        for (k in 0 until samples.size - 1) {
            if (samples[k].isPresent) visibleSeconds += samples[k + 1].time - samples[k].time
        }
        val faceVisibleFraction = if (seconds > 0) visibleSeconds / seconds else 0.0

        val baseline = baseline
        if (baseline == null || !(visibleSeconds > 0)) {
            return PresenceReport(seconds = seconds, faceVisibleFraction = faceVisibleFraction)
        }

        var longestAway = 0.0
        var awaySince: Double? = null
        for (sample in samples) {
            val away = !sample.isPresent || !isContact(sample, baseline)
            if (away && awaySince == null) awaySince = sample.time
            val since = awaySince
            if (!away && since != null) {
                longestAway = SwiftMath.max(longestAway, sample.time - since)
                awaySince = null
            }
        }
        awaySince?.let { longestAway = SwiftMath.max(longestAway, last.time - it) }

        var contactSeconds = 0.0
        for (k in 0 until samples.size - 1) {
            val sample = samples[k]
            if (sample.isPresent && isContact(sample, baseline)) contactSeconds += samples[k + 1].time - sample.time
        }

        val visible = samples.filter { it.isPresent }
        val headMovementDegrees = hypot(
            standardDeviation(visible.map { it.headYaw.toDouble() }),
            standardDeviation(visible.map { it.headPitch.toDouble() }),
        )

        val bins = maxOf(1, ceil(seconds).toInt())
        val contact = IntArray(bins)
        val seen = IntArray(bins)
        for (sample in visible) {
            val bin = minOf(bins - 1, (sample.time - first.time).toInt())
            seen[bin] += 1
            if (isContact(sample, baseline)) contact[bin] += 1
        }

        return PresenceReport(
            seconds = seconds,
            faceVisibleFraction = faceVisibleFraction,
            eyeContactFraction = contactSeconds / visibleSeconds,
            eyeContactSeconds = contactSeconds,
            longestLookAwaySeconds = longestAway,
            headMovementDegrees = headMovementDegrees,
            timeline = (0 until bins).map { if (seen[it] > 0) contact[it].toDouble() / seen[it].toDouble() else null },
        )
    }

    private fun isContact(sample: PresenceSample, baseline: Gaze): Boolean {
        val yaw = sample.gazeYaw.toDouble() - baseline.yaw
        val pitch = (sample.gazePitch.toDouble() - baseline.pitch) * 1.2
        return hypot(yaw, pitch) < CONTACT_DEGREES
    }

    private companion object {
        const val CONTACT_DEGREES = 13.0
        const val CALIBRATION_SECONDS = 1.5
        const val MINIMUM_CALIBRATION_SAMPLES = 5

        fun median(values: List<Double>): Double {
            if (values.isEmpty()) return 0.0
            return values.sortedWith { a, b -> if (a < b) -1 else if (b < a) 1 else 0 }[values.size / 2]
        }

        fun standardDeviation(values: List<Double>): Double {
            if (values.size < 2) return 0.0
            var sum = 0.0
            for (v in values) sum += v
            val mean = sum / values.size.toDouble()
            var squares = 0.0
            for (v in values) squares += (v - mean) * (v - mean)
            return sqrt(squares / values.size.toDouble())
        }
    }
}
