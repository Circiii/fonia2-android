package tech.fonia.phones

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import tech.fonia.domain.ContentSource
import tech.fonia.domain.FoniaContent
import java.io.File
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertTrue

/**
 * The evaluation behind `phones/EVAL.md`: ZIPA-small int8 and the restricted GOP-SF on the recorded content clips.
 * Writes `phones/build/eval-report.md` and `phones/build/eval-trials.csv`. Skipped when the model, ffmpeg or the
 * content submodule is missing. The assertions only guard against regressions; the numbers are the report.
 */
class PronunciationEvalTest {
    private val policy = ScoringPolicy()

    private class Condition(val name: String, val posteriors: Map<String, Posteriors>)

    @Test
    fun evaluateOnTheContentClips() {
        val clips = VoiceClips.open()
        assumeTrue(clips != null, "the content submodule (fonia.content/voice) is missing")
        assumeTrue(Ffmpeg.available, "ffmpeg is not on PATH: the JVM has no MP3 decoder")
        val modelFile = ModelCache.fetch(ZipaModel.INT8)
        assumeTrue(modelFile.isSuccess, "ZIPA-small int8 is not available: ${modelFile.exceptionOrNull()?.message}")
        val content = FoniaContent.load(ContentSource.directory(File(System.getProperty("fonia.content"))))

        val texts = TrialBuilder.clipTexts(clips!!)
        val audio = decodeAll(clips, texts)
        val trials = TrialBuilder.build(content, audio.keys.toList())
        val report = StringBuilder()

        val loadStart = System.nanoTime()
        val model = openModel(modelFile.getOrThrow())
        val loadMs = (System.nanoTime() - loadStart) / 1e6
        val (rawRun, paddedRun) = model.use { m ->
            Condition("raw", audio.mapValues { m.posteriors(it.value) }) to
                Condition("padded", audio.mapValues { (text, pcm) -> m.posteriors(padded(pcm, text.hashCode())) })
        }
        val main = judgeAll(trials, paddedRun)
        val rawOutcomes = judgeAll(trials, rawRun)

        report.appendLine("# ZIPA-small int8 + restricted GOP-SF on the content clips")
        report.appendLine()
        report.appendLine("Written by `./gradlew :phones:test` (`PronunciationEvalTest`). Do not edit by hand; `phones/EVAL.md` discusses it.")
        report.appendLine()
        report.appendLine("- Model: `${ZipaModel.INT8.name}` (${ZipaModel.INT8.bytes} bytes, SHA-256 ${ZipaModel.INT8.sha256.take(12)}…), ONNX Runtime 1.31.1 on the CPU.")
        report.appendLine("- Clips: ${audio.size} single-token Romanian clips of `content/voice` (one ElevenLabs voice), ${audio.keys.count { it !in TrialBuilder.drills(content) }} words and ${audio.keys.count { it in TrialBuilder.drills(content) }} syllable drills, decoded by ffmpeg to 16 kHz (soxr).")
        report.appendLine("- Policy: correct if LPR ≥ ${policy.acceptLpr}, try again if LPR ≤ ${policy.rejectLpr}, otherwise not heard; not heard if the fit is under ${policy.minFitPerLabel} nats per label.")
        report.appendLine("- Main condition: `padded` (each clip inside 250 ms of −75 dBFS noise, 10 ms fades). `raw` is the clip as decoded.")
        report.appendLine()

        section(report, "Verdicts (padded)", main)
        aucSection(report, "AUC of −LPR, mispronounced against correct (padded)", main)
        bySound(report, main)
        operatingPoints(report, main)
        listings(report, main)
        fitGate(report, main)
        report.appendLine("## Raw clips (no margin, no fade)")
        report.appendLine()
        section(report, null, rawOutcomes)
        aucSection(report, null, rawOutcomes)
        stability(report, main, rawOutcomes)

        // The fp32 export is compared only when it is already in the cache: it is never downloaded for this (260 MB).
        val fp32File = File(File(System.getProperty("fonia.models"), "zipa-small-crctc-500k"), ZipaModel.FP32.fileName)
        if (ZipaModel.FP32.matches(fp32File)) {
            val fp32Padded = openModel(fp32File).use { m -> Condition("padded fp32", audio.mapValues { (text, pcm) -> m.posteriors(padded(pcm, text.hashCode())) }) }
            val fp32Outcomes = judgeAll(trials, fp32Padded)
            report.appendLine("## The fp32 export of the same checkpoint (padded), for what int8 costs")
            report.appendLine()
            section(report, null, fp32Outcomes)
            aucSection(report, null, fp32Outcomes)
        } else {
            report.appendLine("(The fp32 model is not in the cache, so int8 is not compared with it here.)")
            report.appendLine()
        }

        latency(report, modelFile.getOrThrow(), audio, content, loadMs)

        val build = File("build").also { it.mkdirs() }
        File(build, "eval-report.md").writeText(report.toString(), Charsets.UTF_8)
        writeCsv(File(build, "eval-trials.csv"), main)
        println(report)

        // Regression guards, well under what this model gives today.
        val (contentAuc, _, _) = Metrics.aucWithInterval(main.filter { it.trial.kind == Kind.ContentPair }, main.filter { it.trial.kind == Kind.Correct && !it.trial.drill })
        assertTrue(contentAuc > 0.75, "AUC on the content minimal pairs fell to $contentAuc")
        val words = main.filter { it.trial.kind == Kind.Correct && !it.trial.drill }
        assertTrue(rate(words, Verdict.TryAgain) < 0.25, "false alarms on correct words rose to ${rate(words, Verdict.TryAgain)}")
    }

    private fun decodeAll(clips: VoiceClips, texts: List<String>): Map<String, FloatArray> {
        val pool = Executors.newFixedThreadPool(maxOf(2, Runtime.getRuntime().availableProcessors() / 2))
        try {
            val futures = texts.map { text -> text to pool.submit(Callable { clips.decode(text) }) }
            return futures.mapNotNull { (text, future) -> future.get()?.let { text to it } }.toMap().toSortedMap()
        } finally {
            pool.shutdown()
        }
    }

    private fun judgeAll(trials: List<Trial>, condition: Condition): List<Outcome> {
        val scorer = PhoneScorer(AcousticModel { error("posteriors are precomputed") }, policy)
        return trials.map { Outcome(it, scorer.judge(condition.posteriors.getValue(it.clip), it.intended, it.sound)) }
    }

    private fun rate(outcomes: List<Outcome>, verdict: Verdict): Double =
        if (outcomes.isEmpty()) Double.NaN else outcomes.count { it.score.verdict == verdict }.toDouble() / outcomes.size

    private fun pct(value: Double) = if (value.isNaN()) "–" else String.format(Locale.ROOT, "%.1f %%", 100 * value)

    private fun num(value: Double, digits: Int = 3) = if (value.isNaN()) "–" else String.format(Locale.ROOT, "%.${digits}f", value)

    private val sets = listOf(
        "Correct words" to { o: Outcome -> o.trial.kind == Kind.Correct && !o.trial.drill },
        "Correct drills" to { o: Outcome -> o.trial.kind == Kind.Correct && o.trial.drill },
        "Content minimal pairs (words)" to { o: Outcome -> o.trial.kind == Kind.ContentPair },
        "All clip pairs one substitution apart" to { o: Outcome -> o.trial.kind == Kind.AnyPair || o.trial.kind == Kind.ContentPair },
        "Simulated substitutions, words" to { o: Outcome -> o.trial.kind == Kind.Simulated && !o.trial.drill },
        "Simulated substitutions, drills" to { o: Outcome -> o.trial.kind == Kind.Simulated && o.trial.drill },
        "Another word" to { o: Outcome -> o.trial.kind == Kind.OtherWord },
    )

    private fun section(report: StringBuilder, title: String?, outcomes: List<Outcome>) {
        if (title != null) {
            report.appendLine("## $title")
            report.appendLine()
        }
        report.appendLine("| Set | Trials | Correct | Try again | Not heard (uncertain / poor fit / short) | Substitute right, of try again |")
        report.appendLine("|---|---|---|---|---|---|")
        for ((name, filter) in sets) {
            val set = outcomes.filter(filter)
            if (set.isEmpty()) continue
            val tryAgain = set.filter { it.score.verdict == Verdict.TryAgain }
            val substitute = if (set.first().trial.mispronounced && set.first().trial.kind != Kind.OtherWord) {
                val right = tryAgain.count { it.score.heardAs?.id == it.trial.truth }
                "$right of ${tryAgain.size}"
            } else {
                "–"
            }
            val abstained = set.filter { it.score.verdict == Verdict.NotHeard }
            val reasons = listOf(Abstention.Uncertain, Abstention.PoorFit, Abstention.TooShort).joinToString(" / ") { r -> abstained.count { it.score.abstention == r }.toString() }
            report.appendLine("| $name | ${set.size} | ${pct(rate(set, Verdict.Correct))} | ${pct(rate(set, Verdict.TryAgain))} | ${pct(rate(set, Verdict.NotHeard))} ($reasons) | $substitute |")
        }
        report.appendLine()
    }

    private fun aucSection(report: StringBuilder, title: String?, outcomes: List<Outcome>) {
        if (title != null) {
            report.appendLine("## $title")
            report.appendLine()
            report.appendLine("95 % intervals from 1000 resamples of clips (one voice: they say nothing about other speakers).")
            report.appendLine()
        }
        val correctWords = outcomes.filter { it.trial.kind == Kind.Correct && !it.trial.drill }
        val correctAll = outcomes.filter { it.trial.kind == Kind.Correct }
        val rows = listOf(
            Triple("Content minimal pairs vs correct words", outcomes.filter { it.trial.kind == Kind.ContentPair }, correctWords),
            Triple("All clip pairs vs all correct clips", outcomes.filter { it.trial.kind == Kind.AnyPair || it.trial.kind == Kind.ContentPair }, correctAll),
            Triple("Simulated (words) vs correct words", outcomes.filter { it.trial.kind == Kind.Simulated && !it.trial.drill }, correctWords),
            Triple("Simulated (all) vs all correct clips", outcomes.filter { it.trial.kind == Kind.Simulated }, correctAll),
        )
        report.appendLine("| Comparison | Mispronounced | Correct | AUC | 95 % CI |")
        report.appendLine("|---|---|---|---|---|")
        for ((name, positives, negatives) in rows) {
            if (positives.isEmpty()) continue
            val (auc, low, high) = Metrics.aucWithInterval(positives, negatives)
            report.appendLine("| $name | ${positives.size} | ${negatives.size} | ${num(auc)} | ${num(low)}–${num(high)} |")
        }
        report.appendLine()
    }

    private fun bySound(report: StringBuilder, outcomes: List<Outcome>) {
        report.appendLine("## By target sound (padded, words only)")
        report.appendLine()
        report.appendLine("False alarm = try again on a correct word. Detection = try again on a simulated substitution of that target in a word.")
        report.appendLine()
        report.appendLine("| Sound | Correct trials | False alarms | Not heard | Simulated | Detected | Missed (correct) | AUC (simulated vs correct) | Median LPR correct / simulated |")
        report.appendLine("|---|---|---|---|---|---|---|---|---|")
        for (sound in TargetSound.entries) {
            val correct = outcomes.filter { it.trial.kind == Kind.Correct && !it.trial.drill && it.trial.sound == sound }
            val simulated = outcomes.filter { it.trial.kind == Kind.Simulated && !it.trial.drill && it.trial.sound == sound }
            val auc = Metrics.auc(simulated.map { -it.lpr }, correct.map { -it.lpr })
            report.appendLine(
                "| ${sound.letter} | ${correct.size} | ${pct(rate(correct, Verdict.TryAgain))} | ${pct(rate(correct, Verdict.NotHeard))} | ${simulated.size} | " +
                    "${pct(rate(simulated, Verdict.TryAgain))} | ${pct(rate(simulated, Verdict.Correct))} | ${num(auc)} | " +
                    "${num(Metrics.percentile(correct.map { it.lpr }, 50.0), 1)} / ${num(Metrics.percentile(simulated.map { it.lpr }, 50.0), 1)} |",
            )
        }
        report.appendLine()
    }

    private fun operatingPoints(report: StringBuilder, outcomes: List<Outcome>) {
        report.appendLine("## Other thresholds (padded, words only)")
        report.appendLine()
        report.appendLine("Each row moves `rejectLpr` (try again at or under it) with `acceptLpr` at 0. False alarms on correct words; detection on simulated substitutions in words and on the content pairs.")
        report.appendLine()
        report.appendLine("| rejectLpr | False alarms | Correct words not heard | Detected (simulated) | Detected (content pairs) |")
        report.appendLine("|---|---|---|---|---|")
        val correct = outcomes.filter { it.trial.kind == Kind.Correct && !it.trial.drill && it.score.abstention != Abstention.PoorFit }
        val simulated = outcomes.filter { it.trial.kind == Kind.Simulated && !it.trial.drill && it.score.abstention != Abstention.PoorFit }
        val pairs = outcomes.filter { it.trial.kind == Kind.ContentPair && it.score.abstention != Abstention.PoorFit }
        fun under(set: List<Outcome>, threshold: Double) = if (set.isEmpty()) Double.NaN else set.count { it.lpr <= threshold }.toDouble() / set.size
        fun between(set: List<Outcome>, threshold: Double) = if (set.isEmpty()) Double.NaN else set.count { it.lpr > threshold && it.lpr < 0 }.toDouble() / set.size
        for (threshold in listOf(-1.0, -2.0, -3.0, -4.0, -5.0, -6.0, -8.0)) {
            report.appendLine("| ${num(threshold, 0)} | ${pct(under(correct, threshold))} | ${pct(between(correct, threshold))} | ${pct(under(simulated, threshold))} | ${pct(under(pairs, threshold))} |")
        }
        val sorted = correct.map { it.lpr }.sorted()
        val fivePercent = sorted[maxOf(0, (0.05 * sorted.size).toInt() - 1)]
        report.appendLine()
        report.appendLine("Post hoc, on these same clips: false alarms stay at or under 5 % of correct words for rejectLpr below ${num(fivePercent, 2)}.")
        report.appendLine()
    }

    private fun listings(report: StringBuilder, outcomes: List<Outcome>) {
        report.appendLine("## Correct words called try again (padded)")
        report.appendLine()
        val alarms = outcomes.filter { it.trial.kind == Kind.Correct && !it.trial.drill && it.score.verdict == Verdict.TryAgain }
        if (alarms.isEmpty()) report.appendLine("None.")
        for (o in alarms.sortedBy { it.lpr }) {
            report.appendLine("- ${o.trial.clip} /${o.trial.intended.ipa}/, ${o.trial.sound.letter}: heard as ${o.score.heardAs?.soundsLike} (LPR ${num(o.lpr, 1)})")
        }
        report.appendLine()
        report.appendLine("## Content minimal pairs, trial by trial (padded)")
        report.appendLine()
        report.appendLine("| Clip | Judged as | Target | Truth | Verdict | Heard as | LPR |")
        report.appendLine("|---|---|---|---|---|---|---|")
        for (o in outcomes.filter { it.trial.kind == Kind.ContentPair }.sortedBy { it.trial.intended.text }) {
            report.appendLine("| ${o.trial.clip} | ${o.trial.intended.text} | ${o.trial.sound.letter} | ${o.trial.truth} | ${o.score.verdict} | ${o.score.targets.minByOrNull { it.lpr }?.heardAs?.soundsLike ?: "–"} | ${num(o.lpr, 1)} |")
        }
        report.appendLine()
        report.appendLine("Their own words, for comparison:")
        report.appendLine()
        val pairWords = outcomes.filter { it.trial.kind == Kind.ContentPair }.map { it.trial.clip }.toSet()
        for (o in outcomes.filter { it.trial.kind == Kind.Correct && it.trial.clip in pairWords }.sortedBy { it.trial.clip }) {
            report.appendLine("- ${o.trial.clip}, ${o.trial.sound.letter}: ${o.score.verdict}, LPR ${num(o.lpr, 1)}")
        }
        report.appendLine()
    }

    private fun fitGate(report: StringBuilder, outcomes: List<Outcome>) {
        report.appendLine("## The fit gate (padded)")
        report.appendLine()
        report.appendLine("Fit = (best candidate log-likelihood − free best path) / labels. Percentiles:")
        report.appendLine()
        report.appendLine("| Set | 1st | 5th | 50th | 95th |")
        report.appendLine("|---|---|---|---|---|")
        for ((name, filter) in listOf(
            "Correct words" to { o: Outcome -> o.trial.kind == Kind.Correct && !o.trial.drill },
            "Correct drills" to { o: Outcome -> o.trial.kind == Kind.Correct && o.trial.drill },
            "Simulated substitutions" to { o: Outcome -> o.trial.kind == Kind.Simulated },
            "Another word" to { o: Outcome -> o.trial.kind == Kind.OtherWord },
        )) {
            val fits = outcomes.filter(filter).map { it.score.fitPerLabel }.filter { !it.isNaN() }
            report.appendLine("| $name | ${num(Metrics.percentile(fits, 1.0), 2)} | ${num(Metrics.percentile(fits, 5.0), 2)} | ${num(Metrics.percentile(fits, 50.0), 2)} | ${num(Metrics.percentile(fits, 95.0), 2)} |")
        }
        report.appendLine()
        val other = outcomes.filter { it.trial.kind == Kind.OtherWord }
        report.appendLine("Another word called correct: ${other.count { it.score.verdict == Verdict.Correct }} of ${other.size} (${pct(rate(other, Verdict.Correct))}); try again ${pct(rate(other, Verdict.TryAgain))}; not heard ${pct(rate(other, Verdict.NotHeard))}.")
        report.appendLine()
    }

    /** How often the same trial gets the same verdict with and without the margins: the aggregate hides flips. */
    private fun stability(report: StringBuilder, padded: List<Outcome>, raw: List<Outcome>) {
        report.appendLine("Same verdict with and without the 250 ms margins:")
        report.appendLine()
        for ((name, kind) in listOf("correct words" to Kind.Correct, "simulated substitutions in words" to Kind.Simulated)) {
            val pairs = padded.indices.filter { padded[it].trial.kind == kind && !padded[it].trial.drill }
            val same = pairs.count { padded[it].score.verdict == raw[it].score.verdict }
            val swings = pairs.filter { padded[it].score.verdict != raw[it].score.verdict }
                .maxByOrNull { kotlin.math.abs(padded[it].lpr - raw[it].lpr) }
            val example = swings?.let { " The largest swing: ${padded[it].trial.clip} (${padded[it].trial.sound.letter}), LPR ${num(raw[it].lpr, 1)} without margins, ${num(padded[it].lpr, 1)} with them." } ?: ""
            report.appendLine("- $name: $same of ${pairs.size} (${pct(same.toDouble() / pairs.size)}).$example")
        }
        report.appendLine()
    }

    private fun latency(report: StringBuilder, file: File, audio: Map<String, FloatArray>, content: FoniaContent, loadMs: Double) {
        val drills = TrialBuilder.drills(content)
        val words = audio.filterKeys { it !in drills }
        report.appendLine("## Latency per word on this PC")
        report.appendLine()
        report.appendLine("${Runtime.getRuntime().availableProcessors()} logical CPUs (${System.getProperty("os.name")}, ${System.getProperty("os.arch")}), JVM ${System.getProperty("java.version")}. ${words.size} word clips, median ${num(Metrics.percentile(words.values.map { it.size / 16_000.0 }, 50.0), 2)} s of audio each, after 20 warm-up words. Model load (cold, first session): ${num(loadMs, 0)} ms.")
        report.appendLine()
        report.appendLine("| ONNX Runtime threads | fbank median | model median / 90th / max | GOP + verdict median / max | total median / 90th |")
        report.appendLine("|---|---|---|---|---|")
        for (threads in listOf(1, 4, 0)) {
            openModel(file, threads).use { model ->
                val scorer = PhoneScorer(model, policy)
                val fbank = ArrayList<Double>()
                val net = ArrayList<Double>()
                val gop = ArrayList<Double>()
                val total = ArrayList<Double>()
                val entries = words.entries.toList()
                for ((i, entry) in (entries.take(20) + entries).withIndex()) {
                    val pronunciation = RomanianG2P.pronounce(entry.key)
                    val start = System.nanoTime()
                    val posteriors = model.posteriors(entry.value)
                    val afterModel = System.nanoTime()
                    for (sound in pronunciation.sounds) scorer.judge(posteriors, pronunciation, sound)
                    val end = System.nanoTime()
                    if (i < 20) continue
                    fbank += model.lastFbankNanos / 1e6
                    net += model.lastModelNanos / 1e6
                    gop += (end - afterModel) / 1e6
                    total += (end - start) / 1e6
                }
                val label = if (threads == 0) "ONNX Runtime's default" else threads.toString()
                report.appendLine(
                    "| $label | ${num(Metrics.percentile(fbank, 50.0), 2)} ms | ${num(Metrics.percentile(net, 50.0), 1)} / ${num(Metrics.percentile(net, 90.0), 1)} / ${num(net.max(), 1)} ms | " +
                        "${num(Metrics.percentile(gop, 50.0), 2)} / ${num(gop.max(), 2)} ms | ${num(Metrics.percentile(total, 50.0), 1)} / ${num(Metrics.percentile(total, 90.0), 1)} ms |",
                )
            }
        }
        report.appendLine()
    }

    private fun writeCsv(file: File, outcomes: List<Outcome>) {
        val lines = ArrayList<String>()
        lines += "kind,clip,drill,intended,ipa,sound,truth,verdict,abstention,heard_as,lpr,gop,p_correct,fit_per_label,frames"
        for (o in outcomes) {
            val worst = o.score.targets.minByOrNull { it.lpr }
            lines += listOf(
                o.trial.kind, o.trial.clip, o.trial.drill, o.trial.intended.text, "/${o.trial.intended.ipa}/", o.trial.sound.rawValue,
                o.trial.truth ?: "", o.score.verdict, o.score.abstention ?: "", worst?.heardAs?.id ?: "",
                num(o.lpr, 4), num(worst?.score ?: Double.NaN, 4), num(worst?.pCorrect ?: Double.NaN, 4), num(o.score.fitPerLabel, 4), o.score.frames,
            ).joinToString(",") { "\"$it\"" }
        }
        file.writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
    }
}
