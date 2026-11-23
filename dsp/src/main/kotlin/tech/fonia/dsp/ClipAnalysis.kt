package tech.fonia.dsp

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/** Analyzes a whole recording. Runs after the take; call it off the audio thread. */
fun analyzeClip(
    pcm: FloatArray,
    sampleRate: Double,
    group: VoiceGroup,
    calibration: TalkerCalibration?,
    thresholds: DSPThresholds = DSPThresholds.v1,
    evidence: SpeechEvidence? = null,
): ClipSummary {
    val norm = (calibration ?: TalkerCalibration()).norm(group, thresholds)
    return ClipAnalysis(pcm, sampleRate, norm, thresholds).run(evidence)
}

private class AnalysisFrame(
    val time: Double,
    val features: FrameFeatures,
    val levelDb: Float,
    val hfDb: Float,
    val vowelDb: Float,
    var pitchHz: Float,
    var melodyHz: Float,
    val clarity: Float,
    var speaking: Boolean = false,
)

private class PitchPoint(val time: Double, val hz: Float)

private class PitchSummary(
    val medianHz: Float,
    val sdSt: Float,
    val rangeSt: Float,
    val pvq: Float,
    val finalSlopeSt: Float,
)

private class ClipAnalysis(
    private val pcm: FloatArray,
    private val sampleRate: Double,
    private val norm: SibilantNorm,
    private val thresholds: DSPThresholds,
) {
    private val hop: Int = max(1, (thresholds.analysis.hopSeconds * sampleRate).rounded().toInt())
    private val frameLength: Int = powerOfTwoFrame(thresholds.analysis.frameSeconds, sampleRate)
    private val duration: Double = pcm.size.toDouble() / sampleRate

    private val frames = ArrayList<AnalysisFrame>()
    private var floorDb = -100f
    private var peakDb = -100f
    private var hfFloorDb = -130f
    private var hfPeakDb = -130f
    private var firstSpeech = -1
    private var lastSpeech = -1
    private var activeFrames = 0

    private val hopSeconds: Double get() = hop.toDouble() / sampleRate

    fun run(evidence: SpeechEvidence?): ClipSummary {
        computeFrames()
        if (frames.isEmpty()) return emptySummary()
        cleanStrictPitch()
        foldMelodyPitch()
        computeFloors()
        markSpeech()

        val speechStart = if (firstSpeech >= 0) frames[firstSpeech].time else 0.0
        val speechEnd = if (lastSpeech >= 0) frames[lastSpeech].time else 0.0
        val bandwidth = measureBandwidth()
        val fricatives = findFricatives(bandwidth)
        val tracker = EnvelopeTracker(sampleRate, thresholds.envelope)
        val envelope = envelopeOf(pcm, tracker)
        val trills = findTrills(envelope, tracker)
        val taps = findTaps(envelope, tracker)
        val syllables = findSyllables()
        val energyPauses = findPauses()

        return summarize(
            energyStart = speechStart, energyEnd = speechEnd, energyPauses = energyPauses,
            fricatives = fricatives, trills = trills, taps = taps, energySyllables = syllables,
            bandwidth = bandwidth, evidence = evidence,
        )
    }

    // MARK: Frames

    private fun computeFrames() {
        if (pcm.size < frameLength) return
        val spectrum = FrameSpectrum(frameLength)
        val extractor = FeatureExtractor(spectrum.binCount, sampleRate.toFloat() / frameLength.toFloat(), thresholds.bands)
        val pitch = YINPitchTracker(sampleRate, thresholds.pitch)
        val pitchOffset = max(0, (frameLength - pitch.requiredInputCount) / 2)
        val settings = thresholds.pitch

        frames.ensureCapacity((pcm.size - frameLength) / hop + 1)
        var start = 0
        while (start + frameLength <= pcm.size) {
            spectrum.compute(pcm, start)
            val features = extractor.extract(spectrum.power)
            val estimate = pitch.estimate(pcm, start + pitchOffset)
            val strict = estimate.clarity > settings.strictClarity &&
                estimate.hz > settings.minHz && estimate.hz < settings.maxHz
            val melodic = estimate.clarity >= settings.melodyClarity &&
                estimate.hz >= settings.melodyMinHz && estimate.hz <= settings.melodyMaxHz
            frames.add(
                AnalysisFrame(
                    time = (start.toDouble() + frameLength.toDouble() / 2) / sampleRate,
                    features = features,
                    // Digital silence clamps here so floors stay meaningful.
                    levelDb = max(-120f, features.levelDb),
                    hfDb = max(-130f, features.hfDb),
                    vowelDb = max(-120f, features.vowelDb),
                    pitchHz = if (strict) estimate.hz else 0f,
                    melodyHz = if (melodic) estimate.hz else 0f,
                    clarity = estimate.clarity,
                ),
            )
            start += hop
        }
    }

    /** Positive values of `values[from...to]`, in order. */
    private fun positive(values: FloatArray, from: Int, to: Int): FloatArray {
        val result = FloatList(to - from + 1)
        for (k in from..to) if (values[k] > 0f) result.add(values[k])
        return result.toFloatArray()
    }

    /** Drops octave jumps, which otherwise inflate range and slope statistics. */
    private fun cleanStrictPitch() {
        val raw = FloatArray(frames.size) { frames[it].pitchHz }
        val radius = thresholds.pitch.octaveNeighbourFrames
        for (i in frames.indices) {
            if (!(raw[i] > 0f)) continue
            val neighbours = positive(raw, max(0, i - radius), min(raw.size - 1, i + radius))
            val centre = median(neighbours)
            if (neighbours.size < 3 || abs(semitones(raw[i], centre)) > thresholds.pitch.octaveOutlierSt) {
                frames[i].pitchHz = 0f
            }
        }
    }

    /**
     * Octave errors come in runs (a whole vowel read at twice F0), so fold against the take's median
     * first, then drop what still disagrees with its neighbours.
     */
    private fun foldMelodyPitch() {
        val settings = thresholds.pitch
        val melody = FloatArray(frames.size) { frames[it].melodyHz }
        val centre = median(positive(melody, 0, melody.size - 1))
        if (!(centre > 0f)) return
        val folded = melody.copyOf()
        for (i in folded.indices) {
            if (!(folded[i] > 0f)) continue
            val st = semitones(folded[i], centre)
            if (st > settings.melodyFoldUpSt) {
                folded[i] /= 2f
            } else if (st < settings.melodyFoldDownSt) {
                folded[i] *= 2f
            }
        }
        val radius = settings.melodyNeighbourFrames
        for (i in frames.indices) {
            if (!(folded[i] > 0f)) {
                frames[i].melodyHz = 0f
                continue
            }
            val neighbours = positive(folded, max(0, i - radius), min(folded.size - 1, i + radius))
            val keep = neighbours.size >= 3 &&
                abs(semitones(folded[i], median(neighbours))) <= settings.melodyOutlierSt
            frames[i].melodyHz = if (keep) folded[i] else 0f
        }
    }

    private fun computeFloors() {
        val settings = thresholds.analysis
        val levels = FloatArray(frames.size) { frames[it].levelDb }.also { it.sort() }
        val hf = FloatArray(frames.size) { frames[it].hfDb }.also { it.sort() }
        floorDb = percentile(levels, settings.floorPercentile)
        peakDb = percentile(levels, settings.peakPercentile)
        hfFloorDb = percentile(hf, settings.floorPercentile)
        hfPeakDb = percentile(hf, settings.peakPercentile)
    }

    private fun markSpeech() {
        val settings = thresholds.analysis
        val margin = max(
            settings.speechMarginMinDb,
            min(settings.speechMarginMaxDb, (peakDb - floorDb) * settings.speechMarginShare),
        )
        val threshold = max(floorDb + margin, peakDb - settings.speechMaxBelowPeakDb)
        val hang = (settings.speechHangSeconds / hopSeconds).rounded().toInt()
        var last = Int.MIN_VALUE / 2
        for (i in frames.indices) {
            if (frames[i].levelDb > threshold) {
                frames[i].speaking = true
                last = i
                activeFrames += 1
            } else if (i - last <= hang) {
                frames[i].speaking = true
            }
        }
        firstSpeech = frames.indexOfFirst { it.speaking }
        lastSpeech = frames.indexOfLast { it.speaking }
        // Room hum and codec noise can be periodic; pitch outside speech means nothing.
        for (frame in frames) {
            if (!frame.speaking) {
                frame.pitchHz = 0f
                frame.melodyHz = 0f
            }
        }
    }

    // MARK: Fricatives

    private fun findFricatives(bandwidth: Float): List<FricativeSegment> {
        val multitaper = MultitaperAnalyzer(sampleRate, thresholds.bands, thresholds.fricative)
        val segments = ArrayList<FricativeSegment>()
        var startIndex = -1
        fun flush(end: Int) {
            if (startIndex < 0) return
            val first = startIndex
            if (frames[end].time - frames[first].time >= thresholds.fricative.minSegmentSeconds - hopSeconds / 2) {
                segments.add(makeSegment(first, end, bandwidth, multitaper))
            }
        }
        for (i in frames.indices) {
            val reading = classifyFrication(
                frames[i].features, hfFloorDb, frames[i].clarity, norm, thresholds.fricative,
            )
            if (reading.present && frames[i].hfDb > hfPeakDb - thresholds.fricative.maxBelowHfPeakDb) {
                if (startIndex < 0) startIndex = i
            } else if (startIndex >= 0) {
                flush(i - 1)
                startIndex = -1
            }
        }
        if (startIndex >= 0) flush(frames.size - 1)
        return segments
    }

    private fun makeSegment(first: Int, last: Int, bandwidth: Float, multitaper: MultitaperAnalyzer): FricativeSegment {
        val start = frames[first].time
        val end = frames[last].time
        val core = fricativeCore(start, end, duration, thresholds.fricative)
        val coreFirst = max(0, (core.start * sampleRate).toInt())
        val coreLast = min(pcm.size, (core.end * sampleRate).toInt())
        val stats = multitaper.features(pcm, coreFirst, coreLast - coreFirst)

        val span = frames.subList(first, last + 1)
        val coreFrames = span.filter { it.time >= core.start && it.time <= core.end }
        val measured = coreFrames.ifEmpty { span }
        val voicedCount = measured.count { it.clarity > thresholds.fricative.voicedClarity }
        val above = mean(FloatArray(measured.size) { measured[it].hfDb - hfFloorDb })
        val sNess = norm.sNess(stats)
        val lateral = thresholds.lateral
        return FricativeSegment(
            start = start, end = end, coreStart = core.start, coreEnd = core.end,
            place = if (sNess >= 0.5f) SibilantPlace.S else SibilantPlace.SH,
            sNess = sNess,
            isBorderline = norm.isBorderline(sNess),
            voiced = voicedCount * 2 > measured.size,
            voicingMeasurable = end - start >= thresholds.fricative.minVoicingSeconds,
            diffuse = isDiffuse(stats, above, norm, thresholds.fricative),
            lateralHint = bandwidth >= lateral.minBandwidthHz &&
                stats.lowBandDb >= lateral.minLowBandDb &&
                stats.bandRatioDb >= lateral.minBandRatioDb,
            centroidHz = stats.centroidHz, peakHz = stats.peakHz, bandRatioDb = stats.bandRatioDb,
            tiltDb = stats.tiltDb, lowBandDb = stats.lowBandDb, flatness = stats.flatness, aboveFloorDb = above,
        )
    }

    /**
     * Effective bandwidth from the frames with the most high-frequency energy. A word's vowels say
     * little about the top of the band, and frication is where a lowpass would hurt.
     */
    private fun measureBandwidth(): Float {
        if (pcm.isEmpty()) return 0f
        val count = max(1, frames.size / 10)
        // A stable sort with Swift's `>` as the order, so equal levels keep frame order, as in Swift.
        val loudest = frames.indices.sortedWith { a, b ->
            val x = frames[a].hfDb
            val y = frames[b].hfDb
            if (x > y) -1 else if (y > x) 1 else 0
        }.take(count)
        val spectrum = FrameSpectrum(frameLength)
        val average = FloatArray(spectrum.binCount)
        for (index in loudest) {
            spectrum.compute(pcm, index * hop)
            // vDSP_vadd
            for (k in average.indices) average[k] = average[k] + spectrum.power[k]
        }
        val binHz = sampleRate.toFloat() / frameLength.toFloat()
        val firstBin = (thresholds.bands.centroidLowHz / binHz).toInt()
        if (firstBin >= average.size) return 0f
        val top = sliceMax(average, firstBin) ?: return 0f
        if (!(top > 0f)) return 0f
        val limit = top * 10f.pow(-thresholds.sibilant.bandwidthDropDb / 10f)
        val found = average.indexOfLast { it > limit }
        val lastBin = if (found >= 0) found else firstBin
        return lastBin.toFloat() * binHz
    }

    // MARK: Rhotics

    private fun findTrills(envelope: FloatArray, tracker: EnvelopeTracker): List<TrillSegment> {
        val settings = thresholds.trill
        val detector = TrillDetector(tracker.rate, settings)
        val window = detector.windowCount
        val step = max(1, (settings.stepSeconds * tracker.rate).rounded().toInt())
        if (window > envelope.size) return emptyList()

        val segments = ArrayList<TrillSegment>()
        var current: TrillSegment? = null
        var next = window
        while (next <= envelope.size) {
            // Swift's `defer { end += step }`: the step happens however the iteration ends.
            val end = next
            next += step
            val time = (end * tracker.hop).toDouble() / sampleRate
            val index = frameIndex(time)
            val frame = frames[index]
            if (!frame.speaking) continue
            if (frame.pitchHz > 0f && frame.pitchHz < settings.creakyBelowHz) continue
            // A trill is a voiced sonorant: skip windows that are mostly hiss.
            val from = max(0, index - settings.lookbackFrames)
            val recentCount = index - from + 1
            var hissy = 0
            for (k in from..index) if (frames[k].features.hfShare > settings.hissyShare) hissy += 1
            if (hissy.toFloat() > recentCount.toFloat() * settings.hissyFraction) continue
            var voiced = 0
            for (k in from..index) if (frames[k].melodyHz > 0f) voiced += 1
            if (voiced.toFloat() < recentCount.toFloat() * settings.minVoicedShare) continue
            if (median(FloatArray(recentCount) { frames[from + it].levelDb }) < peakDb - settings.maxBelowPeakDb) continue
            val result = detector.detect(envelope, end - window)
            if (!result.isTrill) continue
            val segmentStart = time - settings.windowSeconds
            val open = current
            if (open != null && segmentStart <= open.end + settings.mergeGapSeconds) {
                current = open.copy(end = time, periodicity = max(open.periodicity, result.periodicity))
            } else {
                if (open != null) segments.add(open)
                current = TrillSegment(start = segmentStart, end = time, rate = result.rate, periodicity = result.periodicity)
            }
        }
        current?.let { segments.add(it) }
        return segments
    }

    private fun findTaps(envelope: FloatArray, tracker: EnvelopeTracker): List<TapEvent> {
        if (firstSpeech < 0) return emptyList()
        val settings = thresholds.tap
        val first = max(0, (frames[firstSpeech].time * sampleRate).toInt() / tracker.hop)
        val last = min(envelope.size, (frames[lastSpeech].time * sampleRate).toInt() / tracker.hop + 1)
        if (first >= last) return emptyList()
        val candidates = findTapCandidates(envelope.copyOfRange(first, last), tracker.rate.toFloat(), settings)
        val events = ArrayList<TapEvent>()
        for (candidate in candidates) {
            val time = ((first + candidate.index) * tracker.hop).toDouble() / sampleRate
            val index = frameIndex(time)
            val inner = settings.voicedFlankInnerFrames
            val outer = settings.voicedFlankOuterFrames
            val lowerFrom = max(0, index - outer)
            val lowerTo = max(lowerFrom, index - inner)
            val upperFrom = min(frames.size, index + inner + 1)
            val upperTo = min(frames.size, index + outer + 1)
            val voicedBefore = (lowerFrom until lowerTo).any { frames[it].pitchHz > 0f }
            val voicedAfter = (upperFrom until upperTo).any { frames[it].pitchHz > 0f }
            val nearDip = max(0, index - settings.hissCheckFrames)..min(frames.size - 1, index + settings.hissCheckFrames)
            val hiss = nearDip.any { frames[it].features.hfShare > settings.maxHfShare }
            if (voicedBefore && voicedAfter && !hiss) {
                events.add(
                    TapEvent(time = time, closureMs = candidate.widthMs, depthDb = candidate.depthDb, score = candidate.score),
                )
            }
        }
        return events
    }

    private fun frameIndex(time: Double): Int =
        max(0, min(frames.size - 1, ((time - frames[0].time) / hopSeconds).rounded().toInt()))

    // MARK: Syllables and pauses

    /** Syllable nuclei after de Jong & Wempe: the vowel band keeps hisses from counting. */
    private fun findSyllables(): List<Double> {
        val settings = thresholds.syllable
        val smooth = FloatArray(frames.size) { i ->
            val a = max(0, i - 1)
            val b = min(frames.size - 1, i + 1)
            var sum = 0f
            for (k in a..b) sum += frames[k].vowelDb
            sum / (b - a + 1).toFloat()
        }
        val spoken = frames.filter { it.speaking }.map { it.vowelDb }.toFloatArray().also { it.sort() }
        val threshold = percentile(spoken, 0.95f) - settings.belowPeakDb
        val nuclei = ArrayList<Int>()
        if (smooth.size <= 2) return emptyList()
        for (i in 1 until smooth.size - 1) {
            if (!(smooth[i] >= smooth[i - 1] && smooth[i] > smooth[i + 1] && smooth[i] >= threshold && frames[i].speaking)) {
                continue
            }
            val voiced = (max(0, i - 2)..min(frames.size - 1, i + 2)).any { frames[it].pitchHz > 0f }
            if (!voiced) continue
            var leftMin = smooth[i]
            var j = i - 1
            while (j >= max(0, i - settings.valleyLookFrames)) {
                leftMin = min(leftMin, smooth[j])
                if (smooth[j] > smooth[i]) break
                j -= 1
            }
            var rightMin = smooth[i]
            j = i + 1
            while (j <= min(smooth.size - 1, i + settings.valleyLookFrames)) {
                rightMin = min(rightMin, smooth[j])
                if (smooth[j] > smooth[i]) break
                j += 1
            }
            if (!(smooth[i] - leftMin >= settings.minProminenceDb && smooth[i] - rightMin >= settings.minProminenceDb)) continue
            val previous = nuclei.lastOrNull()
            if (previous != null && frames[i].time - frames[previous].time < settings.minGapSeconds) {
                if (smooth[previous] < smooth[i]) nuclei[nuclei.size - 1] = i
                continue
            }
            nuclei.add(i)
        }
        return nuclei.map { frames[it].time }
    }

    private fun findPauses(): List<TimeSpan> {
        if (firstSpeech < 0) return emptyList()
        val pauses = ArrayList<TimeSpan>()
        var start = -1
        for (i in firstSpeech..lastSpeech) {
            if (!frames[i].speaking && start < 0) start = i
            if (frames[i].speaking && start >= 0) {
                if (frames[i].time - frames[start].time >= thresholds.analysis.minPauseSeconds) {
                    pauses.add(TimeSpan(start = frames[start].time, end = frames[i].time))
                }
                start = -1
            }
        }
        return pauses
    }

    // MARK: Summary

    private fun summarize(
        energyStart: Double, energyEnd: Double, energyPauses: List<TimeSpan>,
        fricatives: List<FricativeSegment>, trills: List<TrillSegment>, taps: List<TapEvent>, energySyllables: List<Double>,
        bandwidth: Float, evidence: SpeechEvidence?,
    ): ClipSummary {
        val segments = evidence?.segments
        var speechStart = energyStart
        var speechEnd = energyEnd
        var pauses = energyPauses
        var speechSeconds = frames.count { it.speaking }.toDouble() * hopSeconds
        if (segments != null) {
            speechStart = segments.firstOrNull()?.start ?: 0.0
            speechEnd = min(duration, segments.lastOrNull()?.end ?: 0.0)
            pauses = segments.zipWithNext()
                .filter { (previous, next) -> next.start - previous.end >= thresholds.analysis.minPauseSeconds }
                .map { (previous, next) -> TimeSpan(start = previous.end, end = next.start) }
            speechSeconds = segments.fold(0.0) { sum, segment -> sum + segment.duration }
        }
        fun inSpeech(time: Double): Boolean = segments?.any { time >= it.start && time <= it.end } ?: true

        val points: List<PitchPoint>
        val pointStep: Double
        val voicedShare: Float
        val f0 = evidence?.f0
        if (f0 != null) {
            points = f0.hz.indices.mapNotNull { i ->
                val time = (i.toDouble() + 0.5) * f0.hopSeconds
                if (f0.confidence[i] >= 0.5f && inSpeech(time)) PitchPoint(time, f0.hz[i]) else null
            }
            pointStep = f0.hopSeconds
            voicedShare = (points.size.toDouble() * f0.hopSeconds / max(0.01, speechSeconds)).toFloat()
        } else {
            points = frames.filter { it.speaking && it.pitchHz > 0f }.map { PitchPoint(it.time, it.pitchHz) }
            pointStep = hopSeconds
            val spoken = frames.count { it.speaking }
            voicedShare = if (spoken > 0) points.size.toFloat() / spoken.toFloat() else 0f
        }
        val pitchStats = pitchStatistics(points)
        val hesitations = findHesitations(
            points = points, pointStep = pointStep, pauses = pauses, speechStart = speechStart,
            syllables = energySyllables, fricatives = fricatives,
        )

        val span = max(0.001, speechEnd - speechStart)
        val phonation = max(
            0.001,
            if (segments != null) speechSeconds else span - pauses.fold(0.0) { sum, pause -> sum + pause.duration },
        )
        val syllables = if (segments != null) energySyllables.filter { inSpeech(it) } else energySyllables

        val spoken = frames.filter { it.speaking }.map { it.levelDb }.toFloatArray()
        val meanDb = if (spoken.isEmpty()) -100f else mean(spoken)

        return ClipSummary(
            durationSeconds = duration,
            sampleRate = sampleRate,
            floorDb = floorDb,
            peakDb = peakDb,
            speechStart = speechStart,
            speechEnd = speechEnd,
            fricatives = fricatives,
            trills = trills,
            taps = taps,
            syllables = syllables,
            pauses = pauses,
            hesitations = hesitations,
            pitch = PitchStatistics(
                medianHz = pitchStats.medianHz, sdSt = pitchStats.sdSt, rangeSt = pitchStats.rangeSt, voicedShare = voicedShare,
            ),
            loudness = LoudnessStatistics(meanDb = meanDb, sdDb = standardDeviation(spoken)),
            articulationRate = (syllables.size.toDouble() / phonation).toFloat(),
            speechRate = (syllables.size.toDouble() / span).toFloat(),
            pvq = pitchStats.pvq,
            finalSlopeSt = pitchStats.finalSlopeSt,
            speechSeconds = speechSeconds,
            track = makeTrack(fricatives, evidence),
            trackHopSeconds = hopSeconds,
            quality = captureQuality(speechSeconds, bandwidth),
            norm = norm,
            usedSpeechModel = segments != null,
            usedPitchModel = evidence?.f0 != null,
        )
    }

    private fun pitchStatistics(points: List<PitchPoint>): PitchSummary {
        if (points.size < thresholds.pitch.minFramesForStats) return PitchSummary(0f, 0f, 0f, 0f, 0f)
        val hz = FloatArray(points.size) { points[it].hz }
        val centre = percentile(hz.sortedArray(), 0.5f)
        val st = FloatArray(hz.size) { semitones(hz[it], centre) }
        val sortedSt = st.sortedArray()
        val average = mean(hz)
        val tail = points.subList((points.size.toDouble() * 0.66).toInt(), points.size)
        var slope = 0f
        if (tail.size >= 4) {
            val firstTime = tail.first().time
            val lastTime = tail.last().time
            val xs = FloatArray(tail.size) { tail[it].time.toFloat() }
            val ys = FloatArray(tail.size) { semitones(tail[it].hz, centre) }
            val meanX = mean(xs)
            val meanY = mean(ys)
            var numerator = 0f
            var denominator = 0f
            for (k in xs.indices) {
                numerator += (xs[k] - meanX) * (ys[k] - meanY)
                denominator += (xs[k] - meanX) * (xs[k] - meanX)
            }
            slope = (if (denominator > 0f) numerator / denominator else 0f) * (lastTime - firstTime).toFloat()
        }
        return PitchSummary(
            medianHz = centre,
            sdSt = standardDeviation(st),
            rangeSt = percentile(sortedSt, 0.95f) - percentile(sortedSt, 0.05f),
            pvq = if (average > 0f) standardDeviation(hz) / average else 0f,
            finalSlopeSt = slope,
        )
    }

    private fun findHesitations(
        points: List<PitchPoint>, pointStep: Double, pauses: List<TimeSpan>, speechStart: Double,
        syllables: List<Double>, fricatives: List<FricativeSegment>,
    ): List<TimeSpan> {
        val settings = thresholds.hesitation
        val result = ArrayList<TimeSpan>()
        val run = ArrayList<PitchPoint>()
        fun flush() {
            try {
                if (run.size < 2) return
                val first = run.first()
                val last = run.last()
                val length = last.time - first.time
                if (!(length >= settings.minSeconds && length <= settings.maxSeconds)) return
                val average = mean(FloatArray(run.size) { run[it].hz })
                var sum = 0f
                for (point in run) {
                    val st = semitones(point.hz, average)
                    sum += st * st
                }
                val spread = sqrt(sum / run.size.toFloat())
                val nearPause = pauses.any {
                    abs(it.end - first.time) < settings.nearPauseSeconds || abs(it.start - last.time) < settings.nearPauseSeconds
                } || first.time - speechStart < settings.nearPauseSeconds
                val nuclei = syllables.count { it > first.time && it < last.time }
                val hissFree = fricatives.none { it.end > first.time && it.start < last.time }
                if (spread < settings.maxPitchSdSt && nearPause && nuclei <= settings.maxSyllables && hissFree) {
                    result.add(TimeSpan(start = first.time, end = last.time))
                }
            } finally {
                run.clear()
            }
        }
        for (point in points) {
            val previous = run.lastOrNull()
            if (previous != null && point.time - previous.time > pointStep * 2.5) flush()
            run.add(point)
        }
        flush()
        return result
    }

    private fun makeTrack(fricatives: List<FricativeSegment>, evidence: SpeechEvidence?): List<TrackPoint> {
        var next = 0
        val f0 = evidence?.f0
        return frames.map { frame ->
            while (next < fricatives.size && fricatives[next].end < frame.time) next += 1
            val place = if (next < fricatives.size && fricatives[next].start <= frame.time) fricatives[next].place else null
            var strict = frame.pitchHz
            var melody = if (frame.speaking) frame.melodyHz else 0f
            if (f0 != null) {
                val index = (frame.time / f0.hopSeconds - 0.5).rounded().toInt()
                val trusted = index in f0.hz.indices && f0.confidence[index] >= 0.5f
                strict = if (trusted) f0.hz[index] else 0f
                melody = if (frame.speaking) strict else 0f
            }
            TrackPoint(
                time = frame.time, db = frame.levelDb, vowelDb = frame.vowelDb, hz = strict, melodyHz = melody,
                hfShare = frame.features.hfShare, fricative = place, speaking = frame.speaking,
            )
        }
    }

    private fun captureQuality(speechSeconds: Double, bandwidth: Float): CaptureQuality {
        val settings = thresholds.capture
        val clipped = clippedSampleCount(pcm, settings)
        val clippedFraction = if (pcm.isEmpty()) 0f else clipped.toFloat() / pcm.size.toFloat()
        val peak = maxmgv(pcm, 0, pcm.size)
        val rms = speechRms()
        val range = peakDb - floorDb
        val sibilant = thresholds.sibilant
        return CaptureQuality(
            isClipped = clippedFraction > settings.maxClippedFraction,
            isTooQuiet = rms < settings.minSpeechRmsDb || range < settings.minDynamicRangeDb,
            // Excludes the hangover, so a click does not borrow length from it.
            isTooShort = activeFrames.toDouble() * hopSeconds < settings.minSpeechSeconds,
            clippedFraction = clippedFraction,
            peakAmplitude = peak,
            speechRmsDb = rms,
            dynamicRangeDb = range,
            speechSeconds = speechSeconds,
            sampleRate = sampleRate,
            bandwidthHz = bandwidth,
            canJudgeSibilants = sampleRate >= sibilant.minSampleRateHz && bandwidth >= sibilant.minBandwidthHz,
        )
    }

    private fun speechRms(): Float {
        if (firstSpeech < 0 || pcm.isEmpty()) return -120f
        val first = max(0, (frames[firstSpeech].time * sampleRate).toInt())
        val last = min(pcm.size, (frames[lastSpeech].time * sampleRate).toInt())
        if (last <= first) return -120f
        val value = rmsqv(pcm, first, last - first)
        return 20f * log10(value + 1e-9f)
    }

    private fun emptySummary(): ClipSummary {
        val peak = if (pcm.isNotEmpty()) maxmgv(pcm, 0, pcm.size) else 0f
        return ClipSummary(
            durationSeconds = duration, sampleRate = sampleRate, floorDb = -100f, peakDb = -100f,
            speechStart = 0.0, speechEnd = 0.0, fricatives = emptyList(), trills = emptyList(), taps = emptyList(),
            syllables = emptyList(), pauses = emptyList(), hesitations = emptyList(),
            pitch = PitchStatistics(medianHz = 0f, sdSt = 0f, rangeSt = 0f, voicedShare = 0f),
            loudness = LoudnessStatistics(meanDb = -100f, sdDb = 0f),
            articulationRate = 0f, speechRate = 0f, pvq = 0f, finalSlopeSt = 0f, speechSeconds = 0.0,
            track = emptyList(), trackHopSeconds = hopSeconds,
            quality = CaptureQuality(
                isClipped = false, isTooQuiet = peak < 1e-4f, isTooShort = true, clippedFraction = 0f, peakAmplitude = peak,
                speechRmsDb = -120f, dynamicRangeDb = 0f, speechSeconds = 0.0, sampleRate = sampleRate, bandwidthHz = 0f,
                canJudgeSibilants = false,
            ),
            norm = norm, usedSpeechModel = false, usedPitchModel = false,
        )
    }
}
