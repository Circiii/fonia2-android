package tech.fonia.features.app

import android.content.Context
import tech.fonia.audio.AudioSessionController
import tech.fonia.audio.CaptureEngine
import tech.fonia.audio.CaptureInfo
import tech.fonia.audio.CapturedClip
import tech.fonia.audio.ClipLocation
import tech.fonia.audio.FixtureSource
import tech.fonia.audio.HopProcessor
import tech.fonia.audio.LiveLevels
import tech.fonia.audio.MicrophoneSource
import tech.fonia.audio.PlaybackEngine
import tech.fonia.audio.SoundEffects
import tech.fonia.audio.SpeechFallback

/**
 * The real engines of :audio behind [AudioBackend], as `AudioHub.init` makes them on iOS: one session controller,
 * one playback engine, the device voice, the effects of `voice/sfx`, and a new `CaptureEngine` for every take, over
 * the microphone or, in the recorded-take mode (`foniaFixtures`), over a `FixtureSource` that plays the clip in real
 * time. Everything after the source is the same chain: 10 ms hops, the live analysis, the end of the take, the judge.
 */
class LiveAudioBackend(context: Context, effectsDirectory: ClipLocation?) : AudioBackend {
    private val appContext = context.applicationContext
    private val controller = AudioSessionController(appContext)
    private val engine = PlaybackEngine(appContext)

    // `TextToSpeech` starts its engine when it is made; it waits for the first text that has no clip.
    private val voice by lazy { SpeechFallback(appContext) }
    private val sounds = effectsDirectory?.let { SoundEffects(it) }

    override val session: AudioSessionPort = object : AudioSessionPort {
        override fun configure(playbackOnly: Boolean) = controller.configure(playbackOnly)

        override fun activate() = controller.activate()

        override fun deactivate() = controller.deactivate()

        override suspend fun requestMicrophone(prompt: suspend () -> Boolean): Boolean = controller.requestMicrophone(prompt)
    }

    override val playback: PlaybackPort = object : PlaybackPort {
        override suspend fun preload(urls: List<ClipLocation>) = engine.preload(urls)

        override suspend fun play(url: ClipLocation, levels: LiveLevels?): Boolean = engine.play(url, levels)

        override suspend fun play(clip: CapturedClip, levels: LiveLevels?): Boolean = engine.play(clip, levels)

        override suspend fun playRange(clip: CapturedClip, from: Double, to: Double): Boolean = engine.playRange(clip, from, to)

        override suspend fun setSpeed(value: Float) = engine.setSpeed(value)

        override suspend fun stop() = engine.stop()

        override suspend fun release() = engine.release()
    }

    override val fallback: SpeechFallbackPort = object : SpeechFallbackPort {
        // Never the phone's synthetic voice (owner's order, 2026-11-21): a text with no recorded clip stays silent and is
        // only shown on screen. Missing clips are listed in domain/src/test/resources/missing-clips.txt.
        override suspend fun speak(text: String, language: SpeechFallback.Language, levels: LiveLevels?) {
            if (tech.fonia.features.IS_DEBUG_BUILD) android.util.Log.w("FoniaVoice", "no clip for: $text")
        }

        override fun stop() = Unit
    }

    override val effects: SoundEffectsPort? = sounds?.let { effects ->
        object : SoundEffectsPort {
            override var isMuted: Boolean
                get() = effects.isMuted
                set(value) {
                    effects.isMuted = value
                }

            override fun play(effect: SoundEffect) = effects.play(effect)
        }
    }

    override fun captureEngine(source: TakeSource): CaptureEnginePort = when (source) {
        TakeSource.Microphone -> {
            val microphone = MicrophoneSource(appContext)
            Take(CaptureEngine(microphone)) { microphone.captureInfo }
        }
        is TakeSource.Fixture -> Take(CaptureEngine(FixtureSource(source.clip, source.pacing))) { null }
    }

    private class Take(private val engine: CaptureEngine, private val info: () -> CaptureInfo?) : CaptureEnginePort {
        override suspend fun start(levels: LiveLevels, makeProcessor: (Double) -> HopProcessor) = engine.start(levels, makeProcessor)

        override suspend fun stop(): CapturedClip = engine.stop()

        override suspend fun discard() = engine.discard()

        override val captureInfo: CaptureInfo? get() = info()
    }
}
