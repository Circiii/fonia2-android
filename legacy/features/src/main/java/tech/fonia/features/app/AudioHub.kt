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
import tech.fonia.audio.SpeechGate
import tech.fonia.audio.TakeEnd
import tech.fonia.design.FoniaSfx
import tech.fonia.design.SfxCue
import tech.fonia.features.IS_DEBUG_BUILD
import tech.fonia.domain.Language
import tech.fonia.domain.Voice
import tech.fonia.domain.VoiceIndex
import tech.fonia.dsp.DSPThresholds
import tech.fonia.dsp.LiveAnalyzer
import tech.fonia.dsp.LiveFrame
import tech.fonia.dsp.TalkerCalibration
import tech.fonia.dsp.VoiceGroup
import tech.fonia.judge.TalkerVoice

/**
 * What one take has heard so far, written by the capture thread and read by the screens (the "Nu te aud" cue) and by
 * the talker tracker (the median pitch of the voiced hops). One instance per take.
 */
class TakeStats(val sampleRate: Double, val source: String) {
    /** Loudest sample of the take so far, linear 0…1. */
    @Volatile var peak: Float = 0f
        internal set

    @Volatile var samples: Long = 0
        internal set

    private val pitches = FloatArray(MAX_PITCHES)

    @Volatile private var pitchCount = 0

    /** Seconds of audio delivered since the microphone opened. */
    val seconds: Double get() = if (sampleRate > 0) samples / sampleRate else 0.0

    val peakDb: Float get() = 20f * kotlin.math.log10(peak + 1e-9f)

    internal fun addPitch(hz: Float) {
        val n = pitchCount
        if (hz > 0f && n < MAX_PITCHES) {
            pitches[n] = hz
            pitchCount = n + 1
        }
    }

    private val hiss = FloatArray(MAX_PITCHES)

    @Volatile private var hissCount = 0

    internal fun addHiss(sNess: Float) {
        val n = hissCount
        if (!sNess.isNaN() && n < MAX_PITCHES) {
            hiss[n] = sNess
            hissCount = n + 1
        }
    }

    /**
     * Where the take's hiss sat between Ș (0) and S (1), the median of the live hops that heard one (the same value
     * the live place meter shows); NaN under ~50 ms of hiss.
     */
    fun hissMedian(): Float {
        val n = hissCount
        if (n < 5) return Float.NaN
        val sorted = hiss.copyOf(n).also { it.sort() }
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2f
    }

    /** Median pitch of the voiced hops, NaN when the take had less than ~0.25 s of voice (a hiss, silence, a beep). */
    fun pitchMedian(): Float = TalkerVoice.medianVoiced(pitches, pitchCount, minVoiced = MIN_VOICED_HOPS)

    private companion object {
        const val MAX_PITCHES = 4096

        /** About 0.25 s of 10.7 ms hops. */
        const val MIN_VOICED_HOPS = 24
    }
}

/** Feeds the DSP from the capture thread. One instance per take, so nothing is shared across threads. */
/** A listener on the capture: the samples of one hop and the live analyser's reading after it. Audio thread; keep nothing. */
fun interface HopTap {
    fun hop(samples: FloatArray, offset: Int, count: Int, frame: LiveFrame)
}

private class AnalyzerProcessor(
    sampleRate: Double,
    group: VoiceGroup,
    calibration: TalkerCalibration?,
    thresholds: DSPThresholds,
    private val gate: SpeechGate,
    private val stats: TakeStats,
    /** Sees every hop and the live reading after it (the speech coach), on the audio thread. */
    private val tap: HopTap? = null,
) : HopProcessor {
    private val analyzer = LiveAnalyzer(sampleRate, group, calibration, thresholds)

    /** The `into` overload of `process`, so a hop allocates nothing, as on iOS. */
    private val frame: LiveFrame = LiveFrame.silent
    private var lastPitch = Float.NaN
    private var lastHiss = Float.NaN
    private var logged = false

    override fun process(samples: FloatArray, offset: Int, count: Int, levels: LiveLevels) {
        var peak = stats.peak
        for (i in offset until offset + count) {
            val a = kotlin.math.abs(samples[i])
            if (a > peak) peak = a
        }
        stats.peak = peak
        stats.samples += count
        val frame = analyzer.process(samples, offset, count, frame)
        tap?.hop(samples, offset, count, frame)
        // A new analysis hop (the analyzer returns its last reading between hops): one pitch per hop.
        if (frame.pitchHz > 0f && frame.pitchHz != lastPitch) stats.addPitch(frame.pitchHz)
        if (frame.fricativePresent && frame.sNess != lastHiss) stats.addHiss(frame.sNess)
        lastHiss = if (frame.fricativePresent) frame.sNess else Float.NaN
        lastPitch = frame.pitchHz
        if (!logged && stats.seconds >= 3.0) {
            logged = true
            AudioHub.log(
                "mic 3 s: source=${stats.source} rate=${stats.sampleRate.toInt()} peak=${"%.1f".format(stats.peakDb)} dBFS " +
                    "floor=${"%.0f".format(frame.floorDb)} dB",
            )
        }
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
            isFricativeVoiced = frame.fricativeVoiced,
            hissFlatness = frame.hissFlatness,
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
    /**
     * Debug only (`foniaMicFile`): every take reads this WAV in real time instead of the microphone, then digital
     * silence, through exactly the same live path (levels, end of take, judge, screens).
     */
    val micFile: java.io.File? = null,
) {
    val levels = LiveLevels()
    val gate = SpeechGate()

    /** Is the target sound being said now? The phoneme model on the last second ([tech.fonia.features.session.LiveSpot]). */
    val spot = tech.fonia.features.session.LiveSpot()

    /** What the take running now (or the last one) has heard; null before the first take. */
    @Volatile
    var take: TakeStats? = null
        private set

    /**
     * The microphone has been open [SILENT_AFTER_SECONDS] and delivered nothing near a voice: digital silence (an
     * emulator without the host microphone, a privacy switch) or far below speech. The screens show "Nu te aud".
     */
    val micSeemsSilent: Boolean
        get() = capture != null && lastTakeWasSilent

    /** The last take had a dead microphone (same rule), also after it ended. */
    val lastTakeWasSilent: Boolean
        get() {
            val t = take ?: return false
            return t.seconds >= SILENT_AFTER_SECONDS && t.peakDb < SILENT_PEAK_DB
        }

    private var capture: CaptureEnginePort? = null
    private var isSessionReady = false

    /** iOS `isListening`: the microphone is open, so nothing else should play. */
    val isListening: Boolean
        get() = capture != null

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
            backend.session.configure(playbackOnly = usesFixtures || micFile != null)
            backend.session.activate()
            isSessionReady = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Stays false, so the next sound tries again. A call may be holding the audio.
        }
    }

    suspend fun requestMicrophone(): Boolean {
        if (usesFixtures || micFile != null) return true
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

    /**
     * Debug: `foniaMicFile` naming a folder feeds a whole run (the quick check, a drill) right, wrong and silent takes:
     * the WAV named after the take's text without diacritics (`supa.wav` for „supă”) when there is one, else the next
     * WAV in name order, one per take.
     */
    private val micPlaylist: List<java.io.File>? by lazy {
        micFile?.takeIf { it.isDirectory }?.listFiles()?.filter { it.isFile && it.name.endsWith(".wav") }?.sortedBy { it.name }
    }
    private var micTurn = 0

    private fun nextMicFile(text: String?): java.io.File {
        val list = micPlaylist
        if (list.isNullOrEmpty()) return micFile!!
        val slug = text?.let { asciiSlug(it) }
        list.firstOrNull { slug != null && it.nameWithoutExtension == slug }?.let { return it }
        return list[micTurn++ % list.size]
    }

    private fun asciiSlug(text: String): String = java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9]+"), "_").trim('_')

    /**
     * The generated clips of the v2 content (`spec/content/voice`, ElevenLabs), by text: asked only when the content's
     * own recordings have none. Lower-cased, trimmed text → asset path.
     */
    var extraClips: Map<String, String> = emptyMap()

    private fun extraClip(text: String, language: Language, voice: Voice?): String? =
        if (language == Language.Ro && voice == null) extraClips[text.trim().lowercase()] else null

    fun hasClip(text: String, language: Language): Boolean =
        voices?.clipUrl(text, language) != null || extraClip(text, language, null) != null

    /** `clipURL(text:language:)`: where the recorded clip of a text is, if there is one. */
    fun clipURL(text: String, language: Language, voice: Voice? = null): ClipLocation? {
        val path = voices?.clipUrl(text, language, voice) ?: extraClip(text, language, voice) ?: return null
        val assets = assets ?: return null
        return ClipLocation.Asset(assets, path)
    }

    /**
     * Says a text with its recorded clip; Foni's mouth follows it through `levels.talk`.
     * A text with no clip stays silent and is only shown on screen: never the phone's synthetic voice (owner's
     * decision, 2026-11-21; iOS still falls back to `AVSpeechSynthesizer`). What still has no clip is listed in
     * domain/src/test/resources/missing-clips.txt and tools/voice/REPORT.md.
     */
    suspend fun say(text: String, language: Language = Language.Ro, voice: Voice? = null) {
        prepare()
        if (!currentCoroutineContext().isActive) return
        val url = clipURL(text, language, voice)
        if (url != null && backend.playback.play(url, levels)) return
        logMissingClip(text, language, voice)
    }

    private fun logMissingClip(text: String, language: Language, voice: Voice?) {
        if (!IS_DEBUG_BUILD) return
        // runCatching: android.util.Log is a stub that throws in the JVM unit tests.
        runCatching { android.util.Log.w("FoniaVoice", "no clip: ${language.rawValue}|${voice?.rawValue ?: "-"}|$text") }
    }

    /** The clip at [speed] (a twister's tempo ladder: slow, medium, fast); the speed is set back afterwards. */
    suspend fun sayAt(text: String, speed: Float, language: Language = Language.Ro) {
        if (speed == 1f) return say(text, language)
        backend.playback.setSpeed(speed)
        try {
            say(text, language)
        } finally {
            withContext(NonCancellable) { backend.playback.setSpeed(1f) }
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
        backend.playback.stop()
    }

    suspend fun startListening(
        group: VoiceGroup,
        calibration: TalkerCalibration?,
        fixtureText: String? = null,
        fixtureLanguage: Language = Language.Ro,
        /** Made once the stream's rate is known; it then sees every hop (the speech coach). */
        tap: ((sampleRate: Double) -> HopTap)? = null,
        /** His calibrated room and voice („Calibrare”): the live floor starts at his room, the level spans his voice. */
        room: tech.fonia.judge.PersonalReference.Room? = null,
        /** The Romanian sound this take practises: the live spotter listens for it. */
        spotSound: tech.fonia.phones.TargetSound? = null,
    ) {
        prepare()
        stopSpeaking()
        capture?.discard()
        gate.reset()
        // `FixtureMode.captureSource`: the clip of the text, else the clip of "soare", else the microphone.
        val fixture = fixtureText?.let { clipURL(it, fixtureLanguage) }
        val fallbackFixture = clipURL(FixtureMode.FALLBACK_TEXT, Language.Ro)
        val source: TakeSource = when {
            micFile != null -> TakeSource.Fixture(ClipLocation.File(nextMicFile(fixtureText)), thenSilence = true)
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
        spot.begin(spotSound)
        analysisThresholds = DSPThresholds.v1
        val sourceName = when (source) {
            is TakeSource.Fixture -> if (micFile != null) "micFile:${source.clip.name}" else "fixture:${source.clip.name}"
            TakeSource.Microphone -> "microphone"
        }
        try {
            engine.start(levels) { rate ->
                // The processor is made once the stream runs, so the stream's real rate and device are known here.
                val thresholds = if (sibilantsOff(engine.captureInfo, rate)) withoutSibilants else DSPThresholds.v1
                analysisThresholds = thresholds
                val info = engine.captureInfo
                val stats = TakeStats(rate, sourceName)
                take = stats
                log(
                    "take start: source=$sourceName preset=${info?.inputPreset ?: "-"} requested=${info?.requestedPreset ?: "-"} " +
                        "rate=${rate.toInt()} device=${info?.deviceType ?: "-"} narrowBand=${info?.isNarrowBand ?: "-"} " +
                        "effectsOff=${info?.platformEffectsSwitchedOff ?: "-"} group=${group.rawValue}",
                )
                val own = tap?.invoke(rate)
                val spotTap = spot.tap(rate)
                val both = if (own != null && spotTap != null) HopTap { s, o, c, f -> own.hop(s, o, c, f); spotTap.hop(s, o, c, f) } else own ?: spotTap
                AnalyzerProcessor(rate, group, calibration, live(thresholds, room), gate, stats, both)
            }
        } catch (e: Exception) {
            capture = null
            spot.end()
            captureClosed()
            log("take start failed: $e")
            throw e
        }
    }

    /** Where the last take's hiss sat on the Ș…S axis, live (NaN: no hiss heard). */
    fun lastTakeHiss(): Float = take?.hissMedian() ?: Float.NaN

    /** Median pitch of the voiced hops of the last take (NaN: no voice), for the talker tracker. */
    fun lastTakePitch(): Float = take?.pitchMedian() ?: Float.NaN

    private fun captureClosed() {
        if (!FoniaSfx.gate.isCapturing) return
        FoniaSfx.gate.captureClosed()
        FoniaSfx.log("capture closed t=${FoniaSfx.gate.clock()}")
    }

    suspend fun stopListening(): CapturedClip? {
        val engine = capture ?: return null
        capture = null
        spot.end()
        val clip = try {
            engine.stop()
        } finally {
            captureClosed()
        }
        levels.publish()
        take?.let {
            log("take end: ${"%.1f".format(it.seconds)} s peak=${"%.1f".format(it.peakDb)} dBFS f0=${"%.0f".format(it.pitchMedian())} Hz")
        }
        return clip
    }

    suspend fun cancelListening() {
        spot.end()
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
        /** "Nu te aud": the microphone has been open this long ... */
        const val SILENT_AFTER_SECONDS = 2.0

        /** ... and nothing in it reached this peak (-66 dBFS: under any room's noise on a working phone microphone). */
        const val SILENT_PEAK_DB = -66f

        /**
         * The live analysis' noise floor, for the letter and the end of the take only (the judge reads the clip with
         * [analysisThresholds]): v1 lets the floor climb 30 dB/s for the first 1.2 s, so a "sss" started right after the
         * beep became the floor within a second and the held bar stopped (a man's held S, 2026-11-23: 108 of 362 hiss
         * hops heard). The floor now starts at -55 dB and learns fast only 0.5 s at 12 dB/s, and a voice is 9 dB above
         * it: an immediate hiss stays heard (362 of 362), a -45 dB room is still learnt as the floor.
         */
        fun live(thresholds: DSPThresholds, room: tech.fonia.judge.PersonalReference.Room? = null): DSPThresholds = thresholds.copy(
            live = thresholds.live.copy(
                // With „Calibrare” the floor starts just over his measured room, and 0…1 spans his room to his voice.
                initialFloorDb = room?.let { (it.floorDb + 3f).coerceIn(-90f, -45f) } ?: -55f,
                // A measured room needs no fast learning: a held "aaa" or "sss" must not become the floor.
                floorLearnSeconds = if (room != null) 0.1f else 0.5f,
                floorLearnRiseDbPerSecond = 12f,
                vadMarginDb = 9f,
                levelRangeDb = room?.let { (it.speechDb - it.floorDb - 4f).coerceIn(18f, 40f) } ?: thresholds.live.levelRangeDb,
            ),
        )

        internal fun log(message: String) {
            // runCatching: android.util.Log is a stub that throws in the JVM unit tests.
            runCatching { android.util.Log.i("FoniaMic", message) }
        }

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
