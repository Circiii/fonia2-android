package tech.fonia.features.reading

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Monotonic nanoseconds for the probe's clock. The UI test moves its own by hand, so a probe needn't last a minute. */
fun interface ProbeClock {
    fun nanos(): Long

    companion object {
        val System: ProbeClock = ProbeClock { java.lang.System.nanoTime() }
    }
}

/** Where a probe is: the words hidden while the adult gives the instructions, waiting for the first word, the minute, then marking where the child got to. */
@Suppress("EnumEntryName")
enum class ProbePhase { prep, ready, running, marking }

/** What a tap on a word does once the minute is over. */
@Suppress("EnumEntryName")
enum class MarkMode { lastWord, words }

/**
 * One probe, from the instructions to the last word marked. Snapshot state, read by the screen; the clock is the
 * only time it trusts, so the result doesn't depend on how often the screen redraws. Main thread only.
 */
class ProbeRun(val probe: ReadingProbe, private val clock: ProbeClock = ProbeClock.System) {
    var phase by mutableStateOf(ProbePhase.prep)
        private set
    var startedBy by mutableStateOf<StartSource?>(null)
        private set

    /** The seconds the screen shows; the score reads [seconds]. */
    var shownSeconds by mutableDoubleStateOf(0.0)
        private set

    /** Words the adult marked as read differently or skipped. */
    var marked by mutableStateOf<Set<Int>>(emptySet())
        private set

    /** The last word the child read, 0-based; null until the adult marks it. */
    var lastRead by mutableStateOf<Int?>(null)
        private set

    var timeUp by mutableStateOf(false)
        private set

    var markMode by mutableStateOf(MarkMode.lastWord)

    private var startNanos = 0L
    private var stopNanos = 0L

    /** The instructions are given: the words appear and the clock waits for the first word. */
    fun showWords() {
        if (phase == ProbePhase.prep) phase = ProbePhase.ready
    }

    /** The first word: from a tap, or from the microphone at [atNanos], which may be a little before now. */
    fun start(by: StartSource, atNanos: Long = clock.nanos()) {
        if (phase != ProbePhase.ready) return
        startNanos = minOf(atNanos, clock.nanos())
        startedBy = by
        phase = ProbePhase.running
    }

    /** Called by the screen's ticker. True when this tick closed the minute. */
    fun tick(): Boolean {
        if (phase != ProbePhase.running) return false
        val elapsed = (clock.nanos() - startNanos) / 1e9
        if (elapsed >= PROBE_SECONDS) {
            close(timeUp = true)
            return true
        }
        shownSeconds = elapsed
        return false
    }

    /** "Gata": the child finished or stopped before the minute was over. */
    fun stop() {
        if (phase != ProbePhase.running) return
        if (tick()) return
        close(timeUp = false)
    }

    private fun close(timeUp: Boolean) {
        val limit = startNanos + (PROBE_SECONDS * 1e9).toLong()
        stopNanos = if (timeUp) limit else minOf(clock.nanos(), limit)
        this.timeUp = timeUp
        markMode = MarkMode.lastWord
        phase = ProbePhase.marking
        shownSeconds = seconds
    }

    /** The seconds read: from the first word to the stop, at most a minute. */
    val seconds: Double get() = if (phase != ProbePhase.marking) 0.0 else ((stopNanos - startNanos) / 1e9).coerceIn(0.0, PROBE_SECONDS)

    /** A tap on word [index]: during the minute it marks or unmarks it; after it, it does what [markMode] says. */
    fun tap(index: Int) {
        if (index !in probe.words.indices) return
        when (phase) {
            ProbePhase.running -> toggle(index)
            ProbePhase.marking -> if (markMode == MarkMode.lastWord) lastRead = index else toggle(index)
            ProbePhase.prep, ProbePhase.ready -> Unit
        }
    }

    /** "A citit tot". */
    fun readEverything() {
        if (phase == ProbePhase.marking) lastRead = probe.words.lastIndex
    }

    private fun toggle(index: Int) {
        marked = if (index in marked) marked - index else marked + index
    }

    /** The marks that count: the ones up to the last word read. */
    val countedMarks: Int get() = lastRead?.let { last -> marked.count { it <= last } } ?: marked.size

    /** Null until the last word is marked. */
    fun score(): ProbeScore? = lastRead?.let { ProbeScore.of(probe, it, marked, seconds) }
}
