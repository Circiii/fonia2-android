package tech.fonia.phones

/** What the child hears back (KR only: never "wrong", PLAN §3). */
enum class Verdict {
    /** „Corect”. */
    Correct,

    /** „Mai încearcă”: the target came out as one of its alternatives, clearly. */
    TryAgain,

    /** „Nu am auzit clar”: the scorer abstains. */
    NotHeard,
}

/** Why the scorer abstained. */
enum class Abstention {
    /** Less audio than the model needs (under ~0.2 s of output frames). */
    TooShort,

    /** The best candidate explains the audio much worse than the unconstrained phone path: another word, noise. */
    PoorFit,

    /** The target is neither clearly right nor clearly one alternative: the margin falls between the thresholds. */
    Uncertain,

    /** The word has no occurrence of the sound asked for. */
    NoTarget,
}

/**
 * Decision thresholds. LPR is the log posterior ratio of the target against its best alternative, in nats.
 *
 * The defaults were fixed from what the numbers mean, before any evaluation run, and not tuned on the clips:
 * "try again" needs the best alternative to be at least e³ ≈ 20 times as likely as the target (a strong Bayes factor
 * under equal priors), "correct" needs the target to be the likeliest candidate, the band between them abstains, and
 * a word whose best candidate trails the unconstrained phone path by more than 3 nats per label was not this word.
 * None is calibrated on children: the pilot sets them, per sound, for false alarms under 5 % (`phones/EVAL.md`).
 */
data class ScoringPolicy(
    val acceptLpr: Double = 0.0,
    val rejectLpr: Double = -3.0,
    /**
     * Mean log-likelihood deficit per label (phones and word marks), against the unconstrained best path, under which
     * the audio is taken not to be this word.
     */
    val minFitPerLabel: Double = -3.0,
    /** Output frames (20 ms each) below which the scorer abstains. */
    val minFrames: Int = 10,
    /** PLAN §7.1 P3: the tapped R counts as correct, except in the trill games. */
    val tapAccepted: Boolean = true,
) {
    init {
        require(rejectLpr <= acceptLpr) { "rejectLpr must not exceed acceptLpr" }
    }
}

/** One occurrence of the target sound in the word. */
data class TargetScore(
    val gop: OccurrenceGop,
    val letters: IntRange,
    val verdict: Verdict,
    /** Posterior of [verdict] (pCorrect for Correct, 1 − pCorrect for TryAgain), 0 when abstaining. Uncalibrated. */
    val confidence: Double,
) {
    val sound: TargetSound get() = gop.sound

    /** Log posterior of the correct realisation among the candidates (the GOP-SF score). */
    val score: Double get() = gop.gop
    val lpr: Double get() = gop.lpr
    val pCorrect: Double get() = gop.pCorrect

    /** The most likely substitute, whatever the verdict. */
    val heardAs: Alternative get() = gop.best.alternative!!
}

/** The result for one word and one target sound. */
data class WordScore(
    val pronunciation: Pronunciation,
    val sound: TargetSound,
    /** One entry per occurrence of [sound], in word order. */
    val targets: List<TargetScore>,
    val verdict: Verdict,
    val confidence: Double,
    /** The substitute behind a TryAgain (the occurrence with the lowest LPR); null otherwise. */
    val heardAs: Alternative?,
    val abstention: Abstention?,
    /** (best candidate log-likelihood − unconstrained best path) / labels: near 0 when the audio is this word. */
    val fitPerLabel: Double,
    /** Model output frames (20 ms each). */
    val frames: Int,
)

/**
 * Phone-level pronunciation scoring for a known word and target sound: ZIPA posteriors, then GOP-SF restricted to the
 * target's plausible alternatives ([Alternatives]), then a three-state verdict with abstention.
 *
 * Input: the word's audio at 16 kHz, cut by the VAD with some margin, before any noise reduction (the branch that
 * feeds Whisper and ZIPA, not the 48 kHz one the S/Ș DSP reads). Not thread-safe: one scorer per worker thread.
 */
class PhoneScorer(private val model: AcousticModel, val policy: ScoringPolicy = ScoringPolicy()) {
    private val gop = RestrictedGop()

    fun score(samples16k: FloatArray, word: String, sound: TargetSound): WordScore =
        score(samples16k, RomanianG2P.pronounce(word), sound)

    fun score(samples16k: FloatArray, pronunciation: Pronunciation, sound: TargetSound): WordScore =
        judge(model.posteriors(samples16k), pronunciation, sound)

    /** The decision on precomputed posteriors (pure: the evaluation and the tests call it directly). */
    fun judge(posteriors: Posteriors, pronunciation: Pronunciation, sound: TargetSound): WordScore {
        val occurrences = pronunciation.occurrences(sound)
        if (occurrences.isEmpty()) {
            return WordScore(pronunciation, sound, emptyList(), Verdict.NotHeard, 0.0, null, Abstention.NoTarget, Double.NaN, posteriors.frames)
        }
        if (posteriors.frames < policy.minFrames) {
            return WordScore(pronunciation, sound, emptyList(), Verdict.NotHeard, 0.0, null, Abstention.TooShort, Double.NaN, posteriors.frames)
        }
        val gops = occurrences.map { gop.score(posteriors, pronunciation, it, sound, policy.tapAccepted) }
        val bestCandidate = gops.maxOf { maxOf(it.correct.logLikelihood, it.best.logLikelihood) }
        val labels = pronunciation.phones.size + pronunciation.phones.count { it.wordStart }
        val fit = (bestCandidate - posteriors.bestPathLogProb()) / labels
        val heard = fit >= policy.minFitPerLabel
        val targets = gops.map { g ->
            val verdict = when {
                !heard -> Verdict.NotHeard
                g.lpr >= policy.acceptLpr -> Verdict.Correct
                g.lpr <= policy.rejectLpr -> Verdict.TryAgain
                else -> Verdict.NotHeard
            }
            val confidence = when (verdict) {
                Verdict.Correct -> g.pCorrect
                Verdict.TryAgain -> 1.0 - g.pCorrect
                Verdict.NotHeard -> 0.0
            }
            TargetScore(g, pronunciation.phones[g.phoneIndex].letters, verdict, confidence)
        }
        val rejected = targets.filter { it.verdict == Verdict.TryAgain }.minByOrNull { it.lpr }
        return when {
            !heard -> WordScore(pronunciation, sound, targets, Verdict.NotHeard, 0.0, null, Abstention.PoorFit, fit, posteriors.frames)
            rejected != null -> WordScore(pronunciation, sound, targets, Verdict.TryAgain, rejected.confidence, rejected.heardAs, null, fit, posteriors.frames)
            targets.all { it.verdict == Verdict.Correct } ->
                WordScore(pronunciation, sound, targets, Verdict.Correct, targets.minOf { it.confidence }, null, null, fit, posteriors.frames)
            else -> WordScore(pronunciation, sound, targets, Verdict.NotHeard, 0.0, null, Abstention.Uncertain, fit, posteriors.frames)
        }
    }
}
