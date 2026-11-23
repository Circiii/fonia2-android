package tech.fonia.judge

import tech.fonia.dsp.DSPThresholds
import tech.fonia.dsp.SibilantPlace
import tech.fonia.dsp.SoundOutcome
import tech.fonia.dsp.TimingEnvelopes
import tech.fonia.dsp.analyzeClip
import tech.fonia.dsp.heardSpeech
import tech.fonia.live.Clip
import tech.fonia.model.ErrorKind
import tech.fonia.model.Verdict

/**
 * The sound judge (packages E2 + E5 + E8): one take in, a [TakeResult] out, abstaining when it is not sure.
 *
 * ```
 * P0 capture gate → P1 word gate → P2 target → P3 features  ([measure], ≤ 100 ms, no model)
 * P4 fusion head (fast, or full with phoneme evidence) → P5 decision + unlock  ([decide], < 1 ms)
 * ```
 *
 * Call [measure] once when the take ends, show [decide] with no phoneme evidence at once if its result is
 * [TakeResult.fastFinal], otherwise call [decide] again when the phoneme model answers (or decide on the fast result
 * when it times out). [judge] does both in one call. Thread-safe: no mutable state.
 */
class TakeJudge(
    val spec: JudgeSpec,
    val dspThresholds: DSPThresholds = DSPThresholds.v1,
    val unlock: UnlockView = UnlockView(spec.unlock),
) {
    private val measurer = Measurer(spec.population, dspThresholds)
    private val heads = spec.fusion.mapValues { FusionHead(it.value) }

    fun judge(
        request: JudgeRequest,
        clip: Clip,
        phones: PhoneEvidence? = null,
        words: WordEvidence? = null,
        runtime: RuntimeContext = RuntimeContext(),
    ): TakeResult = decide(measure(request, clip), phones, words, runtime)

    /** P0–P3. Off the audio thread; allocates. */
    fun measure(request: JudgeRequest, clip: Clip): Measured {
        val micros = LinkedHashMap<String, Long>()
        val t0 = System.nanoTime()
        val cap = spec.population.capture
        val notes = ArrayList<String>()
        val blocked = HashSet<ErrorKind>()
        fun gated(reason: RetakeReason, report: CaptureReport?) =
            Measured(request, reason, report, emptyList(), emptyMap(), null, blocked, notes, micros)

        if (clip.isEmpty || clip.sampleRate <= 0) return gated(RetakeReason.NoSpeech, null)
        if (clip.dropped > cap.maxDropped) {
            return gated(RetakeReason.Dropped, measurer.captureReport(clip.duration, clip.sampleRate, clip.dropped, null))
        }
        val summary = analyzeClip(clip.samples, clip.sampleRate, request.talker.voice, request.calibration, dspThresholds)
        micros["analyzeClip"] = (System.nanoTime() - t0) / 1000
        val baseReport = measurer.captureReport(clip.duration, clip.sampleRate, clip.dropped, summary)
        val q = summary.quality
        // P0: capture problems outrank everything; a bad take is not a pronunciation error.
        val captureGate = when {
            q.isClipped -> RetakeReason.Clipped
            // Flat but loud is noise, flat and faint is nothing; only a faint voice with some range is "too quiet".
            q.isTooQuiet && q.dynamicRangeDb < dspThresholds.capture.minDynamicRangeDb && q.speechRmsDb > cap.noisyRmsDb ->
                RetakeReason.Noisy
            q.isTooQuiet && q.dynamicRangeDb < dspThresholds.capture.minDynamicRangeDb -> RetakeReason.NoSpeech
            q.isTooQuiet -> RetakeReason.TooQuiet
            q.isTooShort ->
                if (summary.peakDb - summary.floorDb > dspThresholds.capture.minDynamicRangeDb) RetakeReason.TooShort else RetakeReason.NoSpeech
            !heardSpeech(summary, dspThresholds) -> RetakeReason.NoSpeech
            else -> null
        }
        if (captureGate != null) return gated(captureGate, baseReport)
        val f0 = summary.pitch.medianHz
        if (request.talker == TalkerGroup.Child && f0 > 0f && f0 < cap.adultVoiceRatio * (request.childF0Hz ?: cap.childF0Hz)) {
            return gated(RetakeReason.AdultVoice, baseReport)
        }
        // P1 (DSP part): at the word level the speech must last 0.5×–2.5× what the word needs.
        if (request.level == Level.Word) {
            val w = spec.population.word
            val expected = phonemeCount(request.text) * w.secondsPerPhoneme + w.baseSeconds
            val spoken = summary.speechEnd - summary.speechStart
            if (spoken < w.minRatio * expected || spoken > w.maxRatio * expected) {
                notes.add("duration ${"%.2f".format(spoken)} s for ${"%.2f".format(expected)} s")
                return gated(RetakeReason.OtherWord, baseReport)
            }
        }
        // P2–P3.
        val t1 = System.nanoTime()
        val env = TimingEnvelopes.of(clip.samples, clip.sampleRate)
        val family = traits(request.sound).family
        val (occurrences, outcome) = when (family) {
            Family.Sibilant, Family.Affricate ->
                measurer.sibilants(request, clip.samples, clip.sampleRate, summary, env, notes, blocked)
            Family.Rhotic, Family.Lateral ->
                measurer.rhotic(request, clip.samples, clip.sampleRate, summary, env, notes, blocked)
        }
        micros["features"] = (System.nanoTime() - t1) / 1000
        // Band SNR at sibilants and affricates, on the frication found (plan 03 §8; the reference is the clip's own
        // 4–8 kHz floor, not "the 300 ms before", which at a medial S is the vowel).
        var snr = Float.NaN
        var range = Float.NaN
        val seg = occurrences.mapNotNull { it.segment }
        val hissy = family == Family.Sibilant || family == Family.Affricate
        if (hissy) {
            val band = measurer.bandLevels(clip.samples, clip.sampleRate)
            range = band.rangeDb
            if (seg.isNotEmpty()) snr = seg.minOf { band.snr(it.first, it.second) }
        }
        val report = baseReport.copy(bandSnrDb = snr, bandRangeDb = range)
        val global = mapOf(
            Features.BAND_SNR to snr,
            Features.BAND_RANGE to range,
            Features.BANDWIDTH to q.bandwidthHz,
            Features.SPEECH_SECONDS to summary.speechSeconds.toFloat(),
            Features.F0 to f0,
        )
        micros["measure"] = (System.nanoTime() - t0) / 1000
        val pending = pendingOnPhones(request, occurrences, blocked)
        // Noise that hides the 4–8 kHz band hides a sibilant too: with too little range there, a missing S proves nothing.
        if ((!snr.isNaN() && snr < cap.minBandSnrDb) || (!range.isNaN() && range < cap.minBandSnrDb)) {
            return Measured(request, RetakeReason.Noisy, report, occurrences, global, outcome, blocked, notes, micros, pending)
        }
        return Measured(request, null, report, occurrences, global, outcome, blocked, notes, micros, pending)
    }

    /**
     * The kinds `Measurer` blocks only because the DSP cannot see them, which the phoneme model can judge (plan 03
     * §3.8, §3.10): Z/J/GE omission and stopping when the weak voiced frication was not found, and R's kinds outside
     * vowel–R–vowel. Word-final devoicing (Romanian devoices there), low-bandwidth place and lateral stay blocked.
     */
    private fun pendingOnPhones(request: JudgeRequest, occurrences: List<Occurrence>, blocked: Set<ErrorKind>): Set<ErrorKind> {
        val out = HashSet<ErrorKind>()
        val t = traits(request.sound)
        if (t.voiced && occurrences.any { it.features[Features.PRESENT] == 0f }) out += setOf(ErrorKind.Omission, ErrorKind.Stopping)
        if (request.sound == tech.fonia.model.SoundId.R && occurrences.all { it.features[Features.DIP_DB]?.isNaN() != false }) {
            out += setOf(ErrorKind.RtoL, ErrorKind.RtoGlide, ErrorKind.RtoVowel, ErrorKind.Omission)
        }
        return out.intersect(blocked)
    }

    /** P1 (model part), P4 and P5. [phones] selects the full head; without it the fast head decides. */
    fun decide(
        measured: Measured,
        phones: PhoneEvidence? = null,
        words: WordEvidence? = null,
        runtime: RuntimeContext = RuntimeContext(),
    ): TakeResult {
        val t0 = System.nanoTime()
        val request = measured.request
        val versions = spec.versions(request.sound)
        val micros = LinkedHashMap(measured.micros)
        fun evidence(features: Map<String, Float>) = JudgeEvidence(
            features = features, outcome = measured.outcome, capture = measured.capture,
            segments = measured.occurrences.mapNotNull { it.segment }, blockedKinds = measured.blocked, notes = measured.notes,
        )
        fun retake(reason: RetakeReason, features: Map<String, Float>) =
            TakeResult(Verdict.Retake, 1f, reason, Head.Gate, evidence = evidence(features), versions = versions, micros = micros)

        val firstFeatures = measured.global + (measured.occurrences.firstOrNull()?.features ?: emptyMap())
        measured.gate?.let { return retake(it, firstFeatures) }
        val w = spec.population.word
        // P1 word gate on the phone fit: words and sentences only (plan 03 §8: isolated sounds and syllables have none).
        val wordLevel = request.level == Level.Word || request.level == Level.Sentence
        if (wordLevel && phones?.fitPerLabel != null && phones.frames >= w.minFitFrames && phones.fitPerLabel < w.minFitPerLabel) {
            return retake(RetakeReason.OtherWord, firstFeatures)
        }
        if (words?.otherWord == true) return retake(RetakeReason.OtherWord, firstFeatures)

        val fusion = spec.fusion[request.sound]
        val head = heads[request.sound]
        if (fusion == null || head == null || measured.occurrences.isEmpty()) {
            // Nothing to judge (no target in the text, or no spec): ask the adult, never guess.
            return TakeResult(Verdict.Unsure, 0f, null, Head.Gate, evidence = evidence(firstFeatures), versions = versions, micros = micros)
        }
        // The full head needs a reliable alignment; an unreliable one is kept in the trace but the fast head decides.
        val useFull = phones != null && phones.reliable && phones.lpr.isNotEmpty() && "full" in fusion.heads
        val which = if (useFull) Head.Full else Head.Fast
        val zipa: Map<String, Float> = if (phones == null) emptyMap() else buildMap {
            for ((k, v) in phones.lpr) put(Features.LPR_PREFIX + k, v)
            phones.gop?.let { put(Features.ZIPA_GOP, it) }
            phones.fitPerLabel?.let { put(Features.ZIPA_FIT, it) }
        }
        val notes = ArrayList(measured.notes)
        if (phones != null && !useFull) notes += if (phones.reliable) "noFullHead" else "phonesUnreliable"
        phones?.notes?.forEach { notes += "zipa:$it" }
        // P2 check (plan 03 §5 item 4): the phone interval should overlap the DSP segment within 60 ms.
        val span = phones?.targetSpan
        val dspSegments = measured.occurrences.mapNotNull { it.segment }
        if (useFull && span != null && dspSegments.isNotEmpty() &&
            dspSegments.none { it.first - 0.06 <= span.second && span.first <= it.second + 0.06 }
        ) notes += "zipaDspApart"
        val blocked = if (useFull) measured.blocked - measured.pending else measured.blocked
        // Several occurrences (sentences): the one least likely correct speaks for the take.
        val scored = measured.occurrences.map { occ ->
            val features = measured.global + occ.features + zipa
            features to Probs(fusion, head.probabilities(features, which.raw))
        }
        val (features, probs) = scored.minBy { it.second.correct }
        var decision = DecisionPolicy.decide(probs, which, request.sound, unlock, runtime, blocked, measured.capture)
        // Safety net from the parity-locked judge (plan 03 §1, head v0): a lateral hint on an S-family target never
        // passes as correct without the phoneme model; it goes to the adult.
        val lateralHint = measured.outcome == SoundOutcome.LateralHint && traits(request.sound).place == SibilantPlace.S
        if (lateralHint && !useFull && decision.verdict == Verdict.Correct) {
            decision = decision.copy(verdict = Verdict.Unsure)
        }
        micros["decide"] = (System.nanoTime() - t0) / 1000
        if (phones != null && phones.micros > 0) micros["phones"] = phones.micros
        return TakeResult(
            verdict = decision.verdict,
            confidence = decision.confidence,
            head = which,
            probabilities = probs.asMap(),
            topError = decision.topError,
            topErrorProbability = decision.topErrorProbability,
            withheldKind = decision.withheld,
            adultNote = decision.adultNote,
            evidence = evidence(features).copy(blockedKinds = blocked, notes = notes),
            versions = versions,
            fastFinal = which == Head.Fast && decision.verdict == Verdict.Correct,
            micros = micros,
        )
    }
}
