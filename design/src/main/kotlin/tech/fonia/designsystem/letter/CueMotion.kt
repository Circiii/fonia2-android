package tech.fonia.designsystem.letter

import tech.fonia.designsystem.motion.Motion
import tech.fonia.designsystem.motion.Motion.bell
import tech.fonia.designsystem.motion.Motion.clamp01
import tech.fonia.designsystem.motion.Motion.kick
import tech.fonia.designsystem.motion.Motion.lerp
import tech.fonia.designsystem.motion.Motion.progress
import tech.fonia.designsystem.motion.Motion.step
import tech.fonia.designsystem.tokens.FoniaLetterTokens
import tech.fonia.designsystem.tokens.FoniaMotion
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/** What a cue needs to know about the letter it plays on. */
class CueContext {
    /** The cap height in em (Allo: 0.727). */
    var capEm = 0.727f
    /** Half the width of the letter's body in em (for the snap contact and the puffs). */
    var halfWidthEm = 0.35f
    var targetIsR = false
    var targetIsL = false
    /** The inflation the drive gives right now (cues that override it start from here). */
    var liveInflation = 0.55f
    /** POP's peak above the current scale, so that the absolute peak is 1.70 (§4). */
    var popPeak = 0.31f
    /** Signed direction of the Unsure tilt (alternates). */
    var tiltSign = 1f
}

/**
 * The animation vocabulary as closed-form functions of the time since the cue (seconds). Every function writes into
 * a [LetterPose] on top of what is already there, so cues compose (a Beat during a held Almost, a Pop during anything).
 * Each one is settled by its [duration]: holding cues are then evaluated at exactly that time forever, which is also
 * the reduced-motion frame.
 */
object CueMotion {
    private val B = FoniaMotion.bounce
    private val R = FoniaMotion.reward
    private val F = FoniaMotion.follow
    private val C = FoniaMotion.comma
    private val TAN9 = tan(9.0 * PI / 180.0).toFloat()

    /** Time the pop particles leave / Correct's stars fly. */
    const val POP_BURST_T = 0.10f
    const val CORRECT_STARS_T = 0.20f

    fun duration(c: LetterCue): Float = when (c) {
        LetterCue.Breathe -> 7.0f
        LetterCue.Pop -> 1.1f
        LetterCue.Correct -> 1.15f
        LetterCue.Almost -> 0.95f
        LetterCue.Unsure -> 1.6f
        LetterCue.Retake -> 0.75f
        is LetterCue.Beat -> 0.36f
        LetterCue.DropIn -> 0.95f
        LetterCue.Thicken -> 0.75f
        LetterCue.Stretch -> 1.15f
        LetterCue.CommaDrop -> 1.25f
        LetterCue.CommaLift -> 1.4f
        is LetterCue.SwapGlide -> 1.35f
        is LetterCue.TrillRipple -> if (c.uvular) 1.1f else 0.7f
        LetterCue.VoicingWave -> 1.5f
        LetterCue.AirToCentre -> 1.5f
        LetterCue.TongueBehindTeeth -> 1.6f
        LetterCue.ClosureSnap -> 1.0f
        LetterCue.SagAndRefill -> 1.6f
        is LetterCue.SlotDrop -> 0.95f
        else -> 0f
    }

    /** Cues that keep their last frame until Rest or the next verdict (the frame carries the information). */
    fun holds(c: LetterCue): Boolean = when (c) {
        LetterCue.Correct, LetterCue.Almost, LetterCue.Unsure, LetterCue.Thicken, LetterCue.CommaDrop, LetterCue.CommaLift,
        is LetterCue.SwapGlide, is LetterCue.TrillRipple, LetterCue.VoicingWave, LetterCue.AirToCentre,
        LetterCue.TongueBehindTeeth, LetterCue.ClosureSnap, LetterCue.SagAndRefill -> true
        else -> false
    }

    /** Verdicts and instructions replace each other (one meaning at a time). */
    fun exclusive(c: LetterCue): Boolean = holds(c) || c == LetterCue.Retake || c == LetterCue.Stretch

    fun apply(c: LetterCue, t: Float, p: LetterPose, x: CueContext) {
        when (c) {
            LetterCue.Breathe -> breathe(t, p)
            LetterCue.Pop -> pop(t, p, x)
            LetterCue.Correct -> correct(t, p)
            LetterCue.Almost -> almost(t, p)
            LetterCue.Unsure -> unsure(t, p, x)
            LetterCue.Retake -> retake(t, p)
            is LetterCue.Beat -> beat(t, p, c.strength)
            LetterCue.DropIn, is LetterCue.SlotDrop -> dropIn(t, p)
            LetterCue.Thicken -> thicken(t, p)
            LetterCue.Stretch -> stretch(t, p)
            LetterCue.CommaDrop -> commaDrop(t, p, x)
            LetterCue.CommaLift -> commaLift(t, p)
            is LetterCue.SwapGlide -> swapGlide(t, p, x)
            is LetterCue.TrillRipple -> trill(t, p, c.uvular)
            LetterCue.VoicingWave -> voicingWave(t, p)
            LetterCue.AirToCentre -> air(t, p)
            LetterCue.TongueBehindTeeth -> tongue(t, p)
            LetterCue.ClosureSnap -> snap(t, p, x)
            LetterCue.SagAndRefill -> sag(t, p, x)
            else -> Unit
        }
    }

    /**
     * Reduced motion for the cues that do not hold a frame (§4, §5.1): no jump, no particles, no turn; what carries the
     * meaning stays. POP and Correct leave a static ✓, Retake only shows ↻, a Beat is only the wght change.
     */
    fun applyReduced(c: LetterCue, t: Float, p: LetterPose) {
        when (c) {
            LetterCue.Pop -> { p.badge = LetterPose.BADGE_CHECK; p.badgeKind = 1; p.badgeScale = 1f }
            LetterCue.Retake -> { p.badge = LetterPose.BADGE_RETAKE; p.badgeKind = 3; p.badgeScale = if (t < 0.7f) 1f else 0f }
            is LetterCue.Beat -> p.wghtAdd += 150f * c.strength * (if (t < 0.14f) 1f else 0f)
            else -> Unit
        }
    }

    // ---------------------------------------------------------------- base vocabulary

    /** wght ±30 and scale ±1.5 % on a slow sine, two cycles of 3.5 s, faded in and out. */
    private fun breathe(t: Float, p: LetterPose) {
        val env = progress(t, 0f, 0.6f) * (1f - progress(t, 6.2f, 7.0f))
        val s = sin(Motion.TAU * t / 3.5f - PI.toFloat() / 2f) * 0.5f + 0.5f // 0 → 1 → 0, starts at rest
        p.wghtAdd += 30f * s * env
        p.scale *= 1f + 0.015f * s * env
        p.ty -= 0.012f * s * env
    }

    /** Anticipation (0.92, 100 ms) → 1.70 peak on `reward` → rest; a shock ring from the burst. */
    private fun pop(t: Float, p: LetterPose, x: CueContext) {
        if (t < POP_BURST_T) {
            val a = Motion.easeInOut(t / POP_BURST_T)
            p.scale *= 1f - 0.08f * a
            p.sy *= 1f - 0.04f * a
            p.sx *= 1f + 0.03f * a
            return
        }
        val u = t - POP_BURST_T
        // from 0.92 a fast swing up to the peak, ringing down to 1 (the peak ratio is set by the engine via inflation)
        val k = kick(u, 0.42f, 420f)
        val settle = step(u, R)
        p.scale *= lerp(0.92f, 1f, settle) + x.popPeak * k
        p.wghtAdd += 60f * clamp01(k)
        p.ringT = u
    }

    /** Crouch → jump 0.16 em with stretch → land with squash (0.8 → 1.08 → 1) → the sticker sticks (3° → 0). */
    private fun correct(t: Float, p: LetterPose) {
        val crouchEnd = 0.09f
        val land = 0.47f
        val h = 0.17f
        when {
            t < crouchEnd -> {
                val a = Motion.easeInOut(t / crouchEnd)
                p.sy *= 1f - 0.12f * a
                p.sx *= 1f + 0.07f * a
            }
            t < land -> {
                val u = (t - crouchEnd) / (land - crouchEnd)
                p.ty -= h * 4f * u * (1f - u)
                p.shadowLift += h * 4f * u * (1f - u)
                // stretch at take-off, round at the apex, a little stretch falling
                val st = 0.10f * (1f - progress(u, 0f, 0.45f)) + 0.05f * progress(u, 0.65f, 1f)
                p.sy *= 1f + st
                p.sx *= 1f - st * 0.6f
                p.rot += 3f * Motion.easeOutCubic(progress(u, 0f, 0.4f))
            }
            else -> {
                val u = t - land
                val k = kick(u, B)
                p.sy *= 1f - 0.2f * k
                p.sx *= 1f + 0.13f * k
                p.rot += 3f * (1f - step(u, B))
                p.shadowSpread += 0.12f * clamp01(k)
            }
        }
        if (t >= land) {
            p.badge = LetterPose.BADGE_CHECK
            p.badgeKind = 1
            p.badgeScale = step(t - land - 0.05f, B)
        }
    }

    /** Shear 9° on `bounce`, the face drains to half (a liquid line that sloshes), one swing ±3°. */
    private fun almost(t: Float, p: LetterPose) {
        p.shear += TAN9 * step(t, B)
        val drain = Motion.easeOutCubic(progress(t, 0.04f, 0.42f))
        p.halfFill = 1f
        p.fillLine = lerp(1.15f, 0.5f, drain)
        val swing = if (t < 0.48f) 3f * sin(Motion.TAU * t / 0.48f) * (1f - t / 0.48f) else 0f
        p.rot += swing
        p.slosh += -swing * 2.2f + 4f * kick(t - 0.3f, 0.35f, 120f)
        p.sloshWave = t * 7f
    }

    /** The outline boils (4 noise frames swapped at 8 fps) for 1.2 s, then freezes tilted 4°, with "?". */
    private fun unsure(t: Float, p: LetterPose, x: CueContext) {
        val boil = 1f - progress(t, 1.05f, 1.25f)
        p.noise = maxOf(p.noise, boil)
        p.noiseSeed = (floor(t * FoniaMotion.TREMBLE_FPS).toInt()) and 3
        p.rot += 4f * x.tiltSign * step(t - 1.05f, B)
        p.scale *= 1f - 0.04f * progress(t, 0f, 0.3f)
        if (t > 1.1f) {
            p.badge = LetterPose.BADGE_QUESTION
            p.badgeKind = 3
            p.badgeScale = step(t - 1.1f, B)
        }
    }

    /** 1 → 0.9 → 1 with an 8° turn there and back; ↻ for 600 ms. */
    private fun retake(t: Float, p: LetterPose) {
        p.scale *= 1f - 0.1f * bell(progress(t, 0f, 0.3f))
        p.rot += 8f * bell(progress(t, 0f, 0.42f)) * (1f - 0.6f * progress(t, 0.2f, 0.42f))
        if (t < 0.7f) {
            p.badge = LetterPose.BADGE_RETAKE
            p.badgeKind = 3
            p.badgeScale = step(t, B) * (1f - progress(t, 0.55f, 0.7f))
        }
    }

    /** A 0.06 em hop and wght +150 for 140 ms. */
    private fun beat(t: Float, p: LetterPose, strength: Float) {
        p.ty -= 0.06f * strength * bell(progress(t, 0f, 0.24f))
        p.wghtAdd += 150f * strength * (1f - progress(t, 0.14f, 0.26f))
        val k = kick(t - 0.2f, B)
        p.sy *= 1f - 0.05f * strength * clamp01(k)
        p.sx *= 1f + 0.03f * strength * clamp01(k)
    }

    /** Falls from 1.6 em above (stretched), lands with a squash and settles; the shadow grows as it nears. */
    private fun dropIn(t: Float, p: LetterPose) {
        val fall = 0.34f
        if (t < fall) {
            val u = t / fall
            val y = -1.6f * (1f - u * u)
            p.ty += y
            p.shadowLift += -y
            p.alpha *= progress(t, 0f, 0.08f)
            p.sy *= 1f + 0.16f * u
            p.sx *= 1f - 0.09f * u
            p.rot += -7f * (1f - u)
            p.shadowSpread -= 0.4f * (1f - u)
        } else {
            val u = t - fall
            val k = kick(u, B)
            p.sy *= 1f - 0.24f * k
            p.sx *= 1f + 0.16f * k
            p.shadowSpread += 0.1f * clamp01(k)
        }
    }

    /** wght +200 with a soft swell, stays. */
    private fun thicken(t: Float, p: LetterPose) {
        p.wghtAdd += 200f * step(t, B)
        p.scale *= 1f + 0.05f * kick(t, B)
        p.rimMul *= 1f + 0.15f * step(t, B)
    }

    /** Ribbon: scaleX 1 → 1.5 anchored on the left, with air streaks; springs back. */
    private fun stretch(t: Float, p: LetterPose) {
        val out = Motion.out(progress(t, 0f, 0.38f))
        val back = step(t - 0.62f, B)
        val e = out * (1f - back)
        p.sx *= 1f + 0.5f * e
        p.sy *= 1f - 0.12f * e
        p.tx += 0.17f * e
        p.wghtAdd -= 120f * e
        p.streakT = t
    }

    // ---------------------------------------------------------------- instruction animations (§5.2)

    /**
     * Target Ș/Ț, heard S/T. The letter starts as heard (sharp, no comma); the comma pops in high above it, falls on
     * the `comma` spring (one bounce), and sticks; the body rounds on `bounce` and squashes at the impact.
     */
    private fun commaDrop(t: Float, p: LetterPose, x: CueContext) {
        val appear = 0.16f
        val fallAt = 0.30f
        val startDy = -(x.capEm + 0.30f)
        p.commaMode = 1
        val f = step(t - fallAt, C)
        p.commaDy = startDy * (1f - f)
        p.commaScale = Motion.easeOutCubic(progress(t, 0f, appear)) * (1f + 0.25f * bell(progress(t, 0f, appear * 1.6f)))
        p.commaRot = -28f * (1f - step(t - fallAt - 0.05f, B))
        // a tiny hover before the fall
        if (t < fallAt) p.commaDy += -0.03f * sin(Motion.TAU * t / 0.3f)
        // the body: sharp as heard, then round as the comma arrives (the comma lands ~50 ms after the body moves)
        p.rond = lerp(FoniaLetterTokens.ROND_SHARP, FoniaLetterTokens.ROND_ROUND, step(t - (fallAt + 0.04f), B))
        p.rondMix = 1f
        val impact = fallAt + 0.12f
        val k = kick(t - impact, B)
        p.sy *= 1f - 0.07f * k
        p.sx *= 1f + 0.04f * k
        p.wghtAdd += 40f * clamp01(k)
    }

    /**
     * Target S/T, heard Ș/Ț. The heard comma sits under the letter (round, as heard), wiggles loose, then falls out of
     * the frame turning; the letter sharpens and gives a little relieved hop.
     */
    private fun commaLift(t: Float, p: LetterPose) {
        p.phantomComma = true
        p.commaMode = 1
        val loose = 0.32f
        if (t < loose) {
            val w = progress(t, 0.05f, loose)
            p.commaRot = 10f * sin(Motion.TAU * t / 0.11f) * w
            p.commaDy = -0.035f * Motion.easeOutCubic(w)
        } else {
            val u = t - loose
            p.commaDy = -0.035f + 0.5f * 9.5f * u * u - 0.6f * u
            p.commaDx = 0.12f * u
            p.commaRot = 200f * Motion.easeIn(progress(u, 0f, 0.9f))
            p.commaAlpha = 1f - progress(u, 0.55f, 0.85f)
        }
        // round as heard → sharp
        p.rond = lerp(FoniaLetterTokens.ROND_ROUND, FoniaLetterTokens.ROND_SHARP, step(t - 0.38f, B))
        p.rondMix = 1f
        val k = kick(t - 0.36f, B)
        p.ty -= 0.035f * clamp01(k)
    }

    /**
     * The heard letter appears as a flat ghost and glides out to the left; the target glides in from the right as the
     * sticker (2 × 260 ms on `bounce`). R ends with the trill ripple, L with a short press on top.
     */
    private fun swapGlide(t: Float, p: LetterPose, x: CueContext) {
        p.ghostMode = 1
        val out = step(t - 0.12f, B)
        p.ghostDx = -1.1f * out
        p.ghostAlpha = 0.45f * (1f - progress(t, 0.3f, 0.55f))
        p.ghostScale = 1f - 0.12f * out
        val inT = t - 0.2f
        p.targetDx = 1.25f * (1f - step(inT, B))
        p.alpha *= progress(inT, 0f, 0.08f)
        p.rot += -6f * (1f - step(inT, B))
        val end = 0.72f
        if (x.targetIsR && t > end) trillRipple(t - end, p)
        if (x.targetIsL && t > end) {
            val u = progress(t, end, end + 0.2f)
            p.sy *= 1f - 0.08f * bell(u)
            p.sx *= 1f + 0.03f * bell(u)
        }
    }

    private fun trillRipple(u: Float, p: LetterPose) {
        if (u > 0.42f) return
        p.ripple += 0.03f * (1f - u / 0.42f)
        p.ripplePhase = Motion.TAU * 22f * u
    }

    /** A plucked-string wave through R's leg (3 crests, 22 Hz, 420 ms); uvular: a Sun dot rises from behind to the top. */
    private fun trill(t: Float, p: LetterPose, uvular: Boolean) {
        trillRipple(t - 0.05f, p)
        val k = kick(t - 0.05f, 0.3f, 900f)
        p.sx *= 1f + 0.015f * k
        if (uvular) p.sunDotT = t
    }

    /** A line under the letter turns into a wave, rises into it, and the letter thickens (wght +200); a small wave stays. */
    private fun voicingWave(t: Float, p: LetterPose) {
        p.waveT = t
        val thick = step(t - 0.62f, B)
        p.wghtAdd += 200f * thick
        p.scale *= 1f + 0.04f * kick(t - 0.62f, B)
        p.rimMul *= 1f + 0.1f * thick
    }

    /** Two puffs leave the sides, join in the middle and fly out toward the child (`out` curve, 1.2 s). */
    private fun air(t: Float, p: LetterPose) {
        p.puffT = t
        // the letter breathes out a little as the air leaves through the middle
        val k = kick(t - 0.55f, B)
        p.sx *= 1f - 0.03f * k
        p.sy *= 1f + 0.02f * k
    }

    /** Teeth under the letter, the tongue pokes out between them, then slides behind them (bounce at the end). */
    private fun tongue(t: Float, p: LetterPose) {
        p.teethT = t
        p.ty -= 0.05f * Motion.easeOutCubic(progress(t, 0f, 0.3f))
    }

    /** A small T comes from the left, accelerating, and snaps onto the letter; both squash at the contact. */
    private fun snap(t: Float, p: LetterPose, x: CueContext) {
        p.ghostMode = 2
        val hit = 0.34f
        val contact = -(x.halfWidthEm + 0.21f)
        p.ghostScale = 0.55f
        p.ghostAlpha = progress(t, 0f, 0.06f)
        if (t < hit) {
            val u = t / hit
            p.ghostDx = lerp(-1.9f, contact, u * u)
            p.ghostDy = -0.04f * bell(u)
        } else {
            val k = kick(t - hit, R)
            p.ghostDx = contact + 0.04f * k
            p.ghostSx = 1f - 0.25f * clamp01(k)
            p.sx *= 1f - 0.10f * k
            p.tx += 0.03f * k
            p.rot += 2.5f * k
        }
    }

    /** Sags (thin, 0.9, tilted 5°, 480 ms), then fills back up to full on `follow` and stays full. */
    private fun sag(t: Float, p: LetterPose, x: CueContext) {
        val down = Motion.easeOutCubic(progress(t, 0f, 0.48f))
        val up = step(t - 0.55f, F)
        p.inflationMix = 1f
        p.inflation = lerp(lerp(x.liveInflation, 0.08f, down), 1f, up)
        p.rot += 5f * down * (1f - up)
        p.ty += 0.04f * down * (1f - up)
        p.sy *= 1f - 0.05f * down * (1f - up)
        val k = kick(t - 0.9f, B)
        p.sy *= 1f + 0.04f * clamp01(k)
    }
}
