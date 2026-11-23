package tech.fonia.app.lesson

/**
 * Decides when a take is over, from what the frame loop reads off the board (chapter 01 §6.2): end of voice + 150 ms,
 * or the step's max duration, or the source falling silent (a fixture that ended, a stalled stream), or 8 s with no
 * voice at all. Pure and single-threaded (the frame loop); times in nanoseconds.
 */
class TakeEndDetector(private val tuning: LessonTuning) {
    enum class End { VoiceEnded, MaxDuration, SourceEnded, Silence }

    private var openedAt = 0L
    private var voiceStart = 0L
    private var lastVoice = 0L
    private var lastSeq = -1L
    private var lastSeqAt = 0L
    private var maxNanos = 0L
    private var tailNanos = 0L

    /** True once the child started speaking in this take. */
    val voiceStarted: Boolean get() = voiceStart != 0L

    /** A new take: at most [maxSeconds] after the voice starts; [longTail] for parts with pauses (bursts, trains, sentences). */
    fun reset(now: Long, maxSeconds: Float, longTail: Boolean = false) {
        openedAt = now
        voiceStart = 0L
        lastVoice = 0L
        lastSeq = -1L
        lastSeqAt = now
        maxNanos = (maxSeconds * 1e9).toLong()
        tailNanos = (if (longTail) tuning.endTailLongMillis else tuning.endTailMillis) * 1_000_000L
    }

    /** Milliseconds of quiet since the mic opened, 0 once the voice started (the capsule pulses past 4 s). */
    fun quietMillis(now: Long): Long = if (voiceStart != 0L) 0L else (now - openedAt) / 1_000_000L

    /**
     * [evidence]: the frame carries speech features (friction, voicing, trill). While the stream settles (the
     * analyser's room floor warms up for about a second and reports "speaking" on plain noise), only such frames count.
     */
    fun onFrame(now: Long, seq: Long, speaking: Boolean, evidence: Boolean = false): End? {
        if (seq != lastSeq) {
            lastSeq = seq
            lastSeqAt = now
        }
        val settled = now - openedAt >= tuning.settleMillis * 1_000_000L
        if (speaking && (settled || evidence || voiceStart != 0L)) {
            if (voiceStart == 0L) voiceStart = now
            lastVoice = now
        }
        if (voiceStart == 0L) {
            return if (now - openedAt >= tuning.quietCloseMillis * 1_000_000L) End.Silence else null
        }
        return when {
            !speaking && now - lastVoice >= tailNanos -> End.VoiceEnded
            now - voiceStart >= maxNanos -> End.MaxDuration
            now - lastSeqAt >= tuning.stallMillis * 1_000_000L -> End.SourceEnded
            else -> null
        }
    }
}
