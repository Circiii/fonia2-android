package tech.fonia.mouth3d

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A critically damped spring per channel (docs/v2/04 §9), integrated semi-implicitly with sub-steps of at most
 * [MAX_STEP] and `dt` capped at [MAX_DT], so a dropped frame never makes the mouth jump or ring.
 */
class SpringVector(size: Int) {
    val value = FloatArray(size)
    val velocity = FloatArray(size)

    fun snap(target: FloatArray) {
        target.copyInto(value); velocity.fill(0f)
    }

    fun step(target: FloatArray, omega: Float, dt: Float) {
        var left = min(dt, MAX_DT)
        while (left > 1e-6f) {
            val h = min(left, MAX_STEP)
            for (i in value.indices) {
                val a = omega * omega * (target[i] - value[i]) - 2f * omega * velocity[i]
                velocity[i] += a * h
                value[i] += velocity[i] * h
            }
            left -= h
        }
    }

    /** Reduce-motion variant: straight line, reaching the target in [duration] seconds, no overshoot. */
    fun linear(target: FloatArray, dt: Float, duration: Float, span: FloatArray) {
        for (i in value.indices) {
            val maxStep = span[i] * min(dt, MAX_DT) / duration
            val d = target[i] - value[i]
            value[i] += d.coerceIn(-maxStep, maxStep)
            velocity[i] = 0f
        }
    }

    fun settled(target: FloatArray, eps: Float = 1e-3f): Boolean =
        value.indices.all { abs(target[it] - value[it]) < eps && abs(velocity[it]) < eps * 10 }

    companion object {
        const val MAX_DT = 1f / 20f
        const val MAX_STEP = 1f / 240f
    }
}

/**
 * Finger rotation (docs/v2/04 §9): drag ±60° sideways, ±25° up/down, elastic return to the preset 2 s after the finger
 * lifts. Auto-rotation is a gentle sway (the back of the mouth is open, so never a full turn).
 */
class Orbit {
    var yaw = 0f; private set
    var pitch = 0f; private set
    private var yawVel = 0f
    private var pitchVel = 0f
    private var idle = 0f
    var dragging = false; private set

    fun drag(dYawDeg: Float, dPitchDeg: Float) {
        dragging = true; idle = 0f
        yaw = (yaw + dYawDeg).coerceIn(-MAX_YAW, MAX_YAW)
        pitch = (pitch + dPitchDeg).coerceIn(-MAX_PITCH, MAX_PITCH)
        yawVel = 0f; pitchVel = 0f
    }

    fun release() { dragging = false; idle = 0f }

    /** Returns true while the orbit is still moving (or waiting to return). */
    fun step(dt: Float, reduceMotion: Boolean): Boolean {
        if (dragging) return false
        if (yaw == 0f && pitch == 0f) return false
        idle += min(dt, SpringVector.MAX_DT)
        if (idle < RETURN_AFTER) return true
        if (reduceMotion) {
            yaw = 0f; pitch = 0f; return false
        }
        val h = min(dt, SpringVector.MAX_DT)
        yawVel += (-RETURN_OMEGA * RETURN_OMEGA * yaw - 2 * RETURN_OMEGA * yawVel) * h
        pitchVel += (-RETURN_OMEGA * RETURN_OMEGA * pitch - 2 * RETURN_OMEGA * pitchVel) * h
        yaw += yawVel * h; pitch += pitchVel * h
        if (abs(yaw) < 0.05f && abs(pitch) < 0.05f && abs(yawVel) < 0.5f && abs(pitchVel) < 0.5f) {
            yaw = 0f; pitch = 0f; yawVel = 0f; pitchVel = 0f; return false
        }
        return true
    }

    companion object {
        const val MAX_YAW = 100f
        const val MAX_PITCH = 25f
        const val RETURN_AFTER = 2f
        const val RETURN_OMEGA = 5f
        const val SWAY_DEG = 28f
        const val SWAY_HZ = 0.09f
    }
}

/** What the scene asks for this frame. */
data class SceneInputs(
    val pose: PoseSpec,
    val rest: PoseSpec,
    /** 0 = rest, 1 = the target pose (the lesson's slider / the timeline). */
    val progress: Float,
    /** The cut-away is wanted *and* allowed (approved, or the review override). */
    val inside: Boolean,
    val playing: Boolean = true,
    val autoRotate: Boolean = false,
    val reduceMotion: Boolean = false,
    /** The channel groups that move toward the pose; the others stay at rest (a guided, step-by-step reveal). */
    val groups: Set<PoseGroup> = PoseGroup.ALL,
    /** The part the guide's current step is about: it glows (see [Articulator]). */
    val highlight: Articulator? = null,
    /** The cut-away may open when the child turns the mouth past [MouthScene.CUT_YAW] (the pose is approved, or review). */
    val cutAllowed: Boolean = false,
)

/**
 * The parts a guided sequence moves one after the other: the lips (round, spread), the jaw and teeth (jaw open, lips
 * parted so the teeth show) and the tongue. [SceneInputs.groups] lists the ones that have moved so far.
 */
enum class PoseGroup {
    Lips, Jaw, Tongue;

    companion object {
        val ALL: Set<PoseGroup> = entries.toSet()

        fun of(c: PoseChannel): PoseGroup = when (c) {
            PoseChannel.LipRound, PoseChannel.LipSpread -> Lips
            PoseChannel.JawOpen, PoseChannel.LipPart -> Jaw
            else -> Tongue
        }

        fun of(key: String): PoseGroup? = entries.firstOrNull { it.name.equals(key, ignoreCase = true) }
    }
}

/** The pose and camera to draw. */
class SceneFrame(
    val pose: Pose,
    val yawDeg: Float,
    val pitchDeg: Float,
    /** 0 front … 1 inside (the clip plane and the camera move together). */
    val insideAmount: Float,
    val zoom: Float,
    /** False once nothing moves: the renderer stops asking for frames. */
    val animating: Boolean,
    /** Seconds since the scene started (air, voice and glow pulses). */
    val timeSec: Float = 0f,
    /** A stop sound (Ț, CE, GE) is in its closure: the air waits behind the tongue. */
    val closure: Boolean = false,
    /** Seconds since the last release of a closure. */
    val sinceRelease: Float = 10f,
    /** −1…1, the slow idle breathing (0 with reduce motion). */
    val breath: Float = 0f,
    /** Only the idle (breathing, air, glow) moves: the renderer may run at a lower frame rate. */
    val ambient: Boolean = false,
    /** 0 closed … 1 the cut plane at the midline (the near cheek slides away from the profile). */
    val cutAmount: Float = insideAmount,
)

/**
 * The pure heart of [Mouth3D]: springs toward `lerp(rest, pose(t), progress)`, the key animation, the flutter, the
 * inside transition and the orbit. Runs on the GL thread (or the 2D fallback's frame loop); tested on the JVM.
 */
class MouthScene {
    private val n = PoseChannel.entries.size
    private val springs = SpringVector(n)
    private val inside = SpringVector(1)
    private val cut = SpringVector(1)
    private val cutTarget = FloatArray(1)
    private val one = floatArrayOf(1f)
    private val groupOf = Array(n) { PoseGroup.of(PoseChannel.entries[it]) }
    private val span = FloatArray(n) { PoseChannel.entries[it].max - PoseChannel.entries[it].min }
    private val target = FloatArray(n)
    val orbit = Orbit()
    private var time = 0f
    private var animTime = 0f
    private var lastPoseKey: String? = null
    private var started = false
    private var swayTime = 0f
    private var lastKey: PoseKey? = null
    private var sinceKey = 10f
    private var cutStarted = false

    fun step(dt: Float, input: SceneInputs): SceneFrame {
        val d = max(0f, dt)
        time += d
        if (input.pose.key != lastPoseKey) { animTime = 0f; lastPoseKey = input.pose.key }
        val anim = input.pose.animation
        val animated = anim != null && input.playing && !input.reduceMotion && input.progress > 0f
        if (animated) animTime += d
        val key = if (animated) anim!!.keyAt(animTime) else null
        if (key !== lastKey) { sinceKey = 0f; lastKey = key } else sinceKey += d
        val goal = key?.pose ?: input.pose.pose
        val p = input.progress.coerceIn(0f, 1f)
        val restV = input.rest.pose.v
        for (i in 0 until n) {
            val pi = if (groupOf[i] in input.groups) p else 0f
            target[i] = restV[i] + (goal.v[i] - restV[i]) * pi
        }
        val insideTarget = floatArrayOf(if (input.inside) 1f else 0f)
        if (!started) {
            springs.snap(target); inside.snap(insideTarget); started = true
        } else if (input.reduceMotion) {
            springs.linear(target, d, REDUCED_SEC, span)
            inside.linear(insideTarget, d, REDUCED_SEC, floatArrayOf(1f))
        } else {
            springs.step(target, (key?.speed ?: SpringSpeed.Normal).omega, d)
            inside.step(insideTarget, INSIDE_OMEGA, d)
        }
        val out = springs.value.copyOf()
        // a slow breath: the jaw drops a hair and comes back (the tongue rides it)
        val breath = if (input.reduceMotion) 0f else sin(2.0 * Math.PI * BREATH_HZ * time).toFloat()
        out[PoseChannel.JawOpen.ordinal] = (out[PoseChannel.JawOpen.ordinal] + BREATH_JAW * (1f + breath)).coerceIn(0f, 1f)
        val flutter = input.pose.flutter
        val fluttering = flutter != null && input.playing && !input.reduceMotion && p > 0f
        if (fluttering) {
            val c = flutter!!.channel
            out[c.ordinal] = (out[c.ordinal] + flutter.offset(time) * p).coerceIn(c.min, c.max)
        }
        val orbitMoving = orbit.step(d, input.reduceMotion)
        val swaying = input.autoRotate && !input.reduceMotion && !orbit.dragging
        if (swaying) swayTime += d
        val sway = if (input.autoRotate && !input.reduceMotion) Orbit.SWAY_DEG * sin(2.0 * Math.PI * Orbit.SWAY_HZ * swayTime).toFloat() else 0f
        val ia = inside.value[0].coerceIn(0f, 1f)
        val front = input.pose.front
        val ins = input.pose.inside
        val yaw = front.yawDeg + (ins.yawDeg - front.yawDeg) * ia + orbit.yaw + sway * (1 - 0.5f * ia)
        // the cut opens at the profile: asked for (inside), or the child turned the mouth far enough
        cutTarget[0] = if ((input.inside || input.cutAllowed) && abs(yaw) >= CUT_YAW) 1f else 0f
        if (!cutStarted) { cut.snap(cutTarget); cutStarted = true }
        else if (input.reduceMotion) cut.linear(cutTarget, d, REDUCED_SEC, one)
        else cut.step(cutTarget, CUT_OMEGA, d)
        val settled = springs.settled(target) && inside.settled(insideTarget) && cut.settled(cutTarget)
        val closure = animated && input.pose.air?.stop == true && key === anim!!.keys.first()
        val moving = !settled || animated || fluttering || orbitMoving || swaying || orbit.dragging
        return SceneFrame(
            pose = Pose(out),
            yawDeg = yaw,
            pitchDeg = front.pitchDeg + (ins.pitchDeg - front.pitchDeg) * ia + orbit.pitch,
            insideAmount = ia,
            zoom = front.zoom + (ins.zoom - front.zoom) * ia,
            animating = moving || !input.reduceMotion,
            timeSec = time,
            closure = closure,
            sinceRelease = if (animated && !closure) sinceKey else 10f,
            breath = breath,
            ambient = !moving,
            cutAmount = cut.value[0].coerceIn(0f, 1f),
        )
    }

    companion object {
        const val REDUCED_SEC = 0.15f
        const val INSIDE_OMEGA = 7f
        /** From this yaw on (either side) the cut-away may open. */
        const val CUT_YAW = 80f
        const val CUT_OMEGA = 6f
        const val BREATH_HZ = 0.22f
        const val BREATH_JAW = 0.012f
    }
}
