package tech.fonia.phones

import kotlin.math.exp

/** Likelihood of one candidate for a target: the accepted pronunciation or one [alternative]. */
data class CandidateScore(
    /** Null for the accepted (correct) realisation. */
    val alternative: Alternative?,
    /** log P(word with this candidate at the target | audio), summed over the candidate's variants. */
    val logLikelihood: Double,
    /** The variant with the highest likelihood (for R: "r" or "ɾ"). */
    val bestVariant: List<String>,
)

/** GOP-SF of one occurrence of a target sound, restricted to its plausible alternatives. */
data class OccurrenceGop(
    val phoneIndex: Int,
    val sound: TargetSound,
    val correct: CandidateScore,
    val alternatives: List<CandidateScore>,
) {
    /** The alternative with the highest likelihood: the most likely substitute. */
    val best: CandidateScore = alternatives.maxBy { it.logLikelihood }

    /**
     * GOP-SF-SD restricted: log posterior of the accepted realisation among {accepted, alternatives}, with a uniform
     * prior (Cao et al. 2026 eq. 5–12 with the substitution set cut down as in Parikh et al. 2025). ≤ 0.
     */
    val gop: Double = correct.logLikelihood - alternatives.fold(correct.logLikelihood) { sum, c -> logAdd(sum, c.logLikelihood) }

    /** Log posterior ratio against the best alternative: > 0 when the target beats every alternative. */
    val lpr: Double = correct.logLikelihood - best.logLikelihood

    /** exp(gop): posterior of "said right" among the candidates. Uncalibrated (CTC posteriors are peaky). */
    val pCorrect: Double get() = exp(gop)
}

/**
 * Restricted segmentation-free GOP over CTC posteriors. For a target phone it runs one CTC forward pass per variant
 * of each candidate (the accepted realisation, each alternative, omission), with the rest of the word as context,
 * and compares their likelihoods. A word of 5–8 phones, 1 s of audio (50 frames) and 4–6 candidates costs well under
 * a millisecond; the acoustic model is the real cost.
 *
 * Label sequences are assembled in a reused buffer and the forward pass keeps its rows, so scoring allocates per
 * candidate (the small result objects) but never per frame. Not thread-safe.
 */
class RestrictedGop {
    private val ctc = CtcForward()
    private var labels = IntArray(64)

    /** Scores occurrence [phoneIndex] of [pronunciation] (which must realise [sound] there). */
    fun score(
        posteriors: Posteriors,
        pronunciation: Pronunciation,
        phoneIndex: Int,
        sound: TargetSound,
        tapAccepted: Boolean = true,
        alternatives: List<Alternative> = Alternatives.of(sound, tapAccepted),
        accepted: List<List<String>> = Alternatives.accepted(sound, tapAccepted),
    ): OccurrenceGop {
        val plan = LabelPlan(pronunciation)
        val correct = candidate(posteriors, plan, phoneIndex, null, accepted)
        val scored = alternatives.map { candidate(posteriors, plan, phoneIndex, it, it.variants) }
        return OccurrenceGop(phoneIndex, sound, correct, scored)
    }

    /** log P(labels | audio) for the whole pronunciation as written. */
    fun logLikelihood(posteriors: Posteriors, pronunciation: Pronunciation): Double {
        val plan = LabelPlan(pronunciation)
        return sequence(posteriors, plan, -1, IntArray(0))
    }

    private fun candidate(
        posteriors: Posteriors,
        plan: LabelPlan,
        phoneIndex: Int,
        alternative: Alternative?,
        variants: List<List<String>>,
    ): CandidateScore {
        var total = NEG_INF
        var best = NEG_INF
        var bestVariant = variants.first()
        for (variant in variants) {
            val ids = IntArray(variant.size) { ZipaInventory.id(variant[it]) }
            val ll = sequence(posteriors, plan, phoneIndex, ids)
            total = logAdd(total, ll)
            if (ll > best) {
                best = ll
                bestVariant = variant
            }
        }
        return CandidateScore(alternative, total, bestVariant)
    }

    /** CTC log-likelihood of the plan with phone [replaced] swapped for [ids] (no swap when [replaced] < 0). */
    private fun sequence(posteriors: Posteriors, plan: LabelPlan, replaced: Int, ids: IntArray): Double {
        var count = 0
        fun put(id: Int) {
            if (count == labels.size) labels = labels.copyOf(labels.size * 2)
            labels[count++] = id
        }
        for (i in plan.phones.indices) {
            if (plan.wordStart[i]) put(ZipaInventory.WORD_START)
            val tokens = if (i == replaced) ids else plan.phones[i]
            for (id in tokens) put(id)
        }
        return ctc.logLikelihood(posteriors, labels, count)
    }

    /** Token ids of each phone, looked up once per pronunciation. */
    private class LabelPlan(pronunciation: Pronunciation) {
        val phones: Array<IntArray> = Array(pronunciation.phones.size) { i ->
            val tokens = pronunciation.phones[i].tokens
            IntArray(tokens.size) { ZipaInventory.id(tokens[it]) }
        }
        val wordStart: BooleanArray = BooleanArray(pronunciation.phones.size) { pronunciation.phones[it].wordStart }
    }
}
