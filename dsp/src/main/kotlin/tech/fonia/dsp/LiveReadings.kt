package tech.fonia.dsp

/** What went wrong in a live reading, for the coach's fixes. */
enum class LiveFault(val rawValue: String) {
    OTHER_PLACE("otherPlace"),
    UNVOICED("unvoiced"),
    NO_TRILL("noTrill"),
    S_LIKE("sLike"),
    NO_SOUND("noSound"),
}

/** One microphone frame mapped onto a meter whose target zone starts at `DSPThresholds.Live.meterZone`. */
data class LiveReading(
    /** 0...1, or `null` when nothing is being measured. */
    val value: Float?,
    val ok: Boolean,
    val fault: LiveFault? = null,
)

private fun toZone(metric: Float, target: Float, zone: Float): Float = clamp01(zone * metric / target)

private fun inZone(k: Float, zone: Float): Float = zone + (1f - zone) * clamp01(k)

/** S or Ș family: where the hiss sits, plus voicing for Z, J and GE/GI. */
fun sibilantReading(
    frame: LiveFrame, expected: SibilantPlace, voiced: Boolean, thresholds: DSPThresholds = DSPThresholds.v1,
): LiveReading {
    val zone = thresholds.live.meterZone
    if (!frame.fricativePresent) {
        // A vowel has no hiss: nothing to place on the scale, but the talker is still speaking.
        return LiveReading(value = null, ok = false, fault = if (frame.speaking) LiveFault.NO_SOUND else null)
    }
    val toward = if (expected == SibilantPlace.S) frame.sNess else 1f - frame.sNess
    val placeOk = toward >= 0.5f
    var value = if (placeOk) inZone((toward - 0.5f) / 0.4f, zone) else clamp01(zone * toward / 0.5f)
    val voiceOk = !voiced || frame.fricativeVoiced || frame.clarity > thresholds.fricative.voicedClarity
    val fault: LiveFault? = if (!placeOk) LiveFault.OTHER_PLACE else if (!voiceOk) LiveFault.UNVOICED else null
    if (fault == LiveFault.UNVOICED) value = min(value, zone - 0.1f)
    return LiveReading(value = value, ok = fault == null, fault = fault)
}

fun trillReading(frame: LiveFrame, thresholds: DSPThresholds = DSPThresholds.v1): LiveReading {
    if (!frame.speaking) return LiveReading(value = null, ok = false)
    val zone = thresholds.live.meterZone
    val target = thresholds.live.trillMeterThreshold
    val strength = if (frame.trillDetected) max(0.8f, frame.trillStrength) else frame.trillStrength
    val ok = frame.trillDetected || frame.trillStrength > target
    return LiveReading(
        value = if (ok) inZone((strength - target) / 0.5f, zone) else toZone(strength, target, zone),
        ok = ok,
        fault = if (ok) null else LiveFault.NO_TRILL,
    )
}

/** Voice on. Used for "palm on the throat" and for L, whose place cannot be heard reliably. */
fun voiceReading(frame: LiveFrame, thresholds: DSPThresholds = DSPThresholds.v1): LiveReading {
    if (!frame.speaking) return LiveReading(value = null, ok = false)
    val ok = frame.pitchHz > 0f
    return LiveReading(
        value = if (ok) inZone(frame.clarity, thresholds.live.meterZone) else 0.4f,
        ok = ok,
        fault = if (ok) null else LiveFault.UNVOICED,
    )
}

/** English TH: weak, diffuse friction. A sharp, high hiss means it came out as S. */
fun englishTHReading(frame: LiveFrame, thresholds: DSPThresholds = DSPThresholds.v1): LiveReading {
    if (!frame.fricativePresent) return LiveReading(value = null, ok = false)
    val sLike = frame.sNess >= 0.5f && !frame.fricativeDiffuse && frame.fricativeStrength > thresholds.live.liveThStrength
    return LiveReading(
        value = if (sLike) {
            clamp01(0.6f - frame.fricativeStrength * 0.4f)
        } else {
            inZone(1f - frame.fricativeStrength, thresholds.live.meterZone)
        },
        ok = !sLike,
        fault = if (sLike) LiveFault.S_LIKE else null,
    )
}

data class UtteranceVerdict(
    val ok: Boolean,
    val fault: LiveFault?,
    val seconds: Double,
)

/**
 * Groups frames into utterances (speech, then a stretch of quiet) and judges each one: good when
 * enough time was on target and it outweighs the time off target. Time, not frame counts, because
 * frame rates differ. Used for syllables and short sounds said several times. The first frames of
 * a hiss often lean the wrong way, so a short S still passes when most of it was right.
 */
class UtteranceTracker(thresholds: DSPThresholds = DSPThresholds.v1) {
    private val minGoodSeconds: Double = thresholds.live.utteranceMinGoodSeconds
    private val quietSeconds: Double = thresholds.live.utteranceQuietSeconds
    private val minSeconds: Double = thresholds.live.utteranceMinSeconds
    private var on = false
    private var quiet = 0.0
    private var seconds = 0.0
    private var good = 0.0
    private var bad = 0.0

    // Insertion order, so a tie between faults goes to the one heard first. Swift's Dictionary order
    // is randomised per process, so a tie there has no fixed answer.
    private val faults = LinkedHashMap<LiveFault, Double>()

    /** Returns a verdict when an utterance has just ended. */
    fun push(reading: LiveReading, active: Boolean, seconds: Double): UtteranceVerdict? {
        val step = seconds
        if (active) {
            on = true
            quiet = 0.0
            this.seconds += step
            if (reading.ok) {
                good += step
            } else {
                val fault = reading.fault
                if (fault != null) {
                    // Vowels around the target carry no hiss: that is not a wrong sound.
                    if (fault != LiveFault.NO_SOUND) bad += step
                    faults[fault] = (faults[fault] ?: 0.0) + step
                }
            }
            return null
        }
        if (!on) return null
        quiet += step
        if (!(quiet >= quietSeconds)) return null
        val verdict = currentVerdict()
        reset()
        // Clicks and bumps are not attempts.
        return if (verdict.seconds >= minSeconds) verdict else null
    }

    fun reset() {
        on = false
        quiet = 0.0
        seconds = 0.0
        good = 0.0
        bad = 0.0
        faults.clear()
    }

    private fun currentVerdict(): UtteranceVerdict {
        val ok = good >= minGoodSeconds && good >= bad
        if (ok) return UtteranceVerdict(ok = true, fault = null, seconds = seconds)
        // The most heard specific fault wins; a short wrong hiss in a long vowel is still what to fix.
        var specific: LiveFault? = null
        var most = 0.0
        for ((fault, time) in faults) {
            if (fault == LiveFault.NO_SOUND) continue
            if (specific == null || most < time) {
                specific = fault
                most = time
            }
        }
        return UtteranceVerdict(ok = false, fault = specific ?: LiveFault.NO_SOUND, seconds = seconds)
    }
}
