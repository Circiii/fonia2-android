package tech.fonia.judge

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import tech.fonia.dsp.VoiceGroup
import tech.fonia.dsp.alignFricatives
import tech.fonia.dsp.analyzeClip
import tech.fonia.dsp.decodeWAV
import tech.fonia.dsp.expectedFricatives
import tech.fonia.live.Clip
import tech.fonia.model.SoundId
import tech.fonia.model.Verdict
import tech.fonia.phones.Posteriors
import tech.fonia.phones.TargetSound
import tech.fonia.phones.ZipaModel
import tech.fonia.phones.ZipaPhoneScorer
import java.util.IdentityHashMap
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * OPT-IN evaluation on local audio that is never committed (synthetic adult voices). Skipped unless
 * `-Pfonia.judge.audio=<dir with sarah_s.wav …>` and/or `-Pfonia.judge.voice=<content/voice>` are given. Writes
 * `build/judge-eval/report.md` and `cases.csv`. Adult and synthetic voices only prove the chain works, not accuracy
 * on children.
 */
class JudgeEvalTest {
    private val spec = loadSpec()
    private val shipped = TakeJudge(spec)
    private val detector = TakeJudge(spec, unlock = UnlockView.everythingUnlocked())

    /**
     * One judged take: what it should be, what the detector said (all kinds unlocked), what the shipped policy said;
     * `detector`/`shipped` are the fast head (v0), `full`/`fullShipped` the same take with ZIPA (v1, "-" without it).
     */
    private data class Case(
        val source: String, val group: String, val text: String, val target: SoundId, val level: Level, val position: Position,
        val expected: String, val detector: String, val shipped: String, val top: String, val pCorrect: Float, val measureMs: Double,
        val note: String = "",
        val features: String = "",
        val voice: String = "",
        val full: String = "-",
        val fullShipped: String = "-",
        val fullHead: String = "-",
        val fullTop: String = "-",
        val zipa: String = "",
    )

    /** What the full-head grid search needs again: the measurement and the phone evidence of each take. */
    private class FitCase(val voice: String, val expected: String, val measured: Measured, val phones: PhoneEvidence?)

    private val cases = ArrayList<Case>()
    private val fitCases = ArrayList<FitCase>()

    // ---------------------------------------------------------------------------------------------------- the full head

    /** ZIPA, opt-in (-Pfonia.judge.zipa=true): the model from the Gradle cache, as :engine:phones' tests find it. */
    private val zipaModel: ZipaModel? by lazy {
        if (System.getProperty("fonia.judge.zipa") != "true") return@lazy null
        val file = File(System.getProperty("fonia.models") ?: return@lazy null, "zipa-small-crctc-500k/model.int8.onnx")
        if (!file.isFile) return@lazy null
        ZipaModel.load(file, intraOpThreads = 2)
    }
    private val zipa: ZipaPhoneScorer? by lazy { zipaModel?.let { ZipaPhoneScorer(it) } }
    private val posteriorCache = IdentityHashMap<FloatArray, Posteriors>()

    /** Full-head time per unique audio on this PC: resample to 16 kHz + pad + fbank + network, then scoring, ms. */
    private val modelMs = ArrayList<Double>()
    private val scoringMs = ArrayList<Double>()

    private fun phones(pcm: FloatArray, rate: Double, request: JudgeRequest): PhoneEvidence? {
        val scorer = zipa ?: return null
        val model = zipaModel ?: return null
        val target = TargetSound.fromRawValue(request.sound.raw) ?: return null
        val pron = ZipaPhoneScorer.pronunciation(request, target) ?: return null
        val posteriors = posteriorCache.getOrPut(pcm) {
            val t0 = System.nanoTime()
            val p = model.posteriors(ZipaPhoneScorer.pad(ZipaPhoneScorer.to16k(Clip(pcm, rate))))
            modelMs += (System.nanoTime() - t0) / 1e6
            p
        }
        val t1 = System.nanoTime()
        val e = scorer.evidence(posteriors, pron, request, target)
        scoringMs += (System.nanoTime() - t1) / 1e6
        return e
    }

    private val shown = listOf(
        Features.PRESENT, Features.TOWARD, Features.S_NESS, Features.FRIC_DUR, Features.ONSET_FRIC, Features.BURST, Features.GAP,
        Features.RISE, Features.ABOVE_FLOOR, Features.REL_LEVEL, Features.VOICING, Features.VOICED_FRAC, Features.R_TAP, Features.L_TAP, Features.DIP_DB, Features.DIP_MS,
    )

    private fun label(r: TakeResult): String = when (val v = r.verdict) {
        Verdict.Correct -> "correct"
        Verdict.Almost -> "almost"
        Verdict.Unsure -> "unsure"
        Verdict.Retake -> "retake:${r.retakeReason?.raw}"
        is Verdict.NamedError -> v.kind.raw + (v.heardAs?.let { "(${it.raw})" } ?: "")
    }

    private fun voiceOf(source: String): String = when {
        source.startsWith("content") -> "content"
        source.startsWith("eleven:") -> source.removePrefix("eleven:").substringBefore("_").substringBefore("/")
        else -> source
    }

    private fun run(
        source: String, group: String, talker: TalkerGroup, pcm: FloatArray, rate: Double, text: String, target: SoundId,
        level: Level, position: Position, expected: String, note: String = "",
    ): TakeResult {
        val request = JudgeRequest(target, level, text, position, talker)
        val clip = Clip(pcm, rate, audioSource = "eval")
        val t0 = System.nanoTime()
        val measured = detector.measure(request, clip)
        val ms = (System.nanoTime() - t0) / 1e6
        val d = detector.decide(measured)
        val s = shipped.decide(measured)
        val ph = if (measured.gate == null) phones(pcm, rate, request) else null
        val f = ph?.let { detector.decide(measured, it) }
        val fs = ph?.let { shipped.decide(measured, it) }
        val voice = voiceOf(source)
        fitCases += FitCase(voice, expected, measured, ph)
        cases.add(
            Case(source, group, text, target, level, position, expected, label(d), label(s),
                d.topError?.let { "${it.raw}:${"%.2f".format(d.topErrorProbability)}" } ?: "-", d.probabilities["correct"] ?: Float.NaN, ms, note,
                shown.joinToString(" ") { k -> "$k=${d.evidence.features[k]?.let { v -> "%.2f".format(v) } ?: "-"}" } +
                    " snr=${"%.1f".format(d.evidence.capture?.bandSnrDb ?: Float.NaN)}",
                voice = voice,
                full = f?.let { label(it) } ?: label(d),
                fullShipped = fs?.let { label(it) } ?: label(s),
                fullHead = f?.head?.raw ?: d.head.raw,
                fullTop = f?.topError?.let { "${it.raw}:${"%.2f".format(f.topErrorProbability)}" } ?: "-",
                zipa = ph?.let { e ->
                    "fit=${"%.2f".format(e.fitPerLabel ?: Float.NaN)} gop=${"%.2f".format(e.gop ?: Float.NaN)} rel=${e.reliable} " +
                        e.notes.joinToString(" ") + " " + e.lpr.entries.joinToString(" ") { (k, v) -> "$k=${"%.1f".format(v)}" }
                } ?: "",
            ),
        )
        return d
    }

    // ---------------------------------------------------------------------------------------------------- audio

    /**
     * The ElevenLabs files run the words together with almost no pause, so silence does not split them. Each word of
     * the S and Ș lists has a sibilant: align the whole text to the take, take each word's sibilants as anchors, and cut
     * at the quietest 10 ms (smoothed over 50 ms) between the last anchor of a word and the first of the next.
     */
    private fun splitByAnchors(pcm: FloatArray, rate: Double, words: List<String>, voice: VoiceGroup): List<FloatArray>? {
        val summary = analyzeClip(pcm, rate, voice, null)
        val matches = alignFricatives(words.joinToString(" "), summary)
        val counts = words.map { expectedFricatives(it).size }
        if (counts.any { it == 0 } || counts.sum() != matches.size) return null
        val spans = ArrayList<Pair<Double, Double>>()
        var k = 0
        for (c in counts) {
            val found = matches.subList(k, k + c).mapNotNull { it.detected }
            k += c
            if (found.isEmpty()) return null
            spans.add(found.first().start to found.last().end)
        }
        val hop = (0.01 * rate).toInt()
        val db = FloatArray(pcm.size / hop) { i ->
            var e = 0.0
            for (j in i * hop until (i + 1) * hop) e += pcm[j].toDouble() * pcm[j]
            (10 * log10(e / hop + 1e-12)).toFloat()
        }
        val smooth = FloatArray(db.size) { i -> (maxOf(0, i - 2)..minOf(db.size - 1, i + 2)).map { db[it] }.average().toFloat() }
        val cuts = ArrayList<Int>()
        for (w in 0 until spans.size - 1) {
            val a = (spans[w].second / 0.01).toInt() + 1
            val b = (spans[w + 1].first / 0.01).toInt() - 1
            if (b <= a) return null
            cuts.add((a..b).minBy { smooth[it] })
        }
        val edges = listOf(0) + cuts + listOf(db.size)
        return (0 until words.size).map { pcm.copyOfRange(edges[it] * hop, minOf(pcm.size, edges[it + 1] * hop)) }
    }

    private fun ffmpegDecode(file: File): Pair<FloatArray, Double>? {
        return try {
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
            // Pad with low noise so a gate sees silence around the word, as a real take has.
            val pad = quietAt(0.25, rate)
            Pair(catArrays(pad, pcm, pad), rate)
        } catch (_: Exception) {
            null
        }
    }

    private fun quietAt(seconds: Double, rate: Double, amp: Float = 0.0003f): FloatArray {
        val g = Lcg(5)
        return FloatArray((seconds * rate).toInt()) { (g.unit() * 2 - 1) * amp }
    }

    private fun catArrays(vararg parts: FloatArray): FloatArray {
        val out = FloatArray(parts.sumOf { it.size })
        var o = 0
        for (p in parts) { p.copyInto(out, o); o += p.size }
        return out
    }

    // ---------------------------------------------------------------------------------------------------- text helpers

    private fun position(word: String, grapheme: String): Position {
        val w = romanian(word)
        val i = w.indexOf(grapheme)
        return when {
            i <= 0 -> Position.Initial
            i + grapheme.length >= w.length -> Position.Final
            else -> Position.Medial
        }
    }

    private val graphemes = mapOf(
        SoundId.S to listOf("s"), SoundId.Sh to listOf("ș"), SoundId.Z to listOf("z"), SoundId.J to listOf("j"),
        SoundId.Ts to listOf("ț"), SoundId.Ce to listOf("ce", "ci"), SoundId.Ge to listOf("ge", "gi"),
        SoundId.R to listOf("r"), SoundId.L to listOf("l"),
    )

    /** Letters that would put a second sibilant/affricate of another kind in the word and confuse the alignment. */
    private val rivals = mapOf(
        SoundId.S to listOf("ș", "z", "ț", "j", "x", "ce", "ci", "ge", "gi"),
        SoundId.Sh to listOf("s", "z", "ț", "j", "ce", "ci", "ge", "gi"),
        SoundId.Z to listOf("s", "ș", "ț", "j", "ce", "ci", "ge", "gi"),
        SoundId.J to listOf("s", "ș", "ț", "z", "ce", "ci", "ge", "gi"),
        SoundId.Ts to listOf("s", "ș", "z", "j", "ce", "ci", "ge", "gi"),
        SoundId.Ce to listOf("s", "ș", "z", "j", "ț", "ge", "gi"),
        SoundId.Ge to listOf("s", "ș", "z", "j", "ț", "ce", "ci"),
        SoundId.R to listOf("l"),
        SoundId.L to listOf("r"),
    )

    private val syllable = Regex("^[aeiouăâî]{0,2}(s|ș|z|j|ț|r|l|c|g)[aeiouăâî]{0,3}$")

    private fun levelOf(word: String): Level = when {
        word.length >= 4 && word.toSet().size == 1 -> Level.Isolated
        word.length <= 4 && syllable.matches(word) -> Level.Syllable
        else -> Level.Word
    }

    private fun soundsOf(word: String): List<SoundId> {
        val w = romanian(word)
        return SoundId.entries.filter { s ->
            graphemes.getValue(s).any { w.contains(it) } && rivals.getValue(s).none { w.contains(it) }
        }
    }

    private fun swap(word: String, from: String, to: String): String {
        val w = romanian(word)
        val i = w.indexOf(from)
        return if (i < 0) w else w.substring(0, i) + to + w.substring(i + from.length)
    }

    // ---------------------------------------------------------------------------------------------------- manipulations

    private fun rmsOf(x: FloatArray, a: Int, b: Int): Float {
        var e = 0.0
        for (i in a until b) e += x[i].toDouble() * x[i]
        return if (b > a) sqrt(e / (b - a)).toFloat() else 0f
    }

    private fun omit(pcm: FloatArray, rate: Double, seg: Pair<Double, Double>): FloatArray {
        val a = ((seg.first - 0.005) * rate).toInt().coerceIn(0, pcm.size)
        val b = ((seg.second + 0.005) * rate).toInt().coerceIn(a, pcm.size)
        val out = catArrays(pcm.copyOfRange(0, a), pcm.copyOfRange(b, pcm.size))
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
            val g = if (edge >= ramp) gain else 1f - (1f - gain) * edge / ramp
            out[i] *= g
        }
        return out
    }

    /** S → T-like: the frication is replaced by a closure (except word-initially), a 4 ms burst and 15 ms of aspiration. */
    private fun stop(pcm: FloatArray, rate: Double, seg: Pair<Double, Double>, initial: Boolean): FloatArray {
        val a = (seg.first * rate).toInt().coerceIn(0, pcm.size)
        val b = (seg.second * rate).toInt().coerceIn(a, pcm.size)
        val level = rmsOf(pcm, a, b)
        val g = Lcg(9)
        val closure = if (initial) FloatArray(0) else quietAt(maxOf(0.03, (b - a) / rate - 0.03), rate)
        val burstN = (0.004 * rate).toInt()
        val burst = FloatArray(burstN) { i -> (g.unit() * 2 - 1) * level * 3f * exp(-i / (0.0015 * rate)).toFloat() }
        val aspN = minOf((0.015 * rate).toInt(), b - a)
        val asp = FloatArray(aspN) { i -> pcm[b - aspN + i] * 0.5f * (1f - i.toFloat() / aspN * 0.5f) }
        return catArrays(pcm.copyOfRange(0, a), closure, burst, asp, pcm.copyOfRange(b, pcm.size))
    }

    // ---------------------------------------------------------------------------------------------------- the run

    private fun evaluateWord(source: String, group: String, talker: TalkerGroup, pcm: FloatArray, rate: Double, word: String, deep: Boolean) {
        val w = romanian(word)
        val level = levelOf(w)
        for (sound in soundsOf(w)) {
            val g = graphemes.getValue(sound).first { w.contains(it) }
            val pos = if (level == Level.Isolated) Position.Any else position(w, g)
            val r = run(source, group, talker, pcm, rate, w, sound, level, pos, "correct")
            if (!deep) continue
            val seg = r.evidence.segments.firstOrNull()
            // Cross-tests: the same audio judged against a neighbouring target, with the text the child was asked to say.
            when (sound) {
                SoundId.S -> {
                    run(source, group, talker, pcm, rate, swap(w, "s", "ș"), SoundId.Sh, level, pos, "placeSwap(s)")
                    run(source, group, talker, pcm, rate, swap(w, "s", "z"), SoundId.Z, level, pos, if (pos == Position.Final) "n/a:final" else "devoicing(s)")
                    run(source, group, talker, pcm, rate, swap(w, "s", "ț"), SoundId.Ts, level, pos, "deaffrication(s)")
                }
                SoundId.Sh -> {
                    run(source, group, talker, pcm, rate, swap(w, "ș", "s"), SoundId.S, level, pos, "placeSwap(ș)")
                    run(source, group, talker, pcm, rate, swap(w, "ș", "j"), SoundId.J, level, pos, if (pos == Position.Final) "n/a:final" else "devoicing(ș)")
                }
                SoundId.L -> if (level != Level.Isolated) run(source, group, talker, pcm, rate, swap(w, "l", "r"), SoundId.R, level, pos, "rToL(l)")
                else -> Unit
            }
            // Manipulated takes of S and Ș words.
            if ((sound == SoundId.S || sound == SoundId.Sh) && seg != null && level != Level.Isolated) {
                run(source, group, talker, omit(pcm, rate, seg), rate, w, sound, level, pos, "omission", "cut")
                run(source, group, talker, weaken(pcm, rate, seg), rate, w, sound, level, pos, "weakOrShort", "-24 dB")
                run(source, group, talker, stop(pcm, rate, seg, pos == Position.Initial), rate, w, sound, level, pos, "stopping", "burst")
            }
        }
    }

    private fun retakes(source: String, group: String, talker: TalkerGroup, pcm: FloatArray, rate: Double, word: String) {
        val w = romanian(word)
        val sound = soundsOf(w).firstOrNull() ?: return
        val pos = position(w, graphemes.getValue(sound).first { w.contains(it) })
        val n = pcm.size
        val g = Lcg(21)
        run(source, group, talker, quietAt(1.2, rate), rate, w, sound, Level.Word, pos, "retake", "silence")
        run(source, group, talker, FloatArray((1.2 * rate).toInt()) { (g.unit() * 2 - 1) * 0.3f }, rate, w, sound, Level.Word, pos, "retake", "white noise")
        run(source, group, talker, FloatArray(n) { (pcm[it] * 30f).coerceIn(-1f, 1f) }, rate, w, sound, Level.Word, pos, "retake", "clipped")
        run(source, group, talker, FloatArray(n) { pcm[it] * 0.003f }, rate, w, sound, Level.Word, pos, "retake", "very quiet")
        // Truncated: only the first 80 ms of speech survive.
        val env = FloatArray(n / 480) { i -> rmsOf(pcm, i * 480, (i + 1) * 480) }
        val start = env.indexOfFirst { it > env.max() * 0.02f }.coerceAtLeast(0) * 480
        val cut = FloatArray(n) { if (it < start + (0.08 * rate).toInt()) pcm[it] else 0f }
        for (i in cut.indices) cut[i] += (g.unit() * 2 - 1) * 0.0003f
        run(source, group, talker, cut, rate, w, sound, Level.Word, pos, "retake|weakOrShort", "truncated 80 ms")
        // Background noise at about 0 dB SNR in 4–8 kHz.
        val level = rmsOf(pcm, 0, n)
        run(source, group, talker, FloatArray(n) { pcm[it] + (g.unit() * 2 - 1) * level * 1.2f }, rate, w, sound, Level.Word, pos, "retake|unsure", "noise ~0 dB")
        // The same adult voice, judged as a child's take: no adult-voice gate, only the priors move.
        run(source, group, TalkerGroup.Child, pcm, rate, w, sound, Level.Word, pos, "info:childGroup", "talker=child")
    }

    @Test
    fun evaluate() {
        val audioDir = System.getProperty("fonia.judge.audio")?.let { File(it) }?.takeIf { it.isDirectory }
        val voiceDir = System.getProperty("fonia.judge.voice")?.let { File(it) }?.takeIf { File(it, "manifest.json").isFile }
        assumeTrue(audioDir != null || voiceDir != null, "opt-in: pass -Pfonia.judge.audio=<dir> and/or -Pfonia.judge.voice=<content/voice>")
        val notes = ArrayList<String>()

        if (audioDir != null) {
            val lists = mapOf(
                "s" to "Sare Soare Sanie Masă Pisică Pas Nas",
                "sh" to "Șarpe Șoarece Șapcă Mașină Ușă Coș Naș",
            )
            notes.add("sarah_rest.wav not used: its R and L words have no sibilant anchor to split the run-together take on; R, L, Z, J, Ț, CE and GE are evaluated on the prompt voices")
            for (voice in listOf("sarah", "roger")) {
                val group = if (voice == "roger") "man" else "woman"
                val talker = if (voice == "roger") TalkerGroup.Man else TalkerGroup.Woman
                for ((key, list) in lists) {
                    val file = File(audioDir, "${voice}_$key.wav")
                    if (!file.isFile) continue
                    val wav = decodeWAV(file.readBytes())
                    val words = list.split(" ")
                    val parts = splitByAnchors(wav.pcm, wav.sampleRate, words, talker.voice)
                    if (parts == null) {
                        notes.add("${file.name}: the sibilant anchors did not line up with ${words.size} words, skipped")
                        continue
                    }
                    for ((k, w) in words.withIndex()) {
                        val pcm = catArrays(quietAt(0.15, wav.sampleRate), parts[k], quietAt(0.15, wav.sampleRate))
                        evaluateWord("eleven:${file.name}", group, talker, pcm, wav.sampleRate, w, deep = true)
                    }
                    if (key == "s") {
                        val first = catArrays(quietAt(0.15, wav.sampleRate), parts[0], quietAt(0.15, wav.sampleRate))
                        retakes("eleven:${file.name}", group, talker, first, wav.sampleRate, words[0])
                    }
                }
            }
        }

        // One word per file (ElevenLabs): <audio>/words_v1/<voice>/<word>.mp3. Real words are judged as
        // themselves (and cross-tested, as above); the "error" words stand for a substitution the child makes.
        val wordsDir = audioDir?.let { File(it, "words_v1") }?.takeIf { it.isDirectory }
        if (wordsDir != null) {
            var decoded = 0
            for (voiceFolder in wordsDir.listFiles()!!.filter { it.isDirectory }.sortedBy { it.name }) {
                val voice = voiceFolder.name
                val (group, talker) = if (voice == "roger") "man" to TalkerGroup.Man else "woman" to TalkerGroup.Woman
                for (file in voiceFolder.listFiles()!!.filter { it.extension == "mp3" }.sortedBy { it.name }) {
                    val spoken = romanian(file.nameWithoutExtension)
                    val (pcm, rate) = ffmpegDecode(file) ?: continue
                    decoded++
                    val source = "eleven:$voice/words_v1"
                    if (spoken in realWords) evaluateWord(source, group, talker, pcm, rate, spoken, deep = true)
                    for ((targetText, sound, expected) in substitutions[spoken].orEmpty()) {
                        val g = graphemes.getValue(sound).first { romanian(targetText).contains(it) }
                        run(source, group, talker, pcm, rate, targetText, sound, Level.Word, position(targetText, g), expected, "said $spoken")
                    }
                }
            }
            notes.add("words_v1: $decoded one-word ElevenLabs clips (Sarah, Roger, Lily; MP3 44.1 kHz), real words judged as themselves plus ${substitutions.values.sumOf { it.size }} substitution roles per voice")
        }

        if (voiceDir != null) {
            val manifest = JudgeSpec.json.parseToJsonElement(File(voiceDir, "manifest.json").readText()).let { it as kotlinx.serialization.json.JsonObject }
            val words = manifest.keys.filter { it.startsWith("ro|ro|") }.map { it.substring(6) }
                .filter { Regex("[a-zăâîșț]+").matches(it) }
            var decoded = 0
            for (w in words) {
                if (soundsOf(w).isEmpty()) continue
                val rel = manifest[("ro|ro|$w")].toString().trim('"')
                val (pcm, rate) = ffmpegDecode(File(voiceDir, rel)) ?: continue
                decoded++
                evaluateWord("content", "woman", TalkerGroup.Woman, pcm, rate, w, deep = true)
            }
            notes.add("content/voice: $decoded single-word or syllable clips decoded with ffmpeg (MP3, 44.1 kHz)")
        }
        writeReport(notes)
    }

    /** Words of words_v1 that are real Romanian words, judged as themselves. */
    private val realWords = setOf(
        "lac", "rac", "mare", "male", "cal", "car", "pară", "pală", "nor", "tren", "ramă", "lună", "lapte", "ger", "cer", "zer",
        "fugi", "geam", "pace", "ceai", "arici", "țară", "ceară", "ață", "colț", "zare", "sare", "dare", "buză", "orez", "joc",
        "șoc", "ajutor", "garaj", "sară", "tară",
    )

    /** Spoken word → (the word the child was asked to say, its target, the expected kind(heardAs)). The L→R, S→Z, S→Ț and Ș→J cross-tests of real words come from evaluateWord. */
    private val substitutions: Map<String, List<Triple<String, SoundId, String>>> = mapOf(
        "iac" to listOf(Triple("rac", SoundId.R, "rToGlide"), Triple("lac", SoundId.L, "lToGlide")),
        "maie" to listOf(Triple("mare", SoundId.R, "rToGlide"), Triple("male", SoundId.L, "lToGlide")),
        "cai" to listOf(Triple("car", SoundId.R, "rToGlide"), Triple("cal", SoundId.L, "lToGlide")),
        "ac" to listOf(Triple("rac", SoundId.R, "omission"), Triple("lac", SoundId.L, "omission")),
        "rac" to listOf(Triple("lac", SoundId.L, "lToR(r)")),
        "mare" to listOf(Triple("male", SoundId.L, "lToR(r)")),
        "car" to listOf(Triple("cal", SoundId.L, "lToR(r)")),
        "pară" to listOf(Triple("pală", SoundId.L, "lToR(r)")),
        "cer" to listOf(Triple("ger", SoundId.Ge, "devoicing(ce)")),
        "zer" to listOf(Triple("ger", SoundId.Ge, "affricatePlace(z)")),
        "jer" to listOf(Triple("ger", SoundId.Ge, "deaffrication(j)")),
        "fuji" to listOf(Triple("fugi", SoundId.Ge, "deaffrication(j)")),
        "fuci" to listOf(Triple("fugi", SoundId.Ge, "devoicing(ce)")),
        "ter" to listOf(Triple("cer", SoundId.Ce, "stopping")),
        "șer" to listOf(Triple("cer", SoundId.Ce, "deaffrication(ș)")),
        "țer" to listOf(Triple("cer", SoundId.Ce, "affricatePlace(ț)")),
        "tară" to listOf(Triple("țară", SoundId.Ts, "stopping")),
        "ceară" to listOf(Triple("țară", SoundId.Ts, "affricatePlace(ce)")),
        "asă" to listOf(Triple("ață", SoundId.Ts, "deaffrication(s)")),
        "ată" to listOf(Triple("ață", SoundId.Ts, "stopping")),
        "jare" to listOf(Triple("zare", SoundId.Z, "placeSwap(j)")),
        "dare" to listOf(Triple("zare", SoundId.Z, "stopping")),
        "busă" to listOf(Triple("buză", SoundId.Z, "devoicing(s)")),
        "zoc" to listOf(Triple("joc", SoundId.J, "placeSwap(z)")),
        "doc" to listOf(Triple("joc", SoundId.J, "stopping")),
    )

    // ---------------------------------------------------------------------------------------------------- report

    private fun writeReport(notes: List<String>) {
        val dir = File(System.getProperty("fonia.judge.build") ?: "build", "judge-eval").also { it.mkdirs() }
        File(dir, "cases.csv").printWriter().use { out ->
            out.println("source,voice,group,text,target,level,position,expected,detector,shipped,top,full,fullShipped,fullHead,fullTop,pCorrect,measureMs,note,features,zipa")
            for (c in cases) {
                out.println(listOf(c.source, c.voice, c.group, c.text, c.target.raw, c.level.raw, c.position.raw, c.expected, c.detector, c.shipped, c.top,
                    c.full, c.fullShipped, c.fullHead, c.fullTop,
                    "%.3f".format(c.pCorrect), "%.1f".format(c.measureMs), c.note, c.features, c.zipa).joinToString(","))
            }
        }
        val lines = ArrayList<String>()
        lines += "# Judge evaluation (generated by JudgeEvalTest)"
        lines += ""
        notes.forEach { lines += "- $it" }
        lines += "- ${cases.size} judged takes. Detector = every kind unlocked; shipped = spec/unlock.json (wave 1 in trial, nothing unlocked)."
        lines += ""
        fun bucket(label: String) = when {
            label.startsWith("retake") -> "retake"
            label == "correct" || label == "almost" || label == "unsure" -> label
            else -> "named:" + label.substringBefore("(")
        }
        val outcomes = listOf("correct", "almost", "unsure", "retake")
        for (sourceKind in listOf("eleven", "content")) {
            val sub = cases.filter { it.source.startsWith(sourceKind) }
            if (sub.isEmpty()) continue
            lines += "## ${if (sourceKind == "eleven") "ElevenLabs words (tmp/test_audio, 48 kHz WAV)" else "Prompt voices (content/voice, 44.1 kHz MP3)"}"
            lines += ""
            val named = sub.map { bucket(it.detector) }.filter { it.startsWith("named:") }.distinct().sorted()
            val cols = outcomes + named
            lines += "Detector view, by expected label (rows) and verdict (columns):"
            lines += ""
            lines += "| expected | n | " + cols.joinToString(" | ") + " |"
            lines += "|---|---|" + cols.joinToString("") { "---|" }
            for ((exp, group) in sub.groupBy { it.expected }.toSortedMap()) {
                val counts = cols.map { col -> group.count { bucket(it.detector) == col } }
                lines += "| $exp | ${group.size} | " + counts.joinToString(" | ") + " |"
            }
            lines += ""
            lines += "By target sound, correct takes only (detector view):"
            lines += ""
            lines += "| target | n | correct | almost | unsure | retake | named (false alarm) |"
            lines += "|---|---|---|---|---|---|---|"
            for ((t, group) in sub.filter { it.expected == "correct" }.groupBy { it.target }.toSortedMap()) {
                val b = group.map { bucket(it.detector) }
                lines += "| ${t.raw} | ${group.size} | ${b.count { it == "correct" }} | ${b.count { it == "almost" }} | ${b.count { it == "unsure" }} | " +
                    "${b.count { it == "retake" }} | ${b.count { it.startsWith("named:") }} |"
            }
            lines += ""
            val ok = sub.filter { it.expected == "correct" }
            val err = sub.filter { !it.expected.startsWith("retake") && it.expected != "correct" && !it.expected.startsWith("n/a") && !it.expected.startsWith("info") }
            fun pct(a: Int, b: Int) = if (b == 0) "-" else "${100 * a / b} % ($a/$b)"
            lines += "Summary (detector view):"
            lines += ""
            lines += "- correct takes accepted as Correct: ${pct(ok.count { it.detector == "correct" }, ok.size)}; Almost ${pct(ok.count { it.detector == "almost" }, ok.size)}"
            lines += "- false alarm (a named error on a correct take): ${pct(ok.count { bucket(it.detector).startsWith("named:") }, ok.size)}"
            lines += "- abstention on correct takes (Unsure): ${pct(ok.count { it.detector == "unsure" }, ok.size)}; Retake ${pct(ok.count { it.detector.startsWith("retake") }, ok.size)}"
            lines += "- error takes: right kind named ${pct(err.count { bucket(it.detector) == "named:" + it.expected.substringBefore("(") }, err.size)}, " +
                "wrong kind named ${pct(err.count { bucket(it.detector).startsWith("named:") && bucket(it.detector) != "named:" + it.expected.substringBefore("(") }, err.size)}, " +
                "praised as Correct ${pct(err.count { it.detector == "correct" }, err.size)}, abstained (Unsure/Almost) ${pct(err.count { it.detector == "unsure" || it.detector == "almost" }, err.size)}"
            lines += "- error takes whose most likely error class is the right one (named or not): ${pct(err.count { it.top.substringBefore(":") == it.expected.substringBefore("(") }, err.size)}"
            val heard = err.filter { it.expected.contains("(") && bucket(it.detector) == "named:" + it.expected.substringBefore("(") }
            lines += "- of those named right, heardAs right: ${pct(heard.count { it.detector.substringAfter("(", "").trimEnd(')') == it.expected.substringAfter("(").trimEnd(')') }, heard.size)}"
            val shippedNamed = sub.count { bucket(it.shipped).startsWith("named:") }
            lines += "- shipped policy: named errors shown to the child ${shippedNamed} (wave 1 is in trial); verdict changed from the detector on ${sub.count { it.detector != it.shipped }} takes"
            val ms = sub.map { it.measureMs }.sorted()
            lines += "- P0–P3 time per take on this PC: median ${"%.0f".format(ms[ms.size / 2])} ms, p95 ${"%.0f".format(ms[(ms.size * 95) / 100])} ms"
            lines += ""
            lines += "Retake and capture cases:"
            lines += ""
            for (c in sub.filter { it.expected.startsWith("retake") || it.expected.startsWith("info") }) {
                lines += "- ${c.group} ${c.text} [${c.note}]: expected ${c.expected}, got ${c.detector}"
            }
            lines += ""
        }
        if (zipa != null) fullHeadReport(lines)
        File(dir, "report.md").writeText(lines.joinToString("\n") + "\n")
        println(lines.joinToString("\n"))
    }

    // ---------------------------------------------------------------------------------------------------- v0 / v1

    private fun scored(expected: String) = !expected.startsWith("retake") && !expected.startsWith("n/a") && !expected.startsWith("info")

    private fun kindOf(label: String) = label.substringBefore("(")

    private fun named(l: String) = l != "correct" && l != "almost" && l != "unsure" && !l.startsWith("retake")

    /** Per-take cost of the grid search: false alarms and wrong praise weigh most, abstaining least. */
    private fun cost(expected: String, label: String): Double =
        if (expected == "correct") when {
            label == "correct" -> 0.0
            label == "almost" -> 0.6
            named(label) -> 6.0
            else -> 1.0
        } else when {
            named(label) && kindOf(label) == kindOf(expected) -> 0.0
            named(label) -> 3.0
            label == "correct" -> 5.0
            else -> 1.0
        }

    private fun judgeWith(params: FullHeadParams): TakeJudge {
        val fusion = spec.fusion.mapValues { (_, f) -> if (f.phoneMap.isEmpty()) f else f.copy(heads = f.heads + ("full" to FullHead.build(f, params))) }
        return TakeJudge(JudgeSpec(spec.population, fusion, spec.unlock, spec.feedback), unlock = UnlockView.everythingUnlocked())
    }

    private class Row(val voice: String, val target: SoundId, val expected: String, val label: String)

    private fun summary(title: String, rows: List<Row>): List<String> {
        val out = ArrayList<String>()
        fun pct(a: Int, b: Int) = if (b == 0) "-" else "%.0f %%".format(100.0 * a / b)
        out += "**$title**"
        out += ""
        out += "| target | correct takes | accepted | almost | unsure | retake | false alarm | error takes | named right | named wrong | praised | abstained |"
        out += "|---|---|---|---|---|---|---|---|---|---|---|---|"
        val groups: List<Pair<String, List<Row>>> = rows.groupBy { it.target }.toSortedMap().map { it.key.raw to it.value } + listOf("**all**" to rows)
        for ((t, g) in groups) {
            val ok = g.filter { it.expected == "correct" }
            val err = g.filter { it.expected != "correct" }
            val acc = ok.count { it.label == "correct" }
            val fa = ok.count { named(it.label) }
            val right = err.count { named(it.label) && kindOf(it.label) == kindOf(it.expected) }
            val praised = err.count { it.label == "correct" }
            out += "| $t | ${ok.size} | $acc (${pct(acc, ok.size)}) | ${ok.count { it.label == "almost" }} | ${ok.count { it.label == "unsure" }} | " +
                "${ok.count { it.label.startsWith("retake") }} | $fa (${pct(fa, ok.size)}) | ${err.size} | $right (${pct(right, err.size)}) | " +
                "${err.count { named(it.label) && kindOf(it.label) != kindOf(it.expected) }} | $praised (${pct(praised, err.size)}) | " +
                "${err.count { it.label == "almost" || it.label == "unsure" }} |"
        }
        out += ""
        return out
    }

    private fun fullHeadReport(lines: MutableList<String>) {
        val usable = fitCases.filter { scored(it.expected) }
        val withPhones = usable.filter { it.phones != null }
        lines += "## v0 (fast head) against v1 (full head, ZIPA), detector view, every scored take"
        lines += ""
        lines += "- ${usable.size} scored takes, ${withPhones.size} with phone evidence (${withPhones.count { it.phones!!.reliable }} reliable); " +
            "the rest stopped at a capture or word gate (both heads alike)."
        val fm = modelMs.sorted()
        val sm = scoringMs.sorted()
        if (fm.isNotEmpty() && sm.isNotEmpty()) {
            lines += "- Full-head time on this PC, per unique clip (resample to 16 kHz + pad + fbank + ZIPA int8, 2 threads): median " +
                "${"%.0f".format(fm[fm.size / 2])} ms, p95 ${"%.0f".format(fm[(fm.size * 95) / 100])} ms, max ${"%.0f".format(fm.last())} ms (${fm.size} clips); " +
                "GOP + alignment per request: median ${"%.2f".format(sm[sm.size / 2])} ms, p95 ${"%.2f".format(sm[(sm.size * 95) / 100])} ms."
        }
        lines += ""
        val shippedParams = spec.fusion.values.first().fullFrom ?: FullHeadParams(0.5f, 0.75f, 0.25f, 1f)
        val fast = usable.map { Row(it.voice, it.measured.request.sound, it.expected, label(detector.decide(it.measured))) }
        val shippedJudge = judgeWith(shippedParams)
        val full = usable.map { Row(it.voice, it.measured.request.sound, it.expected, label(shippedJudge.decide(it.measured, it.phones))) }
        lines += summary("v0: fast head (no phoneme model)", fast)
        lines += summary("v1: full head with the shipped constants $shippedParams (in-sample: chosen on these takes)", full)
        // Grid search with leave-one-voice-out.
        val grid = ArrayList<FullHeadParams>()
        for (a in listOf(0.25f, 0.5f, 0.75f, 1f)) for (c in listOf(0.5f, 0.75f, 1f, 1.5f, 2f)) for (g in listOf(0f, 0.25f, 0.5f)) for (b in listOf(-2f, -1.5f, -1f, -0.5f, 0f, 1f)) {
            grid += FullHeadParams(a, c, g, b)
        }
        val labels = grid.associateWith { p -> val j = judgeWith(p); withPhones.map { label(j.decide(it.measured, it.phones)) } }
        val voices = withPhones.map { it.voice }.distinct().sorted()
        // Each voice weighs the same (the prompt voice has twice the takes of the three others together).
        fun meanCost(p: FullHeadParams, keep: (FitCase) -> Boolean): Double {
            val l = labels.getValue(p)
            val sum = HashMap<String, Double>()
            val n = HashMap<String, Int>()
            for ((i, fc) in withPhones.withIndex()) if (keep(fc)) {
                sum[fc.voice] = (sum[fc.voice] ?: 0.0) + cost(fc.expected, l[i])
                n[fc.voice] = (n[fc.voice] ?: 0) + 1
            }
            return if (n.isEmpty()) Double.MAX_VALUE else sum.keys.sumOf { sum.getValue(it) / n.getValue(it) } / n.size
        }
        val lovo = arrayOfNulls<String>(withPhones.size)
        lines += "**Leave-one-voice-out** (grid of ${grid.size}: alpha × c × g × correctBias; cost per take: false alarm 6, praised error 5, wrong kind 3, " +
            "abstained 1, Almost on a correct take 0.6; mean over voices, each voice weighing the same):"
        lines += ""
        for (v in voices) {
            val best = grid.minBy { meanCost(it) { fc -> fc.voice != v } }
            val l = labels.getValue(best)
            for ((i, fc) in withPhones.withIndex()) if (fc.voice == v) lovo[i] = l[i]
            lines += "- held out $v: chosen on the others $best, held-out cost ${"%.3f".format(meanCost(best) { fc -> fc.voice == v })}"
        }
        val all = grid.minBy { meanCost(it) { true } }
        val shippedCost = grid.firstOrNull { it == shippedParams }?.let { "%.3f".format(meanCost(it) { true }) } ?: "(off the grid)"
        lines += "- all voices: $all, cost ${"%.3f".format(meanCost(all) { true })}; the shipped constants cost $shippedCost"
        lines += ""
        val lovoRows = withPhones.mapIndexed { i, fc -> Row(fc.voice, fc.measured.request.sound, fc.expected, lovo[i]!!) } +
            usable.filter { it.phones == null }.map { Row(it.voice, it.measured.request.sound, it.expected, label(detector.decide(it.measured))) }
        lines += summary("v1 held out: each voice judged with the constants chosen on the other voices", lovoRows)
        for (v in voices) {
            lines += summary("by voice, v0 → $v", fast.filter { it.voice == v })
            lines += summary("by voice, v1 held out → $v", lovoRows.filter { it.voice == v })
        }
    }
}
