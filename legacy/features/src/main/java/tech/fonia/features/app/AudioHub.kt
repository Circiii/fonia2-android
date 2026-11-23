package tech.fonia.features.app

import android.content.res.AssetManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import tech.fonia.audio.CaptureInfo
import tech.fonia.audio.CapturedClip
import tech.fonia.audio.ClipLocation
import tech.fonia.audio.FixtureMode
import tech.fonia.audio.HopProcessor
import tech.fonia.audio.LiveLevels
import tech.fonia.audio.SpeechFallback
import tech.fonia.audio.SpeechGate
import tech.fonia.audio.TakeEnd
import tech.fonia.design.FoniaSfx
import tech.fonia.design.SfxCue
import tech.fonia.domain.Language
import tech.fonia.domain.Voice
import tech.fonia.domain.VoiceIndex
import tech.fonia.dsp.DSPThresholds
import tech.fonia.dsp.LiveAnalyzer
import tech.fonia.dsp.LiveFrame
import tech.fonia.dsp.TalkerCalibration
import tech.fonia.dsp.VoiceGroup

/** Feeds the DSP from the capture thread. One instance per take, so nothing is shared across threads. */
private class AnalyzerProcessor(
    sampleRate: Double,
    group: VoiceGroup,
    calibration: TalkerCalibration?,
    thresholds: DSPThresholds,
    private val gate: SpeechGate,
) : HopProcessor {
    private val analyzer = LiveAnalyzer(sampleRate, group, calibration, thresholds)

    /** The `into` overload of `process`, so a hop allocates nothing, as on iOS. */
    private val frame: LiveFrame = LiveFrame.silent

    override fun process(samples: FloatArray, offset: Int, count: Int, levels: LiveLevels) {
        val frame = analyzer.process(samples, offset, count, frame)
        gate.update(isSpeaking = frame.speaking)
        levels.publish(
            level = frame.level,
            rms = frame.rms,
            pitchHz = frame.pitchHz,
            clarity = frame.clarity,
            sNess = frame.sNess,
            fricativeStrength = frame.fricativeStrength,
            trillStrength = frame.trillStrength,
            blowStrength = frame.blowStrength,
            isFricative = frame.fricativePresent,
        )
    }
}

/**
 * Everything that makes or takes sound, in one place. The microphone runs only while a take does, so the system's
 * microphone indicator tells the truth.
 *
 * `@MainActor` on iOS: call it from the main thread only (every screen does, from its own coroutines). [assets] turns
 * the clip paths of [voices] into locations; [microphone] shows the permission prompt, which needs the activity.
 */
class AudioHub(
    private val voices: VoiceIndex?,
    private val backend: AudioBackend,
    /** In the emulator or a UI test there is no voice: takes come from a recorded clip of the target. */
    val usesFixtures: Boolean,
    private val assets: AssetManager? = null,
    private val microphone: MicrophonePermission? = null,
) {
    val levels = LiveLevels()
    val gate = SpeechGate()

    private var capture: CaptureEnginePort? = null
    private var isSessionReady = false

    /**
     * The analysis settings of the take running now, or of the last one: [DSPThresholds.v1], with S/Ș switched off
     * when the microphone is narrow band ([sibilantsOff]). The judge reads the clip with the same settings.
     */
    @Volatile
    var analysisThresholds: DSPThresholds = DSPThresholds.v1
        private set

    /** The in-app "Sunete" switch: every effect goes through [FoniaSfx] (his `SoundEffects` player is retired). */
    var effectsMuted: Boolean
        get() = FoniaSfx.muted
        set(value) {
            FoniaSfx.muted = value
        }

    suspend fun prepare() {
        if (isSessionReady) return
        try {
            backend.session.configure(playbackOnly = usesFixtures)
            backend.session.activate()
            isSessionReady = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Stays false, so the next sound tries again. A call may be holding the audio.
        }
    }

    suspend fun requestMicrophone(): Boolean {
        if (usesFixtures) return true
        return backend.session.requestMicrophone(prompt = { microphone?.request() ?: false })
    }

    /**
     * His effects now play from the new set, through the one gate of [FoniaSfx] (quiet rule, no stacking, the
     * switch, the phone's silent mode); his `SoundEffects` player is no longer used for them.
     */
    fun effect(effect: SoundEffect) {
        FoniaSfx.play(
            when (effect) {
                SoundEffect.Correct -> SfxCue.TakeGood
                SoundEffect.Almost -> SfxCue.TakeAlmost
                SoundEffect.Pop -> SfxCue.Pop
                SoundEffect.Star -> SfxCue.Star
                SoundEffect.Unlock -> SfxCue.Unlock
                SoundEffect.LevelUp -> SfxCue.BlockDone
            },
        )
    }

    fun hasClip(text: String, language: Language): Boolean = voices?.clipUrl(text, language) != null

    /** `clipURL(text:language:)`: where the recorded clip of a text is, if there is one. */
    fun clipURL(text: String, language: Language, voice: Voice? = null): ClipLocation? {
        val path = voices?.clipUrl(text, language, voice) ?: return null
        val assets = assets ?: return null
        return ClipLocation.Asset(assets, path)
    }

    /**
     * Says a text: the recorded clip when there is one, the device's voice otherwise.
     * Foni's mouth follows either one through `levels.talk`.
     */
    suspend fun say(text: String, language: Language = Language.Ro, voice: Voice? = null) {
        prepare()
        // Whatever was being spoken stops here: two voices at once would hide the very sound being taught.
        backend.fallback.stop()
        if (!currentCoroutineContext().isActive) return
        val url = clipURL(text, language, voice)
        if (url != null && backend.playback.play(url, levels)) return
        if (!currentCoroutineContext().isActive) return
        try {
            backend.fallback.speak(
                text,
                if (language == Language.Ro) SpeechFallback.Language.Romanian else SpeechFallback.Language.English,
                levels,
            )
        } catch (cancelled: CancellationException) {
            backend.fallback.stop()
            throw cancelled
        }
    }

    /** The same clip at 0.6 speed: the pitch stays, the word opens up. Only for hearing how it's built. */
    suspend fun saySlowly(text: String, language: Language = Language.Ro) {
        backend.playback.setSpeed(0.6f)
        try {
            say(text, language)
        } finally {
            // iOS sets the speed back even when the task was cancelled, since its awaits don't throw.
            withContext(NonCancellable) { backend.playback.setSpeed(1f) }
        }
    }

    /** For short spoken prompts: a recorded voice or nothing. A device voice saying "Bravo!" would be worse than silence. */
    suspend fun sayIfRecorded(text: String?) {
        if (text == null || !currentCoroutineContext().isActive || !hasClip(text, Language.Ro)) return
        say(text)
    }

    suspend fun preload(texts: List<String>, language: Language) {
        if (voices == null) return
        backend.playback.preload(texts.mapNotNull { clipURL(it, language) })
    }

    suspend fun play(clip: CapturedClip) {
        backend.playback.play(clip, levels)
    }

    suspend fun play(clip: CapturedClip, from: Double, to: Double) {
        backend.playback.playRange(clip, from, to)
    }

    suspend fun stopSpeaking() {
        backend.fallback.stop()
        backend.playback.stop()
    }

    suspend fun startListening(
        group: VoiceGroup,
        calibration: TalkerCalibration?,
        fixtureText: String? = null,
        fixtureLanguage: Language = Language.Ro,
    ) {
        prepare()
        stopSpeaking()
        capture?.discard()
        gate.reset()
        // `FixtureMode.captureSource`: the clip of the text, else the clip of "soare", else the microphone.
        val fixture = fixtureText?.let { clipURL(it, fixtureLanguage) }
        val fallbackFixture = clipURL(FixtureMode.FALLBACK_TEXT, Language.Ro)
        val source: TakeSource = when {
            usesFixtures && fixture != null -> TakeSource.Fixture(fixture)
            usesFixtures && fallbackFixture != null -> TakeSource.Fixture(fallbackFixture)
            else -> TakeSource.Microphone
        }
        val engine = backend.captureEngine(source)
        // The quiet rule: from here no effect starts, and the microphone waits until the last one has ended + 150 ms.
        val quiet = FoniaSfx.gate.captureWillOpen()
        FoniaSfx.log("capture open-request wait=${quiet}ms")
        try {
            if (quiet > 0) delay(quiet)
        } catch (cancelled: CancellationException) {
            captureClosed()
            throw cancelled
        }
        FoniaSfx.log("capture open t=${FoniaSfx.gate.clock()}")
        capture = engine
        val gate = gate
        analysisThresholds = DSPThresholds.v1
        try {
            engine.start(levels) { rate ->
                // The processor is made once the stream runs, so the stream's real rate and device are known here.
                val thresholds = if (sibilantsOff(engine.captureInfo, rate)) withoutSibilants else DSPThresholds.v1
                analysisThresholds = thresholds
                AnalyzerProcessor(rate, group, calibration, thresholds, gate)
            }
        } catch (e: Exception) {
            capture = null
            captureClosed()
            throw e
        }
    }

    private fun captureClosed() {
        if (!FoniaSfx.gate.isCapturing) return
        FoniaSfx.gate.captureClosed()
        FoniaSfx.log("capture closed t=${FoniaSfx.gate.clock()}")
    }

    suspend fun stopListening(): CapturedClip? {
        val engine = capture ?: return null
        capture = null
        val clip = try {
            engine.stop()
        } finally {
            captureClosed()
        }
        levels.publish()
        return clip
    }

    suspend fun cancelListening() {
        try {
            capture?.discard()
        } finally {
            capture = null
            captureClosed()
        }
        levels.publish()
    }

    /** Also gives the audio back, so music that was interrupted can carry on. */
    suspend fun shutDown() {
        cancelListening()
        stopSpeaking()
        try {
            backend.session.deactivate()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
        }
        isSessionReady = false
    }

    /** Waits for someone to say something and go quiet. Returns when the voice has ended or time is up. */
    suspend fun waitForTakeEnd(
        silence: Double = TakeEnd.DEFAULT_SILENCE,
        startTimeout: Double = TakeEnd.DEFAULT_START_TIMEOUT,
        maximum: Double = TakeEnd.DEFAULT_MAXIMUM,
    ) = gate.waitForTakeEnd(silence, startTimeout, maximum)

    companion object {
        /**
         * S/Ș can't be told apart under 32 kHz (the analysers already know that from the rate, as on iOS) or from a
         * Bluetooth headset microphone, whose band stops near 8 kHz even when the stream says 48 kHz. Android names
         * the device ([CaptureInfo.isNarrowBand]), so both the live analysis and the judge switch S/Ș off for it.
         */
        fun sibilantsOff(info: CaptureInfo?, rate: Double): Boolean =
            info?.isNarrowBand == true || rate < CaptureInfo.NARROW_BAND_RATE

        /** [DSPThresholds.v1] with the S/Ș analysis off: no rate is high enough. */
        val withoutSibilants: DSPThresholds =
            DSPThresholds.v1.copy(sibilant = DSPThresholds.v1.sibilant.copy(minSampleRateHz = Double.POSITIVE_INFINITY))
    }
}
