package tech.fonia.speech

import kotlin.math.exp
import kotlin.math.ln

/**
 * One decoder pass over a single encoder output, token by token through `predictLogits`.
 *
 * `TextDecoder.decodeText` cannot do teacher forcing: it seeds forced prompt tokens with a
 * log-probability of 0 and never reads their logits, so `prefixTokens` returns no score for them.
 *
 * Sequences that share a prefix share their steps, so scoring both words of a pair repeats only
 * where the token sequences differ. Slots past the rewind point are the runtime's to hide.
 */
internal class ForcedDecoder(private val decoder: WhisperDecoderRuntime, private val positions: Int) {
    class Step(
        /** Empty for greedy steps, which are never revisited. */
        val logits: FloatArray,
        val normalizer: Float,
        val topID: Int,
        val topLogit: Float,
        /** Cross-attention over the real audio; null when the model does not expose it. */
        val alignment: FloatArray?,
    ) {
        fun logp(id: Int): Double = (logits[id] - normalizer).toDouble()

        val topLogp: Double
            get() = (topLogit - normalizer).toDouble()
    }

    private val capacity = decoder.kvCacheMaxSequenceLength
    private val fedTokens = ArrayList<Int>()
    private val stepList = ArrayList<Step>()

    val fed: List<Int>
        get() = fedTokens

    val steps: List<Step>
        get() = stepList

    var stepsRun = 0
        private set

    /** Longest sequence a step can still be run for; one KV slot stays free for its update mask. */
    val maxLength: Int
        get() = capacity - 1

    /** Leaves [steps] holding one entry per token of [sequence]. */
    suspend fun run(sequence: List<Int>) {
        if (sequence.size > maxLength) throw SpeechEngineError.TextTooLong(tokens = sequence.size, limit = maxLength)
        // A step without logits cannot price a different next token, so it cannot be the last one shared.
        var common = 0
        while (common < fedTokens.size && common < sequence.size && fedTokens[common] == sequence[common]) common += 1
        while (common > 0 && stepList[common - 1].logits.isEmpty()) common -= 1
        rewind(common)
        for (token in sequence.subList(common, sequence.size)) feed(token)
    }

    suspend fun feed(token: Int, keepLogits: Boolean = true): Step {
        val index = fedTokens.size
        if (index >= maxLength) throw SpeechEngineError.TextTooLong(tokens = index + 1, limit = maxLength)
        val output = decoder.predictLogits(token, index)
        val logits = output.logits
        // vDSP.indexOfMaximum: the first index of the largest value.
        var top = 0
        var topLogit = Float.NEGATIVE_INFINITY
        for (i in logits.indices) {
            if (logits[i] > topLogit) {
                topLogit = logits[i]
                top = i
            }
        }
        // topLogit + log(sum(exp(logits - topLogit))), in Float as vDSP and vForce compute it.
        var sum = 0f
        for (logit in logits) sum += exp(-topLogit + logit)
        val normalizer = topLogit + ln(sum)
        if (!normalizer.isFinite()) throw SpeechEngineError.DecodingFailed("logits are not finite")
        val step = Step(
            logits = logits,
            normalizer = normalizer,
            topID = top,
            topLogit = topLogit,
            alignment = output.alignmentWeights?.let { it.copyOf(minOf(positions, it.size)) },
        )
        fedTokens.add(token)
        stepList.add(if (keepLogits) step else Step(FloatArray(0), normalizer, step.topID, topLogit, step.alignment))
        stepsRun += 1
        return step
    }

    fun rewind(length: Int) {
        if (length >= fedTokens.size) return
        decoder.rewind(length)
        while (fedTokens.size > length) fedTokens.removeAt(fedTokens.size - 1)
        while (stepList.size > length) stepList.removeAt(stepList.size - 1)
    }
}
