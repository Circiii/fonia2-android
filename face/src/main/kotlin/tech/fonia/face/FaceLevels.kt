package tech.fonia.face

import kotlin.math.sqrt

/**
 * One reading of the face. Blendshape-style values run 0...1, angles are in degrees.
 *
 * Angles are measured from the camera axis: 0 means pointing straight at the camera, positive yaw
 * is toward the viewer's right on the unmirrored image, positive pitch is up.
 */
data class FaceFrame(
    val isPresent: Boolean = true,
    val jawOpen: Float = 0f,
    val smile: Float = 0f,
    val funnel: Float = 0f,
    val pucker: Float = 0f,
    val puff: Float = 0f,
    val tongueOut: Float = 0f,
    val gazeYaw: Float = 0f,
    val gazePitch: Float = 0f,
    val headYaw: Float = 0f,
    val headPitch: Float = 0f,
) {
    /** Lip rounding, whichever of the two shapes is stronger. */
    val round: Float
        get() = SwiftMath.max(funnel, pucker)

    /**
     * 1 when the gaze points straight at the camera, falling to 0 at 30 degrees away. Not relative
     * to how the phone is held: [PresenceTracker] handles that for reports.
     */
    val gazeAtCamera: Float
        get() {
            if (!isPresent) return 0f
            return SwiftMath.max(0f, 1f - sqrt(gazeYaw * gazeYaw + gazePitch * gazePitch) / 30f)
        }

    companion object {
        val ABSENT = FaceFrame(isPresent = false)
    }
}

/**
 * Latest face values for the screen to pull once per frame, the same pattern as the audio levels.
 * Written by the camera work off the main thread, read from anywhere, not observable on purpose.
 *
 * Fields are independent, so a reader can see parts of two neighbouring frames. That is invisible
 * at display rate. Swift uses relaxed atomics on the bit patterns; volatile fields are the JVM's
 * nearest, a little stronger, and available on every Android version.
 */
class FaceLevels {
    @Volatile private var present = false

    @Volatile private var jawOpenValue = 0f

    @Volatile private var smileValue = 0f

    @Volatile private var funnelValue = 0f

    @Volatile private var puckerValue = 0f

    @Volatile private var puffValue = 0f

    @Volatile private var tongueOutValue = 0f

    @Volatile private var gazeYawValue = 0f

    @Volatile private var gazePitchValue = 0f

    @Volatile private var headYawValue = 0f

    @Volatile private var headPitchValue = 0f

    fun publish(frame: FaceFrame) {
        jawOpenValue = frame.jawOpen
        smileValue = frame.smile
        funnelValue = frame.funnel
        puckerValue = frame.pucker
        puffValue = frame.puff
        tongueOutValue = frame.tongueOut
        gazeYawValue = frame.gazeYaw
        gazePitchValue = frame.gazePitch
        headYawValue = frame.headYaw
        headPitchValue = frame.headPitch
        present = frame.isPresent
    }

    val isPresent: Boolean get() = present
    val jawOpen: Float get() = jawOpenValue
    val smile: Float get() = smileValue
    val funnel: Float get() = funnelValue
    val pucker: Float get() = puckerValue
    val puff: Float get() = puffValue
    val tongueOut: Float get() = tongueOutValue
    val gazeYaw: Float get() = gazeYawValue
    val gazePitch: Float get() = gazePitchValue
    val headYaw: Float get() = headYawValue
    val headPitch: Float get() = headPitchValue

    val round: Float
        get() = SwiftMath.max(funnel, pucker)

    val gazeAtCamera: Float
        get() = snapshot().gazeAtCamera

    fun snapshot(): FaceFrame = FaceFrame(
        isPresent = isPresent,
        jawOpen = jawOpen,
        smile = smile,
        funnel = funnel,
        pucker = pucker,
        puff = puff,
        tongueOut = tongueOut,
        gazeYaw = gazeYaw,
        gazePitch = gazePitch,
        headYaw = headYaw,
        headPitch = headPitch,
    )
}

/** Swift's `min`, `max` and `rounded()`, which differ from Kotlin's on NaN, on the zeros and on halves. */
internal object SwiftMath {
    fun min(x: Float, y: Float): Float = if (y < x) y else x

    fun max(x: Float, y: Float): Float = if (y >= x) y else x

    fun min(x: Double, y: Double): Double = if (y < x) y else x

    fun max(x: Double, y: Double): Double = if (y >= x) y else x

    fun minOrNull(values: List<Float>): Float? {
        if (values.isEmpty()) return null
        var result = values[0]
        for (k in 1 until values.size) if (values[k] < result) result = values[k]
        return result
    }

    fun maxOrNull(values: List<Float>): Float? {
        if (values.isEmpty()) return null
        var result = values[0]
        for (k in 1 until values.size) if (result < values[k]) result = values[k]
        return result
    }

    /** `rounded()`: halves away from zero (Kotlin's `round` goes to even). */
    fun rounded(x: Double): Double {
        if (!x.isFinite()) return x
        val whole = kotlin.math.truncate(x)
        return if (kotlin.math.abs(x - whole) >= 0.5) whole + kotlin.math.sign(x) else whole
    }
}
