package tech.fonia.judge

import tech.fonia.dsp.SibilantPlace
import tech.fonia.model.ErrorKind
import tech.fonia.model.SoundId
import tech.fonia.model.Verdict

/**
 * The one change that would help most, as an articulator instruction KEY (the app owns the words and the drawing):
 * lips, tongue tip, voice, air, length. Chosen from the most likely error kind, or, when no kind is clear, from the
 * cue that misses its zone by the most.
 */
enum class HintKey(val raw: String) {
    /** Round the lips (Ș, J, CE, GE said too S-like). */
    LipsRound("lipsRound"),

    /** Spread the lips, smile (S, Z, Ț said too Ș-like). */
    LipsSpread("lipsSpread"),

    /** Tongue tip down behind the lower teeth, air through the middle (a lateral S). */
    TongueTipDown("tongueTipDown"),

    /** Tongue tip up behind the upper teeth (L, the start of Ț/CE/GE, R that glides). */
    TongueTipUp("tongueTipUp"),

    /** Tongue back inside, behind the teeth (interdental). */
    TongueBack("tongueBack"),

    /** Tongue tip up, touch, then let the air out: the closure-then-release of an affricate said without one. */
    StopThenRelease("stopThenRelease"),

    /** Tongue tip taps / vibrates behind the upper teeth (R said as L, a vowel, uvular, or missing). */
    TapTongue("tapTongue"),

    /** Hum while hissing (Z, J, GE said voiceless). */
    AddVoice("addVoice"),

    /** Whisper it, no hum (S, Ș, Ț, CE said voiced). */
    NoVoice("noVoice"),

    /** More air, a stronger hiss (weak frication, or a stop instead of a hiss). */
    MoreAir("moreAir"),

    /** Hold the sound longer (too short, or missing). */
    HoldLonger("holdLonger"),
}

data class Hint(val key: HintKey, val confidence: Float, /** "kind" (from the error class) or "cue" (from a measured cue). */ val from: String)

/** A measured value against what the target needs; [ok] is null when the take cannot tell. */
data class Gauge(val value: Float, val needed: Float, val ok: Boolean?)

/**
 * Where the hiss sat between Ș (0) and S (1), the target zone, and whose reference placed it ("personal" when the
 * person's own anchors set the boundary, "group" for the talker group's prior).
 */
data class PlaceAxis(val value: Float, val zoneLow: Float, val zoneHigh: Float, val reference: String) {
    val inZone: Boolean get() = value in zoneLow..zoneHigh
}

/** Voicing found vs expected; [fraction] is the voiced share of the frication's core. */
data class VoicingCue(val present: Boolean?, val expected: Boolean, val fraction: Float)

/** Closure (silence or burst) then release (frication) for Ț, CE, GE. */
data class ClosureCue(val closure: Boolean?, val release: Boolean?, val gapMs: Float, val burstDb: Float)

/**
 * What the UI can draw for one take (no text): each cue against its target zone, and the single most useful change.
 * Null fields do not apply to this sound or could not be measured on this take.
 */
data class TakeVisual(
    val placeAxis: PlaceAxis? = null,
    val voicing: VoicingCue? = null,
    /** R: 0..1 strength of the tap/trill (events and the amplitude dip) against what a tap needs. */
    val trill: Gauge? = null,
    val closure: ClosureCue? = null,
    /** Frication duration, ms, against the minimum for the level. */
    val duration: Gauge? = null,
    /** Frication level relative to the vowel (dB), against the floor (personal when the person has a reference). */
    val loudness: Gauge? = null,
    val hint: Hint? = null,
)

/** Builds [TakeVisual] from a decided take (pure; thresholds from `population.json` → `judge2.visual`). */
object TakeVisuals {
    fun of(
        request: JudgeRequest,
        features: Map<String, Float>,
        verdict: Verdict,
        topError: ErrorKind?,
        topErrorProbability: Float,
        pCorrect: Float,
        t: VisualThresholds,
        reference: PersonalReference? = null,
    ): TakeVisual {
        val sound = request.sound
        val tr = traits(sound)
        fun f(name: String): Float = features[name] ?: Float.NaN

        val sNess = f(Features.S_NESS)
        val place = tr.place
        val axis = if (place != null && !sNess.isNaN() && !f(Features.TOWARD).isNaN()) {
            val half = t.placeZoneHalf
            val personal = request.calibration?.anchors?.let { a -> a.count { it.place == SibilantPlace.S } >= 3 && a.count { it.place == SibilantPlace.SH } >= 3 } == true
            if (place == SibilantPlace.S) PlaceAxis(sNess, 0.5f + half, 1f, if (personal) "personal" else "group")
            else PlaceAxis(sNess, 0f, 0.5f - half, if (personal) "personal" else "group")
        } else null

        val voiced = f(Features.VOICED_FRAC)
        val voicing = if (tr.family == Family.Sibilant || tr.family == Family.Affricate) {
            if (voiced.isNaN()) null else {
                val judged = !f(Features.VOICING).isNaN() || !tr.voiced
                VoicingCue(if (judged) voiced >= t.voicedFraction else null, tr.voiced, voiced)
            }
        } else null

        val trill = if (sound == SoundId.R) {
            val tap = f(Features.R_TAP)
            val dip = f(Features.DIP_DB)
            val strength = when {
                tap == 1f -> 1f
                !dip.isNaN() -> ((dip - t.dipFloorDb) / (t.dipFullDb - t.dipFloorDb)).coerceIn(0f, 1f)
                tap == 0f -> 0f
                else -> Float.NaN
            }
            if (strength.isNaN()) null else Gauge(strength, t.trillNeeded, strength >= t.trillNeeded)
        } else null

        val closure = if (tr.family == Family.Affricate && f(Features.PRESENT) == 1f) {
            val gap = f(Features.GAP_RAW)
            val burst = f(Features.BURST)
            val closed = when {
                !gap.isNaN() && gap >= t.closureGapMs -> true
                !burst.isNaN() && burst >= t.closureBurstDb -> true
                gap.isNaN() && burst.isNaN() -> null
                else -> false
            }
            val fric = f(Features.FRIC_DUR)
            ClosureCue(closed, if (fric.isNaN()) null else fric >= t.releaseMinMs, gap, burst)
        } else null

        val dur = f(Features.FRIC_DUR)
        val needed = when {
            tr.family == Family.Affricate -> t.releaseMinMs
            request.level == Level.Isolated -> t.isolatedMinMs
            else -> t.wordMinMs
        }
        val duration = if ((tr.family == Family.Sibilant || tr.family == Family.Affricate) && !dur.isNaN()) Gauge(dur, needed, dur >= needed) else null

        val rel = f(Features.REL_LEVEL)
        val floor = reference?.loudnessFloor(sound, t) ?: t.relLevelFloorDb
        val loudness = if (tr.family == Family.Sibilant && !rel.isNaN()) Gauge(rel, floor, rel >= floor) else null

        val hint = if (verdict == Verdict.Correct) null else
            fromKind(sound, topError, topErrorProbability, dur, needed, t)
                ?: fromCues(sound, axis, voicing, closure, duration, loudness, trill, pCorrect)
        return TakeVisual(axis, voicing, trill, closure, duration, loudness, hint)
    }

    private fun fromKind(sound: SoundId, kind: ErrorKind?, p: Float, dur: Float, needed: Float, t: VisualThresholds): Hint? {
        if (kind == null || p < t.minKindProbability) return null
        val sLike = traits(sound).place == SibilantPlace.S
        val key = when (kind) {
            ErrorKind.PlaceSwap, ErrorKind.AffricatePlace -> if (sLike) HintKey.LipsSpread else HintKey.LipsRound
            ErrorKind.Deaffrication -> HintKey.StopThenRelease
            ErrorKind.Stopping -> HintKey.MoreAir
            ErrorKind.Omission -> if (sound == SoundId.R) HintKey.TapTongue else if (sound == SoundId.L) HintKey.TongueTipUp else HintKey.HoldLonger
            ErrorKind.WeakOrShort -> if (!dur.isNaN() && dur < needed) HintKey.HoldLonger else HintKey.MoreAir
            ErrorKind.Devoicing -> HintKey.AddVoice
            ErrorKind.Lateral -> HintKey.TongueTipDown
            ErrorKind.Interdental -> HintKey.TongueBack
            ErrorKind.RtoL, ErrorKind.RtoVowel -> HintKey.TapTongue
            ErrorKind.RtoGlide, ErrorKind.RUvular -> HintKey.TongueTipUp
            ErrorKind.LtoGlide, ErrorKind.LtoR -> HintKey.TongueTipUp
        }
        return Hint(key, p, "kind")
    }

    /** No clear error class: the cue that misses its zone by the most, its confidence scaled by 1 − p(correct). */
    private fun fromCues(
        sound: SoundId, axis: PlaceAxis?, voicing: VoicingCue?, closure: ClosureCue?, duration: Gauge?, loudness: Gauge?,
        trill: Gauge?, pCorrect: Float,
    ): Hint? {
        val doubt = (1f - pCorrect).coerceIn(0f, 1f)
        val candidates = ArrayList<Pair<HintKey, Float>>()
        if (axis != null && !axis.inZone) {
            val miss = if (axis.value < axis.zoneLow) axis.zoneLow - axis.value else axis.value - axis.zoneHigh
            candidates += (if (traits(sound).place == SibilantPlace.S) HintKey.LipsSpread else HintKey.LipsRound) to miss.coerceIn(0f, 1f)
        }
        if (voicing?.present != null && voicing.present != voicing.expected) {
            candidates += (if (voicing.expected) HintKey.AddVoice else HintKey.NoVoice) to kotlin.math.abs(voicing.fraction - 0.5f) * 2f
        }
        if (closure?.closure == false) candidates += HintKey.StopThenRelease to 0.6f
        if (duration?.ok == false && duration.needed > 0f) candidates += HintKey.HoldLonger to (1f - duration.value / duration.needed).coerceIn(0f, 1f)
        if (loudness?.ok == false) candidates += HintKey.MoreAir to ((loudness.needed - loudness.value) / 12f).coerceIn(0f, 1f)
        if (trill?.ok == false) candidates += HintKey.TapTongue to (trill.needed - trill.value).coerceIn(0f, 1f)
        val best = candidates.maxByOrNull { it.second } ?: return null
        return Hint(best.first, best.second * doubt, "cue")
    }
}
