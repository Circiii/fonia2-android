package tech.fonia.features.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import tech.fonia.audio.CaptureInfo
import tech.fonia.audio.CapturedClip
import tech.fonia.audio.ClipLocation
import tech.fonia.audio.HopProcessor
import tech.fonia.audio.LiveLevels

// The stand-in for the :audio engines, for the JVM tests (`SessionRunTests`, `PathModelTests`), where there is no
// Android audio: the microphone permission is asked as the real session asks it, nothing plays and no take can start,
// so a screen shows what it shows when a take can't start. The app itself runs `LiveAudioBackend`, with a
// `FixtureSource` in place of the microphone when `foniaFixtures` asks for it.

/** A session that only knows about the permission, as `AudioSessionController.requestMicrophone(prompt)` does. */
private class PermissionOnlySession(private val context: Context?) : AudioSessionPort {
    override fun configure(playbackOnly: Boolean) = Unit

    override fun activate() = Unit

    override fun deactivate() = Unit

    override suspend fun requestMicrophone(prompt: suspend () -> Boolean): Boolean {
        val held = context?.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        return held || prompt()
    }
}

private class QuietEffects : SoundEffectsPort {
    override var isMuted: Boolean = false

    override fun play(effect: SoundEffect) = Unit
}

/** A capture that can't start. */
private class NoCapture : CaptureEnginePort {
    override suspend fun start(levels: LiveLevels, makeProcessor: (Double) -> HopProcessor) {
        throw IllegalStateException("No audio here")
    }

    override suspend fun stop(): CapturedClip = CapturedClip(FloatArray(0), 0.0)

    override suspend fun discard() = Unit

    override val captureInfo: CaptureInfo? get() = null
}

/** No sound out, no capture. */
class NoAudioBackend(context: Context?) : AudioBackend {
    override val session: AudioSessionPort = PermissionOnlySession(context)

    override val playback: PlaybackPort = object : PlaybackPort {
        override suspend fun preload(urls: List<ClipLocation>) = Unit
        override suspend fun play(url: ClipLocation, levels: LiveLevels?): Boolean = false
        override suspend fun play(clip: CapturedClip, levels: LiveLevels?): Boolean = false
        override suspend fun playRange(clip: CapturedClip, from: Double, to: Double): Boolean = false
        override suspend fun setSpeed(value: Float) = Unit
        override suspend fun stop() = Unit
        override suspend fun release() = Unit
    }

    override val effects: SoundEffectsPort = QuietEffects()

    override fun captureEngine(source: TakeSource): CaptureEnginePort = NoCapture()
}
