package tech.fonia.live

import tech.fonia.dsp.DSPThresholds
import tech.fonia.dsp.LiveAnalyzer
import tech.fonia.dsp.LiveFrame
import tech.fonia.dsp.LiveReading
import tech.fonia.dsp.SibilantPlace
import tech.fonia.dsp.TalkerCalibration
import tech.fonia.dsp.UtteranceTracker
import tech.fonia.dsp.VoiceGroup
import tech.fonia.dsp.sibilantReading
import tech.fonia.dsp.trillReading
import tech.fonia.dsp.voiceReading
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** One utterance ended (speech, then quiet). Sent on the analysis thread; keep the handler short (`trySend`). */
fun interface LiveEvents {
    /** [fault] is a [LiveFaults] code ([LiveFaults.NONE] when [ok]). */
    fun onUtterance(ok: Boolean, fault: Byte, seconds: Double, captureNanos: Long)
}

/**
 * The continuous band's writer: a hop processor that runs `LiveAnalyzer` (spectral shape, pitch, friction, trill,
 * blow), a fast 10 ms level, the [HoldTracker] and the `UtteranceTracker`, and publishes one [LiveSnapshot] per hop
 * to [board].
 *
 * Threading: [start], [process] and [stop] belong to ONE thread (the analysis thread). [setTarget], [postMouth],
 * [dropped] and [narrowBand] may be called from anywhere; they take effect on the next hop. Nothing is allocated per
 * hop except the small `LiveReading` the dsp readings return.
 */
class LivePipeline(
    val board: LiveBoard = LiveBoard(),
    val config: LiveConfig = LiveConfig(),
    private val clock: NanoClock = NanoClock.System,
    thresholds: DSPThresholds,
    private val calibration: TalkerCalibration?,
) {
    constructor(board: LiveBoard = LiveBoard(), config: LiveConfig = LiveConfig(), clock: NanoClock = NanoClock.System) :
        this(board, config, clock, DSPThresholds.v1, null)

    private val thresholds: DSPThresholds =
        thresholds.copy(live = thresholds.live.copy(analysisHopSeconds = config.analysisHopSeconds))
    private val group: VoiceGroup = when (config.talker) {
        Talker.Child -> VoiceGroup.CHILD
        Talker.Female -> VoiceGroup.FEMALE
        Talker.Male -> VoiceGroup.MALE
    }

    /** Analysis timing and mic → board latency of recent hops. */
    val stats = LiveStats(config.statsWindow)

    /** Utterance verdicts, on the analysis thread. */
    @Volatile var events: LiveEvents? = null

    /** Samples the source lost so far; copied into each snapshot. */
    @Volatile var dropped = 0

    /** Set when the route is a Bluetooth microphone: S/Ș cannot be judged. */
    @Volatile var narrowBand = false

    @Volatile private var pendingTarget: LiveTarget? = null
    private var target: LiveTarget = LiveTarget.AnySound

    private var sampleRate = 0.0
    private var analyzer: LiveAnalyzer? = null
    private val frame = LiveFrame.silent
    private val hold = HoldTracker(config.holdGraceSeconds, config.holdResetsOnBreak)
    private var utterances = UtteranceTracker(this.thresholds)
    private val current = LiveSnapshot()
    private var seq = 0L
    private var clipUntilSeconds = -1.0
    private var takeSeconds = 0.0

    // Mouth (N4): two floats packed in one long so a reader never mixes two face frames.
    private val mouth = AtomicLong(0L)
    @Volatile private var mouthNanos = 0L

    /** The frame most recently published, as the writer sees it. Analysis thread only (UI threads use [board]). */
    val lastWritten: LiveSnapshot get() = current

    /** The rate the pipeline is tuned to, 0 before [start]. */
    val currentSampleRate: Double get() = sampleRate

    /**
     * Prepares a take at [rate] for [target]. Re-creates the analyser only when the rate changed; trackers, room floor
     * and hold restart. Analysis thread.
     */
    fun start(rate: Double, target: LiveTarget) {
        require(rate > 0) { "Sample rate must be positive, was $rate" }
        val existing = analyzer
        if (existing == null || rate != sampleRate) {
            analyzer = LiveAnalyzer(rate, group, calibration, thresholds)
            sampleRate = rate
        } else {
            existing.reset()
        }
        this.target = target
        pendingTarget = null
        hold.reset()
        utterances.reset()
        clipUntilSeconds = -1.0
        takeSeconds = 0.0
        dropped = 0
        current.clearAudio()
        frame.set(LiveFrame.silent)
    }

    /** Changes the target from any thread; applied on the next hop. The hold restarts. */
    fun setTarget(target: LiveTarget) {
        pendingTarget = target
    }

    /** Face engine output (≈ 30 Hz), any thread. Shown as "last value held" in every snapshot (N4). */
    fun postMouth(open: Float, round: Float, nanos: Long) {
        mouth.set((open.toRawBits().toLong() shl 32) or (round.toRawBits().toLong() and 0xFFFF_FFFFL))
        mouthNanos = nanos
    }

    /**
     * Analyses one hop (`samples[offset until offset + count]`, normally 10 ms) and publishes a snapshot.
     * [captureNanos] is the CLOCK_MONOTONIC capture time of the hop's LAST sample (0 = unknown: the clock is used).
     */
    fun process(samples: FloatArray, offset: Int, count: Int, captureNanos: Long) {
        val analyzer = analyzer ?: error("LivePipeline.start was not called")
        if (count <= 0) return
        val began = clock.nanos()
        pendingTarget?.let {
            target = it
            pendingTarget = null
            hold.reset()
            utterances.reset()
        }
        val dt = count / sampleRate
        takeSeconds += dt
        analyzer.process(samples, offset, count, frame)

        // Fast path: the level of this hop alone, against the analyser's room floor.
        var sum = 0f
        var run = 0
        var clipped = false
        var crossings = 0
        var previous = samples[offset]
        for (i in offset until offset + count) {
            val x = samples[i]
            sum += x * x
            if (abs(x) >= config.clipLevel) {
                run += 1
                if (run >= config.clipRunSamples) clipped = true
            } else {
                run = 0
            }
            if ((x >= 0f) != (previous >= 0f)) crossings += 1
            previous = x
        }
        val meanSquare = sum / count
        val hopDb = 10f * log10(meanSquare + 1e-12f)
        val levelRange = thresholds.live.levelRangeDb
        if (clipped) clipUntilSeconds = takeSeconds + config.clipHoldSeconds

        val reading = reading(frame)
        val active = isHolding(frame, reading, clamp01((hopDb - frame.floorDb) / levelRange))
        hold.push(active, dt.toDouble())
        val verdict = utterances.push(reading, frame.speaking, dt.toDouble())

        val s = current
        s.seq = ++seq
        s.captureNanos = if (captureNanos != 0L) captureNanos else began
        s.level = clamp01((hopDb - frame.floorDb) / levelRange)
        s.levelSlow = frame.level
        s.rms = sqrt(max(0f, meanSquare))
        s.speaking = frame.speaking
        s.voicing = frame.clarity
        s.pitchHz = frame.pitchHz
        s.sibilance = frame.fricativeStrength
        s.fricative = frame.fricativePresent
        s.fricativeVoiced = frame.fricativeVoiced
        s.sNess = frame.sNess
        s.centroidHz = if (frame.fricativePresent) (crossings * sampleRate / (2.0 * count)).toFloat() else 0f
        s.trill = frame.trillStrength
        s.trillDetected = frame.trillDetected
        s.blow = frame.blowStrength
        s.onTarget = reading.value ?: 0f
        s.onTargetNow = active
        s.fault = reading.fault?.ordinal?.toByte() ?: LiveFaults.NONE
        s.holdSeconds = hold.seconds.toFloat()
        s.bestHoldSeconds = hold.bestSeconds.toFloat()
        s.uncertainty = if (target is LiveTarget.Sibilant && frame.fricativePresent) {
            clamp01(1f - abs(2f * frame.sNess - 1f))
        } else {
            0f
        }
        s.captureQuality = when {
            narrowBand || sampleRate < thresholds.sibilant.minSampleRateHz -> CaptureQualityCode.NARROW_BAND
            takeSeconds <= clipUntilSeconds -> CaptureQualityCode.CLIPPING
            else -> CaptureQualityCode.OK
        }
        s.dropped = dropped
        val packed = mouth.get()
        s.mouthOpen = Float.fromBits((packed ushr 32).toInt())
        s.lipRound = Float.fromBits(packed.toInt())
        s.mouthNanos = mouthNanos

        val published = clock.nanos()
        s.analysisMicros = (published - began) / 1000f
        s.publishNanos = published
        board.back().copyFrom(s)
        board.publish()
        stats.record(published - began, published - s.captureNanos)

        if (verdict != null) {
            events?.onUtterance(
                verdict.ok,
                verdict.fault?.ordinal?.toByte() ?: LiveFaults.NONE,
                verdict.seconds,
                s.captureNanos,
            )
        }
    }

    /** Publishes a silent frame (mic closed) so the letter relaxes. Analysis thread, or any thread once it stopped. */
    fun stop() {
        current.clearAudio()
        current.seq = ++seq
        val packed = mouth.get()
        current.mouthOpen = Float.fromBits((packed ushr 32).toInt())
        current.lipRound = Float.fromBits(packed.toInt())
        current.mouthNanos = mouthNanos
        current.publishNanos = clock.nanos()
        board.back().copyFrom(current)
        board.publish()
    }

    private fun reading(frame: LiveFrame): LiveReading = when (val t = target) {
        is LiveTarget.Sibilant -> sibilantReading(
            frame,
            if (t.side == SibilantSide.S) SibilantPlace.S else SibilantPlace.SH,
            t.voiced,
            thresholds,
        )
        LiveTarget.Trill -> trillReading(frame, thresholds)
        LiveTarget.Voice -> voiceReading(frame, thresholds)
        LiveTarget.Blow -> if (!frame.speaking && frame.blowStrength <= 0f) {
            NOTHING
        } else {
            val ok = frame.blowStrength > config.blowHold
            if (ok) BLOW_OK else LiveReading(value = frame.blowStrength, ok = false)
        }
        LiveTarget.AnySound -> if (frame.speaking) SPEAKING else NOTHING
    }

    private fun isHolding(frame: LiveFrame, reading: LiveReading, fastLevel: Float): Boolean = when (target) {
        is LiveTarget.Sibilant -> reading.ok
        LiveTarget.Trill -> frame.speaking && (frame.trillDetected || frame.trillStrength > config.trillHold)
        LiveTarget.Voice -> reading.ok
        LiveTarget.Blow -> frame.blowStrength > config.blowHold
        LiveTarget.AnySound -> fastLevel > config.levelHold
    }

    private companion object {
        val NOTHING = LiveReading(value = null, ok = false)
        val SPEAKING = LiveReading(value = 1f, ok = true)
        val BLOW_OK = LiveReading(value = 1f, ok = true)

        fun clamp01(x: Float): Float = min(1f, max(0f, x))
    }
}

/**
 * Percentiles of the last N hops: analysis time (budget ≤ 3 ms, p99 ≤ 5 ms) and capture → publish latency.
 * Written by the analysis thread; [summary] may be read from any thread (debug overlay; it allocates).
 */
class LiveStats(window: Int) {
    private val analysis = LongArray(max(1, window))
    private val latency = LongArray(max(1, window))
    @Volatile private var count = 0L

    internal fun record(analysisNanos: Long, latencyNanos: Long) {
        val i = (count % analysis.size).toInt()
        analysis[i] = analysisNanos
        latency[i] = latencyNanos
        count += 1
    }

    data class Summary(
        val hops: Long,
        val analysisP50Ms: Double,
        val analysisP95Ms: Double,
        val analysisP99Ms: Double,
        val analysisMaxMs: Double,
        val publishLatencyP50Ms: Double,
        val publishLatencyP95Ms: Double,
    )

    fun summary(): Summary {
        val n = min(count, analysis.size.toLong()).toInt()
        if (n == 0) return Summary(0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        val a = analysis.copyOf(n).also { it.sort() }
        val l = latency.copyOf(n).also { it.sort() }
        fun p(values: LongArray, q: Double): Double = values[min(values.size - 1, (q * values.size).toInt())] / 1e6
        return Summary(count, p(a, 0.5), p(a, 0.95), p(a, 0.99), a.last() / 1e6, p(l, 0.5), p(l, 0.95))
    }
}
