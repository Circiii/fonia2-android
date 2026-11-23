package tech.fonia.live

/**
 * Whether the microphone is open. Written only by the [AudioDirector] at the real start and stop of capture.
 * docs/v2/05 §9.3 places this interface in `:core:model`; it lives here until that module's owner moves it.
 */
interface MicGate {
    val isOpen: Boolean
}

/**
 * The quiet rule's times (docs/v2/05 §9.3, decision N8). Data: the app loads them from `spec/`.
 */
data class QuietRule(
    /** The mic opens only this long after the last sound ended (`micOpenDelayMs`), so no tail is recorded. */
    val micOpenDelayMillis: Long = 150,
    /** After the mic closes (end of speech), nothing plays for this long. */
    val tailMillis: Long = 150,
    /** Minimum time between the starts of two effects (the sound budget). */
    val minGapMillis: Long = 120,
)

/**
 * The single place that enforces "nothing plays and nothing vibrates while the microphone is open" (N8):
 *
 * - while the mic is open, and for [QuietRule.tailMillis] after it closes, [tryEmit] refuses (the effect is dropped,
 *   not delayed) and [playbackWaitNanos] says how long a prompt must wait;
 * - the mic may open only [QuietRule.micOpenDelayMillis] after the last sound (prompt or effect) has ended, and
 *   never while a prompt plays ([tryBeginMicOpen]);
 * - two effects start at least [QuietRule.minGapMillis] apart.
 *
 * Every decision is one synchronized check-and-set, so an effect and a mic opening cannot both win a race.
 * The design system's haptics/SoundPool owner and Foni's voice ask this gate (through [MicGate] or [tryEmit]);
 * the [AudioDirector] drives the mic side.
 */
class FxGate(
    val rule: QuietRule = QuietRule(),
    private val clock: NanoClock = NanoClock.System,
) : MicGate {
    private var open = false
    private var playing = 0
    private var soundEndNanos = Long.MIN_VALUE
    private var tailEndNanos = Long.MIN_VALUE
    private var lastEffectNanos = Long.MIN_VALUE

    override val isOpen: Boolean get() = synchronized(this) { open }

    /** A prompt is playing now. */
    val isPlaying: Boolean get() = synchronized(this) { playing > 0 }

    /**
     * Asks to emit a short effect (earcon, haptic) lasting about [durationMillis]. Returns false, and the caller drops
     * it, while the mic is open, during the tail, or within [QuietRule.minGapMillis] of the previous effect.
     */
    fun tryEmit(durationMillis: Long, now: Long = clock.nanos()): Boolean = synchronized(this) {
        if (open || now < tailEndNanos) return false
        if (lastEffectNanos != Long.MIN_VALUE && now - lastEffectNanos < ms(rule.minGapMillis)) return false
        lastEffectNanos = now
        soundEndNanos = maxOf(soundEndNanos, now + ms(durationMillis))
        true
    }

    /** Runs [block] only if [tryEmit] allows it. */
    inline fun emit(durationMillis: Long, block: () -> Unit): Boolean {
        if (!tryEmit(durationMillis)) return false
        block()
        return true
    }

    /** Nanoseconds a prompt must wait before it may start (0 = now): the mic is open or the tail is running. */
    fun playbackWaitNanos(now: Long = clock.nanos()): Long = synchronized(this) {
        when {
            open -> POLL_NANOS
            now < tailEndNanos -> tailEndNanos - now
            else -> 0L
        }
    }

    /**
     * Marks a prompt as started if allowed. Returns 0 on success, or the nanoseconds to wait before trying again.
     */
    fun tryBeginPlayback(now: Long = clock.nanos()): Long = synchronized(this) {
        val wait = playbackWaitNanos(now)
        if (wait == 0L) playing += 1
        wait
    }

    /** A prompt ended (or was stopped) at [now]. */
    fun playbackEnded(now: Long = clock.nanos()) = synchronized(this) {
        if (playing > 0) playing -= 1
        soundEndNanos = maxOf(soundEndNanos, now)
    }

    /**
     * Opens the mic if allowed. Returns 0 when the mic is now marked open (start capturing), or the nanoseconds to wait
     * before trying again: a prompt is playing, or the last sound ended less than [QuietRule.micOpenDelayMillis] ago.
     */
    fun tryBeginMicOpen(now: Long = clock.nanos()): Long = synchronized(this) {
        if (open) return 0L
        if (playing > 0) return POLL_NANOS
        val allowedAt = if (soundEndNanos == Long.MIN_VALUE) Long.MIN_VALUE else soundEndNanos + ms(rule.micOpenDelayMillis)
        if (now < allowedAt) return allowedAt - now
        open = true
        0L
    }

    /** The capture did not start after [tryBeginMicOpen]: the mic is closed again, with no tail. */
    fun micOpenFailed() = synchronized(this) {
        open = false
    }

    /** The capture stopped at [now]. Effects and prompts stay quiet for [QuietRule.tailMillis]. */
    fun micClosed(now: Long = clock.nanos()) = synchronized(this) {
        if (!open) return
        open = false
        tailEndNanos = now + ms(rule.tailMillis)
    }

    private fun ms(millis: Long): Long = millis * 1_000_000L

    companion object {
        /** How often a waiter re-checks when the wait has no known end (mic open, prompt playing). */
        const val POLL_NANOS = 10_000_000L
    }
}
