package tech.fonia.features.guide

import tech.fonia.design.Rating
import tech.fonia.domain.CoachFault
import tech.fonia.domain.CoachMicJudge
import tech.fonia.domain.CoachPlan
import tech.fonia.domain.FoniaContent
import tech.fonia.domain.Language
import tech.fonia.domain.SoundDetector
import tech.fonia.domain.SoundID
import tech.fonia.dsp.DSPThresholds
import tech.fonia.features.session.TakeVerdict
import tech.fonia.features.session.VerdictWriter
import tech.fonia.store.Attempt

/**
 * How the mirror judges a take (iOS `CoachEar.swift`). Each microphone step names what the phone can check there: the
 * sound itself, or only that a voice was on. Before, every step was judged as the plan's first syllable: L ended on
 * "Cum a sunat?" with nobody to answer, and the "mmmm" of Z and J was judged as "za" and "ja".
 */
object CoachEar {
    sealed interface Way {
        /** Only the voice is checked: a hum, or a sound the phone has no check for. */
        data object Voice : Way

        /** The sound's own check, as in a lesson. */
        data class Sound(val sound: SoundID) : Way

        /** The phone hears the take but has nothing to measure it with. */
        data object Unjudged : Way
    }

    /** Voice a long hum has to hold: the same quarter second that counts as speech. */
    val heldVoice: Double = DSPThresholds.v1.judge.voicedSeconds

    /** A syllable's vowel is short, so it needs less. */
    const val SHORT_VOICE = 0.12

    fun way(mic: CoachMicJudge?, plan: CoachPlan, content: FoniaContent): Way {
        if (mic == CoachMicJudge.Voice) return Way.Voice
        if (mic == CoachMicJudge.Th || plan.language != Language.Ro) return Way.Unjudged
        val sound = SoundID.fromRawValue(plan.key) ?: return Way.Unjudged
        if (content.sound(sound).detector == SoundDetector.None) return Way.Unjudged
        return Way.Sound(sound)
    }

    fun voice(heard: Boolean, voicedSeconds: Double, needed: Double): TakeVerdict {
        if (voicedSeconds >= needed) return TakeVerdict(rating = Rating.yes, outcome = Attempt.Outcome.Good, title = "Am auzit vocea")
        if (!heard) return VerdictWriter.notHeard
        return TakeVerdict(rating = Rating.notYet, outcome = Attempt.Outcome.NotYet, title = "Nu aud vocea", tip = "Spune mai lung, cu voce.")
    }

    /** The mirror has no one to ask, so it says plainly what it can't do. */
    fun unjudged(heard: Boolean): TakeVerdict {
        if (!heard) return VerdictWriter.notHeard
        return TakeVerdict(rating = null, outcome = Attempt.Outcome.NotJudged, title = "Te-am auzit", tip = "Sunetul ăsta nu-l pot verifica. Ascultă-te și tu.")
    }

    /**
     * The line under a take that did not pass. A missing voice gets the plan's own "unvoiced" fix; the sound check
     * keeps the plan's first fix, as before; what the phone can't check, or did not hear, keeps its own line.
     */
    fun tip(way: Way, verdict: TakeVerdict, plan: CoachPlan): String? = when {
        verdict.rating == null -> verdict.tip
        way == Way.Voice -> plan.faults.firstOrNull { it.fault == CoachFault.Unvoiced }?.tip ?: verdict.tip
        way is Way.Sound -> plan.faults.firstOrNull()?.tip ?: verdict.tip
        else -> verdict.tip
    }
}
