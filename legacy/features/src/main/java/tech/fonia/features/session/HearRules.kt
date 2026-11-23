package tech.fonia.features.session

import tech.fonia.domain.SoundDetector
import kotlin.math.exp
import kotlin.math.min

/**
 * "Something always happens" (owner, 2026-11-23: "nu se întâmplă nimic"): the pure rules behind what the practice
 * screens show while the microphone is open. Every sound gets an answer at once; the target sound gets more of it.
 */
object HearRules {
    /** The envelope at or above which the screen says "Te aud" (about 2–3 dB over the room, see [envelope]). */
    const val HEARD = 0.06f

    /** Attack and release of the loudness envelope, per second: on in ~30 ms, off in ~170 ms. */
    const val ATTACK_PER_SECOND = 35f
    const val RELEASE_PER_SECOND = 6f

    /** How far from the S/Ș boundary (sNess 0.5) a hiss still counts as "close". */
    const val NEAR_BOUNDARY = 0.15f

    /** Bar speed for the target sound, for a close sound, and for a hiss of the other place. */
    const val CREDIT_RIGHT = 1f
    const val CREDIT_NEAR = 0.4f
    const val CREDIT_OTHER_HISS = 0.1f

    /**
     * One frame of the loudness envelope: [level] is the live analysis' 0…1 level above the measured room floor (so it
     * is adaptive already); fast attack, slower release, so the letter answers the first syllable within a frame or two
     * and does not flicker between words.
     */
    fun envelope(previous: Float, level: Float, dtSeconds: Float): Float {
        val rate = if (level > previous) ATTACK_PER_SECOND else RELEASE_PER_SECOND
        val k = 1f - exp(-rate * dtSeconds.coerceIn(0f, 0.1f))
        return (previous + (level - previous) * k).coerceIn(0f, 1f)
    }

    fun hears(envelope: Float): Boolean = envelope >= HEARD

    /**
     * The living letter's size while listening: it swells with the voice from [base] (its resting inflation, or the held
     * share in the sustain exercise) by up to 0.45 for a loud voice, so any sound is visible, right or not.
     */
    fun letterInflation(base: Float, envelope: Float): Float = (base + 0.45f * min(1f, envelope * 1.6f)).coerceIn(0f, 1f)

    /** The voice wobble of the letter: voiced sound (pitch clarity) makes its outline boil a little. */
    fun letterWobble(envelope: Float, voicing: Float): Float = if (hears(envelope)) (0.35f * voicing).coerceIn(0f, 0.35f) else 0f

    /**
     * How fast the held bar fills on one live reading, 0…1 of real time. The target sound fills it ([CREDIT_RIGHT]); a
     * hiss just across the S/Ș boundary fills it slowly ([CREDIT_NEAR]); a hiss of the other place barely moves it
     * ([CREDIT_OTHER_HISS], it can never finish in the time given); silence and other sounds leave it.
     *
     * [warmTarget] is true for Ș, J, CE, GE (the hiss lives under sNess 0.5).
     */
    fun holdCredit(
        detector: SoundDetector,
        warmTarget: Boolean,
        isFricative: Boolean,
        sNess: Float,
        trill: Float,
        level: Float,
    ): Float = when (detector) {
        SoundDetector.Sibilant -> {
            if (!isFricative || sNess.isNaN()) {
                0f
            } else {
                val towardTarget = if (warmTarget) 0.5f - sNess else sNess - 0.5f
                when {
                    towardTarget >= 0f -> CREDIT_RIGHT
                    towardTarget > -NEAR_BOUNDARY -> CREDIT_NEAR
                    else -> CREDIT_OTHER_HISS
                }
            }
        }
        SoundDetector.Rhotic -> when {
            trill > 0.3f -> CREDIT_RIGHT
            trill > 0.12f -> CREDIT_NEAR
            else -> 0f
        }
        SoundDetector.None -> when {
            level > 0.3f -> CREDIT_RIGHT
            level > 0.12f -> CREDIT_NEAR
            else -> 0f
        }
    }

    /** The microphone is dead when it has been open long enough and nothing in it came near a voice. */
    fun micSilent(openSeconds: Double, peakDb: Float, afterSeconds: Double = 2.0, belowDb: Float = -66f): Boolean =
        openSeconds >= afterSeconds && peakDb < belowDb

    /** What follows a take the phone could not judge. */
    enum class Unjudged {
        /** "Mai spune o dată": the letter's retake cue, the capsule counts down and opens again. */
        Retake,

        /** Enough tries: an encouraging "almost", the letter animates, the session moves on by itself. */
        AlmostAndMoveOn,

        /** The microphone delivered nothing: "Nu te aud" and what to do, and the learner chooses. */
        NoMicrophone,
    }

    fun afterUnjudged(autoRetakes: Int, maxRetakes: Int, micWasSilent: Boolean): Unjudged = when {
        micWasSilent -> Unjudged.NoMicrophone
        autoRetakes < maxRetakes -> Unjudged.Retake
        else -> Unjudged.AlmostAndMoveOn
    }

    /** The countdown shown in the capsule before it opens again after "Mai spune o dată". */
    val retakeCountdown = listOf(3, 2, 1)
}
