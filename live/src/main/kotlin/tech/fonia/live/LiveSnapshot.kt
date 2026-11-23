package tech.fonia.live

/**
 * One frame of the continuous band (docs/v2/02_ARHITECTURA.md §3.1). Mutable on purpose: [LiveBoard] keeps three
 * preallocated instances and the analysis thread fills one per hop, so nothing is allocated at 100 Hz.
 *
 * Read it only inside a draw-phase lambda, once per frame, from [LiveBoard.latest]. Never put it in a `Flow`, a
 * `State` or a composable parameter, and never derive a verdict or a step change from it: the band drops frames;
 * decisions come from the session reducer.
 */
class LiveSnapshot {
    /** Grows by one on every publish. Two reads with the same `seq` saw the same frame. */
    @JvmField var seq = 0L

    /** CLOCK_MONOTONIC (`System.nanoTime`) at which the newest analysed sample was captured. 0 before the first hop. */
    @JvmField var captureNanos = 0L

    /** CLOCK_MONOTONIC when this frame was published. `publishNanos - captureNanos` is mic → board. */
    @JvmField var publishNanos = 0L

    /** 0..1 above the room floor, RMS of the last hop only (the fast path, 10 ms). */
    @JvmField var level = 0f

    /** 0..1 above the room floor over the long spectral window (43 ms); steadier than [level]. */
    @JvmField var levelSlow = 0f

    /** Linear RMS of the last hop. */
    @JvmField var rms = 0f

    /** Voice activity with the analyser's hang time (≈ 220 ms). */
    @JvmField var speaking = false

    /** 0..1 pitch clarity. */
    @JvmField var voicing = 0f

    /** Pitch in Hz, 0 when unvoiced or unclear. */
    @JvmField var pitchHz = 0f

    /** 0..1 strength of the friction (hiss). */
    @JvmField var sibilance = 0f

    /** A fricative is present in this frame. */
    @JvmField var fricative = false

    @JvmField var fricativeVoiced = false

    /** 0 = Ș … 1 = S; 0.5 when there is no friction. */
    @JvmField var sNess = 0.5f

    /**
     * Approximate dominant frequency of the friction, from the zero-crossing rate of the last hop; 0 without friction.
     * Only a display hint: the S/Ș decision is [sNess], from the spectral window.
     */
    @JvmField var centroidHz = 0f

    /** 0..1 trill strength (R). */
    @JvmField var trill = 0f

    @JvmField var trillDetected = false

    /** 0..1 blowing strength. */
    @JvmField var blow = 0f

    /** 0..1 how close the sound is to the step's target ([LiveTarget]); 0 when nothing is measured. */
    @JvmField var onTarget = 0f

    /** This hop counts as holding the target (what fills [holdSeconds]). */
    @JvmField var onTargetNow = false

    /** [LiveFaults] code, or [LiveFaults.NONE]. */
    @JvmField var fault: Byte = LiveFaults.NONE

    /** Continuous "good" time ([HoldTracker]). */
    @JvmField var holdSeconds = 0f

    /** Longest hold of this take. */
    @JvmField var bestHoldSeconds = 0f

    /** 0..1, drives the letter's tremble: 1 when the hiss sits right between S and Ș. */
    @JvmField var uncertainty = 0f

    /** [CaptureQualityCode]. */
    @JvmField var captureQuality: Byte = CaptureQualityCode.OK

    /** Samples lost so far in this take (ring overrun or route change). */
    @JvmField var dropped = 0

    /** Time the analysis of this hop took, in microseconds. */
    @JvmField var analysisMicros = 0f

    // Mouth fields (decision N4). The face engine runs at ~30 Hz, the band at 100 Hz, so these are the last value
    // held: they change only when a new face frame arrives. [mouthNanos] tells how old they are (0 = no face yet).

    /** 0..1 jaw/mouth opening. */
    @JvmField var mouthOpen = 0f

    /** 0..1 lip rounding. */
    @JvmField var lipRound = 0f

    /** CLOCK_MONOTONIC of the face frame the mouth fields come from; 0 when there is none. */
    @JvmField var mouthNanos = 0L

    fun copyFrom(other: LiveSnapshot): LiveSnapshot {
        seq = other.seq
        captureNanos = other.captureNanos
        publishNanos = other.publishNanos
        level = other.level
        levelSlow = other.levelSlow
        rms = other.rms
        speaking = other.speaking
        voicing = other.voicing
        pitchHz = other.pitchHz
        sibilance = other.sibilance
        fricative = other.fricative
        fricativeVoiced = other.fricativeVoiced
        sNess = other.sNess
        centroidHz = other.centroidHz
        trill = other.trill
        trillDetected = other.trillDetected
        blow = other.blow
        onTarget = other.onTarget
        onTargetNow = other.onTargetNow
        fault = other.fault
        holdSeconds = other.holdSeconds
        bestHoldSeconds = other.bestHoldSeconds
        uncertainty = other.uncertainty
        captureQuality = other.captureQuality
        dropped = other.dropped
        analysisMicros = other.analysisMicros
        mouthOpen = other.mouthOpen
        lipRound = other.lipRound
        mouthNanos = other.mouthNanos
        return this
    }

    /** Back to the resting values (silence). Keeps [seq] and the mouth fields. */
    fun clearAudio() {
        captureNanos = 0L
        publishNanos = 0L
        level = 0f
        levelSlow = 0f
        rms = 0f
        speaking = false
        voicing = 0f
        pitchHz = 0f
        sibilance = 0f
        fricative = false
        fricativeVoiced = false
        sNess = 0.5f
        centroidHz = 0f
        trill = 0f
        trillDetected = false
        blow = 0f
        onTarget = 0f
        onTargetNow = false
        fault = LiveFaults.NONE
        holdSeconds = 0f
        bestHoldSeconds = 0f
        uncertainty = 0f
        captureQuality = CaptureQualityCode.OK
        dropped = 0
        analysisMicros = 0f
    }

    override fun toString(): String =
        "LiveSnapshot(seq=$seq, level=$level, speaking=$speaking, sNess=$sNess, sibilance=$sibilance, " +
            "pitchHz=$pitchHz, trill=$trill, blow=$blow, hold=$holdSeconds, fault=$fault, quality=$captureQuality)"
}

/** Values of [LiveSnapshot.fault]. Same order as `tech.fonia.dsp.LiveFault` (checked by a test). */
object LiveFaults {
    const val NONE: Byte = -1
    const val OTHER_PLACE: Byte = 0
    const val UNVOICED: Byte = 1
    const val NO_TRILL: Byte = 2
    const val S_LIKE: Byte = 3
    const val NO_SOUND: Byte = 4

    /** The raw names used by `spec/` and iOS (`otherPlace`, `unvoiced`, …), or null for [NONE]. */
    fun rawName(code: Byte): String? = when (code) {
        OTHER_PLACE -> "otherPlace"
        UNVOICED -> "unvoiced"
        NO_TRILL -> "noTrill"
        S_LIKE -> "sLike"
        NO_SOUND -> "noSound"
        else -> null
    }
}

/** Values of [LiveSnapshot.captureQuality]. */
object CaptureQualityCode {
    const val OK: Byte = 0
    const val TOO_QUIET: Byte = 1
    const val CLIPPING: Byte = 2
    const val NOISY: Byte = 3

    /** Bluetooth microphone or a rate under 32 kHz: S/Ș cannot be judged. */
    const val NARROW_BAND: Byte = 4
}
