package tech.fonia.designsystem.foni

import tech.fonia.designsystem.motion.Motion
import tech.fonia.designsystem.motion.Spring1D
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where every part of Foni is at one moment, in the bird's own frame (the art faces left). Angles in degrees,
 * positive = clockwise on screen: `head` > 0 lifts the beak, `lean` < 0 tips him forward, `wing` < 0 raises the wing
 * up and back (the wave), `wing` > 0 swings it forward across the chest (pointing). Lengths in grid units (the art's
 * 35 × 35 grid) unless named otherwise.
 */
class FoniPose {
    var head = 0f
    var lean = 0f
    /** Lift off the ground, fraction of the square. */
    var rise = 0f
    /** > 0 squashed (wider, shorter), < 0 stretched. */
    var squash = 0f
    var breath = 0f
    var wing = 0f
    var wingLift = 0f
    var tail = 0f
    /** Beak opening, degrees of the lower beak. */
    var beak = 0f
    /** 0 open … 1 shut, for a blink (the open eye squashes). */
    var blink = 0f
    /** Calm closed eyes (sleep). */
    var closed = 0f
    /** Smiling eyes (^ ^). */
    var smile = 0f
    var eyeScale = 1f
    var eyeDx = 0f
    var eyeDy = 0f
    /** Zzz opacity. */
    var zzz = 0f
    /** Thought bubbles: 0 hidden, else 1 + phase (0..1) of the cycle. */
    var dots = 0f
    /** Twinkling stars: 0 hidden, else intensity. */
    var sparkle = 0f
    var sparklePhase = 0f
    /** Overall size (the entrance pop). */
    var scale = 1f
    /** -1…1: 1 faces left (as drawn), -1 faces right; in between he is turning. */
    var facing = 1f

    fun clear() {
        head = 0f; lean = 0f; rise = 0f; squash = 0f; breath = 0f; wing = 0f; wingLift = 0f; tail = 0f; beak = 0f
        blink = 0f; closed = 0f; smile = 0f; eyeScale = 1f; eyeDx = 0f; eyeDy = 0f; zzz = 0f; dots = 0f; sparkle = 0f
        sparklePhase = 0f; scale = 1f; facing = 1f
    }

    fun lerpFrom(a: FoniPose, b: FoniPose, w: Float) {
        fun l(x: Float, y: Float) = x + (y - x) * w
        head = l(a.head, b.head); lean = l(a.lean, b.lean); rise = l(a.rise, b.rise); squash = l(a.squash, b.squash)
        breath = l(a.breath, b.breath); wing = l(a.wing, b.wing); wingLift = l(a.wingLift, b.wingLift); tail = l(a.tail, b.tail)
        beak = l(a.beak, b.beak); blink = l(a.blink, b.blink); closed = l(a.closed, b.closed); smile = l(a.smile, b.smile)
        eyeScale = l(a.eyeScale, b.eyeScale); eyeDx = l(a.eyeDx, b.eyeDx); eyeDy = l(a.eyeDy, b.eyeDy); zzz = l(a.zzz, b.zzz)
        val wc = Motion.clamp01(w)
        dots = if (wc >= 0.5f) b.dots else a.dots
        sparkle = a.sparkle * (1f - wc) + b.sparkle * wc
        sparklePhase = if (b.sparkle > 0f) b.sparklePhase else a.sparklePhase
    }
}

/**
 * Foni's motion: every mood is a pure function of time (so nothing drifts and reduced motion is "time stands still"),
 * moods cross-fade through a soft spring (both keep moving while they blend), and only the continuous inputs — the
 * voice, where he looks, which way he faces — go through springs. Pure Kotlin, no allocation per frame.
 */
class FoniRig(private val seed: Float = 11f) {
    val pose = FoniPose()
    private val from = FoniPose()
    private val to = FoniPose()

    var reduceMotion = false
    private var mood = FoniMood.Idle
    private var prevMood = FoniMood.Idle
    private var moodStart = -100f
    private var prevStart = -100f
    private var time = 0f
    private var started = false
    private var appearAt = Float.NaN

    private var voice = 0f
    private var lastVoiceAt = -100f
    private val eyeX = Spring1D(0.9f, 260f)
    private val eyeY = Spring1D(0.9f, 260f)
    private val face = Spring1D(0.62f, 230f, 1f)
    private var faceTarget = 1f

    /** The mood shown now (the one blending in). */
    val currentMood: FoniMood get() = mood

    fun setMood(m: FoniMood) {
        if (m == mood) return
        prevMood = mood
        prevStart = moodStart
        mood = m
        moodStart = time
    }

    /** Starts the entrance pop (once the art has loaded). */
    fun appear() { appearAt = time }

    /**
     * Advances by [dt] seconds. [voiceLevel] 0–1 (read by the caller in the draw phase); [lookX]/[lookY] the direction
     * to look at, scaled by how far it is (-1…1 each, screen axes); [lookSide] the target's horizontal offset from Foni's
     * centre in sizes (NaN = no target).
     */
    fun update(dt: Float, voiceLevel: Float, lookX: Float = 0f, lookY: Float = 0f, lookSide: Float = Float.NaN) {
        val step = if (started) dt.coerceIn(0f, 0.1f) else 0f
        // the first mood plays from its start (a wave, the cheer) without blending in from Idle
        if (!started) { started = true; prevMood = mood; moodStart = time; prevStart = time }
        time += step

        // voice: in fast, out slow (as on iOS: 0.35 up, 0.08 down per 60 Hz frame), frame-rate independent
        val v = Motion.clamp01(voiceLevel)
        val k = 1f - (1f - (if (v > voice) 0.35f else 0.08f)).toDouble().pow((step * 60f).toDouble()).toFloat()
        voice += (v - voice) * k
        if (v > 0.03f) lastVoiceAt = time

        // facing: turn towards a target that is clearly on the other side
        val hasLook = !lookSide.isNaN()
        // (pointing is the other way round: the wing points behind him, so he turns his back to the target)
        val away = if (mood == FoniMood.Pointing) -1f else 1f
        if (!hasLook) faceTarget = 1f
        else if (lookSide * away > 0.18f) faceTarget = -1f
        else if (lookSide * away < -0.18f) faceTarget = 1f
        face.target = faceTarget
        val faceSign = if (face.value >= 0f) 1f else -1f
        eyeX.target = if (hasLook) lookX * faceSign * 0.36f else 0f
        eyeY.target = if (hasLook) lookY * 0.30f else 0f
        if (reduceMotion) { face.snap(faceTarget); eyeX.snap(eyeX.target); eyeY.snap(eyeY.target) }
        else { face.update(step); eyeX.update(step); eyeY.update(step) }

        // the mood blend
        val still = reduceMotion
        val since = time - moodStart
        val w = if (still) 1f else Motion.step(since, 0.82f, 110f)
        moodPose(to, mood, time, since, still, if (hasLook) lookY else 0f)
        if (w < 0.995f) {
            moodPose(from, prevMood, time, time - prevStart, still, if (hasLook) lookY else 0f)
            pose.lerpFrom(from, to, w)
        } else pose.lerpFrom(to, to, 1f)

        // a little anticipation dip whenever the mood changes
        if (!still) pose.squash += 0.05f * Motion.bell(Motion.progress(since, 0f, 0.24f))

        // the eyes: mood offset plus the look target, kept inside the eye
        val lookW = if (hasLook) 0.45f else 1f
        var ex = pose.eyeDx * lookW + eyeX.value
        var ey = pose.eyeDy * lookW + eyeY.value
        val r = sqrt(ex * ex + ey * ey)
        if (r > 0.42f) { ex *= 0.42f / r; ey *= 0.42f / r }
        pose.eyeDx = ex; pose.eyeDy = ey
        if (hasLook) pose.head += -lookY * 5f * (1f - pose.closed)

        // the voice
        val vv = if (still) (floor(voice * 10f) / 10f) else voice
        when (mood) {
            FoniMood.Listening -> {
                pose.eyeScale += 0.12f * vv
                pose.lean -= 3.5f * vv
                if (!still) pose.head += sin(time * 9f) * 1.6f * vv
            }
            FoniMood.Sleepy -> Unit
            else -> if (vv > 0.02f) {
                pose.beak = max(pose.beak, min(1f, vv * 1.5f) * 34f)
                pose.head -= vv * 4f
                pose.rise += vv * 0.006f
                pose.blink = 0f
            } else if (mood == FoniMood.Talking && !still && time - lastVoiceAt > 0.6f) {
                pose.beak = max(pose.beak, babble(time))
            }
        }

        // turning around: a squish through the middle and a small hop
        pose.facing = face.value.coerceIn(-1f, 1f)
        if (!still) pose.rise += 0.035f * Motion.clamp01(1f - abs(pose.facing))

        // entrance pop
        pose.scale = if (appearAt.isNaN() || still) 1f else 0.55f + 0.45f * Motion.step(time - appearAt, 0.5f, 260f)
    }

    /** Syllable-like beak motion when Talking has no level to follow: 3–5 syllables a second, in phrases. */
    private fun babble(t: Float): Float {
        val phrase = Motion.noise1(t * 0.55f, 7)
        if (phrase < -0.35f) return 0f
        val syll = 0.5f + 0.5f * sin(t * 2f * PI.toFloat() * 4.2f + Motion.noise1(t * 1.3f, 3) * 2f)
        val loud = 0.55f + 0.45f * Motion.noise1(t * 2.7f, 5)
        return 26f * syll * loud * Motion.clamp01((phrase + 0.35f) * 4f)
    }

    // ------------------------------------------------------------------------------------------------ the moods

    private fun moodPose(p: FoniPose, m: FoniMood, time: Float, since: Float, still: Boolean, lookY: Float) {
        p.clear()
        val t = if (still) 1.3f else time + seed * 3.1f
        val sm = if (still) 100f else since
        val osc = if (still) 0f else 1f
        val breathPeriod = if (m == FoniMood.Sleepy) 5.2f else 3.4f
        p.breath = sin(t * TAU / breathPeriod) * osc
        p.lean = sin(t * TAU / 8.3f) * 0.7f * osc
        val blink = if (still) 0f else blink(t)
        val natural = m != FoniMood.Sleepy

        when (m) {
            FoniMood.Idle -> {
                p.head = if (still) -1f else headLook(t, -1f, 7f)
                p.blink = blink
                if (!still) idleVariety(p, t)
            }
            FoniMood.Listening -> {
                p.lean += -5f
                p.head = if (still) -10f else headLook(t, -10f, 2.5f)
                p.eyeScale = 1.1f
                p.wing = -5f
                p.rise = 0.004f
                p.blink = blink
                if (!still) {
                    val local = t % 2.1f
                    p.head += 3f * Motion.bell(Motion.progress(local, 0f, 0.5f))
                }
            }
            FoniMood.Talking -> {
                p.head = (if (still) -3f else headLook(t, -3f, 3.5f)) + sin(t * TAU * 1.7f) * 2.2f * osc
                p.lean += -1f
                p.blink = blink
                p.smile = 0.3f + 0.15f * sin(t * 0.9f) * osc
                if (!still) {
                    val local = t % 2.6f
                    val g = Motion.bell(Motion.progress(local, 0f, 0.75f))
                    p.wing = -22f * g
                    p.wingLift = 0.4f * g
                }
            }
            FoniMood.Thinking -> {
                p.head = 9f + sin(t * TAU / 4f) * 2.5f * osc
                p.eyeDx = -0.14f; p.eyeDy = -0.32f
                p.lean += 1.5f
                p.wing = 5f
                p.blink = if (still) 0f else blink(t * 0.7f)
                if (!still) {
                    val tap = t % 1.1f
                    p.tail = -5f * Motion.bell(Motion.progress(tap, 0f, 0.22f))
                    val chew = t % 3.7f
                    p.beak = 3f * Motion.bell(Motion.progress(chew, 0f, 0.5f))
                }
                p.dots = 1f + (if (still) 0.99f else (sm % 2.4f) / 2.4f)
            }
            FoniMood.Cheering -> {
                p.smile = 1f
                p.head = 4f
                p.beak = 18f + 5f * sin(sm * 8f) * osc
                if (still) {
                    p.wing = -46f; p.wingLift = 0.6f; p.sparkle = 1f; p.sparklePhase = 0.25f
                } else {
                    val period: Float
                    val h: Float
                    val local: Float
                    if (sm < 3f) {
                        period = 0.62f; local = sm % period
                        val n = floor(sm / period)
                        h = 0.09f - 0.012f * n
                    } else {
                        period = 1.3f; local = (sm - 3f) % period; h = 0.032f
                    }
                    hop(p, local, h)
                    val air = Motion.bell(Motion.progress(local, HOP_PREP, HOP_PREP + HOP_AIR))
                    val flap = if (sm < 3f) 1f else 0.55f
                    p.wing = (-95f * air + sin(sm * 24f) * 16f * air) * flap - 10f
                    p.wingLift = air * flap
                    p.sparkle = if (sm < 3.2f) 1f else 0.6f
                    p.sparklePhase = sm
                }
            }
            FoniMood.Encouraging -> {
                p.smile = 0.75f
                p.lean += -3f
                p.blink = blink
                if (still) {
                    p.head = -2f; p.wing = -30f; p.wingLift = 0.5f; p.beak = 6f
                } else {
                    val local = sm % 2.4f
                    val nod = Motion.bell(Motion.progress(local, 0f, 0.34f)) + 0.7f * Motion.bell(Motion.progress(local, 0.42f, 0.76f))
                    p.head = 2f - 8f * nod
                    p.beak = 9f * nod
                    val local2 = (sm + 1.2f) % 3.4f
                    val e = Motion.bell(Motion.progress(local2, 0f, 1.5f)).toDouble().pow(0.5).toFloat()
                    p.wing = (-34f + 12f * sin(local2 * TAU * 1.4f)) * e
                    p.wingLift = 0.5f * e
                }
            }
            FoniMood.Unsure -> {
                p.head = 12f + sin(t * TAU / 3.6f) * 3f * osc
                p.eyeDx = 0.2f; p.eyeDy = -0.22f
                p.lean += 1f
                p.smile = 0.12f
                p.blink = if (still) 0f else blink(t * 0.8f)
                if (!still) {
                    val local = sm % 3f
                    val k = Motion.bell(Motion.progress(local, 0.3f, 1.1f))
                    p.rise += 0.012f * k
                    p.squash -= 0.035f * k
                    p.wing = -14f * k
                    p.wingLift = 0.45f * k
                    p.head += 4f * k
                    val hum = Motion.bell(Motion.progress(local, 1.3f, 2.4f))
                    p.beak = (4f + 1.4f * sin(t * TAU * 7f)) * hum
                } else p.beak = 3f
            }
            FoniMood.Pointing -> {
                // the presenter's gesture: the wing stretches out behind him, level or a little up, towards the target
                // (he turns so that his back is to it) while his eyes glance that way
                val aim = (-lookY * 40f).coerceIn(-20f, 40f)
                val jab = if (still) 0f else 8f * sin(sm * TAU * 2.2f) * (1f - Motion.progress(sm, 0.25f, 1.15f)) * Motion.progress(sm, 0f, 0.25f)
                p.wing = -84f - aim + jab + 3f * sin(t * TAU * 0.8f) * osc
                p.wingLift = 0.55f
                p.head = 4f + aim * 0.15f
                p.lean += 1.5f
                p.eyeDx = 0.32f
                p.eyeDy = -0.05f
                p.beak = if (still) 6f else 12f * Motion.bell(Motion.progress(sm, 0.1f, 0.9f))
                p.smile = 0.2f
                p.blink = blink
            }
            FoniMood.Sleepy -> {
                p.closed = 1f
                p.lean += 2f
                p.wing = 3f
                p.zzz = 1f
                if (still) p.head = 13f else {
                    val local = t % 6.5f
                    val droop = Motion.easeInOut(Motion.progress(local, 0f, 4f)) * 7f
                    val catchUp = Motion.progress(local, 4f, 4.35f)
                    val back = if (local < 4f) 0f else (1f - Motion.step(local - 4f, 0.45f, 160f)) * 7f
                    p.head = 11f + (if (local < 4f) droop else back) - 2f * Motion.bell(catchUp)
                    p.closed = 1f - 0.55f * Motion.bell(Motion.progress(local, 4f, 4.6f))
                    p.tail = 2f * sin(t * TAU / 5.2f)
                }
            }
            FoniMood.Waving -> {
                if (still) {
                    p.wing = -112f; p.wingLift = 1f; p.smile = 1f; p.beak = 14f; p.head = -2f
                } else {
                    val local = sm % 3.2f
                    val e = Motion.easeInOut(Motion.progress(local, 0f, 0.3f)) * (1f - Motion.easeInOut(Motion.progress(local, 1.9f, 2.3f)))
                    p.wing = -112f * e + sin(local * TAU * 2.4f) * 15f * e
                    p.wingLift = e
                    p.smile = e
                    p.beak = 14f * e
                    p.head = headLook(t, -3f, 4f) * (1f - e) + (-2f + 3f * sin(local * TAU * 1.2f)) * e
                    hop(p, local, 0.03f)
                    p.blink = blink * (1f - e)
                }
            }
        }

        if (natural && !still && m != FoniMood.Cheering && m != FoniMood.Pointing) {
            p.tail += tailFlick(t)
            if (m != FoniMood.Waving) p.wing += wingSettle(t)
        }
    }

    /** Idle never looks frozen: every ~5 s one small thing happens, picked by noise. */
    private fun idleVariety(p: FoniPose, t: Float) {
        val period = 5.2f
        val step = floor(t / period)
        val local = t - step * period - 1.2f
        if (local < 0f) return
        when ((noise(step * 1.91f + seed) * 5f).toInt()) {
            0 -> if (local < 0.5f) { // ruffle the feathers
                val k = Motion.bell(local / 0.5f)
                p.lean += sin(local * TAU * 9f) * 2.2f * k
                p.squash += 0.03f * k
                p.wingLift = max(p.wingLift, 0.35f * k)
                p.tail += sin(local * TAU * 8f) * 6f * k
            }
            1 -> { // peer down at something
                val k = Motion.bell(Motion.progress(local, 0f, 1.6f))
                p.head += -12f * Motion.easeInOut(min(1f, k * 1.6f))
                p.eyeDy += 0.25f * k
            }
            2 -> if (local < 0.9f) p.tail += sin(local * TAU * 5f) * 9f * Motion.bell(local / 0.9f) // wag
            3 -> hop(p, local, 0.035f)
            else -> { // look at you, double blink
                val k = Motion.bell(Motion.progress(local, 0f, 1.4f))
                p.head = p.head * (1f - k)
                p.blink = max(p.blink, max(Motion.bell(Motion.progress(local, 0.15f, 0.32f)), Motion.bell(Motion.progress(local, 0.42f, 0.59f))))
            }
        }
    }

    /** One hop with anticipation, stretch on take-off and before landing, a squash on landing and a small rebound. */
    private fun hop(p: FoniPose, since: Float, height: Float) {
        when {
            since < 0f -> Unit
            since < HOP_PREP -> p.squash += 0.10f * Motion.easeOutCubic(since / HOP_PREP)
            since < HOP_PREP + HOP_AIR -> {
                val ph = (since - HOP_PREP) / HOP_AIR
                p.rise += height * 4f * ph * (1f - ph)
                val c = kotlin.math.cos(ph * PI.toFloat())
                p.squash -= 0.075f * c * c
            }
            since < HOP_PREP + HOP_AIR + 0.18f -> p.squash += 0.12f * Motion.bell((since - HOP_PREP - HOP_AIR) / 0.18f)
            since < HOP_PREP + HOP_AIR + 0.32f -> p.squash -= 0.02f * Motion.bell((since - HOP_PREP - HOP_AIR - 0.18f) / 0.14f)
        }
    }

    /** Long holds and quick moves, the way a bird looks around; the move overshoots a little. */
    private fun headLook(t: Float, centre: Float, spread: Float): Float {
        val period = 2.3f
        val step = floor(t / period)
        val a = centre + (noise(step - 1f + seed) * 2f - 1f) * spread
        val b = centre + (noise(step + seed) * 2f - 1f) * spread
        val moved = min(1f, (t - step * period) / 0.2f)
        val overshoot = 1f + 0.18f * sin(moved * PI.toFloat())
        return a + (b - a) * Motion.easeInOut(moved) * overshoot
    }

    /** 0 open, 1 shut; now and then a double blink. */
    private fun blink(t: Float): Float {
        val period = 3.9f
        val step = floor(t / period)
        val at = 0.4f + noise(step * 1.7f + seed) * (period - 1f)
        var shut = Motion.bell(Motion.progress(t - step * period - at, 0f, 0.17f))
        if (noise(step * 3.1f + seed) < 0.22f) shut = max(shut, Motion.bell(Motion.progress(t - step * period - at - 0.27f, 0f, 0.17f)))
        return shut
    }

    private fun tailFlick(t: Float): Float {
        val period = 6.7f
        val step = floor(t / period)
        val at = noise(step * 2.3f + seed) * (period - 1f)
        return -7f * Motion.bell(Motion.progress(t - step * period - at, 0f, 0.34f))
    }

    private fun wingSettle(t: Float): Float {
        val period = 9.1f
        val step = floor(t / period)
        val at = noise(step * 1.3f + seed) * (period - 1f)
        return -6f * Motion.bell(Motion.progress(t - step * period - at, 0f, 0.5f))
    }

    private fun noise(x: Float): Float {
        val v = kotlin.math.sin(x * 12.9898 + 4.1414) * 43758.5453
        return (v - kotlin.math.floor(v)).toFloat()
    }

    private companion object {
        const val TAU = (2 * PI).toFloat()
        const val HOP_PREP = 0.09f
        const val HOP_AIR = 0.34f
    }
}
