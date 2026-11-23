package tech.fonia.audio

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * When the voice started and stopped, written on the analysis task (the hop listener) and read by the flow.
 * iOS keeps it in `FoniaFeatures/App/AudioHub.swift`; it is here so `:features` and its tests share one copy.
 */
class SpeechGate internal constructor(private val nanoTime: () -> Long) {
    constructor() : this(System::nanoTime)

    private companion object {
        const val NEVER = Long.MIN_VALUE
    }

    private val speaking = AtomicBoolean(false)
    private val lastSpeechNanos = AtomicLong(NEVER)
    private val everSpoke = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)

    fun update(isSpeaking: Boolean) {
        speaking.set(isSpeaking)
        if (isSpeaking) {
            everSpoke.set(true)
            lastSpeechNanos.set(nanoTime())
        }
    }

    fun requestStop() {
        stopRequested.set(true)
    }

    val isStopRequested: Boolean get() = stopRequested.get()

    fun reset() {
        stopRequested.set(false)
        speaking.set(false)
        everSpoke.set(false)
        lastSpeechNanos.set(NEVER)
    }

    val hasSpoken: Boolean get() = everSpoke.get()

    /** Infinity before the first voice. */
    val secondsSinceSpeech: Double
        get() {
            val last = lastSpeechNanos.get()
            if (last == NEVER) return Double.POSITIVE_INFINITY
            return (nanoTime() - last) / 1e9
        }

    /**
     * `AudioHub.waitForTakeEnd`: waits for someone to say something and go quiet. Returns at [maximum] seconds or
     * on [requestStop]; after a voice, once it has been quiet for [silence] seconds; with no voice at all, at
     * [startTimeout]. Checks every 50 ms. iOS measures elapsed time with `Date`; this uses the monotonic clock.
     */
    suspend fun waitForTakeEnd(
        silence: Double = TakeEnd.DEFAULT_SILENCE,
        startTimeout: Double = TakeEnd.DEFAULT_START_TIMEOUT,
        maximum: Double = TakeEnd.DEFAULT_MAXIMUM,
    ) {
        val started = nanoTime()
        while (currentCoroutineContext().isActive) {
            val elapsed = (nanoTime() - started) / 1e9
            if (TakeEnd.isOver(elapsed, this, silence, startTimeout, maximum)) return
            delay(TakeEnd.POLL_INTERVAL_MS)
        }
    }
}

/** The end-of-take rule, with iOS's defaults. Each exercise passes its own values (inventory §6.4). */
object TakeEnd {
    const val DEFAULT_SILENCE = 0.7
    const val DEFAULT_START_TIMEOUT = 6.0
    const val DEFAULT_MAXIMUM = 8.0
    const val POLL_INTERVAL_MS = 50L

    /** One check of the loop: true when the take is over after [elapsed] seconds. */
    fun isOver(elapsed: Double, gate: SpeechGate, silence: Double, startTimeout: Double, maximum: Double): Boolean {
        if (elapsed >= maximum || gate.isStopRequested) return true
        return if (gate.hasSpoken) gate.secondsSinceSpeech >= silence else elapsed >= startTimeout
    }
}
