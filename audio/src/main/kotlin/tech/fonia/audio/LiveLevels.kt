package tech.fonia.audio

/**
 * The latest live audio values. The analysis task and playback write them, and the UI reads them once
 * per frame. Not observable on purpose, so audio never triggers a recomposition by itself.
 *
 * iOS uses relaxed atomics; volatile fields are at least that strong. A reader can see fields from two
 * adjacent hops, which is invisible at frame rate.
 */
class LiveLevels {
    @Volatile private var levelValue = 0f

    @Volatile private var rmsValue = 0f

    @Volatile private var pitchValue = 0f

    @Volatile private var clarityValue = 0f

    @Volatile private var sNessValue = 0.5f

    @Volatile private var fricativeValue = 0f

    @Volatile private var trillValue = 0f

    @Volatile private var blowValue = 0f

    @Volatile private var talkValue = 0f

    @Volatile private var fricativeFlag = false

    /** 0...1 loudness above the room floor, for meters. */
    val level: Float get() = levelValue

    /** Linear time-domain RMS of the latest hop. */
    val rms: Float get() = rmsValue

    /** Detected pitch in Hz, or 0 when there is none. */
    val pitchHz: Float get() = pitchValue

    /** 0...1 periodicity of the pitch estimate. */
    val clarity: Float get() = clarityValue

    /** Position between Ș (0) and S (1). 0.5 when no sibilant is present. */
    val sNess: Float get() = sNessValue

    val fricativeStrength: Float get() = fricativeValue

    val trillStrength: Float get() = trillValue

    val blowStrength: Float get() = blowValue

    /** 0...1 loudness of the clip playing now, for a mouth that follows the model voice. */
    val talk: Float get() = talkValue

    val isFricative: Boolean get() = fricativeFlag

    /** Publishes one analysis frame. Omitted fields go back to their resting value. `talk` is not touched. */
    fun publish(
        level: Float = 0f,
        rms: Float = 0f,
        pitchHz: Float = 0f,
        clarity: Float = 0f,
        sNess: Float = 0.5f,
        fricativeStrength: Float = 0f,
        trillStrength: Float = 0f,
        blowStrength: Float = 0f,
        isFricative: Boolean = false,
    ) {
        levelValue = level
        rmsValue = rms
        pitchValue = pitchHz
        clarityValue = clarity
        sNessValue = sNess
        fricativeValue = fricativeStrength
        trillValue = trillStrength
        blowValue = blowStrength
        fricativeFlag = isFricative
    }

    fun publishTalk(value: Float) {
        talkValue = value
    }
}
