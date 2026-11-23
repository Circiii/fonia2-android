package tech.fonia.domain.adaptive

import tech.fonia.domain.Exercise
import tech.fonia.domain.FeedbackLevel
import tech.fonia.domain.Progression
import tech.fonia.domain.SeededRandom
import tech.fonia.domain.StringRawValue
import tech.fonia.domain.byRawValue
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * P5 (03 §1.2; J §1.3, after Maas et al. 2008 [36] and Austermann Hula et al. 2008 [37]): two ways to practise a sound.
 * Acquisition: a new sound, or one still under ~60 %: knowledge of performance on every take, blocked practice, few
 * targets, the model heard often. Generalization: from ~80 % in 2 sessions: knowledge of results on 50 % → 20 % of
 * takes with a summary after the block, random practice, varied contexts and words not practised yet.
 */
enum class PracticeMode(override val rawValue: String) : StringRawValue {
    Acquisition("acquisition"),
    Generalization("generalization"),
    ;

    companion object {
        fun fromRawValue(rawValue: String): PracticeMode? = entries.byRawValue(rawValue)
    }
}

/** What the logoped chose for a sound (P5: "logopedul alege"): one of the modes, or the rule below. */
enum class ModeSetting(override val rawValue: String) : StringRawValue {
    Auto("auto"),
    Acquisition("acquisition"),
    Generalization("generalization"),
    ;

    companion object {
        fun fromRawValue(rawValue: String): ModeSetting? = entries.byRawValue(rawValue)
    }
}

/** One finished session on a sound: its takes with a verdict in the adaptive blocks, retries left out. */
data class SessionTally(val day: String, val good: Int, val judged: Int) {
    val rate: Double get() = if (judged <= 0) 0.0 else good.toDouble() / judged
}

/** The mode rule for "automat" (P5, J §1.3). The thresholds are engineering proposals the logopeds set in the pilot [S]. */
object PracticeModes {
    /** "sunet nou sau < ~60% corect": acquisition. */
    const val ACQUISITION_BELOW = 0.60

    /** "≥ ~80% în 2 sesiuni": generalization ... */
    const val GENERALIZATION_AT = 0.80

    /** ... over the last 2 sessions on the sound. */
    const val GENERALIZATION_SESSIONS = 2

    /** A session counts for the rule only with a block's worth of takes judged on the sound. [inf] */
    val MIN_JUDGED_PER_SESSION = Progression.blockSize

    /** Sessions kept per sound: the rule needs two, the logoped sees the last ones. */
    const val KEPT_SESSIONS = 10

    /** Acquisition's "puține ținte": distinct items in a block, each said in a row (blocked practice). [inf] */
    const val ACQUISITION_TARGETS_PER_BLOCK = 2

    enum class Reason(override val rawValue: String) : StringRawValue {
        /** The logoped (or a parent) set the mode for this sound. */
        SetByAdult("set"),

        /** No session on the sound yet. */
        NewSound("new"),

        /** The last session was under [ACQUISITION_BELOW]. */
        Below60("below60"),

        /** The last [GENERALIZATION_SESSIONS] sessions were at or over [GENERALIZATION_AT]. */
        AtLeast80In2("atLeast80in2"),

        /** In between: the mode of the last session stays. */
        Kept("kept"),
        ;

        companion object {
            fun fromRawValue(rawValue: String): Reason? = entries.byRawValue(rawValue)
        }
    }

    data class Choice(val mode: PracticeMode, val reason: Reason)

    /** The mode for the next session of a sound, from its [setting], the [previous] mode and its past [sessions] (oldest first). */
    fun choose(setting: ModeSetting, previous: PracticeMode?, sessions: List<SessionTally>): Choice {
        when (setting) {
            ModeSetting.Acquisition -> return Choice(PracticeMode.Acquisition, Reason.SetByAdult)
            ModeSetting.Generalization -> return Choice(PracticeMode.Generalization, Reason.SetByAdult)
            ModeSetting.Auto -> Unit
        }
        val counted = sessions.filter { it.judged >= MIN_JUDGED_PER_SESSION }
        val last = counted.lastOrNull() ?: return Choice(PracticeMode.Acquisition, Reason.NewSound)
        val window = counted.takeLast(GENERALIZATION_SESSIONS)
        if (window.size == GENERALIZATION_SESSIONS && window.all { it.rate >= GENERALIZATION_AT }) {
            return Choice(PracticeMode.Generalization, Reason.AtLeast80In2)
        }
        if (last.rate < ACQUISITION_BELOW) return Choice(PracticeMode.Acquisition, Reason.Below60)
        return Choice(previous ?: PracticeMode.Acquisition, Reason.Kept)
    }
}

/**
 * T3 (03 §1.6; G §6.2, §6.4 item 4) and §2.5: the app gives knowledge of results ("corect" / "încă nu"), and how often
 * fades as success rises; knowledge of performance (the live meter and the cue) is for acquisition. In the app's levels:
 * `teach` is knowledge of performance, `quick` is knowledge of results in the moment, `silent` waits for the block's
 * summary.
 */
object FeedbackFading {
    /** "KR pe ~50% din încercări, alese aleator" (T3; Lowe & Buchwald 2017: 50 % gave the best retention, G [77]) ... */
    const val KR_START = 0.50

    /** ... fading to 20 % (P5, J §1.3: "feedback pe 50% → 20% dintre încercări"). */
    const val KR_END = 0.20

    /** The fade starts at the success that opens generalization ... */
    const val FADE_FROM = PracticeModes.GENERALIZATION_AT

    /** ... and ends at blocks with every take good. [inf] */
    const val FADE_TO = 1.0

    /** The share of takes that get knowledge of results, from the success of the last blocks (null before any). */
    fun krShare(recentSuccess: Double?): Double {
        val success = recentSuccess ?: return KR_START
        if (success <= FADE_FROM) return KR_START
        if (success >= FADE_TO) return KR_END
        return KR_START + (KR_END - KR_START) * (success - FADE_FROM) / (FADE_TO - FADE_FROM)
    }

    /** That share as takes of a block, at least one: on blocks of six, 3 at 50 %, 2 in between, 1 at 20 %. */
    fun krCount(share: Double, takes: Int = Progression.blockSize): Int = min(takes, max(1, (share * takes).roundToInt()))

    /**
     * One level per production of a block, drawn at random from [seed] as `FeedbackSchedule` does. Acquisition, or
     * knowledge of performance after a block under half (T2), gets `teach` on every take; generalization gets `quick`
     * on [krCount] of them and `silent` on the rest. A held sound keeps the live meter whatever the mode, as in the
     * iOS mixed block ("still being set up").
     */
    fun levels(mode: PracticeMode, kp: Boolean, recentSuccess: Double?, exercises: List<Exercise>, seed: ULong): List<FeedbackLevel> {
        if (mode == PracticeMode.Acquisition || kp) return exercises.map { FeedbackLevel.Teach }
        val count = krCount(krShare(recentSuccess), exercises.size)
        val drawn = SeededRandom(seed).shuffled(List(count) { FeedbackLevel.Quick } + List(exercises.size - count) { FeedbackLevel.Silent })
        return exercises.mapIndexed { index, exercise -> if (exercise is Exercise.Sustain) FeedbackLevel.Teach else drawn[index] }
    }
}
