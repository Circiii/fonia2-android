package tech.fonia.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The microphone source preset. iOS gets an unprocessed signal from the session's `.measurement` mode;
 * Android asks for it per stream. Never `VOICE_COMMUNICATION`: echo cancelling, gain control and noise
 * suppression erase sibilants.
 */
enum class InputPreset(val audioSource: Int) {
    /** No processing at all (CDD 5.11: no AGC, no high-pass, no echo cancelling). Optional on a phone. */
    Unprocessed(MediaRecorder.AudioSource.UNPROCESSED),

    /** Noise suppression and gain control off by default (CDD 5.4.2), and guaranteed at 44.1 or 48 kHz. */
    VoiceRecognition(MediaRecorder.AudioSource.VOICE_RECOGNITION),
    ;

    companion object {
        /** `UNPROCESSED` where the phone says it supports it, `VOICE_RECOGNITION` otherwise. */
        fun preferred(audioManager: AudioManager): InputPreset =
            preferred(audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED))

        internal fun preferred(unprocessedProperty: String?): InputPreset =
            if (unprocessedProperty == "true") Unprocessed else VoiceRecognition

        fun fromAudioSource(source: Int): InputPreset? = entries.firstOrNull { it.audioSource == source }
    }
}

/** What the microphone stream really opened with. S/Ș analysis checks [isNarrowBand] before it runs. */
data class CaptureInfo(
    val sampleRate: Double,
    /** The preset the stream reports; null if it reports one outside [InputPreset]. */
    val inputPreset: InputPreset?,
    val requestedPreset: InputPreset,
    /** `AudioDeviceInfo.TYPE_*` of the input, null when the system did not name it. */
    val deviceType: Int?,
    val channelCount: Int,
    /** Oboe's performance mode is LowLatency when the stream got the fast path. */
    val isLowLatency: Boolean,
    val framesPerBurst: Int,
    /** Platform pre-processing that was attached and switched on before this module switched it off. */
    val platformEffectsSwitchedOff: List<String>,
) {
    /** A Bluetooth headset microphone (SCO or LE audio), whose narrow band hurts sibilant analysis. */
    val isBluetoothMic: Boolean
        get() = deviceType == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || deviceType == AudioDeviceInfo.TYPE_BLE_HEADSET

    /** Under 32 kHz, or a Bluetooth microphone: S/Ș cannot be judged, as on iOS (inventory §13.2). */
    val isNarrowBand: Boolean get() = isBluetoothMic || sampleRate < NARROW_BAND_RATE

    /** What the attempt log calls this input ([microphone]). */
    val microphone: String get() = microphone(deviceType)

    companion object {
        const val NARROW_BAND_RATE = 32_000.0

        /**
         * What the attempt log calls the microphone of a take, as iOS `AudioSessionMapping.microphone` does: `phone`,
         * `wired`, `bluetooth`, `usb`, or `other`, also for an input the system did not name. With no capture at all
         * the caller writes `none`.
         */
        fun microphone(deviceType: Int?): String = when (deviceType) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "phone"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> "bluetooth"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "usb"
            else -> "other"
        }
    }
}

/**
 * Captures the microphone through an Oboe input stream whose callback is C++, so no Kotlin runs on the
 * audio thread (iOS: an `AVAudioSinkNode` whose block is C). 48 kHz mono float, low latency, the
 * [InputPreset.preferred] preset, the built-in microphone preferred, platform noise suppression and gain
 * control switched off if the phone attached them anyway.
 */
class MicrophoneSource internal constructor(context: Context, hasPermission: () -> Boolean) : CaptureSource {
    constructor(context: Context) : this(context, {
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    })

    private val graph = MicrophoneGraph(context.applicationContext, hasPermission)

    override val sampleRate: Double get() = graph.currentRate

    /** The rate, preset, device and narrow-band verdict of the stream running now, or of the last one. */
    val captureInfo: CaptureInfo? get() = graph.info

    override suspend fun start(ring: CaptureRing): Double = graph.start(ring)

    /**
     * Oboe's estimate of the input latency right now (age of the newest sample the callback delivered), in ms, or
     * null when the stream cannot tell. Safe from any thread; the live pipeline uses it for the capture clock.
     */
    fun inputLatencyMillis(): Double? = graph.latencyMillis()

    override suspend fun stop() = graph.stop()
}

/** iOS's `MicrophoneGraph` actor: one dispatcher at a time, suspending where the actor awaits. */
private class MicrophoneGraph(private val context: Context, private val hasPermission: () -> Boolean) {
    companion object {
        const val TAG = "Microphone"
        const val REBUILD_ATTEMPTS = 3
        const val REBUILD_DELAY_MS = 200L
        const val PREFERRED_RATE = 48_000
        const val STREAM_STATE_STARTED = 4
        const val PERFORMANCE_LOW_LATENCY = 12
        const val FORMAT_I16 = 1
        const val FORMAT_FLOAT = 2
        const val FORMAT_I32 = 4
    }

    private val dispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val audioManager = context.getSystemService(AudioManager::class.java)

    @Volatile
    var currentRate = 0.0
        private set

    @Volatile
    var info: CaptureInfo? = null
        private set

    /** Retained for as long as a stream may run, as iOS retains the ring for the sink block. */
    private var ring: NativeCaptureRing? = null
    @Volatile private var engine: NativeCapture? = null
    private var effects: PlatformEffects? = null
    private var observer: AudioGraphObserver? = null
    private var listening: Job? = null

    suspend fun start(ring: CaptureRing): Double = withContext(dispatcher) {
        if (!hasPermission()) throw CaptureError.MicrophoneDenied()
        require(ring is NativeCaptureRing) { "The microphone writes only into a native ring" }
        val observer = AudioGraphObserver(context)
        this@MicrophoneGraph.observer = observer
        this@MicrophoneGraph.ring = ring
        try {
            buildGraph(ring)
        } catch (e: Throwable) {
            stopNow()
            throw e
        }
        SharedAudioSession.setPrefersNoInterruptionsFromSystemAlerts(true)
        listening = scope.launch {
            for (event in observer.events) handle(event)
        }
        currentRate
    }

    suspend fun stop() = withContext(dispatcher + NonCancellable) { stopNow() }

    private fun stopNow() {
        tearDownGraph()
        listening?.cancel()
        listening = null
        observer?.close()
        observer = null
        ring = null
        SharedAudioSession.setPrefersNoInterruptionsFromSystemAlerts(false)
    }

    private suspend fun handle(event: AudioGraphEvent) {
        when (event) {
            AudioGraphEvent.ConfigurationChanged, AudioGraphEvent.MediaServicesReset -> rebuild()
            is AudioGraphEvent.InterruptionEnded -> if (event.shouldResume) rebuild()
            AudioGraphEvent.RouteChanged -> if (graphIsStale) rebuild()
            AudioGraphEvent.InterruptionBegan, AudioGraphEvent.MediaServicesLost -> tearDownGraph()
        }
    }

    /**
     * A route change often leaves the stream running at the same rate. Rebuilding then would only punch a
     * gap in the take. On Android a stream whose device went away is disconnected, so it is no longer started.
     */
    private val graphIsStale: Boolean
        get() {
            val engine = engine ?: return true
            if (engine.state != STREAM_STATE_STARTED) return true
            return engine.sampleRate.toDouble() != currentRate
        }

    /** Retries because the audio system may still be coming back when the signal arrives. */
    private suspend fun rebuild() {
        tearDownGraph()
        repeat(REBUILD_ATTEMPTS) {
            val ring = ring ?: return
            if (engine != null) return
            try {
                buildGraph(ring)
                return
            } catch (e: Exception) {
                Log.e(TAG, "Microphone rebuild failed: ${e.message}")
                delay(REBUILD_DELAY_MS)
            }
        }
    }

    private fun buildGraph(ring: NativeCaptureRing) {
        val preset = InputPreset.preferred(audioManager)
        // Scoring needs the full bandwidth of the built-in microphone. Only a preference, as on iOS:
        // CaptureInfo reports the device the stream really got.
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val builtIn = inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }?.id ?: 0
        val observer = observer
        val engine = NativeCapture(ring) { observer?.yield(AudioGraphEvent.ConfigurationChanged) }

        val opened = engine.open(PREFERRED_RATE, preset.audioSource, builtIn)
        if (opened != 0 || engine.sampleRate <= 0 || engine.channelCount <= 0) {
            engine.close()
            throw CaptureError.NoInput()
        }
        if (engine.format != FORMAT_FLOAT && engine.format != FORMAT_I16 && engine.format != FORMAT_I32) {
            engine.close()
            throw CaptureError.UnsupportedInputFormat()
        }
        val effects = PlatformEffects.switchOff(engine.sessionId)
        val started = engine.start()
        if (started != 0) {
            effects.release()
            engine.close()
            throw IOException("The microphone stream did not start (Oboe result $started)")
        }

        currentRate = engine.sampleRate.toDouble()
        val device = inputs.firstOrNull { it.id == engine.deviceId }
            ?: audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == engine.deviceId }
        info = CaptureInfo(
            sampleRate = currentRate,
            inputPreset = InputPreset.fromAudioSource(engine.inputPreset),
            requestedPreset = preset,
            deviceType = device?.type,
            channelCount = engine.channelCount,
            isLowLatency = engine.performanceMode == PERFORMANCE_LOW_LATENCY,
            framesPerBurst = engine.framesPerBurst,
            platformEffectsSwitchedOff = effects.switchedOff,
        )
        Log.i(TAG, "Capturing: ${info}")
        this.engine = engine
        this.effects = effects
    }

    fun latencyMillis(): Double? = engine?.latencyMillis()?.takeIf { it >= 0.0 }

    private fun tearDownGraph() {
        engine?.close()
        engine = null
        effects?.release()
        effects = null
    }
}

/** One Oboe input stream (FoniaCapture.cpp) writing into a native ring. */
internal class NativeCapture(ring: NativeCaptureRing, onError: (Int) -> Unit) : AutoCloseable {
    private val listenerId = NativeAudio.registerErrorListener(onError)
    private val handle: Long = ring.withHandle(0L) { NativeAudio.captureCreate(it, listenerId) }
    private val closed = AtomicBoolean(false)

    var sampleRate = 0
        private set
    var channelCount = 0
        private set
    var format = 0
        private set
    var inputPreset = 0
        private set
    var deviceId = 0
        private set
    var framesPerBurst = 0
        private set
    var performanceMode = 0
        private set
    var sessionId = 0
        private set

    init {
        check(handle != 0L) { "Cannot create a capture stream" }
    }

    /** Returns 0 (oboe::Result::OK) or Oboe's error code. */
    fun open(sampleRate: Int, inputPreset: Int, deviceId: Int): Int {
        val info = IntArray(9)
        val result = NativeAudio.captureOpen(handle, sampleRate, inputPreset, deviceId, info)
        if (result == 0) {
            this.sampleRate = info[0]
            channelCount = info[1]
            format = info[2]
            this.inputPreset = info[3]
            this.deviceId = info[4]
            framesPerBurst = info[5]
            performanceMode = info[7]
            sessionId = info[8]
        }
        return result
    }

    fun start(): Int = NativeAudio.captureStart(handle)

    /** Oboe's input latency in ms, -1 when unknown or closed. Synchronized with [close], so the handle stays valid. */
    fun latencyMillis(): Double = synchronized(closed) {
        if (closed.get()) -1.0 else NativeAudio.captureLatencyMillis(handle)
    }

    /** oboe::StreamState. */
    val state: Int get() = NativeAudio.captureState(handle)

    /** Runs the data callback's delivery on [interleaved], without any stream. */
    fun deliver(interleaved: FloatArray, frames: Int, channels: Int): Int =
        NativeAudio.captureDeliver(handle, interleaved, frames, channels)

    override fun close() {
        synchronized(closed) {
            if (!closed.compareAndSet(false, true)) return
            NativeAudio.captureClose(handle)
            NativeAudio.captureDestroy(handle)
        }
        NativeAudio.unregisterErrorListener(listenerId)
    }
}

/**
 * Platform pre-processing on the capture session. UNPROCESSED and VOICE_RECOGNITION should carry none, but
 * a phone may attach some anyway (02 §c.4). Each one found switched on is switched off and kept for the life
 * of the stream; handles to effects that were off are released at once.
 */
internal class PlatformEffects private constructor(private val kept: List<AudioEffect>, val switchedOff: List<String>) {
    fun release() {
        for (effect in kept) runCatching { effect.release() }
    }

    companion object {
        private const val TAG = "Microphone"

        fun switchOff(sessionId: Int): PlatformEffects {
            if (sessionId <= 0) return PlatformEffects(emptyList(), emptyList())
            val kept = mutableListOf<AudioEffect>()
            val switchedOff = mutableListOf<String>()
            fun check(name: String, available: Boolean, create: () -> AudioEffect?) {
                if (!available) return
                val effect = runCatching { create() }.getOrNull() ?: return
                val wasOn = runCatching { effect.enabled }.getOrDefault(false)
                if (wasOn) {
                    val result = runCatching { effect.setEnabled(false) }.getOrDefault(AudioEffect.ERROR)
                    if (result != AudioEffect.SUCCESS) Log.e(TAG, "$name stays on: the platform did not let go of it")
                    switchedOff += name
                    kept += effect
                } else {
                    runCatching { effect.release() }
                }
            }
            check("NoiseSuppressor", NoiseSuppressor.isAvailable()) { NoiseSuppressor.create(sessionId) }
            check("AutomaticGainControl", AutomaticGainControl.isAvailable()) { AutomaticGainControl.create(sessionId) }
            check("AcousticEchoCanceler", AcousticEchoCanceler.isAvailable()) { AcousticEchoCanceler.create(sessionId) }
            return PlatformEffects(kept, switchedOff)
        }
    }
}
