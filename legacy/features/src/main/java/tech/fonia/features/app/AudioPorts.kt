package tech.fonia.features.app

import tech.fonia.audio.CaptureInfo
import tech.fonia.audio.CapturedClip
import tech.fonia.audio.ClipLocation
import tech.fonia.audio.FixtureSource
import tech.fonia.audio.HopProcessor
import tech.fonia.audio.LiveLevels
import tech.fonia.audio.SoundEffects
import tech.fonia.audio.SpeechFallback

// What AudioHub drives. The data types are :audio's own (`LiveLevels`, `CapturedClip`, `HopProcessor`,
// `ClipLocation`, `SpeechGate`, `TakeEnd`); the engines sit behind small interfaces shaped like the :audio classes,
// so the real ones wrap in a line each (`LiveAudioBackend`) and the JVM tests run on `NoAudioBackend`:
//
//   AudioSessionPort    = AudioSessionController(context)     configure, activate, deactivate, requestMicrophone(prompt)
//   PlaybackPort        = PlaybackEngine(context)             preload, play(url), play(clip), playRange, setSpeed, stop, release
//   SpeechFallbackPort  = SpeechFallback(context)             speak(text, Language.Romanian, levels), stop
//   SoundEffectsPort    = SoundEffects(directory)             isMuted, play(Effect)
//   CaptureEnginePort   = CaptureEngine(source)               start(levels) { rate -> HopProcessor }, stop(), discard()
//   TakeSource          = MicrophoneSource(context) or FixtureSource(ClipLocation, Pacing), as FixtureMode.captureSource picks

/** `SoundEffects.Effect`, with the same files. */
typealias SoundEffect = SoundEffects.Effect

/** `AudioSessionController`: who owns the speaker and the microphone right now. */
interface AudioSessionPort {
    /** Before capture starts. `playbackOnly` is the recorded-take mode: nothing asks for the microphone. */
    fun configure(playbackOnly: Boolean = false)

    /** Takes audio focus; throws when the system refuses it, during a call for example. */
    fun activate()

    /** Gives the audio back, so music that was interrupted can carry on. */
    fun deactivate()

    /** True when the microphone is allowed; [prompt] shows the system's question, skipped when it is already allowed. */
    suspend fun requestMicrophone(prompt: suspend () -> Boolean = { false }): Boolean
}

/** `PlaybackEngine`. */
interface PlaybackPort {
    /** Decodes clips ahead of use. */
    suspend fun preload(urls: List<ClipLocation>)

    /** Suspends until the clip ends, `levels.talk` following it. False when it couldn't be played, so the caller can speak instead. */
    suspend fun play(url: ClipLocation, levels: LiveLevels?): Boolean

    /** Plays a take back so the child can hear themselves. */
    suspend fun play(clip: CapturedClip, levels: LiveLevels?): Boolean

    /** Part of a take, in seconds. */
    suspend fun playRange(clip: CapturedClip, from: Double, to: Double): Boolean

    /** How fast the next clips play, 1 being normal; the pitch stays. */
    suspend fun setSpeed(value: Float)

    suspend fun stop()

    suspend fun release()
}

/** `SpeechFallback`: the device's own voice, for a text with no recorded clip. */
interface SpeechFallbackPort {
    /** Returns when the text has been said; cancelling the caller stops it. */
    suspend fun speak(text: String, language: SpeechFallback.Language, levels: LiveLevels? = null)

    fun stop()
}

/** `SoundEffects`: short game sounds from `voice/sfx`, silent while muted. */
interface SoundEffectsPort {
    var isMuted: Boolean

    fun play(effect: SoundEffect)
}

/** Where a take comes from: `MicrophoneSource`, or `FixtureSource` playing a recorded clip in its place. */
sealed interface TakeSource {
    data object Microphone : TakeSource

    data class Fixture(val clip: ClipLocation, val pacing: FixtureSource.Pacing = FixtureSource.Pacing.RealTime) : TakeSource
}

/** `CaptureEngine`: one take. */
interface CaptureEnginePort {
    /** Starts the source; [makeProcessor] gets the source's sample rate. Throws when the source can't start. */
    suspend fun start(levels: LiveLevels, makeProcessor: (Double) -> HopProcessor)

    /** Ends the take and returns all of it: empty when nothing was running. */
    suspend fun stop(): CapturedClip

    suspend fun discard()

    /**
     * What the microphone stream really opened with (`MicrophoneSource.captureInfo`), once it has started; null for a
     * recorded take. Read when the processor is made, which is after the source has started.
     */
    val captureInfo: CaptureInfo?
}

/** Everything AudioHub drives. One per app. */
interface AudioBackend {
    val session: AudioSessionPort
    val playback: PlaybackPort
    val fallback: SpeechFallbackPort

    /** Null when there is no folder of effects: AudioHub then treats them as muted, as iOS does. */
    val effects: SoundEffectsPort?

    fun captureEngine(source: TakeSource): CaptureEnginePort
}
