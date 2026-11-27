package tech.fonia.features.app

import android.content.Context
import tech.fonia.audio.ClipLocation
import tech.fonia.face.FaceSource
import tech.fonia.whisper.FoniaWhisper
import tech.fonia.whisper.WhisperCppModel

/**
 * What the audio backend is built from, as `AudioHub(voices:effectsDirectory:usesFixtures:)` is on iOS: the app's
 * context, the folder of sound effects (`voice/sfx`, null when it isn't there), and whether takes come from clips.
 */
class AudioEnvironment(val context: Context, val usesFixtures: Boolean, val effectsDirectory: ClipLocation?)

/**
 * The platform engines. The defaults are the real ones: the :audio engines (with a `FixtureSource` in place of the
 * microphone when `foniaFixtures` asks for recorded takes) and the Whisper model of :whisper. The stand-ins are for
 * the JVM tests (`NoAudioBackend`) and for `foniaFakeSpeech` (`FakeSpeechModel`).
 */
class FoniaPlatform(
    val audio: (AudioEnvironment) -> AudioBackend = { environment ->
        LiveAudioBackend(environment.context, environment.effectsDirectory)
    },
    val speechModel: (Context, LaunchArguments) -> SpeechModelPort = { context, arguments ->
        if (arguments.fakeSpeech) {
            FakeSpeechModel()
        } else {
            StoreSpeechModel(FoniaWhisper.modelStore(context), WhisperCppModel.megabytes())
        }
    },
    /** `makePreferredFaceSource()`, asked each time the mouth coach opens. See `FaceSources.kt`. */
    val face: (Context, LaunchArguments) -> FaceSource = { context, arguments -> makePreferredFaceSource(context, arguments) },
)
