package tech.fonia.features.session

import kotlin.math.abs
import kotlin.math.exp

/**
 * What counts as "the target sound is heard" live. The letter moves and lights only for this (owner, 2026-11-25: "să
 * nu reacționeze la orice, doar când se aude litera sau sunetul").
 */
sealed interface LiveAim {
    /**
     * A hiss on the target's side of the S ↔ Ș axis: [sharp] for S, Z, Ț; round for Ș, J, CE, GE. [voiced] asks for a
     * hiss with voice (Z, J, GE) or without (S, Ș, Ț, CE); null takes either (the calibration steps).
     */
    data class Place(val sharp: Boolean, val voiced: Boolean? = null) : LiveAim

    /** The tongue-tip flutter of R. */
    data object Trill : LiveAim

    /** A clear voiced sound with no hiss and no flutter (L, whose place cannot be heard live): voice, not noise. */
    data object Voice : LiveAim

    /** No target (a take that practises no Romanian sound): the letter follows the voice, it never lights. */
    data object None : LiveAim

    companion object {
        fun of(id: tech.fonia.domain.SoundID?): LiveAim = when (id) {
            null -> None
            // No voice condition live: between vowels the analyser's voiced flag is set for S as often as for Z
            // (measured on the evaluation clips, tools/eval/out/live_raw.tsv), so the pairs S/Z and Ș/J light alike.
            tech.fonia.domain.SoundID.S, tech.fonia.domain.SoundID.Ts, tech.fonia.domain.SoundID.Z -> Place(sharp = true)
            tech.fonia.domain.SoundID.Sh, tech.fonia.domain.SoundID.Ce, tech.fonia.domain.SoundID.J, tech.fonia.domain.SoundID.Ge -> Place(sharp = false)
            tech.fonia.domain.SoundID.R -> Trill
            tech.fonia.domain.SoundID.L -> Voice
        }
    }
}

/**
 * The calm side of the live chain (owner, 2026-11-24: "totul se mișcă prea repede, să nu mai tremure"): every raw
 * 10 ms value is smoothed with a time constant (attack ~70 ms, release ~320 ms, from [Pacing]), changes under a dead
 * zone are ignored, the S ↔ Ș place is held for a moment after the hiss stops instead of jumping back to "none", and
 * "target heard" has hysteresis, so the letter lights after ~120 ms of the right sound and does not flicker.
 *
 * Pure: one instance per screen, stepped once per drawn frame with the real frame time, so a 120 Hz phone moves exactly
 * as fast as a 60 Hz one.
 */
/** A hiss flatter than this is a noise, not a sibilant (see [AimRules.flatMax]). */
const val HISS_FLAT_MAX = 0.55f

/**
 * When the live letter counts a sound as its target. Numbers chosen on the evaluation clips (LiveAimEval,
 * `tools/eval/out/live_aim.md`): a wrong light costs more than a missed one, so the round side is strict.
 */
data class AimRules(
    /** Recentred place above which a hiss is on the S side. */
    val sharpMin: Float = 0.55f,
    /** Recentred place under which a hiss is on the Ș side. */
    val roundMax: Float = 0.45f,
    /** The right sound must last this long before the letter lights (ms). */
    val onMs: Float = 120f,
    /** R: live trill strength. */
    val trillMin: Float = 0.3f,
    /** L: pitch clarity of a clear voice. */
    val voiceClear: Float = 0.6f,
    /** L: the voice must be this far above the room (0..1). */
    val voiceLevel: Float = 0.08f,
    /**
     * A hiss flatter than this is a noise, not a sibilant: S and Ș have a spectral peak (median flatness 0.2-0.5 on
     * the evaluation clips), a vacuum cleaner, paper, toys and running water are flat (0.57-0.87).
     */
    val flatMax: Float = HISS_FLAT_MAX,
)

class LiveSmoother(private val pacing: Pacing = Pacing.current, private val rules: AimRules = AimRules()) {
    /** Loudness envelope 0..1 above the room (what "Te aud" and the letter's size read). */
    var level = 0f
        private set
    var voicing = 0f
        private set
    var fricative = 0f
        private set
    var trill = 0f
        private set
    var blow = 0f
        private set

    /** Where the hiss sits, 0 = Ș … 1 = S; NaN when no hiss was heard lately. */
    var place = Float.NaN
        private set

    /** True while the target sound is heard (with hysteresis). */
    var hit = false
        private set

    /** 0..1 light of the target heard, smoothed (the glow behind the letter). */
    var glow = 0f
        private set

    /** 0..1 share of the recent hiss that carried voice (Z, J against S, Ș). */
    var hissVoiced = 0f
        private set

    /** Smoothed flatness of the recent hiss. */
    var flat = 0f
        private set

    private var levelTarget = 0f
    private var placeTarget = Float.NaN
    private var sinceHiss = Float.POSITIVE_INFINITY
    private var onFor = 0f
    private var offFor = 0f

    fun reset() {
        level = 0f; voicing = 0f; fricative = 0f; trill = 0f; blow = 0f; place = Float.NaN; hit = false; glow = 0f; hissVoiced = 0f; flat = 0f
        levelTarget = 0f; placeTarget = Float.NaN; sinceHiss = Float.POSITIVE_INFINITY; onFor = 0f; offFor = 0f
    }

    /**
     * One frame. [sNess] is the live place already recentred for the voice (NaN or ignored when [isFricative] is
     * false); [dtSeconds] the real time since the last frame.
     */
    fun step(
        dtSeconds: Float,
        rawLevel: Float,
        rawVoicing: Float,
        isFricative: Boolean,
        rawFricative: Float,
        sNess: Float,
        rawTrill: Float,
        rawBlow: Float,
        aim: LiveAim,
        fricativeVoiced: Boolean = false,
        /** The phoneme model's live answer ([LiveSpot.value]): 1 heard, 0 not heard, NaN none (the acoustic rule decides). */
        spotted: Float = Float.NaN,
        /** Spectral flatness of the hiss frame ([tech.fonia.audio.LiveLevels.hissFlatness]); 0 when unknown. */
        hissFlatness: Float = 0f,
    ) {
        val dt = dtSeconds.coerceIn(0f, 0.1f)
        // Dead zone: a small wobble of the input does not move the target at all.
        val lv = rawLevel.coerceIn(0f, 1f)
        if (abs(lv - levelTarget) >= pacing.liveDeadZone || lv == 0f) levelTarget = lv
        level = follow(level, levelTarget, dt, pacing.liveAttackMs, pacing.liveReleaseMs)
        voicing = follow(voicing, rawVoicing.coerceIn(0f, 1f), dt, pacing.liveAttackMs, pacing.liveReleaseMs)
        fricative = follow(fricative, if (isFricative) rawFricative.coerceIn(0f, 1f) else 0f, dt, pacing.liveAttackMs, pacing.liveReleaseMs)
        trill = follow(trill, rawTrill.coerceIn(0f, 1f), dt, pacing.liveAttackMs, pacing.liveReleaseMs)
        blow = follow(blow, rawBlow.coerceIn(0f, 1f), dt, pacing.liveAttackMs, pacing.liveReleaseMs)

        if (isFricative && !sNess.isNaN()) {
            hissVoiced = if (sinceHiss * 1000f > pacing.placeHoldMs) (if (fricativeVoiced) 1f else 0f)
                else follow(hissVoiced, if (fricativeVoiced) 1f else 0f, dt, VOICED_MS, VOICED_MS)
            flat = if (sinceHiss * 1000f > pacing.placeHoldMs) hissFlatness else follow(flat, hissFlatness, dt, FLAT_MS, FLAT_MS)
            sinceHiss = 0f
            val s = sNess.coerceIn(0f, 1f)
            if (placeTarget.isNaN() || abs(s - placeTarget) >= pacing.placeDeadZone) placeTarget = s
            place = if (place.isNaN()) placeTarget else follow(place, placeTarget, dt, pacing.placeAttackMs, pacing.placeAttackMs)
        } else {
            sinceHiss += dt
            if (sinceHiss * 1000f > pacing.placeHoldMs) {
                place = Float.NaN
                placeTarget = Float.NaN
            }
        }

        val modelSays = !spotted.isNaN() && aim != LiveAim.None
        val on = if (modelSays) spotted >= 0.5f else when (aim) {
            // "Lately": the live hiss flag flickers between 10 ms hops, so a hiss heard in the last 100 ms still counts.
            is LiveAim.Place -> !place.isNaN() && sinceHiss < HISS_RECENT_S && fricative > HISS_MIN && flat <= rules.flatMax &&
                (if (aim.sharp) place > rules.sharpMin else place < rules.roundMax) &&
                (aim.voiced == null || (if (aim.voiced) hissVoiced >= VOICED_SHARE else hissVoiced <= 1f - VOICED_SHARE))
            LiveAim.Trill -> trill > rules.trillMin
            LiveAim.Voice -> voicing > rules.voiceClear && fricative < HISS_MIN && sinceHiss > HISS_RECENT_S && level > rules.voiceLevel
            LiveAim.None -> false
        }
        if (on) {
            onFor += dt; offFor = 0f
            if (modelSays || onFor * 1000f >= rules.onMs) hit = true
        } else {
            offFor += dt; onFor = 0f
            if (offFor * 1000f >= pacing.onTargetOffMs) hit = false
        }
        glow = follow(glow, if (hit) 1f else 0f, dt, pacing.glowAttackMs, pacing.glowReleaseMs)
    }

    /**
     * The letter's inflation while listening. With a target ([voiceMoves] false) it stays at [base] (its resting size,
     * or the held share) and grows to 1 only while the target is heard; without one it swells with the voice up to
     * [Pacing.voiceInflationTop].
     */
    fun inflation(base: Float, voiceMoves: Boolean = false): Float {
        val voiced = if (!voiceMoves) base else {
            val top = pacing.voiceInflationTop.coerceAtLeast(base)
            base + (top - base) * (level * 1.6f).coerceAtMost(1f)
        }
        return (voiced + (1f - voiced) * glow).coerceIn(0f, 1f)
    }

    companion object {
        private const val HISS_RECENT_S = 0.1f

        /** A hiss weaker than this is room noise or breath, not a sound. */
        const val HISS_MIN = 0.08f

        /** Share of the recent hiss that must carry voice for Z / J, or its complement for S / Ș. */
        const val VOICED_SHARE = 0.5f
        private const val VOICED_MS = 60f
        private const val FLAT_MS = 80f

        /** One-pole follower with separate rise and fall time constants (milliseconds). */
        fun follow(value: Float, target: Float, dt: Float, attackMs: Float, releaseMs: Float): Float {
            val tau = (if (target > value) attackMs else releaseMs).coerceAtLeast(1f) / 1000f
            val k = 1f - exp(-dt / tau)
            return value + (target - value) * k
        }
    }
}
