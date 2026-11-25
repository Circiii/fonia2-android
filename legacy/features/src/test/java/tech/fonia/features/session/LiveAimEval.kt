package tech.fonia.features.session

import org.junit.Assume.assumeTrue
import org.junit.Test
import tech.fonia.domain.SoundID
import tech.fonia.dsp.LiveAnalyzer
import tech.fonia.dsp.LiveFrame
import tech.fonia.dsp.VoiceGroup
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Measures the living letter on recorded clips: for every clip and every target sound, does the letter light
 * ([LiveSmoother.hit]) and for how long. A clip that contains the sound should light it; a clip without it (another
 * word, a noise) should not. Runs only when `tools/eval/out/live_eval.on` exists; reads `tools/eval/out/manifest.jsonl`
 * (the judge's evaluation set) plus every WAV/MP3 under `tools/eval/out/negatives/` (noises: nothing may light).
 * Writes `tools/eval/out/live_aim.md`.
 */
class LiveAimEval {
    private val root = File(System.getProperty("fonia.content") ?: ".").absoluteFile.parentFile
    private val out = File(root, "tools/eval/out")

    private class Clip(val file: String, val said: String, val group: VoiceGroup, val level: String)

    private fun decode(file: File): FloatArray? = try {
        val p = ProcessBuilder("ffmpeg", "-v", "error", "-i", file.absolutePath, "-f", "f32le", "-ac", "1", "-ar", "48000", "-").start()
        val bytes = p.inputStream.readBytes()
        p.waitFor()
        val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        FloatArray(fb.remaining()).also { fb.get(it) }
    } catch (e: Exception) { null }

    private fun field(line: String, key: String): String =
        Regex("\"$key\": \"((?:[^\"\\\\]|\\\\.)*)\"").find(line)?.groupValues?.get(1)?.replace("\\\\", "\\")
            ?.replace(Regex("\\\\u([0-9a-fA-F]{4})")) { it.groupValues[1].toInt(16).toChar().toString() } ?: ""

    /** The clip contains a sound of the target's live family (the hiss on the same side; R; any voice for L). */
    private fun has(said: String, id: SoundID): Boolean {
        val t = said.lowercase()
        val sharp = 's' in t || 'x' in t || 'z' in t || 'ț' in t
        val round = 'ș' in t || 'j' in t || "ce" in t || "ci" in t || "ge" in t || "gi" in t
        return when (id) {
            SoundID.S, SoundID.Z, SoundID.Ts -> sharp
            SoundID.Sh, SoundID.J, SoundID.Ce, SoundID.Ge -> round
            SoundID.R -> 'r' in t
            SoundID.L -> 'l' in t
        }
    }

    /** Longest lit stretch (ms) for each aim on one clip, stepped at 60 frames a second as the screen does. */
    private fun lit(pcm: FloatArray, group: VoiceGroup, aims: List<LiveAim>, rules: AimRules = AimRules()): FloatArray {
        val analyzer = LiveAnalyzer(48000.0, group, null, tech.fonia.dsp.DSPThresholds.v1)
        val frame: LiveFrame = LiveFrame.silent
        val smoothers = aims.map { LiveSmoother(Pacing(), rules) }
        val bias = placeBias(group)
        val best = FloatArray(aims.size)
        val run = FloatArray(aims.size)
        val hop = 480
        var at = 0
        var clock = 0f
        var nextFrame = 0f
        val dt = 1f / 60f
        while (at + hop <= pcm.size) {
            analyzer.process(pcm, at, hop, frame)
            at += hop
            clock += 0.01f
            while (nextFrame <= clock) {
                nextFrame += dt
                for (i in aims.indices) {
                    val s = smoothers[i]
                    s.step(
                        dt, frame.level, frame.clarity, frame.fricativePresent, frame.fricativeStrength,
                        if (frame.fricativePresent) recentred(frame.sNess, bias) else Float.NaN,
                        frame.trillStrength, frame.blowStrength, aims[i], frame.fricativeVoiced, hissFlatness = frame.hissFlatness,
                    )
                    if (s.hit) { run[i] += dt * 1000f; if (run[i] > best[i]) best[i] = run[i] } else run[i] = 0f
                }
            }
        }
        return best
    }

    @Test
    fun measure() {
        assumeTrue(File(out, "live_eval.on").exists())
        val ids = SoundID.entries.toList()
        val aims = ids.map { LiveAim.of(it) }
        val seen = HashSet<String>()
        val clips = ArrayList<Clip>()
        File(out, "manifest.jsonl").forEachLine { line ->
            val file = field(line, "file")
            if (field(line, "expected") != "correct" || !seen.add(file)) return@forEachLine
            val group = when (field(line, "group")) { "man" -> VoiceGroup.MALE; "child" -> VoiceGroup.CHILD; else -> VoiceGroup.FEMALE }
            clips += Clip(file, field(line, "said"), group, field(line, "level"))
        }
        val negatives = File(out, "negatives").listFiles { f -> f.extension in setOf("wav", "mp3") }?.sortedBy { it.name } ?: emptyList()

        // [aim][0 = has the sound, 1 = lacks it] -> lit count / total
        val litCount = Array(ids.size) { IntArray(2) }
        val total = Array(ids.size) { IntArray(2) }
        val wrong = Array(ids.size) { ArrayList<String>() }
        val missed = Array(ids.size) { ArrayList<String>() }
        for (c in clips) {
            val pcm = decode(File(c.file)) ?: continue
            val best = lit(pcm, c.group, aims)
            for (i in ids.indices) {
                val k = if (has(c.said, ids[i])) 0 else 1
                total[i][k]++
                val on = best[i] > 0f
                if (on) litCount[i][k]++
                if (k == 1 && on && wrong[i].size < 12) wrong[i] += "${c.said}(${best[i].toInt()})"
                if (k == 0 && !on && missed[i].size < 12) missed[i] += c.said
            }
        }
        val sb = StringBuilder("# Litera vie pe clipuri (${clips.size} clipuri, ${negatives.size} zgomote)\n\n")
        sb.append("| țintă | se aprinde când sunetul e în clip | se aprinde când NU e | exemple false | exemple ratate |\n|---|---|---|---|---|\n")
        for (i in ids.indices) {
            sb.append("| ${ids[i].rawValue} | ${litCount[i][0]}/${total[i][0]} | ${litCount[i][1]}/${total[i][1]} | ${wrong[i].joinToString(" ")} | ${missed[i].joinToString(" ")} |\n")
        }
        sb.append("\n## Zgomote (nimic nu trebuie să se aprindă)\n\n| clip | ținte aprinse (ms) |\n|---|---|\n")
        val noiseLit = IntArray(ids.size)
        for (f in negatives) {
            val pcm = decode(f) ?: continue
            val groups = listOf(VoiceGroup.CHILD, VoiceGroup.MALE)
            val cell = StringBuilder()
            for (g in groups) {
                val best = lit(pcm, g, aims)
                for (i in ids.indices) if (best[i] > 0f) { cell.append("${ids[i].rawValue}/${g.rawValue}:${best[i].toInt()} "); if (g == VoiceGroup.CHILD) noiseLit[i]++ }
            }
            sb.append("| ${f.name} | $cell |\n")
        }
        sb.append("\nZgomote care aprind (grup copil): " + ids.indices.joinToString(", ") { "${ids[it].rawValue} ${noiseLit[it]}/${negatives.size}" } + "\n")
        File(out, "live_aim.md").writeText(sb.toString())
        println(sb)
    }

    /** Raw live readings on the hiss frames of the short clips, to design the aims from. */
    @Test
    fun rawReadings() {
        assumeTrue(File(out, "live_eval.on").exists())
        val seen = HashSet<String>()
        val sb = StringBuilder("said	voice	group	frames	hiss	sNessMed	sNess10	sNess90	voicedShare	clarityMedOnHiss	strengthMed	trillMax	clarityMedSpeaking\n")
        File(out, "manifest.jsonl").forEachLine { line ->
            val file = field(line, "file")
            if (field(line, "expected") != "correct" || !seen.add(file)) return@forEachLine
            val said = field(line, "said")
            if (field(line, "level") == "word" && said.length > 5 && said.toSet().size > 2) return@forEachLine
            val group = when (field(line, "group")) { "man" -> VoiceGroup.MALE; "child" -> VoiceGroup.CHILD; else -> VoiceGroup.FEMALE }
            val pcm = decode(File(file)) ?: return@forEachLine
            val analyzer = LiveAnalyzer(48000.0, group, null, tech.fonia.dsp.DSPThresholds.v1)
            val f: LiveFrame = LiveFrame.silent
            val sn = ArrayList<Float>(); val cl = ArrayList<Float>(); val st = ArrayList<Float>(); val clSp = ArrayList<Float>()
            var voiced = 0; var frames = 0; var trill = 0f
            var at = 0
            while (at + 480 <= pcm.size) {
                analyzer.process(pcm, at, 480, f); at += 480; frames++
                if (f.fricativePresent) { sn += f.sNess; cl += f.clarity; st += f.fricativeStrength; if (f.fricativeVoiced) voiced++ }
                if (f.speaking) clSp += f.clarity
                if (f.trillStrength > trill) trill = f.trillStrength
            }
            fun q(l: List<Float>, p: Float) = if (l.isEmpty()) Float.NaN else l.sorted()[((l.size - 1) * p).toInt()]
            sb.append("$said	${field(line, "voice")}	${group.rawValue}	$frames	${sn.size}	${"%.2f".format(q(sn, .5f))}	${"%.2f".format(q(sn, .1f))}	${"%.2f".format(q(sn, .9f))}	${"%.2f".format(if (sn.isEmpty()) 0f else voiced.toFloat() / sn.size)}	${"%.2f".format(q(cl, .5f))}	${"%.2f".format(q(st, .5f))}	${"%.2f".format(trill)}	${"%.2f".format(q(clSp, .5f))}\n")
        }
        File(out, "live_raw.tsv").writeText(sb.toString())
    }

    /** Tries rule sets: for each, the share of clips lit with the sound and without it, per family. */
    @Test
    fun grid() {
        assumeTrue(File(out, "live_eval.on").exists())
        val seen = HashSet<String>()
        val clips = ArrayList<Pair<Clip, FloatArray>>()
        File(out, "manifest.jsonl").forEachLine { line ->
            val file = field(line, "file")
            if (field(line, "expected") != "correct" || !seen.add(file)) return@forEachLine
            val group = when (field(line, "group")) { "man" -> VoiceGroup.MALE; "child" -> VoiceGroup.CHILD; else -> VoiceGroup.FEMALE }
            val pcm = decode(File(file)) ?: return@forEachLine
            clips += Clip(file, field(line, "said"), group, field(line, "level")) to pcm
        }
        val negatives = (File(out, "negatives").listFiles { f -> f.extension in setOf("wav", "mp3") }?.sortedBy { it.name } ?: emptyList()).mapNotNull { decode(it) }
        val fam = listOf(SoundID.S, SoundID.Sh, SoundID.R, SoundID.L)
        val aims = fam.map { LiveAim.of(it) }
        val sets = ArrayList<AimRules>()
        for (sm in listOf(0.55f, 0.65f, 0.75f)) for (rm in listOf(0.45f, 0.3f, 0.2f, 0.1f)) for (on in listOf(120f, 80f, 60f)) sets += AimRules(sharpMin = sm, roundMax = rm, onMs = on)
        for (tr in listOf(0.2f, 0.3f, 0.4f, 0.5f)) for (on in listOf(120f, 60f)) sets += AimRules(trillMin = tr, onMs = on)
        for (vc in listOf(0.6f, 0.75f, 0.85f, 0.92f)) for (vl in listOf(0.08f, 0.2f, 0.35f)) sets += AimRules(voiceClear = vc, voiceLevel = vl)
        val sb = StringBuilder("sharpMin roundMax onMs trillMin voiceClear voiceLevel | S hit/false/noise | Ș hit/false/noise | R hit/false/noise | L hit/false/noise\n")
        for (rules in sets) {
            val hit = IntArray(4); val hitN = IntArray(4); val fa = IntArray(4); val faN = IntArray(4); val noise = IntArray(4)
            for ((c, pcm) in clips) {
                val best = lit(pcm, c.group, aims, rules)
                for (i in fam.indices) if (has(c.said, fam[i])) { hitN[i]++; if (best[i] > 0f) hit[i]++ } else { faN[i]++; if (best[i] > 0f) fa[i]++ }
            }
            for (pcm in negatives) { val best = lit(pcm, VoiceGroup.CHILD, aims, rules); for (i in fam.indices) if (best[i] > 0f) noise[i]++ }
            sb.append("${rules.sharpMin} ${rules.roundMax} ${rules.onMs.toInt()} ${rules.trillMin} ${rules.voiceClear} ${rules.voiceLevel} |")
            for (i in fam.indices) sb.append(" ${100 * hit[i] / hitN[i].coerceAtLeast(1)}%/${100 * fa[i] / faN[i].coerceAtLeast(1)}%/${noise[i]} |")
            sb.append("\n")
        }
        File(out, "live_grid.txt").writeText(sb.toString())
    }

    /** Flatness and low-band share of the hiss frames: sibilant clips against the noise clips. */
    @Test
    fun hissShape() {
        assumeTrue(File(out, "live_eval.on").exists())
        val sb = StringBuilder()
        fun line(name: String, pcm: FloatArray, group: VoiceGroup) {
            val analyzer = LiveAnalyzer(48000.0, group, null, tech.fonia.dsp.DSPThresholds.v1)
            val f: LiveFrame = LiveFrame.silent
            val fl = ArrayList<Float>(); val lf = ArrayList<Float>(); val sn = ArrayList<Float>()
            var at = 0
            while (at + 480 <= pcm.size) {
                analyzer.process(pcm, at, 480, f); at += 480
                if (f.fricativePresent) { fl += f.hissFlatness; lf += f.hissLfShare; sn += f.sNess }
            }
            fun q(l: List<Float>, p: Float) = if (l.isEmpty()) Float.NaN else l.sorted()[((l.size - 1) * p).toInt()]
            sb.append("%s|%d|%.2f|%.2f|%.2f|%.3f|%.3f|%.3f|%.2f".format(name, sn.size, q(fl, .1f), q(fl, .5f), q(fl, .9f), q(lf, .1f), q(lf, .5f), q(lf, .9f), q(sn, .5f))).append(System.lineSeparator())
        }
        val seen = HashSet<String>()
        File(out, "manifest.jsonl").forEachLine { l ->
            val file = field(l, "file")
            if (field(l, "expected") != "correct" || !seen.add(file)) return@forEachLine
            val said = field(l, "said")
            if (said.length > 4) return@forEachLine
            val group = when (field(l, "group")) { "man" -> VoiceGroup.MALE; "child" -> VoiceGroup.CHILD; else -> VoiceGroup.FEMALE }
            line("clip:" + said, decode(File(file)) ?: return@forEachLine, group)
        }
        for (f in File(out, "negatives").listFiles()!!.sortedBy { it.name }) line("noise:" + f.name, decode(f) ?: continue, VoiceGroup.CHILD)
        for (f in File(out, "probe").listFiles()!!.sortedBy { it.name }) line("held:" + f.name, decode(f) ?: continue, VoiceGroup.MALE)
        File(out, "live_shape.txt").writeText(sb.toString())
    }
}
