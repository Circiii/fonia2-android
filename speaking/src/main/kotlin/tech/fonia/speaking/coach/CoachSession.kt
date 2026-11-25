package tech.fonia.speaking.coach

/**
 * One attempt of the speech coach: the microphone side ([audio], made when the stream's rate is known), the camera
 * side ([face]), the cue arbiter and the timeline the report is made from.
 *
 * Threads: [AudioCoach.process] on the audio thread, [FaceCoach.onFrame] on the camera thread, [tick] from one
 * coroutine (the screen's, ~10 Hz). [levels] are volatile and read by the gauges in the draw phase.
 */
class CoachSession(
    val spec: CoachSpec = CoachSpec.v1,
    /** His speaking level from his previous attempt (dBFS), NaN when there is none. */
    private val calibratedReferenceDb: Double = Double.NaN,
    /** His own gaze calibration, when he made one. */
    gaze: GazeCalibration? = null,
    /** A focused drill on one area: only its cues show. */
    val focus: Area? = null,
) {
    @Volatile var audio: AudioCoach? = null
        private set
    val face = FaceCoach(spec, gaze)
    val levels = CoachLevels()
    private val arbiter = CueArbiter(spec, focus)
    private val active = BooleanArray(Cue.entries.size)
    private val ticks = ArrayList<CoachTick>()
    private var lastRecorded = Double.NEGATIVE_INFINITY

    private var talkStart = 0.0
    private var pendingIssue: InputIssue? = null
    private var pendingSince = 0.0
    private var hint: InputIssue? = null
    private var hintSince = Double.NEGATIVE_INFINITY

    /**
     * The talk starts now ([clockSeconds] on the coach's clock, the same clock [FaceSample.tSeconds] uses). The
     * timeline counts from here; the camera frames before it (the countdown) only make the lens baseline.
     */
    fun startTalk(clockSeconds: Double) {
        talkStart = clockSeconds
    }

    /** Made on the audio thread when the stream starts; returns the coach that [AudioCoach.process] is called on. */
    fun attachAudio(sampleRate: Double): AudioCoach = AudioCoach(sampleRate, spec, calibratedReferenceDb).also { audio = it }

    /**
     * Advances the cues and the timeline to [clockSeconds] on the coach's clock (the audio's own clock runs within a
     * hop of it, counted from [startTalk]). Returns the cue to show.
     */
    fun tick(clockSeconds: Double): Cue? {
        val t = clockSeconds - talkStart
        val a = audio
        val rate = a?.rate ?: Double.NaN
        val audioNow = a?.seconds ?: 0.0
        val speakingRecent = a != null && audioNow - a.lastSpeechSeconds < spec.cues.silenceHoldsCueSeconds
        val warm = (a?.speechSeconds ?: 0.0) >= spec.cues.warmupSeconds
        val hasCamera = face.hasCamera(clockSeconds)
        val visible = hasCamera && face.faceVisible(clockSeconds)
        val away = face.awayFraction
        // The quality gate: one quiet hint, shown only after the problem lasted, kept as long so it does not flicker.
        val noisy = a != null && a.roomDb > spec.quality.maxNoiseDb
        val issue = (if (hasCamera) face.issueNow else null) ?: (if (noisy) InputIssue.Noisy else null)
        if (issue != pendingIssue) {
            pendingIssue = issue
            pendingSince = clockSeconds
        }
        if (hint != pendingIssue && clockSeconds - pendingSince >= spec.quality.hintAfterSeconds && clockSeconds - hintSince >= spec.quality.hintAfterSeconds) {
            hint = pendingIssue
            hintSince = clockSeconds
        }
        levels.hint = hint
        val motion = if (visible) face.motionIndex else Double.NaN
        val pitchSpread = a?.pitchSpreadSt ?: Double.NaN
        val lively = liveliness(pitchSpread, if (visible) face.faceActivity01 else Double.NaN)

        active.fill(false)
        active[Cue.Look.ordinal] = hasCamera && !away.isNaN() && away >= spec.gaze.awayFractionCue
        // In a noisy room the voice measures are less sure: the coach abstains.
        active[Cue.Louder.ordinal] = speakingRecent && a?.quiet == true && !noisy
        active[Cue.Slower.ordinal] = speakingRecent && !rate.isNaN() && rate > spec.pace.bandHigh
        active[Cue.Faster.ordinal] = speakingRecent && !rate.isNaN() && rate < spec.pace.bandLow
        active[Cue.Calmer.ordinal] = !motion.isNaN() && motion > spec.motion.cueIndex
        active[Cue.Livelier.ordinal] = speakingRecent && !lively.isNaN() && lively < spec.liveliness.cueBelow && !noisy
        val cue = arbiter.update(t, active, warm, speakingRecent)

        // What the gauges show (targets; the screen smooths them).
        levels.pace01 = if (rate.isNaN()) Double.NaN else ((rate - spec.pace.gaugeMin) / (spec.pace.gaugeMax - spec.pace.gaugeMin)).coerceIn(0.0, 1.0)
        levels.paceRate = rate
        levels.loudness01 = if (speakingRecent) a?.loudness01 ?: Double.NaN else Double.NaN
        levels.speaking = a?.speaking == true
        levels.looking = if (visible) (if (face.lookingNow) 1.0 else 0.0) else Double.NaN
        levels.hasCamera = hasCamera
        levels.still01 = if (motion.isNaN()) Double.NaN else (1.0 - motion / spec.motion.gaugeMaxIndex).coerceIn(0.0, 1.0)
        levels.lively01 = lively
        levels.cue = cue
        levels.seconds = t

        if (t - lastRecorded >= spec.report.tickSeconds - 1e-6) {
            lastRecorded = t
            ticks += CoachTick(
                t = t,
                speaking = a?.speaking == true || speakingRecent,
                rate = rate,
                quiet = speakingRecent && a?.quiet == true,
                loudness01 = a?.loudness01 ?: Double.NaN,
                // Null when there is no camera or the picture could not be measured (the gate).
                looking = if (hasCamera && visible && face.issueNow == null && !away.isNaN()) face.lookingNow else null,
                camera = hasCamera,
                motion = motion,
                liveliness = lively,
                cue = cue,
            )
        }
        return cue
    }

    fun liveliness(pitchSpreadSt: Double, faceActivity01: Double): Double {
        val p = if (pitchSpreadSt.isNaN()) Double.NaN else
            ((pitchSpreadSt - spec.pitch.flatSpreadSt) / (spec.pitch.livelySpreadSt - spec.pitch.flatSpreadSt)).coerceIn(0.0, 1.0)
        val l = spec.liveliness
        return when {
            p.isNaN() && faceActivity01.isNaN() -> Double.NaN
            p.isNaN() -> Double.NaN // the melody leads: without a voice there is nothing to call lively or flat
            faceActivity01.isNaN() -> p
            else -> (l.pitchWeight * p + l.faceWeight * faceActivity01) / (l.pitchWeight + l.faceWeight)
        }
    }

    /** The timeline so far (copy). */
    fun timeline(): List<CoachTick> = ArrayList(ticks)
}

/** One timeline sample, every [CoachSpec.Report.tickSeconds]. */
data class CoachTick(
    val t: Double,
    val speaking: Boolean,
    val rate: Double,
    val quiet: Boolean,
    val loudness01: Double,
    /** Null when there is no camera (permission refused, no frames) or the picture could not be measured. */
    val looking: Boolean?,
    val motion: Double,
    val liveliness: Double,
    val cue: Cue?,
    /** True when the camera ran at this moment. */
    val camera: Boolean = false,
)

/**
 * What the live gauges draw, written by the tick and read in the draw phase (volatile, no recomposition). NaN = no
 * reading yet: the gauge rests.
 */
class CoachLevels {
    @Volatile var pace01: Double = Double.NaN
    @Volatile var paceRate: Double = Double.NaN
    @Volatile var loudness01: Double = Double.NaN
    @Volatile var speaking: Boolean = false
    @Volatile var looking: Double = Double.NaN
    @Volatile var hasCamera: Boolean = false
    @Volatile var still01: Double = Double.NaN
    @Volatile var lively01: Double = Double.NaN
    @Volatile var cue: Cue? = null
    /** What to change so the coach can measure (null = fine). */
    @Volatile var hint: InputIssue? = null
    @Volatile var seconds: Double = 0.0
}
