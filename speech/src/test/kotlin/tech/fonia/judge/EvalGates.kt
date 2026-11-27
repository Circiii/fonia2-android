package tech.fonia.judge

import java.io.File
import java.util.Locale
import tech.fonia.model.SoundId

/**
 * The opt-in judge gates on the evaluation set ([EvalV2Runner]); all of them are off in the shipped spec
 * (`docs/JUDECATOR_AFRICATE.md`). Writes, next to `cases.csv`:
 *
 *  - `gates.md` 1: how the affricate onset (`stopOnset` and its parts) sits per expected label, with the older
 *    closure / burst / rise cues beside it, and the AUC of good against deaffricated takes; the word-final L's glide
 *    evidence per label;
 *  - `gates.md` 2: before/after on the shipped spec for a grid of `judge2.affricate` (CE, GE) and `judge2.finalL` (L)
 *    settings: correct takes accepted and errors praised, per sound, on all voices;
 *  - `gates.md` 3: leave-one-voice-out, each voice judged with the setting chosen on the others: the [EvalV2Fits] cost,
 *    among the settings whose praised errors stay ≤ 5 % (or ≤ shipped, when shipped is already over 5 %);
 *  - `gates.json`: the setting chosen on all voices, the block for `population.json` → `judge2` if the numbers hold;
 *  - `gates.csv`: per take, the shipped label, the held-out label and the onset.
 */
class EvalGates(private val runner: EvalV2Runner) {
    private val base = loadSpec()
    private val items = runner.items.filter { EvalV2Fits.scored(it.expected) }
    private val voices = items.map { it.voice }.distinct().sorted()

    /** One setting of the gates for one sound. */
    data class Setting(val affricate: AffricateGate? = null, val finalL: FinalLThresholds = FinalLThresholds()) {
        override fun toString(): String {
            val parts = ArrayList<String>()
            affricate?.let { a ->
                if (a.trustAt <= 1f) parts += "trust ≥ ${f2(a.trustAt)} ${a.trust}"
                if (a.vetoAt >= 0f) parts += "veto ≤ ${f2(a.vetoAt)}"
            }
            if (finalL.minGlideNats > 0f) parts += "final L ${f2(finalL.minGlideNats)} nats" + if (finalL.mask) " mask" else ""
            return if (parts.isEmpty()) "off" else parts.joinToString(", ")
        }
    }

    private val fricatives = mapOf(SoundId.Ce to listOf("ʃ", "s"), SoundId.Ge to listOf("ʒ", "z"))

    private fun grid(sound: SoundId): List<Setting> = when (sound) {
        SoundId.Ce, SoundId.Ge -> {
            val out = ArrayList<Setting>()
            // On the prompt voice good CE / GE sit around 0.3 and Ș / J around 0.15–0.25, so the grid goes low.
            for (t in listOf(2f, 0.8f, 0.6f, 0.5f, 0.4f, 0.3f)) {
                for (v in listOf(-1f, 0.05f, 0.1f, 0.15f, 0.2f, 0.3f)) {
                    out += Setting(AffricateGate(t, if (t <= 1f) fricatives.getValue(sound) else emptyList(), v))
                }
            }
            out
        }

        SoundId.L -> listOf(Setting()) + listOf(4f, 6f, 8f, 10f, 12f).flatMap { m -> listOf(false, true).map { Setting(finalL = FinalLThresholds(m, it)) } }

        else -> emptyList()
    }

    private fun judge(sound: SoundId, s: Setting): TakeJudge {
        val j2 = base.population.judge2.copy(
            affricate = s.affricate?.let { mapOf(sound.raw to it) } ?: emptyMap(),
            finalL = s.finalL,
        )
        return TakeJudge(JudgeSpec(base.population.copy(judge2 = j2), base.fusion, base.unlock, base.feedback))
    }

    private val labelCache = HashMap<Pair<SoundId, Setting>, List<String>>()
    private fun labels(sound: SoundId, s: Setting): List<String> = labelCache.getOrPut(sound to s) {
        val j = judge(sound, s)
        subset(sound).map { runner.labelOf(j, it, full = true) }
    }
    private fun subset(sound: SoundId) = items.filter { it.sound == sound }

    private class Score(val accepted: Int, val correct: Int, val praised: Int, val errors: Int, val cost: Double) {
        val acceptedRate get() = if (correct == 0) 0.0 else accepted.toDouble() / correct
        val praisedRate get() = if (errors == 0) 0.0 else praised.toDouble() / errors
    }

    /** Counts on the voices [keep] selects; the cost is the mean over voices of each voice's mean (edits half weight). */
    private fun score(sound: SoundId, s: Setting, keep: (String) -> Boolean): Score {
        val sub = subset(sound)
        val labels = labels(sound, s)
        var accepted = 0
        var correct = 0
        var praised = 0
        var errors = 0
        val sum = HashMap<String, Double>()
        val w = HashMap<String, Double>()
        for ((i, it) in sub.withIndex()) {
            if (!keep(it.voice)) continue
            val l = labels[i]
            if (it.expected == "correct") {
                correct++
                if (l == "correct") accepted++
            } else {
                errors++
                if (l == "correct") praised++
            }
            val weight = if (it.role == "edit") 0.5 else 1.0
            sum[it.voice] = (sum[it.voice] ?: 0.0) + weight * EvalV2Fits.cost(it.expected, l)
            w[it.voice] = (w[it.voice] ?: 0.0) + weight
        }
        val cost = if (w.isEmpty()) Double.MAX_VALUE else sum.keys.sumOf { sum.getValue(it) / w.getValue(it) } / w.size
        return Score(accepted, correct, praised, errors, cost)
    }

    /** The cheapest setting whose praised errors stay ≤ 5 % (≤ shipped when shipped is over); "off" on a tie. */
    private fun choose(sound: SoundId, keep: (String) -> Boolean): Setting {
        val settings = grid(sound)
        val off = score(sound, settings.first(), keep)
        val ceiling = maxOf(0.05, off.praisedRate) + 1e-9
        return settings.filter { score(sound, it, keep).praisedRate <= ceiling }.minBy { score(sound, it, keep).cost }
    }

    private fun features(it: EvalV2Runner.Item): Map<String, Float> = judge(it.sound, Setting()).decide(it.measured, it.phones).evidence.features

    fun run(out: File) {
        val md = ArrayList<String>()
        md += "# Judge gates (off in the shipped spec): affricate onset and word-final L"
        md += ""
        md += "${items.size} judged takes, ${voices.size} voices. Every voice is synthetic: nothing here is evidence about children."
        md += "Shipped spec: thresholds ${base.population.version}, judge2 ${base.population.judge2.version}."
        md += ""
        separation(md)
        val sounds = listOf(SoundId.Ce, SoundId.Ge, SoundId.L).filter { s -> subset(s).isNotEmpty() }
        md += "## 2. Each setting on all voices (shipped spec + that gate; accepted = correct takes praised)"
        md += ""
        for (sound in sounds) {
            md += "### ${sound.letter}"
            md += ""
            md += "| setting | correct accepted | errors praised | cost |"
            md += "|---|---|---|---|"
            for (s in grid(sound)) {
                val sc = score(sound, s) { true }
                md += "| $s | ${pct(sc.accepted, sc.correct)} | ${pct(sc.praised, sc.errors)} | ${f3(sc.cost)} |"
            }
            md += ""
        }
        // 3. Leave one voice out.
        val held = HashMap<String, String>()
        md += "## 3. Leave-one-voice-out (each voice judged with the setting chosen on the others)"
        md += ""
        for (v in voices) {
            val chosen = sounds.associateWith { choose(it) { voice -> voice != v } }
            for ((sound, s) in chosen) {
                val j = judge(sound, s)
                for (it in subset(sound)) if (it.voice == v) held[it.id] = runner.labelOf(j, it, full = true)
            }
            md += "- held out **$v**: " + chosen.entries.joinToString("; ") { (s, c) -> "${s.letter} $c" }
        }
        md += ""
        md += "| sound | shipped: accepted / praised | held out: accepted / praised |"
        md += "|---|---|---|"
        for (sound in sounds) {
            val sub = subset(sound)
            val shipped = labels(sound, Setting())
            fun count(labels: List<String>, correct: Boolean) =
                sub.indices.count { (sub[it].expected == "correct") == correct && labels[it] == "correct" }
            val heldLabels = sub.map { held[it.id] ?: "" }
            val nc = sub.count { it.expected == "correct" }
            val ne = sub.size - nc
            md += "| ${sound.letter} | ${pct(count(shipped, true), nc)} / ${pct(count(shipped, false), ne)} | " +
                "${pct(count(heldLabels, true), nc)} / ${pct(count(heldLabels, false), ne)} |"
        }
        md += ""
        val all = sounds.associateWith { choose(it) { true } }
        md += "**Chosen on all voices:** " + all.entries.joinToString("; ") { (s, c) -> "${s.letter} $c" }
        md += ""
        md += "To ship a gate: copy `gates.json` into `spec/thresholds/population.json` → `judge2`, bump its version, and put"
        md += "the held-out numbers above (with the clips they came from) in `spec/fusion/EVAL_v2.md`."
        File(out, "gates.md").writeText(md.joinToString("\n") + "\n")
        File(out, "gates.json").writeText(
            buildString {
                append("{\n  \"affricate\": {")
                append(
                    all.entries.mapNotNull { (s, c) -> c.affricate?.takeIf { it.trustAt <= 1f || it.vetoAt >= 0f }?.let { s to it } }
                        .joinToString(", ") { (s, a) ->
                            "\"${s.raw}\": {\"trustAt\": ${a.trustAt}, \"trust\": [${a.trust.joinToString(", ") { "\"$it\"" }}], \"vetoAt\": ${a.vetoAt}}"
                        },
                )
                val fl = all[SoundId.L]?.finalL ?: FinalLThresholds()
                append("},\n  \"finalL\": {\"minGlideNats\": ${fl.minGlideNats}, \"mask\": ${fl.mask}}\n}\n")
            },
        )
        val rows = ArrayList<String>()
        rows += "id,voice,sound,expected,shipped,heldout,stopOnset,onsetAttackDb,onsetSpikeDb,voiceLeadMs,gapMs,wordFinal,lprGlide"
        for (sound in sounds) {
            val shipped = labels(sound, Setting())
            for ((i, it) in subset(sound).withIndex()) {
                val f = features(it)
                rows += listOf(
                    it.id, it.voice, sound.raw, it.expected, shipped[i], held[it.id] ?: "", f3(f[Features.STOP_ONSET]),
                    f3(f[Features.ONSET_ATTACK]), f3(f[Features.ONSET_SPIKE]), f3(f[Features.VOICE_LEAD]), f3(f[Features.GAP]),
                    it.phones?.wordFinal?.toString() ?: "", f3(it.phones?.lpr?.get("j+w")),
                ).joinToString(",") { v ->
                    if (v.contains(',') || v.contains('"')) "\"" + v.replace("\"", "\"\"") + "\"" else v
                }
            }
        }
        File(out, "gates.csv").writeText(rows.joinToString("\n") + "\n")
    }

    /** Section 1: the onset per expected label, and the final L's glide evidence. */
    private fun separation(md: MutableList<String>) {
        val cues = listOf(
            Features.STOP_ONSET,
            Features.ONSET_ATTACK,
            Features.ONSET_SPIKE,
            Features.VOICE_LEAD,
            Features.GAP,
            Features.RISE,
            Features.BURST,
        )
        md += "## 1. How the frication starts, per expected label (median; n with a value)"
        md += ""
        md += "| sound | expected | takes | " + cues.joinToString(" | ") + " |"
        md += "|---|---|---|" + cues.joinToString("") { "---|" }
        val auc = ArrayList<String>()
        for (sound in listOf(SoundId.Ce, SoundId.Ge, SoundId.Ts, SoundId.J, SoundId.Sh)) {
            val sub = subset(sound)
            if (sub.isEmpty()) continue
            val byKind = sub.groupBy { it.expected.substringBefore("(") }
            val feats = sub.associate { it.id to features(it) }
            for ((kind, group) in byKind.entries.sortedBy { if (it.key == "correct") "" else it.key }) {
                md += "| ${sound.letter} | $kind | ${group.size} | " + cues.joinToString(" | ") { c ->
                    val v = group.mapNotNull { feats.getValue(it.id)[c]?.takeIf { x -> !x.isNaN() } }
                    if (v.isEmpty()) "–" else "${f2(v.sorted()[v.size / 2])} (${v.size})"
                } + " |"
            }
            val good = byKind["correct"] ?: continue
            val bad = byKind["deaffrication"] ?: continue
            auc += aucLine("${sound.letter}, good (${good.size}) vs deaffrication (${bad.size})", cues, good, bad)
        }
        // Stand-ins while real CE / GE errors are missing: a good take of the fricative the error turns into.
        for ((a, b) in listOf(SoundId.Ce to SoundId.Sh, SoundId.Ge to SoundId.J)) {
            val good = subset(a).filter { it.expected == "correct" }
            val twin = subset(b).filter { it.expected == "correct" }
            if (good.isEmpty() || twin.isEmpty()) continue
            auc += aucLine("${a.letter}, good (${good.size}) vs good ${b.letter} (${twin.size}), a stand-in", cues, good, twin)
        }
        md += ""
        if (auc.isNotEmpty()) {
            md += "AUC = P(the good take's value is higher); 0.5 = no separation, ≤ 0.5 = the wrong way (riseMs is expected low)."
            md += ""
            md += auc
            md += ""
        }
        val l = subset(SoundId.L)
        if (l.isNotEmpty()) {
            md += "Word-final L (ZIPA's own flag): lpr(j+w), median, and how often the shipped judge names lToGlide."
            md += ""
            md += "| position | expected | takes | lpr j+w | named lToGlide |"
            md += "|---|---|---|---|---|"
            val shipped = labels(SoundId.L, Setting())
            val index = l.withIndex().associate { it.value.id to it.index }
            for ((key, group) in l.groupBy { (if (it.phones?.wordFinal == true) "final" else "not final") to it.expected.substringBefore("(") }
                .entries.sortedBy { it.key.first + it.key.second }) {
                val glide = group.mapNotNull { it.phones?.lpr?.get("j+w") }.sorted()
                val named = group.count { shipped[index.getValue(it.id)].startsWith("lToGlide") }
                md += "| ${key.first} | ${key.second} | ${group.size} | ${if (glide.isEmpty()) "–" else f2(glide[glide.size / 2])} | $named |"
            }
            md += ""
        }
    }

    private val featureCache = HashMap<String, Map<String, Float>>()

    private fun aucLine(title: String, cues: List<String>, good: List<EvalV2Runner.Item>, bad: List<EvalV2Runner.Item>): String =
        "- $title: " + cues.joinToString(", ") { c ->
            fun values(items: List<EvalV2Runner.Item>) =
                items.mapNotNull { featureCache.getOrPut(it.id) { features(it) }[c]?.takeIf { x -> !x.isNaN() } }
            "$c ${auc(values(good), values(bad))?.let { f2(it.toFloat()) } ?: "–"}"
        }

    /** Mann–Whitney AUC: P(a > b) + ½ P(a = b); null without values on both sides. */
    private fun auc(a: List<Float>, b: List<Float>): Double? {
        if (a.isEmpty() || b.isEmpty()) return null
        var s = 0.0
        for (x in a) {
            for (y in b) {
                s += if (x > y) {
                    1.0
                } else if (x == y) {
                    0.5
                } else {
                    0.0
                }
            }
        }
        return s / (a.size * b.size)
    }

    private companion object {
        fun f2(v: Float) = String.format(Locale.ROOT, "%.2f", v)
        fun f3(v: Double) = String.format(Locale.ROOT, "%.3f", v)
        fun f3(v: Float?) = if (v == null || v.isNaN()) "" else String.format(Locale.ROOT, "%.3f", v)
        fun pct(k: Int, n: Int) = if (n == 0) "–" else String.format(Locale.ROOT, "%.0f %% (%d/%d)", 100.0 * k / n, k, n)
    }
}
