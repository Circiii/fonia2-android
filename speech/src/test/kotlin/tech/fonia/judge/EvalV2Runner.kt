package tech.fonia.judge

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.exp
import kotlin.math.sqrt
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import tech.fonia.live.Clip
import tech.fonia.model.SoundId
import tech.fonia.model.Verdict
import tech.fonia.phones.Posteriors
import tech.fonia.phones.TargetSound
import tech.fonia.phones.ZipaModel
import tech.fonia.phones.ZipaPhoneScorer

/**
 * OPT-IN evaluation v2 (`tools/eval/`): judges every line of `tools/eval/out/manifest.jsonl` (built by
 * `tools/eval/build_manifest.py` from local, never-committed audio) with the real judge, fast and full head, and writes
 * `tools/eval/out/<tag>/cases.csv` (+ `fit.md` with the leave-one-voice-out fits, + `gates.md` with the opt-in
 * affricate and word-final L gates, [EvalGates]). `tools/eval/summarize.py` makes the
 * tables. Skipped unless the environment variable FONIA_EVAL_MANIFEST names the manifest:
 *
 * ```
 * FONIA_EVAL_MANIFEST=tools/eval/out/manifest.jsonl FONIA_EVAL_TAG=v2 \
 *   ./gradlew :engine:judge:test --rerun --tests tech.fonia.judge.EvalV2Runner -Pfonia.judge.zipa=true
 * ```
 *
 * FONIA_EVAL_TALKER: `dsp` (the app's rule, the DSP tracker's median F0), `estimator` (TalkerEstimator),
 * default `estimator` when the class exists. Every voice is synthetic: nothing here is evidence about children.
 */
class EvalV2Runner {
    private val manifestFile = System.getenv("FONIA_EVAL_MANIFEST")?.let { File(it) }
    private val tag = System.getenv("FONIA_EVAL_TAG") ?: "run"
    private val talkerMethod = System.getenv("FONIA_EVAL_TALKER") ?: "estimator"

    private val spec = loadSpec()
    private val detector = TakeJudge(spec, unlock = UnlockView.everythingUnlocked())
    private val shipped = TakeJudge(spec)

    private val zipaModel: ZipaModel? by lazy {
        if (System.getProperty("fonia.judge.zipa") != "true") return@lazy null
        val file = File(System.getProperty("fonia.models") ?: return@lazy null, "zipa-small-crctc-500k/model.int8.onnx")
        if (!file.isFile) return@lazy null
        ZipaModel.load(file, intraOpThreads = 2)
    }
    private val zipa: ZipaPhoneScorer? by lazy { zipaModel?.let { ZipaPhoneScorer(it) } }
    private val posteriorCache = HashMap<String, Posteriors>()

    private fun phones(key: String, pcm: FloatArray, rate: Double, request: JudgeRequest): PhoneEvidence? {
        val scorer = zipa ?: return null
        val model = zipaModel ?: return null
        val target = TargetSound.fromRawValue(request.sound.raw) ?: return null
        val pron = ZipaPhoneScorer.pronunciation(request, target) ?: return null
        val posteriors = posteriorCache.getOrPut(key) { model.posteriors(ZipaPhoneScorer.pad(ZipaPhoneScorer.to16k(Clip(pcm, rate)))) }
        return scorer.evidence(posteriors, pron, request, target)
    }

    /** One judged take, kept for the fits: the measurement and the phone evidence. */
    class Item(
        val id: String,
        val row: JsonObject,
        val voice: String,
        val group: String,
        val expected: String,
        val role: String,
        val context: String,
        val measured: Measured,
        val phones: PhoneEvidence?,
        val pcm: FloatArray,
        val rate: Double,
        val talkerF0: Float,
    ) {
        val sound: SoundId get() = measured.request.sound
    }

    val items = ArrayList<Item>()

    private fun str(o: JsonObject, k: String) = o[k]?.jsonPrimitive?.content ?: ""

    private fun ffmpegDecode(file: File): Pair<FloatArray, Double>? = try {
        val probe = ProcessBuilder("ffprobe", "-v", "error", "-show_entries", "stream=sample_rate", "-of", "csv=p=0", file.absolutePath)
            .redirectErrorStream(true).start()
        val rate = probe.inputStream.bufferedReader().readText().trim().lines().first().trim().toDouble()
        probe.waitFor()
        val p = ProcessBuilder("ffmpeg", "-v", "error", "-i", file.absolutePath, "-f", "f32le", "-ac", "1", "-").start()
        val out = ByteArrayOutputStream()
        p.inputStream.copyTo(out)
        p.waitFor()
        val buf = ByteBuffer.wrap(out.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val pcm = FloatArray(buf.remaining()).also { buf.get(it) }
        val pad = quiet(0.25, rate)
        Pair(cat(pad, pcm, pad), rate)
    } catch (_: Exception) {
        null
    }

    private fun quiet(seconds: Double, rate: Double, amp: Float = 0.0003f): FloatArray {
        val g = Lcg(5)
        return FloatArray((seconds * rate).toInt()) { (g.unit() * 2 - 1) * amp }
    }

    private fun cat(vararg parts: FloatArray): FloatArray {
        val out = FloatArray(parts.sumOf { it.size })
        var o = 0
        for (p in parts) {
            p.copyInto(out, o)
            o += p.size
        }
        return out
    }

    private fun rms(x: FloatArray, a: Int, b: Int): Float {
        var e = 0.0
        for (i in a until b) e += x[i].toDouble() * x[i]
        return if (b > a) sqrt(e / (b - a)).toFloat() else 0f
    }

    private fun omit(pcm: FloatArray, rate: Double, seg: Pair<Double, Double>): FloatArray {
        val a = ((seg.first - 0.005) * rate).toInt().coerceIn(0, pcm.size)
        val b = ((seg.second + 0.005) * rate).toInt().coerceIn(a, pcm.size)
        val out = cat(pcm.copyOfRange(0, a), pcm.copyOfRange(b, pcm.size))
        val fade = (0.012 * rate).toInt()
        for (k in 0 until fade) {
            if (a - fade + k in out.indices) out[a - fade + k] *= 1f - k.toFloat() / fade
            if (a + k in out.indices) out[a + k] *= k.toFloat() / fade
        }
        return out
    }

    private fun weaken(pcm: FloatArray, rate: Double, seg: Pair<Double, Double>, gain: Float = 0.06f): FloatArray {
        val out = pcm.copyOf()
        val a = ((seg.first - 0.01) * rate).toInt().coerceIn(0, pcm.size)
        val b = ((seg.second + 0.01) * rate).toInt().coerceIn(a, pcm.size)
        val ramp = (0.005 * rate).toInt()
        for (i in a until b) {
            val edge = minOf(i - a, b - 1 - i).coerceAtLeast(0)
            out[i] *= if (edge >= ramp) gain else 1f - (1f - gain) * edge / ramp
        }
        return out
    }

    private fun stop(pcm: FloatArray, rate: Double, seg: Pair<Double, Double>, initial: Boolean): FloatArray {
        val a = (seg.first * rate).toInt().coerceIn(0, pcm.size)
        val b = (seg.second * rate).toInt().coerceIn(a, pcm.size)
        val level = rms(pcm, a, b)
        val g = Lcg(9)
        val closure = if (initial) FloatArray(0) else quiet(maxOf(0.03, (b - a) / rate - 0.03), rate)
        val burstN = (0.004 * rate).toInt()
        val burst = FloatArray(burstN) { i -> (g.unit() * 2 - 1) * level * 3f * exp(-i / (0.0015 * rate)).toFloat() }
        val aspN = minOf((0.015 * rate).toInt(), b - a)
        val asp = FloatArray(aspN) { i -> pcm[b - aspN + i] * 0.5f * (1f - i.toFloat() / aspN * 0.5f) }
        return cat(pcm.copyOfRange(0, a), closure, burst, asp, pcm.copyOfRange(b, pcm.size))
    }

    private fun label(r: TakeResult): String = when (val v = r.verdict) {
        Verdict.Correct -> "correct"
        Verdict.Almost -> "almost"
        Verdict.Unsure -> "unsure"
        Verdict.Retake -> "retake:${r.retakeReason?.raw}"
        is Verdict.NamedError -> v.kind.raw + (v.heardAs?.let { "(${it.raw})" } ?: "")
    }

    /** The talker group of a clip by the method under test; the tracker of each voice keeps the session's memory. */
    private val trackers = HashMap<String, TalkerTracker>()

    private fun talkerF0(clip: Clip, measuredF0: Float): Float = when (talkerMethod) {
        "dsp" -> measuredF0
        else -> TalkerEstimator.estimate(clip, measuredF0).f0Hz
    }

    @Test
    fun evaluate() {
        val manifest = manifestFile?.takeIf { it.isFile }
        assumeTrue(manifest != null, "opt-in: set FONIA_EVAL_MANIFEST to tools/eval/out/manifest.jsonl")
        val out = File(manifest!!.parentFile, tag).also { it.mkdirs() }
        val rows = manifest.readLines().filter { it.isNotBlank() }.map { JudgeSpec.json.parseToJsonElement(it) as JsonObject }
        val decoded = HashMap<String, Pair<FloatArray, Double>?>()
        val clipGroup = HashMap<String, Pair<TalkerGroup, Float>>()
        val t0 = System.nanoTime()
        for (row in rows) {
            val file = str(row, "file")
            val (pcm, rate) = decoded.getOrPut(file) { ffmpegDecode(File(file)) } ?: continue
            val voice = str(row, "voice")
            val sound = SoundId.of(str(row, "sound")) ?: continue
            val level = Level.entries.first { it.raw == str(row, "level") }
            val position = Position.entries.first { it.raw == str(row, "position") }
            val clip = Clip(pcm, rate, audioSource = "eval")
            val (group, f0) = clipGroup.getOrPut(file) {
                val tracker = trackers.getOrPut(voice) { TalkerTracker(TalkerGroup.Child) }
                val probe = detector.measure(JudgeRequest(sound, level, str(row, "text"), position, tracker.group), clip)
                val raw = probe.global[Features.F0] ?: Float.NaN
                val f = if (probe.gate == null) talkerF0(clip, raw) else Float.NaN
                tracker.observe(f) to f
            }
            val request = JudgeRequest(sound, level, str(row, "text"), position, group)
            val measured = detector.measure(request, clip)
            val ph = if (measured.gate == null) phones(file, pcm, rate, request) else null
            val id = str(row, "id")
            items += Item(
                id, row, voice, str(row, "group"), str(row, "expected"), str(row, "role"), str(row, "context"),
                measured, ph, pcm, rate, f0,
            )
            // Runner-made edits of S/Ș words: cut, −24 dB, a burst for the frication.
            if (row["edits"]?.jsonPrimitive?.booleanOrNull == true && str(row, "expected") == "correct") {
                val seg = detector.decide(measured).evidence.segments.firstOrNull() ?: continue
                val edits = listOf(
                    "omission" to omit(pcm, rate, seg),
                    "weakOrShort" to weaken(pcm, rate, seg),
                    "stopping" to stop(pcm, rate, seg, position == Position.Initial),
                )
                for ((kind, x) in edits) {
                    val m = detector.measure(request, Clip(x, rate, audioSource = "eval"))
                    val p = if (m.gate == null) phones("$file#$kind", x, rate, request) else null
                    items += Item("$id.$kind", row, voice, str(row, "group"), kind, "edit", str(row, "context"), m, p, x, rate, f0)
                }
            }
        }
        val seconds = (System.nanoTime() - t0) / 1e9
        writeCases(File(out, "cases.csv"))
        val fit = ArrayList<String>()
        fit += "# Fits on ${items.size} judged takes (${decoded.size} clips, ${"%.0f".format(seconds)} s, talker method $talkerMethod)"
        fit += ""
        fit += "Every voice is synthetic. Leave-one-voice-out: each voice judged with what was chosen on the other voices."
        fit += ""
        EvalV2Fits(this).run(fit, out)
        File(out, "fit.md").writeText(fit.joinToString("\n") + "\n")
        EvalGates(this).run(out)
    }

    fun labelOf(judge: TakeJudge, item: Item, full: Boolean): String =
        label(judge.decide(item.measured, if (full) item.phones else null))

    private fun writeCases(file: File) {
        val header = listOf(
            "id", "voice", "group", "sound", "text", "said", "level", "position", "context", "role", "expected",
            "talker", "talkerF0", "dspF0", "fast", "full", "head", "fastShipped", "fullShipped", "pFast", "pFull", "topFast", "topFull",
            "sNess", "toward", "m1Hz", "fricDurMs", "voicing", "rTap", "dipDb", "lprTop", "zipaReliable", "notes", "centroidHz", "peakHz",
            "zipaFit", "zipaGop", "relLevelDb", "gapMs", "burstDb", "lpr", "stopOnset", "onsetAttackDb", "onsetSpikeDb", "voiceLeadMs",
            "riseMs", "wordFinal",
        )
        val lines = ArrayList<String>()
        lines += header.joinToString(",")
        fun f(v: Float?) = if (v == null || v.isNaN()) "" else String.format(Locale.ROOT, "%.3f", v)
        for (it in items) {
            val m = it.measured
            val d = detector.decide(m)
            val full = detector.decide(m, it.phones)
            val s = shipped.decide(m)
            val sf = shipped.decide(m, it.phones)
            val feats = full.evidence.features
            val lprTop = it.phones?.lpr?.entries?.minByOrNull { e -> e.value }?.let { e -> "${e.key}:${"%.1f".format(Locale.ROOT, e.value)}" } ?: ""
            lines += listOf(
                it.id, it.voice, it.group, it.sound.raw, m.request.text, str(it.row, "said"), m.request.level.raw,
                m.request.position.raw, it.context, it.role, it.expected, m.request.talker.raw, f(it.talkerF0), f(m.global[Features.F0]),
                label(d), label(full), full.head.raw, label(s), label(sf), f(d.probabilities["correct"]), f(full.probabilities["correct"]),
                d.topError?.raw ?: "", full.topError?.raw ?: "",
                f(feats[Features.S_NESS]), f(feats[Features.TOWARD]), f(feats[Features.M1]), f(feats[Features.FRIC_DUR]), f(feats[Features.VOICING]),
                f(feats[Features.R_TAP]), f(feats[Features.DIP_DB]), lprTop, (it.phones?.reliable ?: "").toString(),
                full.evidence.notes.joinToString(" "), f(feats[Features.CENTROID]), f(feats[Features.PEAK]),
                f(it.phones?.fitPerLabel), f(it.phones?.gop), f(feats[Features.REL_LEVEL]), f(feats[Features.GAP_RAW]), f(feats[Features.BURST]),
                it.phones?.lpr?.entries?.joinToString(" ") { e -> "${e.key}=${"%.1f".format(Locale.ROOT, e.value)}" } ?: "",
                f(feats[Features.STOP_ONSET]), f(feats[Features.ONSET_ATTACK]), f(feats[Features.ONSET_SPIKE]), f(feats[Features.VOICE_LEAD]),
                f(feats[Features.RISE]), it.phones?.wordFinal?.toString() ?: f(feats[Features.WORD_FINAL]),
            ).joinToString(",") { v -> if (v.contains(',') || v.contains('"')) "\"" + v.replace("\"", "\"\"") + "\"" else v }
        }
        file.writeText(lines.joinToString("\n") + "\n")
    }
}
