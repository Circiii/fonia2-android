package tech.fonia.live

import kotlinx.coroutines.flow.StateFlow

/** What the audio side is doing. */
sealed interface AudioState {
    data object Idle : AudioState

    /** A prompt (Foni's voice, a model clip, the child's own take) is playing. */
    data object Speaking : AudioState

    /** A prompt just ended; the mic may open after [QuietRule.micOpenDelayMillis]. */
    data object Settling : AudioState

    /** The mic is open and the continuous band is live. */
    data object Listening : AudioState

    data class Suspended(val reason: SuspendReason) : AudioState
}

enum class SuspendReason { Background, FocusLost, NoInput, RouteChanged, Other }

/** A clip to play. `:platform:audio` resolves asset and file paths. */
sealed interface PromptRef {
    data class Asset(val path: String) : PromptRef

    data class File(val path: String) : PromptRef

    /** A take played back so the child hears themselves. */
    data class Take(val clip: Clip) : PromptRef
}

/** Short effects. [estimatedMillis] is how long each may ring, used to delay a mic opening (to be measured). */
enum class Earcon(val estimatedMillis: Long) {
    Correct(400),
    Almost(400),
    Pop(250),
    Star(500),
    Unlock(600),
    LevelUp(600),
}

/** What the microphone stream really opened with (pure twin of `tech.fonia.audio.CaptureInfo`). */
data class CaptureInfo(
    val sampleRate: Double,
    /** Real input preset: `unprocessed`, `voiceRecognition`, `fixture`, `other`. Saved on every attempt. */
    val audioSource: String,
    val requestedSource: String,
    /** `AudioDeviceInfo.TYPE_*`, null when unknown. */
    val deviceType: Int? = null,
    val isLowLatency: Boolean = false,
    val framesPerBurst: Int = 0,
    /** Bluetooth mic or < 32 kHz: S/Ș cannot be judged. */
    val isNarrowBand: Boolean = false,
    /** Input latency Oboe reported right after start (mic → app), null when unavailable. */
    val inputLatencyMillis: Double? = null,
    val platformEffectsSwitchedOff: List<String> = emptyList(),
)

/** [AudioDirector.openMic] was called while audio is suspended (app in background, focus lost, no input). */
class AudioSuspendedException(val reason: SuspendReason) : IllegalStateException("Audio is suspended: $reason")

/**
 * The one owner of playback and capture (port; `OboeAudioDirector` in `:platform:audio`, [FakeAudioDirector] in
 * tests). Nobody else touches `PlaybackEngine`, `SoundEffects` or `CaptureEngine`. Playback and capture never
 * overlap; the rule itself lives in [FxGate].
 */
interface AudioDirector {
    val state: StateFlow<AudioState>

    /** The continuous band, live while [state] is Listening. */
    val board: LiveBoard

    /** Open/closed, for the design system's effect owner and Foni's voice. */
    val micGate: MicGate

    /** Closes the mic first (the take is dropped), waits out the tail, plays, and returns at the end of the clip. False when it did not play. */
    suspend fun play(prompt: PromptRef): Boolean

    /** Opens the mic for [target] once nothing is playing and [QuietRule.micOpenDelayMillis] has passed. Returns at once if already listening. */
    suspend fun openMic(target: LiveTarget): CaptureInfo

    /** Closes the mic and returns the take ([Clip.Empty] when it was not open). */
    suspend fun closeMic(): Clip

    /** Plays an effect unless the mic is open, the tail is running, or it comes too soon after another (dropped, not queued). */
    fun earcon(e: Earcon)

    /** Stops everything now (app in background, focus lost); [openMic] throws and [play] returns false until [resume]. */
    fun suspendAll(reason: SuspendReason)

    fun resume()

    /** Changes the target of the open mic (the next step) without closing it. */
    fun setTarget(target: LiveTarget)
}
