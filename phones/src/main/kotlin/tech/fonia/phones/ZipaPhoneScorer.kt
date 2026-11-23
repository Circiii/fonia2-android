package tech.fonia.phones

import tech.fonia.judge.JudgeRequest
import tech.fonia.judge.Level
import tech.fonia.judge.PhoneEvidence
import tech.fonia.judge.PhoneScorerPort
import tech.fonia.judge.Position
import tech.fonia.live.Clip
import tech.fonia.speech.SpeechResampler
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.random.Random

/**
 * The judge's full head input (plan 03 §5, E3): ZIPA posteriors → restricted GOP over the widened confusion set of the
 * target ([ConfusionSets]) → [PhoneEvidence] for `TakeJudge.decide`.
 *
 * Per take:
 * 1. The clip at 16 kHz ([to16k] resamples the 48 kHz `Clip`), inside 250 ms of −75 dBFS noise with 10 ms fades, the
 *    margin `phones/EVAL.md` found steadier on short clips (CTC needs blank frames around the first and last phone).
 * 2. What should have been said ([pronunciation]): the Romanian G2P of the prompt for syllables, words and sentences;
 *    the target alone for an isolated sound, held or repeated (1–4 copies), so "ssss" is one target, not four.
 * 3. One CTC forward pass per candidate (accepted, each confusion, omission) with the rest of the word as context;
 *    `lpr.<key>` = log P(target) − log P(candidate), clipped to ± [Config.lprClip] nats. With several occurrences
 *    ([Position.Any], sentences, or the medial ones), the occurrence least likely correct speaks for the take.
 * 4. Reliability: enough frames, the word's best candidate within [Config.minFitPerLabel] nats per label of the
 *    unconstrained best path, and a Viterbi alignment ([CtcAligner]) that gives the winning candidate at least one
 *    frame. An unreliable answer still carries its numbers (for the trace) but the judge falls back to the fast head.
 *
 * Not thread-safe (the model's buffers, the GOP scratch): one instance per worker thread; [score] is synchronized so a
 * shared instance is merely serialized.
 */
class ZipaPhoneScorer(
    private val model: AcousticModel,
    val config: Config = Config(),
    /** Saved in the trace (`PhoneEvidence.model`), e.g. `zipa-small-crctc-500k-int8@dfe87c976626`. */
    val modelName: String = "zipa-small-crctc-500k-int8",
) : PhoneScorerPort {
    data class Config(
        /** R tapped [ɾ] counts as correct (false in the trill games). */
        val tapAccepted: Boolean = true,
        /** Output frames (20 ms) below which the answer is unreliable. */
        val minFrames: Int = 10,
        /** Fit per label under which a word's alignment is unreliable (the judge's word gate fires there too). */
        val minFitPerLabel: Double = -3.0,
        /**
         * The same for isolated sounds and syllables, stricter: no word gate stands behind them, and a poor fit there
         * means the model heard another sound sequence (final J heard as nothing, `EVAL_v1.md`).
         */
        val minFitPerLabelShort: Double = -2.0,
        val lprClip: Double = 12.0,
        val padSeconds: Double = 0.25,
        val padDbfs: Double = -75.0,
    )

    private val gop = RestrictedGop()
    private val aligner = CtcAligner()

    /** Wall clock of the last [score], split (fbank+network, scoring), microseconds. */
    @Volatile var lastModelMicros = 0L
        private set
    @Volatile var lastScoringMicros = 0L
        private set

    @Synchronized
    override fun score(samples16k: FloatArray, request: JudgeRequest): PhoneEvidence? {
        val target = TargetSound.fromRawValue(request.sound.raw) ?: return null
        val pronunciation = pronunciation(request, target) ?: return null
        val t0 = System.nanoTime()
        val posteriors = model.posteriors(pad(samples16k, config))
        val t1 = System.nanoTime()
        val evidence = evidence(posteriors, pronunciation, request, target)
        val t2 = System.nanoTime()
        lastModelMicros = (t1 - t0) / 1000
        lastScoringMicros = (t2 - t1) / 1000
        return evidence.copy(micros = (t2 - t0) / 1000)
    }

    /** [score] on a captured take at any rate. */
    fun scoreClip(clip: Clip, request: JudgeRequest): PhoneEvidence? =
        if (clip.isEmpty || clip.sampleRate <= 0) null else score(to16k(clip), request)

    /** The decision on precomputed posteriors (of the padded clip). Pure: tests and the evaluation call it directly. */
    fun evidence(posteriors: Posteriors, pronunciation: Pronunciation, request: JudgeRequest, target: TargetSound): PhoneEvidence {
        val notes = ArrayList<String>()
        val all = pronunciation.occurrences(target)
        if (all.isEmpty()) return PhoneEvidence(emptyMap(), null, posteriors.frames, modelName, reliable = false, notes = listOf("noTarget"))
        if (posteriors.frames < config.minFrames) {
            return PhoneEvidence(emptyMap(), null, posteriors.frames, modelName, reliable = false, notes = listOf("tooShort"))
        }
        val isolated = request.level == Level.Isolated
        val chosen = choose(pronunciation, all, request.position)
        val phones = pronunciation.phones
        // Per occurrence: its candidates (J and Z at the end of a word accept the voiceless sound, ConfusionSets.accepted).
        val scored = chosen.map { index ->
            val wordFinal = !isolated && (index == phones.lastIndex || phones[index + 1].wordStart)
            val candidates = ConfusionSets.of(target, config.tapAccepted, wordFinal)
            val alternatives = candidates.map { c -> c.toAlternative().let { if (isolated) it.copy(variants = repeated(it.variants)) else it } }
            val accepted = ConfusionSets.accepted(target, config.tapAccepted, wordFinal).let { if (isolated) repeated(it) else it }
            candidates to gop.score(posteriors, pronunciation, index, target, config.tapAccepted, alternatives, accepted)
        }
        val (candidates, worst) = scored.minBy { it.second.gop }
        val bestCandidate = scored.maxOf { maxOf(it.second.correct.logLikelihood, it.second.best.logLikelihood) }
        val labelCount = pronunciation.phones.size + pronunciation.phones.count { it.wordStart }
        val fit = (bestCandidate - posteriors.bestPathLogProb()) / labelCount
        val clip = config.lprClip
        val lpr = LinkedHashMap<String, Float>()
        for ((i, c) in candidates.withIndex()) {
            val alt = worst.alternatives[i]
            lpr[c.key] = (worst.correct.logLikelihood - alt.logLikelihood).coerceIn(-clip, clip).toFloat()
        }
        for ((key, members) in ConfusionSets.groups(target)) {
            if (members.any { m -> candidates.none { it.key == m } }) continue
            val ll = members.map { m -> worst.alternatives[candidates.indexOfFirst { it.key == m }].logLikelihood }.reduce(::logAdd)
            lpr[key] = (worst.correct.logLikelihood - ll).coerceIn(-clip, clip).toFloat()
        }
        // The winner's interval: the accepted realisation, or the most likely candidate if it beats it.
        val winnerTokens = if (worst.correct.logLikelihood >= worst.best.logLikelihood) worst.correct.bestVariant else worst.best.bestVariant
        val span = targetSpan(posteriors, pronunciation, worst.phoneIndex, winnerTokens)
        val omitted = winnerTokens.isEmpty()
        val minFit = if (request.level == Level.Word || request.level == Level.Sentence) config.minFitPerLabel else config.minFitPerLabelShort
        val reliable = fit >= minFit && (omitted || span != null)
        if (fit < minFit) notes += "poorFit"
        if (!omitted && span == null) notes += "noAlignment"
        val heard = if (worst.correct.logLikelihood >= worst.best.logLikelihood) "target" else worst.best.alternative?.id ?: "?"
        notes += "heard:$heard"
        if (chosen.size > 1) notes += "occurrences:${chosen.size}"
        return PhoneEvidence(
            lpr = lpr,
            fitPerLabel = fit.toFloat(),
            frames = posteriors.frames,
            model = modelName,
            gop = worst.gop.coerceIn(-clip, 0.0).toFloat(),
            reliable = reliable,
            targetSpan = span,
            notes = notes,
        )
    }

    /** Seconds of the clip as given (padding removed) where [tokens] sit at [phoneIndex] on the best path. */
    private fun targetSpan(posteriors: Posteriors, pronunciation: Pronunciation, phoneIndex: Int, tokens: List<String>): Pair<Double, Double>? {
        if (tokens.isEmpty()) return null
        val labels = ArrayList<Int>()
        var first = -1
        var last = -1
        for ((i, phone) in pronunciation.phones.withIndex()) {
            if (phone.wordStart) labels += ZipaInventory.WORD_START
            val ids = if (i == phoneIndex) tokens else phone.tokens
            if (i == phoneIndex) first = labels.size
            for (t in ids) labels += ZipaInventory.id(t)
            if (i == phoneIndex) last = labels.size - 1
        }
        val alignment = aligner.align(posteriors, labels.toIntArray()) ?: return null
        val a = alignment.spans[first].first
        val b = alignment.spans[last].last
        return (a * FRAME_SECONDS - config.padSeconds).coerceAtLeast(0.0) to ((b + 1) * FRAME_SECONDS - config.padSeconds).coerceAtLeast(0.0)
    }

    companion object {
        /** ZIPA-small CR-CTC: one output row per 20 ms. */
        const val FRAME_SECONDS = 0.02
        const val RATE = 16_000.0

        /** The 16 kHz samples ZIPA reads (no noise reduction, plan 03: nothing before analysis). */
        fun to16k(clip: Clip): FloatArray = when (clip.sampleRate) {
            RATE -> clip.samples
            48_000.0 -> Decimator3.decimate(clip.samples) // the capture rate: the fast exact-third path
            else -> SpeechResampler.resample(clip.samples, clip.sampleRate, RATE)
        }

        /**
         * What should have been said, or null when the text has no occurrence of [target]. Isolated: the target
         * alone (its letters span the whole text).
         */
        fun pronunciation(request: JudgeRequest, target: TargetSound): Pronunciation? {
            if (request.level == Level.Isolated) {
                val text = RomanianG2P.normalize(request.text)
                return Pronunciation(text, listOf(Phone(target.canonical, 0..maxOf(0, text.length - 1), target, wordStart = true)))
            }
            val p = RomanianG2P.pronounce(request.text)
            return if (p.occurrences(target).isEmpty()) null else p
        }

        /** The occurrences [position] asks about (the judge's own reading of `Position`, plan 03 §1 P2). */
        fun choose(pronunciation: Pronunciation, occurrences: List<Int>, position: Position): List<Int> {
            val phones = pronunciation.phones
            fun wordFinal(i: Int) = i == phones.lastIndex || phones[i + 1].wordStart
            return when (position) {
                Position.Initial -> listOf(occurrences.first())
                Position.Final -> listOf(occurrences.last())
                Position.Medial -> occurrences.filter { !phones[it].wordStart && !wordFinal(it) }.ifEmpty { occurrences }
                Position.Any -> occurrences
            }
        }

        /** Each variant held or repeated: v, vv, vvv, vvvv (an omission stays empty). */
        internal fun repeated(variants: List<List<String>>): List<List<String>> =
            variants.flatMap { v -> if (v.isEmpty()) listOf(v) else (1..4).map { k -> List(k) { v }.flatten() } }

        /** The clip inside [Config.padSeconds] of low noise, with 10 ms fades at the clip's edges. Deterministic. */
        fun pad(samples: FloatArray, config: Config = Config()): FloatArray {
            val margin = (config.padSeconds * RATE).toInt()
            val fade = 160
            val random = Random(samples.size)
            val noise = 10.0.pow(config.padDbfs / 20.0)
            val out = FloatArray(samples.size + 2 * margin)
            for (i in out.indices) out[i] = ((random.nextDouble() * 2 - 1) * noise * 1.7).toFloat()
            for (i in samples.indices) {
                val gain = when {
                    i < fade -> 0.5 - 0.5 * cos(PI * i / fade)
                    i >= samples.size - fade -> 0.5 - 0.5 * cos(PI * (samples.size - 1 - i) / fade)
                    else -> 1.0
                }
                out[margin + i] += (samples[i] * gain).toFloat()
            }
            return out
        }
    }
}
