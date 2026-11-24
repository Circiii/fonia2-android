package tech.fonia.judge

import tech.fonia.model.ErrorKind
import kotlin.math.exp

/**
 * P4 (plan 03 §7): multinomial logistic regression per sound. Inputs are z-scored and clipped; a missing (NaN) input
 * is 0, i.e. masked. Classes: "correct" first, then the sound's error kinds.
 */
class FusionHead(val spec: FusionSpec) {
    fun inputVector(features: Map<String, Float>): FloatArray = FloatArray(spec.inputs.size) { i ->
        val input = spec.inputs[i]
        val v = features[input.name]
        if (v == null || v.isNaN()) 0f else ((v - input.mean) / input.scale).coerceIn(input.clip[0], input.clip[1])
    }

    /** Probabilities for [head] ("fast" or "full"); a missing head falls back to "fast". */
    fun probabilities(features: Map<String, Float>, head: String): FloatArray {
        val h = spec.heads[head] ?: spec.heads.getValue("fast")
        val x = inputVector(features)
        val z = FloatArray(spec.classes.size) { c ->
            var s = h.bias[c]
            val row = h.weights[c]
            for (i in x.indices) s += row[i] * x[i]
            s / h.temperature
        }
        val max = z.max()
        val e = FloatArray(z.size) { exp((z[it] - max).toDouble()).toFloat() }
        val sum = e.sum()
        val p = FloatArray(e.size) { e[it] / sum }
        return calibrated(p)
    }

    /** Isotonic table on "correct" when the spec has one; the error classes are rescaled to keep the sum at 1. */
    private fun calibrated(p: FloatArray): FloatArray {
        val table = spec.isotonic["correct"] ?: return p
        val c = interpolate(table, p[0]).coerceIn(0f, 1f)
        val rest = 1f - p[0]
        val out = p.copyOf()
        out[0] = c
        for (i in 1 until out.size) out[i] = if (rest > 0f) p[i] / rest * (1f - c) else (1f - c) / (out.size - 1)
        return out
    }

    private fun interpolate(t: Isotonic, x: Float): Float {
        if (t.x.isEmpty()) return x
        if (x <= t.x.first()) return t.y.first()
        for (i in 1 until t.x.size) {
            if (x <= t.x[i]) {
                val f = (x - t.x[i - 1]) / (t.x[i] - t.x[i - 1])
                return t.y[i - 1] + f * (t.y[i] - t.y[i - 1])
            }
        }
        return t.y.last()
    }
}

/** Class probabilities with the sound's kinds attached. */
class Probs(val spec: FusionSpec, val values: FloatArray) {
    val correct: Float get() = values[0]

    operator fun get(kind: ErrorKind): Float {
        val i = spec.classes.indexOfFirst { it != "correct" && FusionSpec.kindOf(it) == kind }
        return if (i < 0) 0f else values[i]
    }

    /** Error kinds by falling probability. */
    val ranked: List<Pair<ErrorKind, Float>>
        get() = spec.classes.indices.drop(1).map { FusionSpec.kindOf(spec.classes[it]) to values[it] }.sortedByDescending { it.second }

    /** Summed probability of the kinds coarser than [level] (1 presence … 5 distortion). */
    fun sumCoarserThan(level: Int): Float = ranked.filter { spec.level(it.first) < level }.sumOf { it.second.toDouble() }.toFloat()

    fun asMap(): Map<String, Float> = spec.classes.indices.associate { spec.classes[it] to values[it] }
}

/**
 * The v1 full head (plan 03 §7) as a log-linear pool of the fast head and the phoneme model:
 *
 * `logit_k = alpha · fast_k / T_fast  −  c · lpr(candidate_k)   (+ g · gop + correctBias for "correct")`
 *
 * With lpr(x) = log P(target) − log P(x), `−c · lpr` adds c · log(P(x) / P(target)) to class x: the phone model's
 * posterior over {target, candidates} raised to the power c, multiplied into the fast head's (classes without a
 * candidate). `zipa.gop` lowers "correct" when a candidate without a class (an S said as [ts]) takes the mass.
 *
 * A class the phone model cannot see (weakOrShort: a weak S is still [s]) rides on "correct": its logit is the full
 * "correct" logit plus the fast head's own (weakOrShort − correct) difference, so the DSP keeps its full say on that
 * contrast whatever alpha is. Four constants per spec ([FullHeadParams]); `spec/fusion/make_v1.py` writes the same
 * numbers into the JSON and `JudgeSpecTests` checks that they agree.
 */
object FullHead {
    fun build(spec: FusionSpec, params: FullHeadParams): FusionHeadSpec {
        val fast = spec.heads.getValue("fast")
        val names = spec.inputs.map { it.name }
        val t = fast.temperature
        val correctBias = params.alpha * fast.bias[0] / t + params.correctBias
        val correctRow = names.indices.map { i ->
            params.alpha * fast.weights[0][i] / t + if (names[i] == Features.ZIPA_GOP) params.g * spec.inputs[i].scale else 0f
        }
        val bias = ArrayList<Float>()
        val weights = ArrayList<List<Float>>()
        for ((k, cls) in spec.classes.withIndex()) {
            val key = spec.phoneMap[cls]?.let { Features.LPR_PREFIX + it }
            when {
                k == 0 -> { bias += correctBias; weights += correctRow }
                key == null -> {
                    bias += correctBias + (fast.bias[k] - fast.bias[0]) / t
                    weights += names.indices.map { i -> correctRow[i] + (fast.weights[k][i] - fast.weights[0][i]) / t }
                }
                else -> {
                    val kind = FusionSpec.kindOf(cls)
                    val c = if (kind == ErrorKind.PlaceSwap || kind == ErrorKind.AffricatePlace) params.cPlace ?: params.c else params.c
                    bias += params.alpha * fast.bias[k] / t
                    weights += names.indices.map { i ->
                        params.alpha * fast.weights[k][i] / t - if (names[i] == key) c * spec.inputs[i].scale else 0f
                    }
                }
            }
        }
        return FusionHeadSpec(uses = "dsp+zipa", bias = bias, weights = weights, temperature = 1f)
    }
}
