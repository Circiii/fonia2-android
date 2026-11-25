package tech.fonia.judge

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import tech.fonia.live.Clip
import tech.fonia.phones.LiveSpotter
import tech.fonia.phones.TargetSound
import tech.fonia.phones.ZipaModel
import tech.fonia.phones.ZipaPhoneScorer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Measures the live sound spotter ([LiveSpotter]: the phoneme model on the last second of audio, five times a second)
 * on the evaluation clips: a clip that contains the sound must light it, a clip without it and a noise must not.
 * Runs with `-Pfonia.judge.zipa=true` when `tools/eval/out/live_eval.on` exists; writes `tools/eval/out/live_spot.md`.
 */
class LiveSpotEval {
    private val root = generateSequence(File(".").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
    private val out = File(root, "tools/eval/out")

    private fun decode(file: File): FloatArray? = try {
        val p = ProcessBuilder("ffmpeg", "-v", "error", "-i", file.absolutePath, "-f", "f32le", "-ac", "1", "-ar", "16000", "-").start()
        val bytes = p.inputStream.readBytes()
        p.waitFor()
        val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        FloatArray(fb.remaining()).also { fb.get(it) }
    } catch (e: Exception) { null }

    private fun field(line: String, key: String): String =
        Regex("\"$key\": \"((?:[^\"\\\\]|\\\\.)*)\"").find(line)?.groupValues?.get(1)?.replace("\\\\", "\\")
            ?.replace(Regex("\\\\u([0-9a-fA-F]{4})")) { it.groupValues[1].toInt(16).toChar().toString() } ?: ""

    private fun has(said: String, s: TargetSound): Boolean {
        val t = said.lowercase()
        return when (s) {
            TargetSound.S -> 's' in t || 'x' in t
            TargetSound.Sh -> 'ș' in t
            TargetSound.Z -> 'z' in t
            TargetSound.J -> 'j' in t
            TargetSound.Ts -> 'ț' in t
            TargetSound.Ce -> "ce" in t || "ci" in t
            TargetSound.Ge -> "ge" in t || "gi" in t
            TargetSound.R -> 'r' in t
            TargetSound.L -> 'l' in t
        }
    }

    /** Peak live score of every sound over one clip, heard as the app hears it: the last second, every 200 ms. */
    private fun peaks(model: ZipaModel, pcm: FloatArray): FloatArray {
        val best = FloatArray(TargetSound.entries.size)
        val window = 16000
        var end = 3200
        while (true) {
            val to = minOf(end, pcm.size)
            val from = maxOf(0, to - window)
            val post = model.posteriors(ZipaPhoneScorer.pad(pcm.copyOfRange(from, to)))
            for (s in TargetSound.entries) {
                val v = LiveSpotter.score(post, s, lastFrames = LiveSpotter.RECENT_FRAMES + LiveSpotter.PAD_FRAMES)
                if (v > best[s.ordinal]) best[s.ordinal] = v
            }
            if (to == pcm.size) break
            end += 3200
        }
        return best
    }

    @Test
    fun measure() {
        assumeTrue(File(out, "live_eval.on").exists() && System.getProperty("fonia.judge.zipa") == "true")
        val file = File(System.getProperty("fonia.models") ?: return, "zipa-small-crctc-500k/model.int8.onnx")
        assumeTrue(file.isFile)
        val model = ZipaModel.load(file, intraOpThreads = 4)
        val seen = HashSet<String>()
        val clips = ArrayList<Pair<String, FloatArray>>()
        File(out, "manifest.jsonl").forEachLine { line ->
            val f = field(line, "file")
            if (field(line, "expected") != "correct" || !seen.add(f)) return@forEachLine
            val pcm = decode(File(f)) ?: return@forEachLine
            clips += field(line, "said") to peaks(model, pcm)
        }
        val noises = (File(out, "negatives").listFiles { f -> f.extension in setOf("wav", "mp3") }?.sortedBy { it.name } ?: emptyList())
            .mapNotNull { f -> decode(f)?.let { f.name to peaks(model, it) } }
        val sb = StringBuilder("# Detectorul live cu modelul de foneme (${clips.size} clipuri, ${noises.size} zgomote)\n\n")
        sb.append("Pentru fiecare prag: aprins când sunetul e în clip / aprins când nu e / zgomote aprinse.\n\n| sunet |")
        val taus = listOf(0.2f, 0.35f, 0.5f, 0.65f, 0.8f)
        for (t in taus) sb.append(" τ=$t |")
        sb.append("\n|---|" + "---|".repeat(taus.size) + "\n")
        for (s in TargetSound.entries) {
            sb.append("| ${s.rawValue} |")
            for (t in taus) {
                var hit = 0; var hitN = 0; var fa = 0; var faN = 0
                for ((said, p) in clips) if (has(said, s)) { hitN++; if (p[s.ordinal] >= t) hit++ } else { faN++; if (p[s.ordinal] >= t) fa++ }
                val nz = noises.count { it.second[s.ordinal] >= t }
                sb.append(" ${100 * hit / hitN.coerceAtLeast(1)}% / ${100 * fa / faN.coerceAtLeast(1)}% / $nz |")
            }
            sb.append("\n")
        }
        sb.append("\n## La pragul livrat\n\n")
        for (s in TargetSound.entries) {
            val tau = LiveSpotter.tau(s)
            val with = clips.filter { has(it.first, s) }
            val without = clips.filter { !has(it.first, s) }
            val wrong = without.filter { it.second[s.ordinal] >= tau }
            val missed = with.filter { it.second[s.ordinal] < tau }
            val nz = noises.filter { it.second[s.ordinal] >= tau }
            sb.append("- **${s.rawValue}** (prag $tau): aprins ${with.size - missed.size}/${with.size}, fals ${wrong.size}/${without.size}, zgomote ${nz.size}/${noises.size}. False: ${wrong.take(14).joinToString(" ") { it.first }} | ratate: ${missed.take(14).joinToString(" ") { it.first }}\n")
        }
        File(out, "live_spot.md").writeText(sb.toString())
        println(sb)
    }

    /** What the model writes for the short clips (greedy), to choose the token sets from. */
    @Test
    fun greedy() {
        assumeTrue(File(out, "live_eval.on").exists() && System.getProperty("fonia.judge.zipa") == "true")
        val file = File(System.getProperty("fonia.models") ?: return, "zipa-small-crctc-500k/model.int8.onnx")
        val model = ZipaModel.load(file, intraOpThreads = 4)
        val seen = HashSet<String>()
        val sb = StringBuilder()
        File(out, "manifest.jsonl").forEachLine { line ->
            val f = field(line, "file")
            if (field(line, "expected") != "correct" || !seen.add(f)) return@forEachLine
            val said = field(line, "said")
            if (said.length > 4) return@forEachLine
            val pcm = decode(File(f)) ?: return@forEachLine
            sb.append(said).append(" [").append(field(line, "voice")).append("] ").append(model.posteriors(ZipaPhoneScorer.pad(pcm)).greedy().joinToString("")).append(System.lineSeparator())
        }
        File(out, "live_greedy.txt").writeText(sb.toString())
    }

    /** Per-window scores on the clips of `tools/eval/out/probe/` (held sounds): recent frames against the whole window. */
    @Test
    fun probe() {
        assumeTrue(File(out, "live_eval.on").exists() && System.getProperty("fonia.judge.zipa") == "true")
        val file = File(System.getProperty("fonia.models") ?: return, "zipa-small-crctc-500k/model.int8.onnx")
        val model = ZipaModel.load(file, intraOpThreads = 4)
        val sb = StringBuilder()
        for (f in File(out, "probe").listFiles()!!.sortedBy { it.name }) {
            val pcm = decode(f) ?: continue
            sb.append(f.name).append(System.lineSeparator())
            var end = 3200
            while (end <= pcm.size) {
                val from = maxOf(0, end - 16000)
                val post = model.posteriors(ZipaPhoneScorer.pad(pcm.copyOfRange(from, end)))
                sb.append("  t=%.1f greedy=%s".format(end / 16000.0, post.greedy().joinToString("")))
                for (s in TargetSound.entries) sb.append(" %s=%.2f/%.2f".format(s.rawValue, LiveSpotter.score(post, s), LiveSpotter.score(post, s, lastFrames = 200)))
                sb.append(System.lineSeparator())
                end += 3200
            }
        }
        File(out, "live_probe.txt").writeText(sb.toString())
    }
}
