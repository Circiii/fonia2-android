package tech.fonia.speaking.coach

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import tech.fonia.dsp.LiveAnalyzer
import tech.fonia.dsp.LiveFrame
import tech.fonia.dsp.VoiceGroup
import tech.fonia.dsp.decodeWAV
import java.io.File
import kotlin.math.abs

/**
 * The pace on real Romanian speech (opt-in by presence: `HSIC/tmp/coach_clips/clips.json` with 16 kHz WAVs and their
 * texts; never committed). Counts the nuclei the live coach finds against the syllables of the text and writes the
 * table to `HSIC/output/speech2/pace_eval.md`.
 */
class CoachPaceEvaluationTest {
    @Test
    fun romanianClips() {
        val dir = File(System.getProperty("fonia.coach.clips") ?: return)
        val list = File(dir, "clips.json").takeIf { it.exists() } ?: return
        val clips = Json.parseToJsonElement(list.readText()).jsonArray
        val rows = StringBuilder()
        rows.append("| clip | text syllables | nuclei | error | phonation s | rate (nuclei) | rate (text) |\n|---|---|---|---|---|---|---|\n")
        var nSum = 0L
        var sSum = 0L
        var absErr = 0.0
        var counted = 0
        val ratesText = ArrayList<Double>()
        val ratesCoach = ArrayList<Double>()
        for (c in clips) {
            val o = c.jsonObject
            val text = o["text"]!!.jsonPrimitive.content
            if (!RomanianSyllables.countable(text)) continue
            val wav = decodeWAV(File(dir, o["wav"]!!.jsonPrimitive.content).readBytes())
            val s = run(wav.pcm, wav.sampleRate)
            val syl = RomanianSyllables.count(text)
            if (s.phonationSeconds < 1.0) continue
            counted++
            nSum += s.nuclei
            sSum += syl
            absErr += abs(s.nuclei - syl).toDouble() / syl
            val rText = syl / s.phonationSeconds
            ratesText += rText
            ratesCoach += s.articulationRate
            rows.append("| ${o["wav"]!!.jsonPrimitive.content} | $syl | ${s.nuclei} | ${"%+d".format(s.nuclei - syl)} | ${"%.2f".format(s.phonationSeconds)} | ${"%.2f".format(s.articulationRate)} | ${"%.2f".format(rText)} |\n")
        }
        if (counted == 0) return
        // Long free speech of the two test voices (no text: the rate only).
        val extra = StringBuilder()
        File(System.getProperty("fonia.speaking.audio") ?: "").listFiles { f -> f.name.endsWith("_fillers.wav") }?.forEach { f ->
            val wav = decodeWAV(f.readBytes())
            val live = ArrayList<Double>()
            val s = run(wav.pcm, wav.sampleRate, onRate = { live += it })
            val p = CoachSpec.v1.pace
            val over = live.count { it > p.bandHigh } * 100 / maxOf(1, live.size)
            val under = live.count { it < p.bandLow } * 100 / maxOf(1, live.size)
            extra.append("| ${f.name} | ${"%.1f".format(s.seconds)} | ${"%.2f".format(s.phonationSeconds)} | ${s.nuclei} | ${"%.2f".format(s.articulationRate)} | ${s.longPauses.size} | $over % / $under % | ${"%.2f".format(s.pitchSpreadSt)} |\n")
        }
        // The fixture talks (normal, ×1.4, ×0.68, quiet after 10 s) the emulator screenshots use.
        dir.listFiles { f -> f.name.startsWith("talk_") && f.name.endsWith(".wav") }?.sortedBy { it.name }?.forEach { f ->
            val wav = decodeWAV(f.readBytes())
            val live = ArrayList<Double>()
            val s = run(wav.pcm, wav.sampleRate, onRate = { live += it })
            val p = CoachSpec.v1.pace
            val over = live.count { it > p.bandHigh } * 100 / maxOf(1, live.size)
            val under = live.count { it < p.bandLow } * 100 / maxOf(1, live.size)
            extra.append("| ${f.name} | ${"%.1f".format(s.seconds)} | ${"%.2f".format(s.phonationSeconds)} | ${s.nuclei} | ${"%.2f".format(s.articulationRate)} | ${s.longPauses.size} | $over % / $under % | ${"%.2f".format(s.pitchSpreadSt)} |\n")
        }
        fun med(x: List<Double>) = x.sorted()[x.size / 2]
        fun pct(x: List<Double>, p: Double) = x.sorted()[((x.size - 1) * p).toInt()]
        val out = File(System.getProperty("fonia.speaking.out") ?: return).parentFile.resolve("speech2").apply { mkdirs() }
        val report = buildString {
            append("# Ritmul antrenorului pe clipuri românești\n\n")
            append("Clipuri: $counted (vocea sintetică românească a conținutului, ElevenLabs; text cunoscut). Silabele textului: numărate pe grupuri de vocale (ROM).\n\n")
            append("- Nuclee găsite / silabe din text: $nSum / $sSum (${"%.1f".format(100.0 * nSum / sSum)} %)\n")
            append("- Eroare absolută medie pe clip: ${"%.1f".format(100 * absErr / counted)} %\n")
            append("- Ritm de articulare din text (silabe ÷ fonație): mediană ${"%.2f".format(med(ratesText))}, p10 ${"%.2f".format(pct(ratesText, 0.1))}, p90 ${"%.2f".format(pct(ratesText, 0.9))} silabe/s\n")
            append("- Ritm citit de antrenor: mediană ${"%.2f".format(med(ratesCoach))}, p10 ${"%.2f".format(pct(ratesCoach, 0.1))}, p90 ${"%.2f".format(pct(ratesCoach, 0.9))} silabe/s\n\n")
            append(rows)
            if (extra.isNotEmpty()) {
                append("\n## Vorbire liberă (Roger, Sarah, cu umpluturi) și discursurile-fixture\n\n")
                append("| fișier | s | fonație s | nuclee | ritm | pauze lungi | ferestre live peste / sub bandă | melodie (st) |\n|---|---|---|---|---|---|---|---|\n")
                append(extra)
            }
        }
        File(out, "pace_eval.md").writeText(report)
        println(report.lines().take(8).joinToString("\n"))
    }

    /** A small grid over the nucleus settings (printed only; the chosen values go to spec/ by hand). */
    @Test
    fun sweep() {
        val dir = File(System.getProperty("fonia.coach.clips") ?: return)
        val list = File(dir, "clips.json").takeIf { it.exists() } ?: return
        if (System.getProperty("fonia.coach.sweep") == null) return
        val clips = Json.parseToJsonElement(list.readText()).jsonArray.map { it.jsonObject }
            .filter { RomanianSyllables.countable(it["text"]!!.jsonPrimitive.content) }
            .map { decodeWAV(File(dir, it["wav"]!!.jsonPrimitive.content).readBytes()) to RomanianSyllables.count(it["text"]!!.jsonPrimitive.content) }
        val results = ArrayList<Pair<String, Double>>()
        for (prom in listOf(1.5, 2.0, 2.5, 3.0)) for (below in listOf(20.0, 25.0, 30.0)) for (gap in listOf(0.08, 0.1, 0.12)) for (low in listOf(300.0, 250.0)) for (high in listOf(2500.0, 1500.0)) {
            val spec = CoachSpec.v1.copy(pace = CoachSpec.v1.pace.copy(minProminenceDb = prom, belowPeakDb = below, minGapSeconds = gap, vowelLowHz = low, vowelHighHz = high))
            var err = 0.0
            var bias = 0.0
            for ((wav, syl) in clips) {
                val s = run(wav.pcm, wav.sampleRate, spec)
                err += abs(s.nuclei - syl).toDouble() / syl
                bias += (s.nuclei - syl).toDouble() / syl
            }
            results += "prom=$prom below=$below gap=$gap band=$low-$high bias=${"%.3f".format(bias / clips.size)}" to err / clips.size
        }
        results.sortedBy { it.second }.take(12).forEach { println("${"%.3f".format(it.second)}  ${it.first}") }
    }

    private fun run(pcm: FloatArray, rate: Double, spec: CoachSpec = CoachSpec.v1, onRate: (Double) -> Unit = {}): AudioSummary {
        val coach = AudioCoach(rate, spec)
        val live = LiveAnalyzer(rate, VoiceGroup.FEMALE, null)
        val frame = LiveFrame.silent
        val hop = (rate * 0.01).toInt()
        var i = 0
        while (i < pcm.size) {
            val n = minOf(hop, pcm.size - i)
            val f = live.process(pcm, i, n, frame)
            coach.process(pcm, i, n, f.pitchHz)
            i += n
            if ((i / n) % 25 == 0 && !coach.rate.isNaN() && coach.speaking) onRate(coach.rate)
        }
        return coach.summary()
    }
}

/** Romanian syllables from spelling: vowel groups, a word-final non-syllabic „i” after a consonant not counted. */
internal object RomanianSyllables {
    private const val VOWELS = "aeiouăâîAEIOUĂÂÎ"

    /** Texts with onomatopoeia („zzz”, „ci-ci-ci”, „ț-ț-ț”) have no agreed syllable count. */
    fun countable(text: String): Boolean {
        val words = text.lowercase().split(Regex("[^\\p{L}-]+")).filter { it.isNotEmpty() }
        return words.none { w -> Regex("(\\p{L})\\1\\1").containsMatchIn(w) || (w.contains('-') && w.split('-').any { it.length <= 2 }) }
    }

    fun count(text: String): Int = text.lowercase().split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }.sumOf { word(it) }

    fun word(w: String): Int {
        var groups = 0
        var inVowel = false
        for (ch in w) {
            val v = ch in VOWELS
            if (v && !inVowel) groups++
            inVowel = v
        }
        // „pomi”, „lupi”, „faci”: the final i after a consonant is only palatalisation.
        if (groups > 1 && w.length >= 2 && w.last() == 'i' && w[w.length - 2] !in VOWELS) groups--
        return groups.coerceAtLeast(if (w.any { it in VOWELS }) 1 else 0)
    }
}
