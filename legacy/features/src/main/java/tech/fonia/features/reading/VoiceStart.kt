package tech.fonia.features.reading

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import tech.fonia.dsp.VoiceGroup
import tech.fonia.features.app.AudioHub

/** Starts the probe's clock at the first voiced sound. */
fun interface VoiceStart {
    /**
     * Listens until something is said and returns when, on [clock]; null when the phone can't listen. [onListening]
     * runs once the microphone is on. Cancelling it turns the microphone off.
     */
    suspend fun awaitFirstVoice(clock: ProbeClock, onListening: () -> Unit): Long?
}

/**
 * The first voiced frame, through the same analysis every exercise uses (`AudioHub.startListening`, whose
 * `SpeechGate` flips on the first frame the analyser calls speech). Nothing is kept: the take is thrown away the
 * moment the clock has started, so the microphone is on only between "Arată cuvintele" and the first word. It never
 * asks for the microphone during a probe: without the permission the adult starts the clock by hand.
 */
class AudioHubVoiceStart(
    private val audio: AudioHub,
    private val group: VoiceGroup,
    private val mayListen: () -> Boolean,
) : VoiceStart {
    override suspend fun awaitFirstVoice(clock: ProbeClock, onListening: () -> Unit): Long? {
        if (!mayListen()) return null
        try {
            audio.startListening(group = group, calibration = null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        try {
            onListening()
            while (currentCoroutineContext().isActive) {
                if (audio.gate.hasSpoken) {
                    // The gate remembers when it last heard a voice; right after the first one that is the start.
                    val late = audio.gate.secondsSinceSpeech.coerceIn(0.0, 1.0)
                    return clock.nanos() - (late * 1e9).toLong()
                }
                delay(10)
            }
            return null
        } finally {
            withContext(NonCancellable) { audio.cancelListening() }
        }
    }

    companion object {
        /** True when the microphone is already allowed, or takes come from recorded clips (the emulator's tests). */
        fun mayListen(context: Context, audio: AudioHub): Boolean =
            audio.usesFixtures || context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
}
