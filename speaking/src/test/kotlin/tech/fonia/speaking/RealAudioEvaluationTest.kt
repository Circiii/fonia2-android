package tech.fonia.speaking

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import tech.fonia.dsp.decodeWAV
import tech.fonia.dsp.encodeWAV
import tech.fonia.speech.Transcript
import tech.fonia.speech.TranscriptWord
import java.io.File
import java.util.Locale
import kotlin.test.assertTrue

/**
 * Opt-in evaluation on real (synthetic-voice) recordings: runs only when the WAVs exist locally (they are never
 * committed). Writes the cleaned takes, an EDL per mode and RAPORT.md into `fonia.speaking.out`.
 *
 * Modes: A = sound only (no Whisper); B = Whisper's ordinary transcript (it cleans fillers up, like the app's default
 * decoding); C = Whisper asked for a verbatim transcript (as the app does). The transcripts were made on the PC with
 * faster-whisper medium (evaluation only, `whisper/NAME.whisper.json` next to the outputs); without them only A runs.
 */
class RealAudioEvaluationTest {
    private val audioDir = File(System.getProperty("fonia.speaking.audio") ?: "")
    private val outDir = File(System.getProperty("fonia.speaking.out") ?: "build/fara_aaa")

    /** Hand-checked against the waveform and silences: what was said where (seconds, ±0.15 s). */
    private data class Truth(val label: String, val start: Double, val end: Double)

    private val truth = mapOf(
        "roger" to listOf(
            Truth("ăăă", 1.00, 1.50), Truth("ăăă", 3.47, 3.95), Truth("deci", 5.17, 5.44), Truth("practic", 5.50, 5.80),
            Truth("îîî", 7.46, 7.95), Truth("ăăă", 10.72, 11.30), Truth("cum să zic", 12.12, 12.68),
        ),
        "sarah" to listOf(
            Truth("ăăă", 1.09, 1.57), Truth("ăăă", 4.16, 4.73), Truth("deci", 6.36, 6.60), Truth("practic", 6.66, 7.02),
            Truth("ăăă", 8.87, 9.40), Truth("ăăă", 12.18, 12.66), Truth("cum să zic", 12.88, 13.48),
        ),
    )

    private class ModeResult(
        val mode: String, val analysis: SpeakingAnalysis, val clean: CleanResult, val quality: CutQualityReport,
        val found: List<Pair<Truth, FlaggedSpan?>>, val extra: List<FlaggedSpan>,
    )

    @Test
    fun evaluate() {
        val names = listOf("roger", "sarah").filter { File(audioDir, "${it}_fillers.wav").exists() }
        assumeTrue(names.isNotEmpty(), "test audio absent: skipped")
        outDir.mkdirs()
        val report = StringBuilder()
        report.appendLine("# „Tu, fără ăăă”: evaluare pe două voci sintetice")
        report.appendLine()
        report.appendLine("Generat de `RealAudioEvaluationTest` (`:engine:speaking:test`), praguri `${SpeakingThresholds.v1.version}`. Voci ElevenLabs (Roger, Sarah), 48 kHz mono, aceeași frază:")
        report.appendLine("„Bună ziua. Ăăă, astăzi vreau să vă povestesc despre, ăăă, proiectul nostru. Deci, practic, am construit o aplicație care, îîî, ajută copiii să vorbească mai clar. Ăăă, și, cum să zic, cam asta e tot. Vă mulțumesc.”")
        report.appendLine()
        report.appendLine("Șapte umpluturi de găsit în fiecare: 4 sunete (ăăă, ăăă, îîî, ăăă) și 3 cuvinte (deci, practic, cum să zic). Momentele „adevărate” sunt citite de mine din formă de undă și din pauze (±0,15 s).")
        report.appendLine()
        report.appendLine("Moduri: **A** = doar sunetul (fără Whisper); **B** = transcrierea obișnuită a lui Whisper (el „curăță” umpluturile); **C** = Whisper rugat să scrie tot (verbatim), cum face aplicația.")
        report.appendLine()
        var allClean = true
        val sections = StringBuilder()
        val brief = ArrayList<String>()
        for (name in names) {
            val wav = decodeWAV(File(audioDir, "${name}_fillers.wav").readBytes())
            val modes = ArrayList<ModeResult>()
            modes.add(run(name, "A", wav.pcm, wav.sampleRate, null))
            loadTranscript(name, false)?.let { modes.add(run(name, "B", wav.pcm, wav.sampleRate, it)) }
            loadTranscript(name, true)?.let { modes.add(run(name, "C", wav.pcm, wav.sampleRate, it)) }
            for (m in modes) {
                val suffix = when (m.mode) { "A" -> "_curat_fara_whisper"; "B" -> "_curat_whisper_obisnuit"; else -> "_curat" }
                File(outDir, "$name$suffix.wav").writeBytes(encodeWAV(m.clean.samples, m.clean.sampleRate.toInt()))
                File(outDir, "$name$suffix.edl.json").writeText(edl(name, m))
                allClean = allClean && m.quality.allClean
                printSummary(name, m)
                val sounds = m.found.filter { it.first.label in setOf("ăăă", "îîî") }
                brief.add(
                    "| $name | ${m.mode} | ${m.found.count { it.second != null }} din ${m.found.size} " +
                        "(sunete ${sounds.count { it.second != null }} din ${sounds.size}) | ${m.extra.size} | " +
                        "${SpeakingText.decimal(m.clean.stats.secondsRemoved)} s | ${m.quality.joins.count { it.noClick }} din ${m.quality.joins.size} | " +
                        "${if (m.quality.lengthConsistent) "da" else "NU"} |",
                )
            }
            sections.append(section(name, wav.pcm.size / wav.sampleRate, modes))
        }
        report.appendLine("## Pe scurt")
        report.appendLine()
        report.appendLine("| Voce | Mod | Umpluturi găsite și tăiate | Tăiate în plus | Scos | Îmbinări fără pocnet | Lungimi corecte |")
        report.appendLine("|---|---|---|---|---|---|---|")
        brief.forEach { report.appendLine(it) }
        report.appendLine()
        report.append(sections)
        report.append(verification(names))
        report.appendLine("## Ce înseamnă, cinstit")
        report.appendLine()
        report.appendLine("- Vocile sintetice spun „ăăă” prea curat: vocală ținută, ton plat, de obicei cu pauză în jur. Ezitările reale sunt mai scurte, mai șoptite, lipite de cuvinte și amestecate cu respirații; acolo detectorul acustic va rata mai multe și va fi mai nesigur. Pragurile sunt date în `spec/thresholds/speaking.json` și trebuie reglate pe înregistrări reale (G11 cere 50 de clipuri românești).")
        report.appendLine("- Cuvintele-umplutură („deci”, „practic”, „cum să zic”) se găsesc doar cu transcriere: fără Whisper, aplicația vede doar sunetele ținute.")
        report.appendLine("- O tăietură e „nesigură” (punctată pe ecran) sub încrederea 0,8 și nu se face deloc sub 0,6; omul o poate anula sau porni cu o atingere.")
        report.appendLine("- Cum citești coloana „cuvinte pierdute”: „îs, îi” e felul în care Whisper aude „îîî”-ul lui Roger în original (n-a fost găsit, deci a rămas și acum se aude „îți”); „e” → „asta-i” e doar altă scriere. „copiii” → „copii” în Sarah fără Whisper e o pagubă reală, mică: detectorul acustic a luat „iii”-ul lung din „copiii” drept „mmm” și l-a scurtat (cu transcriere, cuvântul îl protejează).")
        report.appendLine("- „Îîî”-ul lui Roger nu e găsit: e rostit cu un suflu la mijloc („î-hî”) și cu tonul căzând mult; nu trece nici de pragul de vocală ținută, nici de „insula” fără consoane. Mai bine ratat decât tăiat greșit.")
        report.appendLine("- Verificarea „fără pocnet” e obiectivă (saltul de eșantion și energia diferenței la îmbinare față de marginile originale), dar nu înlocuiește ascultarea: ascultați fișierele `*_curat.wav`.")
        File(outDir, "RAPORT.md").writeText(report.toString())
        assertTrue(allClean, "a join failed the click check or the length arithmetic; see RAPORT.md")
    }

    private fun run(name: String, mode: String, pcm: FloatArray, sr: Double, transcript: Transcript?): ModeResult {
        val analyzer = SpeakingAnalyzer()
        val started = System.nanoTime()
        val analysis = analyzer.analyze(pcm, sr, transcript)
        val clean = CleanCut.render(pcm, sr, analysis)
        val ms = (System.nanoTime() - started) / 1e6
        println("[$name/$mode] analysis + cut in ${"%.0f".format(ms)} ms")
        val quality = CutQuality.check(pcm, clean, analysis.frames.speechMedianDb)
        val truths = truth[name].orEmpty()
        val used = HashSet<Int>()
        val found = truths.map { t ->
            val s = analysis.spans.filter { it.id !in used && it.id in clean.cutIds && overlap(it, t) > 0.3 * (t.end - t.start) }
                .maxByOrNull { overlap(it, t) }
            if (s != null) used.add(s.id)
            t to s
        }
        val extra = analysis.spans.filter { it.id in clean.cutIds && it.id !in used }
        return ModeResult(mode, analysis, clean, quality, found, extra)
    }

    private fun overlap(s: FlaggedSpan, t: Truth) = minOf(s.end, t.end) - maxOf(s.start, t.start)

    private fun printSummary(name: String, m: ModeResult) {
        println("[$name/${m.mode}] spans:")
        for (s in m.analysis.spans) println("   #${s.id} ${s.kind} '${s.label}' ${f2(s.start)}-${f2(s.end)} conf=${f2(s.confidence)} cut=${s.cut} ${s.source} ${s.reason}")
        for (h in m.analysis.acoustic) println("   ~ac ${f2(h.start)}-${f2(h.end)} ${h.label} conf=${f2(h.confidence)} b=${h.boundedBefore}/${h.boundedAfter} len=${h.lengthening} ch=${f2(h.meanChangeDb)} sd=${f2(h.pitchSdSt)}")
        for (j in m.quality.joins) println("   join ${f2(j.oldStart)}-${f2(j.oldEnd)} ${j.edit.reason} jump=${f2(j.jumpRatio)} click=${f2(j.clickRatio)} edges=${f1(j.edgeBeforeDb)}/${f1(j.edgeAfterDb)} dB")
        println("   removed ${f2(m.clean.stats.secondsRemoved)} s, found ${m.found.count { it.second != null }}/${m.found.size}, extra ${m.extra.size}, length ok ${m.quality.lengthConsistent}")
    }

    private fun section(name: String, duration: Double, modes: List<ModeResult>): String {
        val sb = StringBuilder()
        sb.appendLine("## ${name.replaceFirstChar { it.uppercase() }} (${SpeakingText.decimal(duration)} s)")
        sb.appendLine()
        sb.appendLine("| Mod | Găsite | Ratate | Tăiate în plus | Secunde scoase | Durata nouă | Îmbinări fără pocnet | Margini liniștite | Lungimi corecte |")
        sb.appendLine("|---|---|---|---|---|---|---|---|---|")
        for (m in modes) {
            val ok = m.quality.joins.count { it.noClick }
            val quiet = m.quality.joins.count { it.quietEdges }
            sb.appendLine("| ${m.mode} | ${m.found.count { it.second != null }} din ${m.found.size} | ${m.found.filter { it.second == null }.joinToString { "„${it.first.label}” la ${SpeakingText.decimal(it.first.start)} s" }.ifEmpty { "—" }} | ${m.extra.size} | ${SpeakingText.decimal(m.clean.stats.secondsRemoved)} | ${SpeakingText.decimal(m.clean.stats.cleanSeconds)} s | $ok din ${m.quality.joins.size} | $quiet din ${m.quality.joins.size} | ${if (m.quality.lengthConsistent) "da" else "NU"} |")
        }
        sb.appendLine()
        for (m in modes) {
            sb.appendLine("**Mod ${m.mode}** — ${SpeakingText.headline(m.analysis, m.clean.cutIds)}. ${SpeakingText.cleaned(m.clean.stats)}")
            sb.appendLine()
            sb.appendLine("| Ce | Unde (s) | Găsit ca | Încredere | Sursa |")
            sb.appendLine("|---|---|---|---|---|")
            for ((t, s) in m.found) {
                sb.appendLine("| „${t.label}” | ${SpeakingText.decimal(t.start)}–${SpeakingText.decimal(t.end)} | ${s?.let { "„${it.label}” ${SpeakingText.decimal(it.start)}–${SpeakingText.decimal(it.end)}" } ?: "**ratat**"} | ${s?.let { SpeakingText.decimal(it.confidence * 10) + "/10" } ?: "—"} | ${s?.source?.let { src(it) } ?: "—"} |")
            }
            for (s in m.extra) sb.appendLine("| (în plus) | — | „${s.label}” ${SpeakingText.decimal(s.start)}–${SpeakingText.decimal(s.end)} (${s.kind.name.lowercase()}) | ${SpeakingText.decimal(s.confidence * 10)}/10 | ${src(s.source)} |")
            val offered = m.analysis.spans.filter { !it.cut }
            if (offered.isNotEmpty()) sb.appendLine("\nPropuse, dar netăiate (sub 0,6): " + offered.joinToString { "„${it.label}” la ${SpeakingText.decimal(it.start)} s" } + ".")
            val worst = m.quality.joins.filter { it.edit.start > 0 }.maxByOrNull { maxOf(it.jumpRatio / 2.0, it.clickRatio / 2.5) }
            if (worst != null) sb.appendLine("\nCea mai „grea” îmbinare: ${SpeakingText.decimal(worst.oldStart)} s (salt ×${SpeakingText.decimal(worst.jumpRatio)}, energie ×${SpeakingText.decimal(worst.clickRatio)}; pragurile: ×2,0 și ×2,5). Margini cu sunet (nu liniște): ${m.quality.loudEdges} — acolo tăietura e lipită de o vocală, nu în pauză.")
            sb.appendLine()
        }
        return sb.toString()
    }

    private fun src(s: SpanSource) = when (s) { SpanSource.ACOUSTIC -> "sunet"; SpanSource.TRANSCRIPT -> "text"; SpanSource.BOTH -> "sunet + text" }

    /** Compares Whisper's verbatim transcript of the original and of the cleaned take, when both exist. */
    private fun verification(names: List<String>): String {
        val sb = StringBuilder()
        val rows = ArrayList<String>()
        for (name in names) for (suffix in listOf("_curat", "_curat_fara_whisper")) {
            val before = loadWords(File(outDir, "whisper/${name}_fillers.verbatim.whisper.json")) ?: continue
            val after = loadWords(File(outDir, "whisper/$name$suffix.verbatim.whisper.json")) ?: continue
            val norm = { w: List<String> -> w.map { LexicalDetector.normalize(it) }.filter { it.isNotEmpty() } }
            val b = norm(before)
            val a = norm(after)
            val fillers = Regex(SpeakingThresholds.v1.lexicon.hesitationPattern)
            val markerWords = setOf("deci", "practic", "cum", "zic")
            val keptBefore = b.filter { !fillers.matches(it) }
            val lost = multisetMinus(keptBefore.filter { it !in markerWords && it != "să" }, a)
            val hesBefore = b.count { fillers.matches(it) }
            val hesAfter = a.count { fillers.matches(it) }
            rows.add("| $name$suffix | ${before.joinToString(" ")} | ${after.joinToString(" ")} | $hesBefore → $hesAfter | ${lost.joinToString(", ").ifEmpty { "niciunul" }} |")
        }
        if (rows.isEmpty()) return ""
        sb.appendLine("## Verificare cu Whisper (faster-whisper medium, pe PC, doar pentru evaluare)")
        sb.appendLine()
        sb.appendLine("Am transcris din nou fișierele curățate, tot cu „scrie tot”. În cel curățat ar trebui să dispară umpluturile și să rămână toate celelalte cuvinte.")
        sb.appendLine()
        sb.appendLine("| Fișier | Înainte | După | Sunete de ezitare | Cuvinte pierdute (în afară de umpluturi) |")
        sb.appendLine("|---|---|---|---|---|")
        rows.forEach { sb.appendLine(it) }
        sb.appendLine()
        return sb.toString()
    }

    private fun multisetMinus(a: List<String>, b: List<String>): List<String> {
        val left = b.groupingBy { it }.eachCount().toMutableMap()
        val out = ArrayList<String>()
        for (w in a) { val c = left[w] ?: 0; if (c > 0) left[w] = c - 1 else out.add(w) }
        return out
    }

    private fun loadWords(file: File): List<String>? {
        if (!file.exists()) return null
        val obj = Json.parseToJsonElement(file.readText()).jsonObject
        return obj["words"]!!.jsonArray.map { it.jsonObject["word"]!!.jsonPrimitive.content }
    }

    private fun loadTranscript(name: String, verbatim: Boolean): Transcript? {
        val file = File(outDir, "whisper/${name}_fillers${if (verbatim) ".verbatim" else ""}.whisper.json")
        if (!file.exists()) return null
        val obj: JsonObject = Json.parseToJsonElement(file.readText()).jsonObject
        val words = obj["words"]!!.jsonArray.map {
            val w = it.jsonObject
            TranscriptWord(w["word"]!!.jsonPrimitive.content, w["start"]!!.jsonPrimitive.double, w["end"]!!.jsonPrimitive.double, w["prob"]!!.jsonPrimitive.double)
        }
        return Transcript(obj["text"]!!.jsonPrimitive.content, words, noSpeechProb = 0.0, logp = 0.0)
    }

    private fun edl(name: String, m: ModeResult): String {
        val sb = StringBuilder()
        sb.append("{\n  \"take\": \"").append(name).append("\",\n  \"mode\": \"").append(m.mode).append("\",\n")
        sb.append("  \"thresholds\": \"").append(m.analysis.thresholdsVersion).append("\",\n")
        sb.append("  \"originalSeconds\": ").append(f3(m.clean.stats.originalSeconds)).append(",\n")
        sb.append("  \"cleanSeconds\": ").append(f3(m.clean.stats.cleanSeconds)).append(",\n")
        sb.append("  \"secondsRemoved\": ").append(f3(m.clean.stats.secondsRemoved)).append(",\n")
        sb.append("  \"fillersPerMinuteBefore\": ").append(f2(m.clean.stats.fillersPerMinuteBefore)).append(",\n")
        sb.append("  \"fillersPerMinuteAfter\": ").append(f2(m.clean.stats.fillersPerMinuteAfter)).append(",\n")
        sb.append("  \"spans\": [\n")
        sb.append(m.analysis.spans.joinToString(",\n") { s ->
            "    {\"id\": ${s.id}, \"kind\": \"${s.kind}\", \"label\": \"${s.label}\", \"start\": ${f3(s.start)}, \"end\": ${f3(s.end)}, \"confidence\": ${f2(s.confidence)}, \"cut\": ${s.id in m.clean.cutIds}, \"unsure\": ${s.unsure}, \"source\": \"${s.source}\", \"reason\": \"${s.reason}\"}"
        })
        sb.append("\n  ],\n  \"edits\": [\n")
        sb.append(m.quality.joins.joinToString(",\n") { j ->
            "    {\"oldStart\": ${f3(j.oldStart)}, \"oldEnd\": ${f3(j.oldEnd)}, \"newAt\": ${f3(j.newAt)}, \"reason\": \"${j.edit.reason}\", \"spans\": ${j.edit.spanIds}, \"crossfadeMs\": ${f1(2.0 * j.edit.half / m.clean.sampleRate * 1000)}, \"jumpRatio\": ${f2(j.jumpRatio)}, \"clickRatio\": ${f2(j.clickRatio)}, \"edgeBeforeDb\": ${f1(j.edgeBeforeDb)}, \"edgeAfterDb\": ${f1(j.edgeAfterDb)}, \"noClick\": ${j.noClick}, \"quietEdges\": ${j.quietEdges}}"
        })
        sb.append("\n  ]\n}\n")
        return sb.toString()
    }

    private fun f1(v: Double) = String.format(Locale.ROOT, "%.1f", v)
    private fun f2(v: Double) = String.format(Locale.ROOT, "%.2f", v)
    private fun f3(v: Double) = String.format(Locale.ROOT, "%.3f", v)
}
