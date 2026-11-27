package tech.fonia.judge

import tech.fonia.dsp.DSPThresholds
import tech.fonia.dsp.SibilantPlace
import tech.fonia.dsp.SoundOutcome
import tech.fonia.dsp.TimingEnvelopes
import tech.fonia.dsp.analyzeClip
import tech.fonia.dsp.heardSpeech
import tech.fonia.live.Clip
import tech.fonia.model.ErrorKind
import tech.fonia.model.SoundId
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
        fun gated(reason: RetakeReason, report: CaptureReport?, global: Map<String, Float> = emptyMap()) =
            Measured(request, reason, report, emptyList(), global, null, blocked, notes, micros)

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
        // The adult-voice gate is off in the app (`adultVoiceRatio` 0 in population.json): whoever
        // speaks is judged on their own voice, with the talker group heard from it (TalkerVoice, measureForVoice).
        if (cap.adultVoiceRatio > 0f && request.talker == TalkerGroup.Child && f0 > 0f &&
            f0 < cap.adultVoiceRatio * (request.childF0Hz ?: cap.childF0Hz)
        ) {
            return gated(RetakeReason.AdultVoice, baseReport, mapOf(Features.F0 to f0))
        }
        // P1 (DSP part): at the word level the speech must last 0.5×–2.5× what the word needs.
        if (request.level == Level.Word) {
            val w = spec.population.word
            val expected = phonemeCount(request.text) * w.secondsPerPhoneme + w.baseSeconds
            val spoken = summary.speechEnd - summary.speechStart
            if (spoken < w.minRatio * expected || spoken > w.maxRatio * expected) {
                notes.add("duration ${"%.2f".format(spoken)} s for ${"%.2f".format(expected)} s")
                return gated(RetakeReason.OtherWord, baseReport, mapOf(Features.F0 to f0))
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
        val v2 = spec.population.judge2
        // Several occurrences (sentences): the one least likely correct speaks for the take. v2 bursts ("ț-ț-ț"): the
        // median burst speaks when enough bursts were found (one blurred burst is not a wrong sound).
        val scored = measured.occurrences.map { occ ->
            val placed = trustPlace(withContext(measured.global + occ.features + zipa, request, v2), fusion, v2)
            val features = maskFinalGlide(trustStop(placed, request, v2), request, fusion, phones, v2)
            features to Probs(fusion, head.probabilities(features, which.raw))
        }
        val burst = isBurst(request) && v2.burst.minFound > 0
        val found = scored.filter { it.first[Features.PRESENT] == 1f }
        val (features, probs) = if (burst && found.size >= v2.burst.minFound) {
            notes += "burst:${found.size}/${scored.size}"
            found.sortedBy { it.second.correct }[found.size / 2]
        } else scored.minBy { it.second.correct }
        val fastCorrect = if (which == Head.Fast) probs.correct else head.probabilities(features, Head.Fast.raw)[0]
        var decision = DecisionPolicy.decide(probs, which, request.sound, unlock, runtime, blocked, measured.capture)
        // v2 praise: both heads lean correct and the phoneme model's best candidate is the target itself. With an opt-in
        // gate on (off by default), the candidates it set aside do not count.
        val gated = features["stopTrusted"] == 1f || features["finalGlideMasked"] == 1f
        val candidates = if (gated) features.filterKeys { it.startsWith(Features.LPR_PREFIX) }.values else phones?.lpr?.values.orEmpty()
        if (useFull && decision.verdict != Verdict.Correct && probs.correct >= v2.praise.full && fastCorrect >= v2.praise.fast &&
            candidates.all { it >= 0f }
        ) {
            decision = decision.copy(verdict = Verdict.Correct, confidence = probs.correct, withheld = null, adultNote = null)
            notes += "praiseAgree"
        }
        // v2 affricate veto (off by default): a CE/GE whose frication swells in like a fricative's is not praised.
        val stop = features[Features.STOP_ONSET] ?: Float.NaN
        val vetoAt = v2.affricate[request.sound.raw]?.vetoAt ?: -1f
        if (decision.verdict == Verdict.Correct && !stop.isNaN() && stop <= vetoAt) {
            decision = decision.copy(verdict = Verdict.Almost)
            notes += "stopVeto"
        }
        // v2 word-final L (off by default): lToGlide named on weak glide evidence becomes Almost, with no kind.
        val named = decision.verdict
        if (named is Verdict.NamedError && named.kind == ErrorKind.LtoGlide && !v2.finalL.mask && weakFinalGlide(features, request, phones, v2)) {
            decision = Decision(Verdict.Almost, probs.correct, null, 0f, null, null)
            notes += "finalGlideWeak"
        }
        // Safety net from the parity-locked judge (plan 03 §1, head v0): a lateral hint on an S-family target never
        // passes as correct without the phoneme model. There is no adult to ask any more, so it is "almost" (the take
        // counts and something kind happens) instead of a silent retake.
        val lateralHint = measured.outcome == SoundOutcome.LateralHint && traits(request.sound).place == SibilantPlace.S
        if (lateralHint && !useFull && decision.verdict == Verdict.Correct) {
            decision = decision.copy(verdict = Verdict.Almost)
            notes += "lateralHintAlmost"
        }
        // A word the phoneme model could not align is most often another word: the fast head alone never praises it
        // (heard on the emulator: "lup" in an R drill praised by the fast head at p=0.91 while the alignment failed).
        if (wordLevel && phones != null && !phones.reliable && decision.verdict == Verdict.Correct) {
            return retake(RetakeReason.OtherWord, firstFeatures)
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
            sound = request.sound,
            fastCorrect = fastCorrect,
            visual = TakeVisuals.of(request, features, decision.verdict, decision.topError, decision.topErrorProbability, probs.correct,
                v2.visual, request.reference),
            talker = request.talker,
        )
    }

    /**
     * v2 place trust: when the measured place evidence (`toward`) is already clearly in the target zone, the phoneme
     * model's place candidate (lpr of the placeSwap / affricatePlace class) is masked: ZIPA hears [s] in good Ș on
     * synthetic voices (EVAL_v1) and must not overrule a clear spectrum. Note `placeTrusted` in the trace.
     */
    private fun trustPlace(features: Map<String, Float>, fusion: FusionSpec, v2: Judge2Thresholds): Map<String, Float> {
        val toward = features[Features.TOWARD] ?: return features
        if (toward.isNaN() || toward < v2.placeTrust) return features
        val keys = fusion.phoneMap.filterKeys {
            val k = FusionSpec.kindOf(it)
            k == ErrorKind.PlaceSwap || k == ErrorKind.AffricatePlace
        }.values.map { Features.LPR_PREFIX + it }
        if (keys.none { it in features }) return features
        val out = HashMap(features)
        for (k in keys) out.remove(k)
        out["placeTrusted"] = 1f
        return out
    }

    /**
     * v2 affricate trust (`judge2.affricate`, off by default): with a clear stop onset (`stopOnset` ≥ trustAt) the
     * phoneme model's fricative candidates of an affricate target are set aside and the GOP recomputed without them.
     * Note `stopTrusted` in the trace.
     */
    private fun trustStop(features: Map<String, Float>, request: JudgeRequest, v2: Judge2Thresholds): Map<String, Float> {
        val gate = v2.affricate[request.sound.raw] ?: return features
        val stop = features[Features.STOP_ONSET] ?: return features
        if (stop.isNaN() || stop < gate.trustAt) return features
        return masked(features, gate.trust)?.also { it["stopTrusted"] = 1f } ?: features
    }

    /**
     * Word-final L whose glide evidence is under the margin (`judge2.finalL`, off by default): ZIPA has no dark-L
     * token and moves a good final L towards [w]/[u]. Only a reliable full head's evidence counts.
     */
    private fun weakFinalGlide(features: Map<String, Float>, request: JudgeRequest, phones: PhoneEvidence?, v2: Judge2Thresholds): Boolean {
        val margin = v2.finalL.minGlideNats
        if (margin <= 0f || request.sound != SoundId.L || phones == null || !phones.reliable) return false
        val final = phones.wordFinal ?: (features[Features.WORD_FINAL] == 1f)
        if (!final) return false
        val glide = phones.lpr["j+w"] ?: listOfNotNull(phones.lpr["j"], phones.lpr["w"]).minOrNull() ?: return false
        return glide > -margin
    }

    /** [FinalLThresholds.mask]: the glide candidates of a weak word-final glide are masked before the head. */
    private fun maskFinalGlide(
        features: Map<String, Float>, request: JudgeRequest, fusion: FusionSpec, phones: PhoneEvidence?, v2: Judge2Thresholds,
    ): Map<String, Float> {
        if (!v2.finalL.mask || !weakFinalGlide(features, request, phones, v2)) return features
        val glides = fusion.phoneMap.filterKeys { FusionSpec.kindOf(it) == ErrorKind.LtoGlide }.values.flatMap { listOf(it) + it.split('+') }
        return masked(features, glides)?.also { it["finalGlideMasked"] = 1f } ?: features
    }

    /**
     * [features] without the phone-model candidates [candidates] (`lpr.<key>`), and `zipa.gop` recomputed over the
     * single candidates left: −ln(1 + Σ exp(−lpr)), as `RestrictedGop` gives it (lpr clipped at ±12 nats there, so
     * the GOP is clipped at −12 here). Null when none of them is present.
     */
    private fun masked(features: Map<String, Float>, candidates: List<String>): MutableMap<String, Float>? {
        val keys = candidates.map { Features.LPR_PREFIX + it }.filter { it in features }
        if (keys.isEmpty()) return null
        val out = HashMap(features)
        for (k in keys) out.remove(k)
        if (Features.ZIPA_GOP in out) {
            var sum = 1.0
            for ((k, v) in out) if (k.startsWith(Features.LPR_PREFIX) && '+' !in k && !v.isNaN()) sum += kotlin.math.exp(-v.toDouble())
            out[Features.ZIPA_GOP] = (-kotlin.math.ln(sum)).toFloat().coerceIn(-12f, 0f)
        }
        return out
    }

    private fun isBurst(request: JudgeRequest): Boolean =
        request.level == Level.Syllable && request.text.contains('-') && traits(request.sound).family == Family.Affricate

    /**
     * v2 vowel context: before i/e the S↔Ș evidence is moved by the fitted shift (logit of sNess, keyed
     * "<target place>:<i|e>") and `toward` recomputed; the raw values stay in the trace as `sNessRaw`.
     */
    private fun withContext(features: Map<String, Float>, request: JudgeRequest, v2: Judge2Thresholds): Map<String, Float> {
        if (v2.contextShift.isEmpty()) return features
        val place = traits(request.sound).place ?: return features
        val ctx = when (features[Features.FRONT_CONTEXT]) { 1f -> "i"; 2f -> "e"; else -> return features }
        val shift = v2.contextShift["${if (place == SibilantPlace.S) "s" else "ș"}:$ctx"] ?: return features
        val s = features[Features.S_NESS] ?: return features
        val t = features[Features.TOWARD] ?: Float.NaN
        if (s.isNaN()) return features
        val p = s.coerceIn(1e-4f, 1f - 1e-4f)
        val shifted = (1.0 / (1.0 + kotlin.math.exp(-(kotlin.math.ln(p / (1.0 - p)) + shift)))).toFloat()
        val out = HashMap(features)
        out["sNessRaw"] = s
        out[Features.S_NESS] = shifted
        if (!t.isNaN()) out[Features.TOWARD] = if (place == SibilantPlace.S) shifted else 1f - shifted
        return out
    }
}
