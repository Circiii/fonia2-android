package tech.fonia.judge

import tech.fonia.model.SoundId
import java.io.File
import java.util.Locale

/**
 * The leave-one-voice-out fits of the v2 evaluation ([EvalV2Runner]). Three stages, each chosen on the other voices
 * and applied to the held-out voice, every voice weighing the same, on a per-take cost that follows the owner's "say
 * so when it is right, rarely shrug": false alarm 6, praised error 5, wrong kind 3, abstained on a correct take 2
 * (Almost 1.2), abstained on an error 1; runner-made edits weigh half:
 *
 *  A. vowel context: the sNess logit shift before i/e for S/Z ("s:i", "s:e") and Ș/J ("ș:i", "ș:e");
 *  B. per sound: the full head's pool constants (alpha, c, cPlace, correctBias, g);
 *  C. praise when the heads agree: (p_fast, p_full) thresholds.
 *
 * Writes `lovo.csv` (id → held-out label) and `fit.md` (what each fold chose, and the all-voices choice to ship).
 */
class EvalV2Fits(private val runner: EvalV2Runner) {
    private val base = loadSpec()
    private val items = runner.items.filter { scored(it.expected) }
    private val voices = items.map { it.voice }.distinct().sorted()

    companion object {
        fun scored(e: String) = !e.startsWith("retake") && !e.startsWith("n/a") && !e.startsWith("info")
        private fun kindOf(l: String) = l.substringBefore("(")
        private fun named(l: String) = l != "correct" && l != "almost" && l != "unsure" && !l.startsWith("retake")

        fun cost(expected: String, label: String): Double =
            if (expected == "correct") when {
                label == "correct" -> 0.0
                label == "almost" -> 1.2
                named(label) -> 6.0
                else -> 2.0
            } else when {
                named(label) && kindOf(label) == kindOf(expected) -> 0.0
                named(label) -> 3.0
                label == "correct" -> 5.0
                else -> 1.0
            }
    }

    data class Config(
        val shift: Map<String, Float> = emptyMap(),
        val placeTrust: Float = 2f,
        val heads: Map<SoundId, FullHeadParams> = emptyMap(),
        val praise: PraiseThresholds = PraiseThresholds(),
    )

    private fun judgeFor(c: Config): TakeJudge {
        val fusion = base.fusion.mapValues { (s, f) ->
            val p = c.heads[s] ?: return@mapValues f
            f.copy(fullFrom = p, heads = f.heads + ("full" to FullHead.build(f, p)))
        }
        val j2 = base.population.judge2.copy(version = "fit", contextShift = c.shift, placeTrust = c.placeTrust, praise = c.praise,
            burst = BurstThresholds(minFound = 2))
        val spec = JudgeSpec(base.population.copy(judge2 = j2), fusion, base.unlock, base.feedback)
        return TakeJudge(spec, unlock = UnlockView.everythingUnlocked())
    }

    private fun labels(c: Config, subset: List<EvalV2Runner.Item>): List<String> {
        val j = judgeFor(c)
        return subset.map { runner.labelOf(j, it, full = true) }
    }

    /** Mean over voices (each the same weight) of the mean cost, on the items [keep] selects. */
    private fun meanCost(subset: List<EvalV2Runner.Item>, labels: List<String>, keep: (String) -> Boolean): Double {
        val sum = HashMap<String, Double>()
        val w = HashMap<String, Double>()
        for ((i, it) in subset.withIndex()) if (keep(it.voice)) {
            // Runner-made edits (cut, −24 dB, burst) are crude: half the weight of a said or cross-tested take.
            val weight = if (it.role == "edit") 0.5 else 1.0
            sum[it.voice] = (sum[it.voice] ?: 0.0) + weight * cost(it.expected, labels[i])
            w[it.voice] = (w[it.voice] ?: 0.0) + weight
        }
        return if (w.isEmpty()) Double.MAX_VALUE else sum.keys.sumOf { sum.getValue(it) / w.getValue(it) } / w.size
    }

    private val shiftKeys = listOf("s:i", "s:e", "ș:i", "ș:e")
    private val shiftGrid = listOf(-2f, -1f, 0f, 1f, 2f, 3f, 4f, 5f, 6f)
    private fun headGrid(sound: SoundId): List<FullHeadParams> {
        val place = sound !in setOf(SoundId.R, SoundId.L)
        val out = ArrayList<FullHeadParams>()
        for (a in listOf(0.25f, 0.5f, 0.75f, 1f, 1.5f)) for (c in listOf(0.25f, 0.5f, 0.75f, 1.25f))
            for (cp in if (place) listOf<Float?>(null, 0f, 0.25f, 0.5f) else listOf<Float?>(null))
                // 2026-11-25: the correct-bias range goes past 1 and the GOP weight is free: on CE/GE the chosen values
                // sat at the grid's edge (b = 1), and ZIPA's GOP is low on good [tʃ]/[dʒ] (heard as s / ʒ).
                for (b in listOf(-1f, -0.5f, 0f, 0.5f, 1f, 1.5f, 2f, 3f)) for (g in listOf(0f, 0.25f, 0.5f)) out += FullHeadParams(a, c, g, b, cp)
        return out
    }
    private val praiseGrid = listOf(PraiseThresholds()) +
        listOf(0.5f, 0.6f, 0.7f, 0.8f).flatMap { f -> listOf(0.4f, 0.5f, 0.6f, 0.7f).map { u -> PraiseThresholds(f, u) } }

    // Label caches: (config key, subset key) → labels.
    private val cache = HashMap<String, List<String>>()
    private fun cached(c: Config, subsetKey: String, subset: List<EvalV2Runner.Item>): List<String> =
        cache.getOrPut("$c|$subsetKey") { labels(c, subset) }

    private fun shiftItems(key: String): List<EvalV2Runner.Item> {
        val (place, ctx) = key.split(":")
        val sounds = if (place == "s") setOf(SoundId.S, SoundId.Z) else setOf(SoundId.Sh, SoundId.J)
        val code = if (ctx == "i") 1f else 2f
        // The context feature sits on the first occurrence's features; words with the target before i/e.
        return items.filter { it.sound in sounds && it.row["context"]?.toString()?.trim('"') == ctx }
            .filter { code > 0f }
    }

    /** All three stages on the voices [train] keeps. */
    fun choose(train: (String) -> Boolean): Config {
        // A. context shifts, each key on its own items, with v1 heads.
        val shift = LinkedHashMap<String, Float>()
        // Off unless FONIA_EVAL_SHIFT=true: on the v2 set the folds chose unstable signs (EVAL_v2.md), so v2 ships none.
        for (key in if (System.getenv("FONIA_EVAL_SHIFT") == "true") shiftKeys else emptyList()) {
            val sub = shiftItems(key)
            if (sub.isEmpty()) continue
            val best = shiftGrid.minBy { v -> meanCost(sub, cached(Config(shift = mapOf(key to v)), key, sub), train) }
            if (best != 0f) shift[key] = best
        }
        // A2. place trust, with A, on the sibilants and affricates.
        val placeItems = items.filter { traits(it.sound).place != null }
        val trust = listOf(2f, 0.95f, 0.9f, 0.8f, 0.7f).minBy { v ->
            meanCost(placeItems, cached(Config(shift = shift, placeTrust = v), "place", placeItems), train)
        }
        // B. heads per sound, with A.
        val heads = LinkedHashMap<SoundId, FullHeadParams>()
        for (sound in SoundId.entries) {
            val sub = items.filter { it.sound == sound }
            if (sub.isEmpty()) continue
            val shipped = base.fusion[sound]?.fullFrom ?: continue
            val grid = (headGrid(sound) + shipped).distinct()
            heads[sound] = grid.minBy { p -> meanCost(sub, cached(Config(shift, trust, mapOf(sound to p)), sound.raw, sub), train) }
        }
        // C. praise, with A and B.
        val praise = praiseGrid.minBy { p -> meanCost(items, cached(Config(shift, trust, heads, p), "all", items), train) }
        return Config(shift, trust, heads, praise)
    }

    fun run(lines: MutableList<String>, out: File) {
        val held = HashMap<String, String>()
        for (v in voices) {
            val c = choose { it != v }
            val sub = items.filter { it.voice == v }
            val j = judgeFor(c)
            for (it in sub) {
                val r = j.decide(it.measured, it.phones)
                val h = r.visual?.hint
                held[it.id] = listOf(runner.labelOf(j, it, full = true), r.topError?.raw ?: "", fmt2(r.topErrorProbability),
                    fmt2(r.probabilities["correct"] ?: Float.NaN), h?.key?.raw ?: "", fmt2(h?.confidence ?: Float.NaN)).joinToString(",")
            }
            lines += "- held out **$v**: shift ${c.shift}, placeTrust ${c.placeTrust}, praise ${c.praise}, heads " +
                c.heads.entries.joinToString("; ") { (s, p) -> "${s.raw} ${fmt(p)}" }
        }
        val all = choose { true }
        lines += ""
        lines += "**Chosen on all voices (to ship):**"
        lines += ""
        lines += "- contextShift ${all.shift}"
        lines += "- placeTrust ${all.placeTrust}"
        lines += "- praise ${all.praise}"
        for ((s, p) in all.heads) lines += "- ${s.raw}: ${fmt(p)}"
        File(out, "lovo.csv").writeText("id,heldout,topError,pTop,pCorrect,hint,hintConf\n" +
            items.joinToString("\n") { "${it.id},${held[it.id] ?: ",,,,,"}" } + "\n")
        File(out, "chosen.json").writeText(buildString {
            append("{\n  \"contextShift\": {")
            append(all.shift.entries.joinToString(", ") { "\"${it.key}\": ${it.value}" })
            append("},\n  \"placeTrust\": ${all.placeTrust},\n  \"praise\": {\"fast\": ${all.praise.fast}, \"full\": ${all.praise.full}},\n  \"heads\": {\n")
            append(all.heads.entries.joinToString(",\n") { (s, p) ->
                "    \"${s.raw}\": {\"alpha\": ${p.alpha}, \"c\": ${p.c}, \"g\": ${p.g}, \"correctBias\": ${p.correctBias}" +
                    (p.cPlace?.let { ", \"cPlace\": $it" } ?: "") + "}"
            })
            append("\n  }\n}\n")
        })
    }

    private fun fmt2(v: Float) = if (v.isNaN()) "" else String.format(Locale.ROOT, "%.3f", v)

    private fun fmt(p: FullHeadParams) = String.format(Locale.ROOT, "a%.2f c%.2f cp%s b%.1f", p.alpha, p.c, p.cPlace?.let { "%.2f".format(Locale.ROOT, it) } ?: "=c", p.correctBias)
}
