package tech.fonia.dsp

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * One live reading. Mutable, unlike the Swift struct: `LiveAnalyzer.process(…, into)` fills a
 * caller's instance so the audio path allocates nothing. `process` without `into` returns a copy,
 * which is what the Swift value return means.
 */
data class LiveFrame(
    /** 0...1, in dB above the room floor. */
    var level: Float,
    /** Linear RMS of the latest analysis frame. */
    var rms: Float,
    var floorDb: Float,
    var speaking: Boolean,
    /** 0 when unvoiced. */
    var pitchHz: Float,
    var clarity: Float,
    var fricativePresent: Boolean,
    var fricativeVoiced: Boolean,
    var fricativeDiffuse: Boolean,
    /** 0 Ș-like ... 1 S-like; 0.5 when there is no frication. */
    var sNess: Float,
    var fricativeStrength: Float,
    var trillDetected: Boolean,
    var trillStrength: Float,
    var blowStrength: Float,
    /** Spectral flatness of the hiss frame (0 without a hiss): a sibilant has a peak, broadband noise is flat. */
    var hissFlatness: Float = 0f,
    /** Share of the frame's energy in the low band (0 without a hiss): breath and machines carry more than a sibilant. */
    var hissLfShare: Float = 0f,
) {
    /** Copies every field of `other` into this instance. */
    fun set(other: LiveFrame): LiveFrame {
        level = other.level
        rms = other.rms
        floorDb = other.floorDb
        speaking = other.speaking
        pitchHz = other.pitchHz
        clarity = other.clarity
        fricativePresent = other.fricativePresent
        fricativeVoiced = other.fricativeVoiced
        fricativeDiffuse = other.fricativeDiffuse
        sNess = other.sNess
        fricativeStrength = other.fricativeStrength
        trillDetected = other.trillDetected
        trillStrength = other.trillStrength
        blowStrength = other.blowStrength
        hissFlatness = other.hissFlatness
        hissLfShare = other.hissLfShare
        return this
    }

    companion object {
        /** A new instance each time, so no caller can change the shared value. */
        val silent: LiveFrame
            get() = LiveFrame(
                level = 0f, rms = 0f, floorDb = -90f, speaking = false, pitchHz = 0f, clarity = 0f,
                fricativePresent = false, fricativeVoiced = false, fricativeDiffuse = false, sNess = 0.5f,
                fricativeStrength = 0f, trillDetected = false, trillStrength = 0f, blowStrength = 0f,
            )
    }
}

/**
 * Streaming analysis for the microphone. Single owner: call it from one context only. Scratch is
 * allocated in `init`, so `process(…, into)` allocates nothing and can keep up with short hops.
 */
class LiveAnalyzer(
    private val sampleRate: Double,
    group: VoiceGroup,
    calibration: TalkerCalibration?,
    private val thresholds: DSPThresholds = DSPThresholds.v1,
) {
    private val norm: SibilantNorm = (calibration ?: TalkerCalibration()).norm(group, thresholds)
    private val frameLength: Int = powerOfTwoFrame(thresholds.analysis.frameSeconds, sampleRate)
    private val analysisHop: Int = max(1, (thresholds.live.analysisHopSeconds * sampleRate).rounded().toInt())
    private val judgesSibilants: Boolean = sampleRate >= thresholds.sibilant.minSampleRateHz

    private val ring = MirrorRing(frameLength)
    private val spectrum = FrameSpectrum(frameLength)
    private val extractor = FeatureExtractor(spectrum.binCount, sampleRate.toFloat() / frameLength.toFloat(), thresholds.bands)
    private val pitch = YINPitchTracker(sampleRate, thresholds.pitch)
    private val pitchOffset: Int = max(0, frameLength - pitch.requiredInputCount)
    private val envelope = EnvelopeTracker(sampleRate, thresholds.envelope)
    private val trill = TrillDetector(envelope.rate, thresholds.trill)
    private val envelopeRing = MirrorRing(max(1, trill.windowCount))

    private var floor = NoiseFloor(thresholds.live.initialFloorDb, thresholds.live)
    private var hfFloor = NoiseFloor(thresholds.live.initialHfFloorDb, thresholds.live)
    private var voice = VoiceActivity(thresholds.live.vadMarginDb, thresholds.live.vadHangSeconds)
    private var samplesSinceAnalysis = 0
    private var totalSamples = 0
    private val latest = LiveFrame.silent

    // Scratch for the analysis, reused so nothing is allocated per hop. Swift returns these as values.
    private val features = FrameFeatures.silent
    private val estimate = PitchEstimate.none
    private val frication = FricationFrame()
    private val trillResult = TrillResult.none
    private val envelopeSink = FloatSink { envelopeRing.append(it) }

    /**
     * Absorbs one hop and returns the current reading. Hops shorter than `analysisHopSeconds` update
     * the buffers and return the previous reading, so a small hop cannot overrun the CPU budget.
     * Returns a new `LiveFrame`; use the `into` overload on the audio path.
     */
    fun process(samples: FloatArray, offset: Int = 0, count: Int = samples.size - offset): LiveFrame =
        process(samples, offset, count, LiveFrame.silent)

    /** Same as `process`, writing the reading into `into` (and returning it). Allocates nothing. */
    fun process(samples: FloatArray, offset: Int, count: Int, into: LiveFrame): LiveFrame {
        if (count <= 0) return into.set(latest)
        ring.append(samples, offset, count)
        envelope.push(samples, offset, count, envelopeSink)
        samplesSinceAnalysis += count
        totalSamples += count
        if (samplesSinceAnalysis < analysisHop) return into.set(latest)
        analyze((samplesSinceAnalysis.toDouble() / sampleRate).toFloat())
        samplesSinceAnalysis = 0
        return into.set(latest)
    }

    fun reset() {
        ring.reset()
        envelope.reset()
        envelopeRing.reset()
        floor = NoiseFloor(thresholds.live.initialFloorDb, thresholds.live)
        hfFloor = NoiseFloor(thresholds.live.initialHfFloorDb, thresholds.live)
        voice = VoiceActivity(thresholds.live.vadMarginDb, thresholds.live.vadHangSeconds)
        samplesSinceAnalysis = 0
        totalSamples = 0
        latest.set(LiveFrame.silent)
    }

    private fun analyze(seconds: Float) {
        val settings = thresholds.live
        val frame = ring.storageArray
        val start = ring.latestOffset
        val energy = svesq(frame, start, frameLength)
        val meanSquare = energy / frameLength.toFloat()
        val rmsDb = 10f * log10(meanSquare + 1e-12f)

        spectrum.compute(frame, start)
        extractor.extract(spectrum.power, features)
        val floorDb = floor.update(rmsDb, seconds)
        val hfFloorDb = hfFloor.update(features.hfDb, seconds)
        val speaking = voice.update(rmsDb, floorDb, totalSamples.toDouble() / sampleRate)

        var pitchHz = 0f
        var clarity = 0f
        if (speaking) {
            pitch.estimate(frame, start + pitchOffset, estimate)
            clarity = estimate.clarity
            if (clarity > settings.pitchClarity && estimate.hz > thresholds.pitch.minHz && estimate.hz < thresholds.pitch.maxHz) {
                pitchHz = estimate.hz
            }
        }

        if (speaking && judgesSibilants) {
            classifyFrication(features, hfFloorDb, clarity, norm, thresholds.fricative, frication)
        } else {
            frication.setNone()
        }

        val creaky = pitchHz > 0f && pitchHz < thresholds.trill.creakyBelowHz
        if (speaking && !creaky && !frication.present && totalSamples >= minTrillSamples) {
            trill.detect(envelopeRing.storageArray, envelopeRing.latestOffset, trillResult)
        } else {
            trillResult.setNone()
        }

        val above = rmsDb - floorDb
        val aperiodic = if (!speaking) {
            0f
        } else if (clarity < settings.blowAperiodicLowClarity) {
            1f
        } else if (clarity < settings.blowAperiodicMidClarity) {
            settings.blowAperiodicMid
        } else {
            settings.blowAperiodicHigh
        }
        val airBlow = clamp01((above - settings.blowAboveFloorDb) / settings.blowRangeDb) * aperiodic
        val hissBlow = if (frication.present && !frication.voiced) {
            settings.blowFricativeBase + frication.strength * (1f - settings.blowFricativeBase)
        } else {
            0f
        }

        latest.level = clamp01(above / settings.levelRangeDb)
        latest.rms = sqrt(max(0f, meanSquare))
        latest.floorDb = floorDb
        latest.speaking = speaking
        latest.pitchHz = pitchHz
        latest.clarity = clarity
        latest.fricativePresent = frication.present
        latest.fricativeVoiced = frication.voiced
        latest.fricativeDiffuse = frication.diffuse
        latest.sNess = frication.sNess
        latest.fricativeStrength = frication.strength
        latest.trillDetected = trillResult.isTrill
        latest.trillStrength = trillResult.strength
        latest.blowStrength = max(airBlow, hissBlow)
        latest.hissFlatness = if (frication.present) features.flatness else 0f
        latest.hissLfShare = if (frication.present) features.lfShare else 0f
    }

    private val minTrillSamples: Int get() = trill.windowCount * envelope.hop
}
