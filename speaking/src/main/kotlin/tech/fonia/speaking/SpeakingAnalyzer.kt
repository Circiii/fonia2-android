package tech.fonia.speaking

import tech.fonia.dsp.TimeSpan
import tech.fonia.dsp.VoiceGroup
import tech.fonia.dsp.analyzeEmphasis
import tech.fonia.live.Clip
import tech.fonia.speech.Transcript
import tech.fonia.speech.TranscriptWord
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Everything known about one take: the flagged spans, the pauses, the prosodic transcript and the frames. */
class SpeakingAnalysis(
    val durationSeconds: Double,
    val spans: List<FlaggedSpan>,
    val pauses: List<PauseSpan>,
    val transcript: ProsodicTranscript,
    /** Recognised words after their edges were tightened to the sound (empty without a transcript). */
    val words: List<TranscriptWord>,
    /** Per word, the guard (ms) no cut may enter: wider when the recogniser was unsure, narrower on a measured edge. */
    val wordGuardMs: List<Double>,
    val frames: SpeechFrames,
    val acoustic: List<AcousticHesitation>,
    val thresholdsVersion: String,
) {
    val usedTranscript: Boolean get() = transcript.hasTranscript

    /** The spans cut by default. */
    val defaultCuts: Set<Int> get() = spans.filter { it.cut }.map { it.id }.toSet()

    fun span(id: Int): FlaggedSpan? = spans.firstOrNull { it.id == id }

    /** "3 umpluturi · 1 pornire falsă · 1 pauză de 2,1 s" (plan 01 §10, NO-UM step 3). */
    fun headline(cuts: Set<Int> = defaultCuts): String = SpeakingText.headline(this, cuts)
}

/**
 * Turns a take (and, when Whisper ran, its transcript) into a [SpeakingAnalysis]. Pure and synchronous; call it off
 * the main thread. Works without a transcript: hesitations come from [HesitationDetector] and the transcript is made
 * of speech chunks; with one, [LexicalDetector] adds markers, repetitions and false starts, and the two sources are
 * reconciled (an acoustic hesitation fixes the recogniser's timing, and a recognised word fixes the detector's).
 */
class SpeakingAnalyzer(
    val thresholds: SpeakingThresholds = SpeakingThresholds.v1,
    private val group: VoiceGroup = VoiceGroup.FEMALE,
) {
    fun analyze(clip: Clip, transcript: Transcript? = null): SpeakingAnalysis =
        analyze(clip.samples, clip.sampleRate, transcript)

    fun analyze(samples: FloatArray, sampleRate: Double, transcript: Transcript? = null): SpeakingAnalysis {
        val t = thresholds
        val frames = SpeechFrames.compute(samples, sampleRate, t, group)
        val acoustic = HesitationDetector.detect(frames, t)
        val raw = transcript?.words.orEmpty().filter { LexicalDetector.normalize(it.word).isNotEmpty() }
        val drafts = ArrayList<Draft>()
        val refined = HashSet<Int>()
        val words: List<TranscriptWord>
        if (raw.isEmpty()) {
            words = emptyList()
            for (h in acoustic) drafts.add(Draft.of(h))
        } else {
            val tight = raw.map { tighten(it, frames) }.toMutableList()
            val hits = LexicalDetector.detect(tight, t)
            val claimed = HashSet<Int>()
            for (hit in hits) for (k in hit.first..hit.last) claimed.add(k)
            val usedAcoustic = BooleanArray(acoustic.size)

            // 1. Recognised hesitation tokens take the acoustic bounds when one is near (the recogniser's are rough).
            for (hit in hits) {
                if (hit.kind != SpanKind.HESITATION) continue
                val w = tight[hit.first]
                val match = acoustic.indices.filter { !usedAcoustic[it] }.minByOrNull { distance(acoustic[it], w.start, w.end) }
                if (match != null && distance(acoustic[match], w.start, w.end) <= 0.3) {
                    usedAcoustic[match] = true
                    val h = acoustic[match]
                    drafts.add(Draft(SpanKind.HESITATION, h.start, h.end, w.word.trim(), min(0.97, max(h.confidence, hit.confidence) + 0.05), SpanSource.BOTH, listOf(hit.first), "token+sound"))
                } else {
                    drafts.add(Draft(SpanKind.HESITATION, w.start, w.end, w.word.trim(), t.lexicon.unconfirmedHesitationConfidence, SpanSource.TRANSCRIPT, listOf(hit.first), "token, no sound"))
                }
            }
            // 2. Acoustic hesitations the recogniser cleaned up, swallowed into a word, or misheard as a word.
            for ((ai, h) in acoustic.withIndex()) {
                if (usedAcoustic[ai]) continue
                var keep = true
                val absorbed = ArrayList<Int>()
                for ((wi, w) in tight.withIndex()) {
                    val overlap = min(w.end, h.end) - max(w.start, h.start)
                    if (overlap <= 0) continue
                    if (wi in claimed) continue
                    val wordShare = overlap / max(1e-3, w.end - w.start)
                    val hesShare = overlap / max(1e-3, h.end - h.start)
                    val letters = LexicalDetector.normalize(w.word).length
                    val swallowed = (w.end - w.start) - letters * t.cut.secondsPerLetter >= t.cut.swallowShare * overlap
                    when {
                        // A short, doubtful word sitting on the hesitation is the hesitation, misheard ("îs" for "îîî").
                        w.prob < 0.6 && wordShare >= 0.6 && !h.lengthening -> absorbed.add(wi)
                        // The word's end ran over the hesitation: give the word its own end back.
                        swallowed && h.start - w.start >= t.cut.wordTrimMinSeconds && w.end <= h.end + 0.05 ->
                            tight[wi] = w.copy(end = h.start)
                        // The word's start was pulled back over the hesitation.
                        swallowed && w.end - h.end >= t.cut.wordTrimMinSeconds && w.start >= h.start - 0.05 ->
                            tight[wi] = w.copy(start = h.end)
                        // The steady sound is inside a well-recognised word: a held vowel, not a filler.
                        hesShare > 0.3 -> keep = false
                    }
                }
                if (!keep) continue
                val kind = if (h.lengthening) SpanKind.LENGTHENING else SpanKind.HESITATION
                val conf = if (absorbed.isNotEmpty()) min(0.95, h.confidence + 0.08) else h.confidence
                drafts.add(Draft(kind, h.start, h.end, h.label, conf, if (absorbed.isEmpty()) SpanSource.ACOUSTIC else SpanSource.BOTH, absorbed, if (absorbed.isEmpty()) "sound" else "sound+misheard"))
                for (k in absorbed) claimed.add(k)
            }
            // 2b. Any word (other than the span's own) whose edge runs over a sound-backed hesitation gives it back:
            // a small overlap is timing jitter; a large one only when the word is too long for its letters.
            for (d in drafts) {
                if (d.source == SpanSource.TRANSCRIPT) continue
                for ((wi, w) in tight.withIndex()) {
                    if (wi in d.words || wi in claimed) continue
                    val overlap = min(w.end, d.end) - max(w.start, d.start)
                    if (overlap <= 0) continue
                    val letters = LexicalDetector.normalize(w.word).length
                    val swallowed = overlap <= 0.1 || (w.end - w.start) - letters * t.cut.secondsPerLetter >= t.cut.swallowShare * overlap
                    if (!swallowed) continue
                    if (w.start < d.start && d.start - w.start >= t.cut.wordTrimMinSeconds) tight[wi] = w.copy(end = d.start)
                    else if (w.end > d.end && w.end - d.end >= t.cut.wordTrimMinSeconds) tight[wi] = w.copy(start = d.end)
                }
            }
            // 3. Markers, repetitions, false starts: the (tightened) word bounds.
            for (hit in hits) {
                if (hit.kind == SpanKind.HESITATION) continue
                drafts.add(Draft(hit.kind, tight[hit.first].start, tight[hit.last].end, hit.label, hit.confidence, SpanSource.TRANSCRIPT, (hit.first..hit.last).toList(), hit.reason))
            }
            refineEdges(frames, tight, drafts, refined, t)
            words = tight
        }

        val spans = drafts.sortedBy { it.start }.mapIndexed { id, d ->
            FlaggedSpan(
                id = id, kind = d.kind, start = d.start, end = d.end, label = d.label, confidence = d.confidence,
                source = d.source, cut = d.confidence >= t.cut.cutConfidence, unsure = d.confidence < t.cut.sureConfidence,
                wordIndices = d.words, reason = d.reason,
            )
        }
        val pauses = findPauses(frames, spans, t)
        val prosodic = buildTranscript(frames, words, spans, pauses, t)
        val guards = words.mapIndexed { i, w ->
            when {
                w.prob < t.cut.uncertainProbability -> t.cut.guardUncertainMs
                i in refined -> t.cut.guardRefinedMs
                else -> t.cut.guardMs
            }
        }
        return SpeakingAnalysis(frames.durationSeconds, spans, pauses, prosodic, words, guards, frames, acoustic, t.version)
    }

    /**
     * Recognised word boundaries are ±50 ms. Next to a cut word (marker, repetition, false start), move the shared
     * boundary to the deepest level dip within `edgeSearchMs`, staying inside both words' middles, and update the
     * span and the neighbour; such a neighbour then gets the small `guardRefinedMs`.
     */
    private fun refineEdges(f: SpeechFrames, words: MutableList<TranscriptWord>, drafts: MutableList<Draft>, refined: MutableSet<Int>, t: SpeakingThresholds) {
        val reach = t.cut.edgeSearchMs / 1000
        val inward = t.cut.edgeInwardMs / 1000
        for ((di, d) in drafts.withIndex()) {
            if (d.source != SpanSource.TRANSCRIPT || d.words.isEmpty() || d.kind == SpanKind.HESITATION) continue
            var start = d.start
            var end = d.end
            val first = d.words.first()
            val last = d.words.last()
            if (first > 0 && drafts.none { first - 1 in it.words }) {
                val prev = words[first - 1]
                // Look mostly outwards: a dip inside the cut word (a closure in "prac-tic") would leave a fragment.
                val lo = max(prev.end - 0.03, start - reach)
                val hi = min(start + (end - start) / 2, start + inward)
                dip(f, lo, hi, peak(f, prev.start, prev.end, start, end), t.cut.edgeDipDb)?.let { v -> start = v; words[first - 1] = prev.copy(end = min(prev.end, v)); refined.add(first - 1) }
            }
            if (last + 1 < words.size && drafts.none { last + 1 in it.words }) {
                val next = words[last + 1]
                val lo = max(start + (end - start) / 2, end - inward)
                val hi = min(next.start + 0.03, end + reach)
                dip(f, lo, hi, peak(f, next.start, next.end, start, end), t.cut.edgeDipDb)?.let { v -> end = v; words[last + 1] = next.copy(start = max(next.start, v)); refined.add(last + 1) }
            }
            drafts[di] = Draft(d.kind, start, end, d.label, d.confidence, d.source, d.words, d.reason)
        }
    }

    /**
     * The quietest frame between [lo] and [hi] seconds, if it is at least [depth] dB below [peak]: a real gap between
     * two words, not the softer consonant inside one ("a-m").
     */
    private fun dip(f: SpeechFrames, lo: Double, hi: Double, peak: Float, depth: Double): Double? {
        if (hi - lo < f.hop) return null
        var best = -1
        for (k in f.index(lo)..f.index(hi)) if (best < 0 || f.db[k] < f.db[best]) best = k
        return if (best < 0 || f.db[best] > peak - depth) null else f.time(best)
    }

    /** The softer of the two words' peak levels. */
    private fun peak(f: SpeechFrames, a0: Double, a1: Double, b0: Double, b1: Double): Float {
        fun max(x: Double, y: Double): Float { var m = -200f; for (k in f.index(x)..f.index(y)) m = maxOf(m, f.db[k]); return m }
        return minOf(max(a0, a1), max(b0, b1))
    }

    private class Draft(
        val kind: SpanKind, val start: Double, val end: Double, val label: String, val confidence: Double,
        val source: SpanSource, val words: List<Int>, val reason: String,
    ) {
        companion object {
            fun of(h: AcousticHesitation) = Draft(
                if (h.lengthening) SpanKind.LENGTHENING else SpanKind.HESITATION, h.start, h.end, h.label, h.confidence,
                SpanSource.ACOUSTIC, emptyList(), "sound",
            )
        }
    }

    private fun distance(h: AcousticHesitation, start: Double, end: Double): Double =
        if (min(h.end, end) > max(h.start, start)) 0.0 else min(abs(h.start - end), abs(start - h.end))

    /** Moves a recognised word's edges off silence (the recogniser's timings spill into pauses). */
    private fun tighten(w: TranscriptWord, f: SpeechFrames): TranscriptWord {
        var a = f.index(w.start)
        var b = f.index(w.end) - 1
        val minFrames = (thresholds.cut.wordTrimMinSeconds / f.hop).toInt()
        while (a < b - minFrames && !f.speech[a]) a++
        while (b > a + minFrames && !f.speech[b]) b--
        val start = max(w.start, f.time(a))
        val end = min(w.end, f.time(b + 1))
        return if (end - start >= thresholds.cut.wordTrimMinSeconds) w.copy(start = start, end = end) else w
    }

    /** Silences (inactive frames, no hangover) of at least `minPauseSeconds`, edges included. */
    private fun findPauses(f: SpeechFrames, spans: List<FlaggedSpan>, t: SpeakingThresholds): List<PauseSpan> {
        val out = ArrayList<PauseSpan>()
        val firstSpeech = f.active.indexOfFirst { it }
        val lastSpeech = f.active.indexOfLast { it }
        if (firstSpeech < 0) return emptyList()
        var i = 0
        while (i < f.count) {
            if (f.active[i]) { i++; continue }
            var j = i
            while (j < f.count && !f.active[j]) j++
            val start = f.time(i)
            val end = min(f.durationSeconds, f.time(j))
            // Leading and trailing silence count too: the cleaned version should not start or end with a long wait.
            val edge = i < firstSpeech || j > lastSpeech
            val d = end - start
            val inside = spans.any { it.start <= start && it.end >= end }
            if (!inside && d >= t.pause.minPauseSeconds) {
                val long = if (edge) d > t.pause.shortenToSeconds else d > t.pause.longPauseSeconds
                out.add(PauseSpan(start, end, long))
            }
            i = j
        }
        return out
    }

    private fun buildTranscript(
        f: SpeechFrames, words: List<TranscriptWord>, spans: List<FlaggedSpan>, pauses: List<PauseSpan>, t: SpeakingThresholds,
    ): ProsodicTranscript {
        val p = t.prosody
        // Items: recognised words, plus the spans no recognised word stands for; or chunks without a transcript.
        data class Item(val text: String, val start: Double, val end: Double, val span: FlaggedSpan?, val fromTranscript: Boolean)
        val items = ArrayList<Item>()
        val spanOfWord = HashMap<Int, FlaggedSpan>()
        for (s in spans) for (w in s.wordIndices) spanOfWord[w] = s
        if (words.isNotEmpty()) {
            for ((i, w) in words.withIndex()) items.add(Item(w.word.trim(), w.start, w.end, spanOfWord[i], true))
            for (s in spans) if (s.wordIndices.isEmpty()) items.add(Item(s.label, s.start, s.end, s, false))
        } else {
            for (s in spans) items.add(Item(s.label, s.start, s.end, s, false))
            for (chunk in chunks(f, spans, p)) items.add(Item(dots(f, chunk.first, chunk.second), chunk.first, chunk.second, null, false))
        }
        items.sortBy { it.start }

        val speechDb = (0 until f.count).filter { f.speech[it] }.map { f.db[it] }.sorted().toFloatArray()
        val lo = SpeechFrames.percentile(speechDb, p.loudLowPercentile)
        val hi = max(lo + 1f, SpeechFrames.percentile(speechDb, p.loudHighPercentile))
        val voicedSt = (0 until f.count).filter { f.speech[it] && f.hz[it] > 0f }.map { st(f.hz[it]) }.sorted()
        val medianSt = if (voicedSt.isEmpty()) 0.0 else voicedSt[voicedSt.size / 2]
        val syllables = f.summary.syllables

        val emphasis = runCatching {
            analyzeEmphasis(f.summary, items.map { TimeSpan(it.start, it.end) }).words.map { it.isStressed }
        }.getOrElse { List(items.size) { false } }

        val out = ArrayList<ProsodicWord>()
        var prevEnd = 0.0
        for ((i, it) in items.withIndex()) {
            val a = f.index(it.start)
            val b = max(a + 1, f.index(it.end))
            var peak = -200f
            val sts = ArrayList<Double>()
            val ts = ArrayList<Double>()
            for (k in a until min(b, f.count)) {
                // Loudness: the word's peak frame level (frames are 32 ms, so a click barely moves it).
                if (f.db[k] > peak) peak = f.db[k]
                if (f.hz[k] > 0f) { sts.add(st(f.hz[k]) - medianSt); ts.add(f.time(k)) }
            }
            val loud = ((peak - lo) / (hi - lo)).coerceIn(0f, 1f)
            var movement = PitchMovement.UNVOICED
            var moveSt = 0f
            var level: Float? = null
            if (sts.size >= 3) {
                val keep = sts.indices.filter { abs(sts[it] - sts.sorted()[sts.size / 2]) < 6.0 }
                if (keep.size >= 3) {
                    val fit = HesitationDetector.linearFit(keep.map { ts[it] }, keep.map { sts[it] })
                    level = keep.map { sts[it] }.sorted()[keep.size / 2].toFloat()
                    moveSt = (fit.slope * (ts[keep.last()] - ts[keep.first()])).toFloat()
                    movement = when {
                        moveSt >= p.movementSt -> PitchMovement.RISE
                        moveSt <= -p.movementSt -> PitchMovement.FALL
                        else -> PitchMovement.LEVEL
                    }
                }
            }
            val mid = (it.start + it.end) / 2
            val w0 = mid - p.rateWindowSeconds
            val w1 = mid + p.rateWindowSeconds
            val nuclei = syllables.count { s -> s in w0..w1 }
            val speechTime = (f.index(w0) until min(f.count, f.index(w1))).count { k -> k >= 0 && f.speech[k] } * f.hop
            val rate = if (speechTime > 0.2) (nuclei / speechTime).toFloat() else 0f
            out.add(
                ProsodicWord(
                    index = i, text = it.text, start = it.start, end = it.end, loudness = loud, pitchSt = level,
                    movement = movement, movementSt = moveSt, rate = rate,
                    pauseBefore = if (i == 0) 0.0 else max(0.0, it.start - prevEnd),
                    emphasis = emphasis.getOrElse(i) { false } && it.span == null,
                    spanId = it.span?.id, kind = it.span?.kind, fromTranscript = it.fromTranscript,
                ),
            )
            prevEnd = max(prevEnd, it.end)
        }
        return ProsodicTranscript(out, pauses, summarize(f, words, spans, pauses, voicedSt, t), words.isNotEmpty())
    }

    private fun summarize(
        f: SpeechFrames, words: List<TranscriptWord>, spans: List<FlaggedSpan>, pauses: List<PauseSpan>, voicedSt: List<Double>, t: SpeakingThresholds,
    ): ProsodySummary {
        val first = f.active.indexOfFirst { it }
        val last = f.active.indexOfLast { it }
        val spanStart = if (first < 0) 0.0 else f.time(first)
        val spanEnd = if (last < 0) 0.0 else f.time(last + 1)
        val inner = pauses.filter { it.start >= spanStart && it.end <= spanEnd }
        val pauseSeconds = inner.sumOf { it.duration }
        val longGaps = inner.filter { it.duration > 1.0 }.sumOf { it.duration }
        val speaking = max(0.0, spanEnd - spanStart - longGaps)
        val fillerIds = spans.filter { it.isFiller }.map { it.id }.toSet()
        val lexical = words.indices.count { w -> spans.none { it.id in fillerIds && w in it.wordIndices } }
        val minutes = max(speaking, 1.0) / 60
        val sd = if (voicedSt.size < 2) 0.0 else voicedSt.average().let { m -> sqrt(voicedSt.sumOf { (it - m) * (it - m) } / voicedSt.size) }
        val range = if (voicedSt.size < 2) 0.0 else voicedSt[(voicedSt.size * 0.95).toInt().coerceAtMost(voicedSt.size - 1)] - voicedSt[(voicedSt.size * 0.05).toInt()]
        val nuclei = f.summary.syllables.size
        val speechFrames = f.speech.count { it } * f.hop
        return ProsodySummary(
            durationSeconds = f.durationSeconds,
            speakingSeconds = speaking,
            words = lexical,
            wordsPerMinute = if (words.isEmpty() || speaking < 1.0) null else lexical / (speaking / 60),
            syllablesPerSecond = if (speechFrames > 0.5) nuclei / speechFrames else 0.0,
            pauseCount = inner.size,
            pauseSeconds = pauseSeconds,
            longestPauseSeconds = inner.maxOfOrNull { it.duration } ?: 0.0,
            pauseShare = if (spanEnd > spanStart) pauseSeconds / (spanEnd - spanStart) else 0.0,
            pitchVariationSt = sd,
            pitchRangeSt = range,
            fillerCount = spans.count { it.isFiller && it.confidence >= t.cut.cutConfidence },
            fillersPerMinute = spans.count { it.isFiller && it.confidence >= t.cut.cutConfidence } / minutes,
            repetitionCount = spans.count { it.kind == SpanKind.REPETITION },
            falseStartCount = spans.count { it.kind == SpanKind.FALSE_START },
            longPauseCount = inner.count { it.long },
        )
    }

    /** Speech without a transcript, cut at pauses and at energy valleys into chunks no longer than `chunkMaxSeconds`. */
    private fun chunks(f: SpeechFrames, spans: List<FlaggedSpan>, p: SpeakingThresholds.Prosody): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        val mask = f.speech.copyOf()
        for (s in spans) for (k in f.index(s.start) until min(f.count, f.index(s.end))) mask[k] = false
        var i = 0
        while (i < f.count) {
            if (!mask[i]) { i++; continue }
            var j = i
            var gap = 0
            var last = i
            while (j < f.count) {
                if (mask[j]) { last = j; gap = 0 } else if (++gap * f.hop >= p.chunkSplitPauseSeconds) break
                j++
            }
            splitChunk(f, i, last, p, out)
            i = last + 1
        }
        return out.filter { it.second - it.first >= 0.08 }
    }

    private fun splitChunk(f: SpeechFrames, a: Int, b: Int, p: SpeakingThresholds.Prosody, out: MutableList<Pair<Double, Double>>) {
        val len = (b - a + 1) * f.hop
        if (len <= p.chunkMaxSeconds || b - a < 20) { out.add(f.time(a) to f.time(b + 1)); return }
        // Deepest valley in the middle half.
        var best = (a + b) / 2
        var bestDb = Float.MAX_VALUE
        for (k in (a + (b - a) / 4)..(b - (b - a) / 4)) if (f.db[k] < bestDb) { bestDb = f.db[k]; best = k }
        splitChunk(f, a, best, p, out)
        splitChunk(f, best + 1, b, p, out)
    }

    private fun dots(f: SpeechFrames, start: Double, end: Double): String {
        val n = f.summary.syllables.count { it in start..end }.coerceIn(1, 6)
        return "•".repeat(n)
    }

    private fun st(hz: Float): Double = 12 * ln(hz.toDouble()) / ln(2.0)
}
