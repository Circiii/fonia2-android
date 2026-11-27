package tech.fonia.features.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tech.fonia.dsp.LiveFrame
import tech.fonia.features.app.HopTap
import tech.fonia.ml.HostState
import tech.fonia.ml.ZipaHost
import tech.fonia.phones.LiveSpotter
import tech.fonia.phones.TargetSound

/**
 * The live "is the target sound being said now?" of a take: the letter must not react to just anything, only when its
 * letter or sound is heard. While the microphone is open it keeps the last second of audio and asks the phoneme
 * model about it five times a second ([LiveSpotter]); the living letter and the held bar read [value].
 *
 * One per [tech.fonia.features.app.AudioHub]. Nothing is stored and nothing leaves the phone. Without the model in the
 * APK, or until it is loaded, [value] is NaN and the screens fall back to the acoustic rule.
 */
class LiveSpot {
    /** The phoneme model's host; set once by the app when the APK carries the model. */
    @Volatile
    var host: ZipaHost? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    @Volatile private var target: TargetSound? = null

    @Volatile private var ring: Ring? = null

    @Volatile private var heard = false

    @Volatile private var answeredNanos = 0L

    /** The last score, for logs and tests (NaN before the first answer). */
    @Volatile
    var score = Float.NaN
        private set

    /** 1 when the target was heard in the last moments, 0 when it was not, NaN when there is no fresh answer. */
    fun value(): Float {
        if (target == null || answeredNanos == 0L || System.nanoTime() - answeredNanos > STALE_NANOS) return Float.NaN
        return if (heard) 1f else 0f
    }

    /** A take that practises [sound] starts: the tap of [tap] feeds the ring, the model answers until [end]. */
    fun begin(sound: TargetSound?) {
        end()
        val h = host
        if (sound == null || h == null) return
        target = sound
        job = scope.launch {
            if (h.state.value == HostState.Cold) launch { runCatching { h.warmUp() } }
            var said = false
            while (isActive) {
                delay(PERIOD_MS)
                val r = ring ?: continue
                val audio = r.last() ?: continue
                val s = h.spot(audio, r.rate, sound) ?: continue
                score = s
                heard = s >= LiveSpotter.tau(sound)
                answeredNanos = System.nanoTime()
                if (heard != said) {
                    said = heard
                    runCatching { android.util.Log.i("FoniaSpot", sound.rawValue + (if (heard) " heard" else " gone") + " score=" + "%.2f".format(s)) }
                }
            }
        }
    }

    /** The hop tap of the take's analyser, or null when nothing is spotted. Call after [begin]. */
    fun tap(rate: Double): HopTap? {
        if (target == null) return null
        val r = Ring(rate)
        ring = r
        return r
    }

    fun end() {
        job?.cancel()
        job = null
        target = null
        ring = null
        heard = false
        answeredNanos = 0L
        score = Float.NaN
    }

    /** The last [WINDOW_SECONDS] of the microphone, written on the audio thread, copied by the spotter. */
    private class Ring(val rate: Double) : HopTap {
        private val data = FloatArray((rate * WINDOW_SECONDS).toInt().coerceAtLeast(1))
        private var at = 0
        private var total = 0L

        override fun hop(samples: FloatArray, offset: Int, count: Int, frame: LiveFrame) {
            synchronized(data) {
                for (i in offset until offset + count) {
                    data[at] = samples[i]
                    at = if (at + 1 == data.size) 0 else at + 1
                }
                total += count
            }
        }

        /** Oldest first; null until [MIN_SECONDS] were heard. */
        fun last(): FloatArray? = synchronized(data) {
            if (total < rate * MIN_SECONDS) return null
            val n = minOf(total, data.size.toLong()).toInt()
            val out = FloatArray(n)
            val start = (at - n + data.size) % data.size
            for (i in 0 until n) out[i] = data[(start + i) % data.size]
            out
        }
    }

    companion object {
        const val PERIOD_MS = 200L
        const val WINDOW_SECONDS = 1.0
        const val MIN_SECONDS = 0.3

        /** An answer older than this is not "now" (the model is busy with a verdict, or gone). */
        const val STALE_NANOS = 900_000_000L
    }
}
