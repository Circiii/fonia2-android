package tech.fonia.design

/**
 * The ONE place that decides whether an effect may start (plan 05 §9.3, the quiet rule). Pure logic with an
 * injectable clock, so a JVM test can drive it.
 *
 * - While a take captures (microphone or fixture clip), nothing starts: the request is dropped.
 * - The microphone opens only [QUIET_MS] after the last effect has ended: [captureWillOpen] returns how long the hub
 *   must wait, and from that call on the gate already counts as capturing.
 * - In the [QUIET_MS] after the capture closed, a [SfxSound.deferrable] sound is deferred to the end of the window,
 *   any other is dropped.
 * - Two sounds closer than [MIN_GAP_MS]: the same sound drops; a higher priority one replaces the earlier one; a lower
 *   or equal one drops. So at most one effect is starting at a time.
 * - The in-app switch ([muted]) and the phone ([environment]: silent / vibrate ringer, Do Not Disturb) silence
 *   everything; with a screen reader on, the chrome sounds (taps, pages, cards) step aside.
 */
class SfxGate(var clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    /** What the phone says right now; read at every request. */
    fun interface Environment {
        fun state(): State

        data class State(val silent: Boolean = false, val screenReader: Boolean = false)
    }

    sealed interface Decision {
        data class Play(val at: Long, val replacesLast: Boolean, val capturing: Boolean, val sinceCloseMs: Long?) : Decision
        data class Defer(val at: Long, val delayMs: Long) : Decision
        data class Drop(val at: Long, val reason: String) : Decision

        fun describe(sound: SfxSound): String = when (this) {
            is Play -> "start ${sound.name} t=$at end=${at + sound.durationMs + LATENCY_MS} capturing=$capturing " +
                "sinceClose=${sinceCloseMs ?: "-"}${if (replacesLast) " replaces-last" else ""}"
            is Defer -> "defer ${sound.name} t=$at by=${delayMs}ms"
            is Drop -> "drop ${sound.name} t=$at reason=$reason"
        }
    }

    @Volatile
    var muted: Boolean = false

    @Volatile
    var environment: Environment = Environment { Environment.State() }

    private val lock = Any()
    private var capturing = false
    private var closedAt: Long? = null
    private var lastSound: SfxSound? = null
    private var lastStart = Long.MIN_VALUE / 2
    private var lastEnd = Long.MIN_VALUE / 2

    val isCapturing: Boolean get() = synchronized(lock) { capturing }

    /** The time the last started effect is expected to end (start + length + output latency). */
    val lastEffectEnd: Long get() = synchronized(lock) { lastEnd }

    /**
     * The hub is about to open the microphone: from now on nothing starts. Returns the milliseconds to wait before
     * actually opening it, so the last effect has ended [QUIET_MS] before.
     */
    fun captureWillOpen(): Long = synchronized(lock) {
        capturing = true
        maxOf(0L, lastEnd + QUIET_MS - clock())
    }

    /** The capture stopped (or never started). Effects may start again [QUIET_MS] from now. */
    fun captureClosed() = synchronized(lock) {
        if (!capturing) return@synchronized
        capturing = false
        closedAt = clock()
    }

    fun request(sound: SfxSound, @Suppress("UNUSED_PARAMETER") rate: Float = 1f): Decision = synchronized(lock) {
        val now = clock()
        if (muted) return Decision.Drop(now, "muted")
        if (capturing) return Decision.Drop(now, "capturing")
        val env = runCatching { environment.state() }.getOrDefault(Environment.State())
        if (env.silent) return Decision.Drop(now, "phone-silent")
        if (env.screenReader && sound.isChrome) return Decision.Drop(now, "screen-reader")
        val closed = closedAt
        if (closed != null && now < closed + QUIET_MS) {
            return if (sound.deferrable) Decision.Defer(now, closed + QUIET_MS - now) else Decision.Drop(now, "after-capture")
        }
        var replaces = false
        val last = lastSound
        if (last != null && now - lastStart < MIN_GAP_MS) {
            if (last == sound || sound.priority <= last.priority) {
                return Decision.Drop(now, if (last == sound) "duplicate" else "stacked-under-${last.name}")
            }
            replaces = true
        }
        lastSound = sound
        lastStart = now
        // The latest end of anything still sounding (a short tap after a long chime must not shorten the wait).
        lastEnd = maxOf(lastEnd, now + sound.durationMs + LATENCY_MS)
        Decision.Play(now, replaces, capturing = false, sinceCloseMs = closed?.let { now - it })
    }

    /** Back to a fresh state (tests). */
    fun reset() = synchronized(lock) {
        capturing = false
        closedAt = null
        lastSound = null
        lastStart = Long.MIN_VALUE / 2
        lastEnd = Long.MIN_VALUE / 2
        muted = false
        environment = Environment { Environment.State() }
    }

    companion object {
        /** Silence on each side of a capture (plan 05 §9.3). */
        const val QUIET_MS = 150L

        /** Two effects never start closer than this. */
        const val MIN_GAP_MS = 120L

        /** SoundPool output latency allowance added to every effect's end (a phone's mixer + buffer). */
        const val LATENCY_MS = 60L
    }
}
