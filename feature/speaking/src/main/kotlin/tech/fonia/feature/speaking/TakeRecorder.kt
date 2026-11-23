package tech.fonia.feature.speaking

import tech.fonia.live.Clip
import tech.fonia.speaking.SpeakingAudio

/**
 * Recent mic levels for the recording ring, written from any thread by [SpeakingAudio.record]'s callback and read in
 * the draw phase (never through Compose state, so a level update causes no recomposition). [frame] grows on every
 * write; the drawing polls it once per frame.
 */
class LevelHistory(val capacity: Int = 96) {
    private val levels = FloatArray(capacity)
    private val speaking = BooleanArray(capacity)
    @Volatile var written: Long = 0L
        private set
    @Volatile var startedAtNanos: Long = 0L
    @Volatile var speakingSeconds: Double = 0.0
        private set

    @Synchronized
    fun push(level: Float, isSpeaking: Boolean, dtSeconds: Double) {
        val i = (written % capacity).toInt()
        levels[i] = level
        speaking[i] = isSpeaking
        if (isSpeaking) speakingSeconds += dtSeconds
        written++
    }

    /** The level [age] writes ago (0 = newest), or 0 before anything was written. */
    @Synchronized
    fun level(age: Int): Float {
        if (age >= written || age >= capacity) return 0f
        return levels[((written - 1 - age) % capacity).toInt()]
    }

    @Synchronized
    fun reset() {
        written = 0
        speakingSeconds = 0.0
        levels.fill(0f)
        speaking.fill(false)
        startedAtNanos = System.nanoTime()
    }

    fun elapsedSeconds(now: Long = System.nanoTime()): Double = if (startedAtNanos == 0L) 0.0 else (now - startedAtNanos) / 1e9
}

/**
 * One recording with the NO-UM rules (plan 01 §10, step 2): it ends on "Gata", at [maxSeconds], or after
 * [silenceStopSeconds] of silence once speech was heard.
 */
internal class TakeRecorder(
    private val audio: SpeakingAudio,
    private val maxSeconds: Double,
    private val silenceStopSeconds: Double,
    val levels: LevelHistory = LevelHistory(),
) {
    @Volatile private var lastLevelNanos = 0L
    @Volatile private var heard = false
    @Volatile private var silentFor = 0.0

    suspend fun record(): Clip {
        levels.reset()
        heard = false
        silentFor = 0.0
        lastLevelNanos = 0L
        return audio.record(maxSeconds) { level, speaking ->
            val now = System.nanoTime()
            // Wall-clock spacing of the updates; under virtual time (tests) it is ~0, so assume the usual 50 Hz.
            val measured = if (lastLevelNanos == 0L) 0.0 else (now - lastLevelNanos) / 1e9
            val dt = if (measured < 0.001) 0.02 else measured.coerceAtMost(0.2)
            lastLevelNanos = now
            levels.push(level, speaking, dt)
            if (speaking) { heard = true; silentFor = 0.0 } else if (heard) {
                silentFor += dt
                if (silentFor >= silenceStopSeconds) audio.stopRecording()
            }
        }
    }

    fun stop() = audio.stopRecording()
}
