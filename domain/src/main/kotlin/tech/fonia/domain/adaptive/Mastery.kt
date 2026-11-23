package tech.fonia.domain.adaptive

import tech.fonia.domain.Rung
import tech.fonia.domain.SoundID
import tech.fonia.domain.StringRawValue
import tech.fonia.domain.byRawValue
import kotlin.math.pow

// The adaptive pilot of docs/PLAN.md §7.4 (not on iOS). Every rule here comes from
// `fonia-android-plan/03_cercetare_produs.md` §1.2 (P5), §1.6 (T1–T3) and §2.5, which take it from the research hub
// (`rapoarte/G_ml_inovatii_feedback.md` §6, `rapoarte/J_dovezi_validare_reglementare_ecosistem.md` §1.3). Thresholds
// marked [S] are the hub's starting values, to be set with the logopeds in the pilot; [inf] marks a choice made here
// where the research says nothing.

/** Where the target sound sits in a practised item. The codes are the pilot log's (`SoundPosition`). */
enum class ItemPosition(override val rawValue: String) : StringRawValue {
    Isolated("isolated"),
    Initial("initial"),
    Medial("medial"),
    Final("final"),
    Sentence("sentence"),
    ;

    companion object {
        fun fromRawValue(rawValue: String): ItemPosition? = entries.byRawValue(rawValue)
    }
}

/** A take's verdict as the store keeps it (`Attempt.Outcome` raw values). */
enum class TakeOutcome(override val rawValue: String) : StringRawValue {
    Good("good"),
    Almost("almost"),
    NotYet("notYet"),
    Unclear("unclear"),
    NotJudged("notJudged"),
    ;

    /** P1's decided states: correct, or try again ("aproape", "încă nu"). Unclear and not judged decide nothing. */
    val isVerdict: Boolean get() = this == Good || this == Almost || this == NotYet

    companion object {
        fun fromRawValue(rawValue: String): TakeOutcome? = entries.byRawValue(rawValue)
    }
}

/** Who judged a take that counts as evidence. */
enum class EvidenceSource(override val rawValue: String) : StringRawValue {
    /** The phone's judge (the DSP detectors of `:dsp`; never the shadow scorer, which must not change anything). */
    Detector("detector"),

    /** An adult or a logoped who listened. */
    Adult("adult"),
}

/** One take as evidence about the sound: correct or not, and who said so. */
data class Evidence(val correct: Boolean, val source: EvidenceSource) {
    companion object {
        /**
         * T1 (03 §1.6; G §6.4): a sure verdict counts as 1 (good) or 0 (almost, not yet); an unsure one ("nu am auzit
         * clar", a take nobody judged) changes nothing; the adult's rating wins over the phone's. The learner's own
         * rating is never evidence: the research names only the detector and the adult.
         */
        fun of(machine: TakeOutcome, adult: TakeOutcome?): Evidence? = when {
            adult != null && adult.isVerdict -> Evidence(adult == TakeOutcome.Good, EvidenceSource.Adult)
            machine.isVerdict -> Evidence(machine == TakeOutcome.Good, EvidenceSource.Detector)
            else -> null
        }
    }
}

/** T1's unit: one child × sound × position × rung. The child is the profile the state belongs to. */
data class MasteryKey(val sound: SoundID, val position: ItemPosition, val rung: Rung)

/** P(mastered) of one position × rung of a sound, with what it rests on. */
data class KeyMastery(
    val position: ItemPosition,
    val rung: Rung,
    val pKnown: Double = Bkt.P_INIT,
    /** Takes that updated the estimate, ever. */
    val takes: Int = 0,
    /** The days (`YYYY-MM-DD`) on which a session ended with P ≥ [Bkt.MASTERED_AT], oldest first. */
    val masteredOn: List<String> = emptyList(),
) {
    /** T1's rule: P ≥ 0.9 in at least 2 sessions on different days. */
    val isMastered: Boolean get() = masteredOn.size >= Bkt.MASTERED_DAYS
}

/**
 * Bayesian knowledge tracing (Corbett & Anderson, UMUAI 1994; `pyBKT`, MIT: G §6.1 [80]) for one child × sound ×
 * position × rung (T1). Two hidden states, mastered or not; each judged take moves P(mastered) by Bayes' rule, then
 * the chance to learn is added. The hub found no knowledge tracing published for speech sounds (G §6.1), so the
 * parameters are not fitted: guess and slip are fixed from the judges' known error rates, as T1 asks.
 */
object Bkt {
    /** P(L0): a rung just reached is rarely mastered already. [S] */
    const val P_INIT = 0.10

    /** P(T), the chance to learn between two takes. Speech sounds settle over days of practice, not a few takes. [S] */
    const val P_LEARN = 0.01

    /**
     * Slip: a correct production the phone calls wrong. P1 (03 §1.2) caps false rejections of correct productions at
     * 10 %, the most the pilot accepts (the DSP judge rejected 1 of 122 correct adult words, `phones/EVAL.md`;
     * children are not measured yet).
     */
    const val DETECTOR_SLIP = 0.10

    /**
     * Guess: a production that is not there yet, called correct. The judges accepted 4 of 69 swapped words on the
     * content clips (`phones/EVAL.md`), P1 tolerates more false acceptances at first, and children's partial and
     * distorted productions are cases the judges have not met. Held at 0.3, the bound BKT fits usually keep the guess
     * under (with the slip under 0.1), so that a correct take still says something.
     */
    const val DETECTOR_GUESS = 0.30

    /** T1: "ratingul adultului cu pondere mai mare". An adult's rating moves P as much as this many judged takes. [S] */
    const val ADULT_WEIGHT = 2

    /** T1: a rung is passed at P ≥ 0.9 ... */
    const val MASTERED_AT = 0.90

    /** ... in at least 2 sessions on different days (T1; G §6.4: distributed practice). */
    const val MASTERED_DAYS = 2

    /**
     * A session ends at P ≥ 0.9 "for" a key only if it judged at least this many takes of it: one block's worth, so a
     * session that barely touched the key does not count as one of the two days. [inf]
     */
    const val MIN_TAKES_IN_SESSION = 6

    /** The days kept per key: enough for the rule and for the logoped to see the last ones. */
    const val KEPT_DAYS = 10

    /** P(mastered) after one take. */
    fun update(pKnown: Double, evidence: Evidence): Double {
        val weight = if (evidence.source == EvidenceSource.Adult) ADULT_WEIGHT else 1
        val ifMastered = (if (evidence.correct) 1 - DETECTOR_SLIP else DETECTOR_SLIP).pow(weight)
        val ifNotYet = (if (evidence.correct) DETECTOR_GUESS else 1 - DETECTOR_GUESS).pow(weight)
        val posterior = pKnown * ifMastered / (pKnown * ifMastered + (1 - pKnown) * ifNotYet)
        return posterior + (1 - posterior) * P_LEARN
    }

    /**
     * The key a take of [itemRung]'s material updates. The isolated rung is the sound alone and the rungs from
     * sentences up are connected speech, whatever the letters say ("ra-ra-ra" reads as three words); other rungs keep
     * the position the item has. Null (no key, no update) when the sound is not in the item, as in the rival word of a
     * minimal pair.
     */
    fun keyPosition(itemRung: Rung, itemPosition: ItemPosition?): ItemPosition? = when (itemRung) {
        Rung.Isolated -> ItemPosition.Isolated
        Rung.Sentences, Rung.Retell, Rung.Everyday -> ItemPosition.Sentence
        else -> itemPosition
    }
}
