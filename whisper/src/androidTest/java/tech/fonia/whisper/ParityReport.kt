package tech.fonia.whisper

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale
import kotlin.math.abs

/**
 * Not an iOS test: the harness rows of this device next to the iOS rows of tools/eval_speech/report.md
 * (WhisperKit, Core ML, iOS Simulator CPU), as markdown. Verdicts are compared as the judges give them
 * (pair outcome, sentence result and word statuses, free-speech pace and focus); margins as numbers.
 * Nothing is refitted: the thresholds stay SpeechThresholds.STANDARD on both sides.
 */
object ParityReport {
    private val json = Json { ignoreUnknownKeys = true }

    fun compare(rows: List<JsonObject>, iosReport: String): String {
        val ios = json.parseToJsonElement(iosReport).jsonObject
        val iosPairs = ios["pairs"]!!.jsonArray.map { it.jsonObject }
        val iosSentences = ios["sentences"]!!.jsonArray.map { it.jsonObject }
        val iosSpeech = ios["speech"]!!.jsonArray.map { it.jsonObject }
        val meta = rows.first { it.s("kind") == "meta" }
        val pairs = rows.filter { it.s("kind") == "pair" }
        val sentences = rows.filter { it.s("kind") == "sentence" }
        val speech = rows.filter { it.s("kind") == "speech" }
        val out = mutableListOf<String>()

        out += "# Parity with iOS: Whisper base on ${meta.s("device")}"
        out += ""
        out += "${meta.s("system")}, ${meta.i("cores")} cores, ${meta.i("threads")} ggml threads, ${meta.s("library")}. ggml: ${meta.s("ggml")}"
        out += "Prewarm ${f(meta.d("prewarmSeconds"), 2)} s, load ${f(meta.d("loadSeconds"), 2)} s, first clip ${f(meta.d("firstClipMs"), 0)} ms, second ${f(meta.d("secondClipMs"), 0)} ms."
        out += ""

        // Pairs.
        var agree = 0
        val deltas = mutableListOf<Double>()
        val wordDeltas = mutableListOf<Double>()
        out += "## Minimal pairs"
        out += ""
        out += "| Fixture | Target | Rival | Heard (iOS / Android) | Margin iOS | Margin Android | Shift | Outcome iOS | Outcome Android | Same | ms | Steps (iOS / Android) | No-speech |"
        out += "|---|---|---|---|---:|---:|---:|---|---|---|---:|---|---:|"
        for (row in pairs) {
            val reference = iosPairs.first { it.s("fixture") == row.s("fixture") && it.s("target") == row.s("target") && it.s("rival") == row.s("rival") }
            val outcome = plain(row.s("outcome"))
            val same = outcome == reference.s("outcome")
            if (same) agree += 1
            val shift = row.d("margin") - reference.d("margin")
            deltas += shift
            if (row["contrast"]?.jsonPrimitive?.contentOrNull != null) wordDeltas += shift
            out += "| ${row.s("fixture")} | ${row.s("target")} | ${row.s("rival")} | ${reference.s("heard").ifEmpty { "(nothing)" }} / ${row.s("heard").ifEmpty { "(nothing)" }} | " +
                "${f(reference.d("margin"), 2)} | ${f(row.d("margin"), 2)} | ${signed(shift)} | ${reference.s("outcome")} | $outcome | ${if (same) "yes" else "**no**"} | " +
                "${f(row.d("ms"), 0)} | ${reference.i("decoderSteps")} / ${row.i("decoderSteps")} | ${f(row.d("noSpeechProb"), 2)} |"
        }
        out += ""
        out += "Same outcome as iOS on $agree of ${pairs.size} rows. Margin shift (Android minus iOS, nats): median ${signed(median(deltas))}, " +
            "median absolute ${f(median(deltas.map(::abs)), 2)}, largest ${f(deltas.maxOf(::abs), 2)}; single words: median ${signed(median(wordDeltas))}, " +
            "median absolute ${f(median(wordDeltas.map(::abs)), 2)}, largest ${f(wordDeltas.maxOf(::abs), 2)}."
        out += ""

        // Sentences.
        var sentenceAgree = 0
        var wordAgree = 0
        var wordTotal = 0
        out += "## Sentences"
        out += ""
        out += "| Fixture | Target | Heard (Android) | Result iOS | Result Android | Score iOS / Android | Word statuses that match | ms | Words (Android) |"
        out += "|---|---|---|---|---|---|---:|---:|---|"
        for (row in sentences) {
            val reference = iosSentences.first { it.s("fixture") == row.s("fixture") && it.s("target") == row.s("target") }
            val words = row["words"]!!.jsonArray.map { it.jsonObject }
            val referenceWords = reference["words"]!!.jsonArray.map { it.jsonObject }
            val matches = words.zip(referenceWords).count { (a, b) -> a.s("word") == b.s("word") && a.s("status") == b.s("status") }
            wordAgree += matches
            wordTotal += referenceWords.size
            val same = row.s("result") == reference.s("result")
            if (same) sentenceAgree += 1
            val described = words.joinToString(" ") { w ->
                "${w.s("word")}:${w.s("status")}" + (w["heard"]?.jsonPrimitive?.contentOrNull?.let { "($it)" } ?: "") + "@${f(w.d("start"), 2)}-${f(w.d("end"), 2)}"
            }
            out += "| ${row.s("fixture")} | ${row.s("target")} | ${row.s("heard")} | ${reference.s("result")} | ${row.s("result")}${if (same) "" else " **(differs)**"} | " +
                "${f(reference.d("score"), 2)} / ${f(row.d("score"), 2)} | $matches of ${referenceWords.size} | ${f(row.d("ms"), 0)} | $described |"
        }
        out += ""
        out += "Same result as iOS on $sentenceAgree of ${sentences.size} sentences; same word status on $wordAgree of $wordTotal words."
        out += ""

        // Free speech.
        out += "## Free speech"
        out += ""
        out += "| Fixture | Pace iOS / Android | Words/min iOS / Android | Fillers iOS | Fillers Android | Focus iOS / Android | Score iOS / Android | ms |"
        out += "|---|---|---|---|---|---|---|---:|"
        for (row in speech) {
            val reference = iosSpeech.first { it.s("fixture") == row.s("fixture") }
            val fillers = row["fillerCounts"]!!.jsonObject.entries.joinToString(", ") { "${it.key} ${it.value}" }
            val referenceFillers = reference["fillerCounts"]!!.jsonObject.entries.joinToString(", ") { "${it.key} ${it.value}" }
            out += "| ${row.s("fixture")} | ${reference.s("pace")} / ${row.s("pace")} | ${f(reference.d("wordsPerMinute"), 0)} / ${f(row.d("wordsPerMinute"), 0)} | " +
                "$referenceFillers | $fillers | ${reference.s("focus")} / ${row.s("focus")} | ${f(reference.d("score"), 2)} / ${f(row.d("score"), 2)} | ${f(row.d("ms"), 0)} |"
        }
        out += ""
        for (row in speech) out += "- ${row.s("fixture")} (Android): ${row.s("text")}"
        out += ""

        // Latency.
        val wordMs = pairs.filter { it["contrast"]?.jsonPrimitive?.contentOrNull != null }.map { it.d("ms") }
        val allPairMs = pairs.map { it.d("ms") }
        val sentenceMs = sentences.map { it.d("ms") }
        val iosWordMs = iosPairs.filter { p -> pairs.any { it.s("fixture") == p.s("fixture") && it.s("target") == p.s("target") && it["contrast"]?.jsonPrimitive?.contentOrNull != null } }.map { it.d("ms") }
        out += "## Latency"
        out += ""
        out += "Median of three runs per row. Single-word pair: median ${f(median(wordMs), 0)} ms (iOS ${f(median(iosWordMs), 0)}), slowest ${f(wordMs.max(), 0)} ms. " +
            "All pairs: median ${f(median(allPairMs), 0)} ms (iOS ${f(median(iosPairs.map { it.d("ms") }), 0)}). " +
            "Sentence: median ${f(median(sentenceMs), 0)} ms (iOS ${f(median(iosSentences.map { it.d("ms") }), 0)})."
        out += ""
        return out.joinToString("\n")
    }

    private fun plain(outcome: String): String = outcome.substringBefore("(")

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return Double.NaN
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2
    }

    private fun f(value: Double, digits: Int): String = String.format(Locale.ROOT, "%.${digits}f", value)

    private fun signed(value: Double): String = String.format(Locale.ROOT, "%+.2f", value)

    private fun JsonObject.s(key: String): String = this[key]?.jsonPrimitive?.contentOrNull ?: ""

    private fun JsonObject.d(key: String): Double = this[key]?.jsonPrimitive?.doubleOrNull ?: Double.NaN

    private fun JsonObject.i(key: String): Int = this[key]?.jsonPrimitive?.intOrNull ?: -1
}
