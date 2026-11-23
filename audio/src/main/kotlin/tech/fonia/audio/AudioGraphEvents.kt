package tech.fonia.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import java.util.concurrent.CopyOnWriteArraySet

/** System signals that an audio graph or the session must react to. */
internal sealed interface AudioGraphEvent {
    /** iOS: `AVAudioEngineConfigurationChange`. Android: the Oboe stream was disconnected and closed. */
    data object ConfigurationChanged : AudioGraphEvent

    data object InterruptionBegan : AudioGraphEvent

    data class InterruptionEnded(val shouldResume: Boolean) : AudioGraphEvent

    data object RouteChanged : AudioGraphEvent

    /** Android has no broadcast for a dead audio server; kept so the graphs handle it the iOS way when it is raised. */
    data object MediaServicesLost : AudioGraphEvent

    data object MediaServicesReset : AudioGraphEvent
}

/**
 * Turns audio notifications into a channel of [AudioGraphEvent], for the owner of this observer.
 * The callbacks only decode and send, because a graph must not be torn down inside them.
 *
 * iOS observes `NotificationCenter`; here the sources are the shared audio focus listener
 * ([SharedAudioSession], for interruptions) and an `AudioDeviceCallback` (for route changes).
 */
internal class AudioGraphObserver(context: Context) : AutoCloseable {
    private val channel = Channel<AudioGraphEvent>(8, BufferOverflow.DROP_OLDEST)
    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var knownDevices: Set<Int> = currentDevices()

    private val sessionListener: (AudioGraphEvent) -> Unit = { channel.trySend(it) }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = devicesChanged()

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = devicesChanged()
    }

    val events: ReceiveChannel<AudioGraphEvent> get() = channel

    init {
        SharedAudioSession.addListener(sessionListener)
        audioManager.registerAudioDeviceCallback(deviceCallback, handler)
    }

    /** iOS `watch(engine)`: the owner of a graph reports its own configuration changes here. */
    fun yield(event: AudioGraphEvent) {
        channel.trySend(event)
    }

    /** Registering the callback replays every connected device; only a real difference is a route change. */
    private fun devicesChanged() {
        val now = currentDevices()
        if (now != knownDevices) {
            knownDevices = now
            channel.trySend(AudioGraphEvent.RouteChanged)
        }
    }

    private fun currentDevices(): Set<Int> = audioManager.getDevices(AudioManager.GET_DEVICES_ALL).mapTo(HashSet()) { it.id }

    override fun close() {
        SharedAudioSession.removeListener(sessionListener)
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        channel.close()
    }
}

/** Pure translations from Android audio values to this module's types (iOS: `AudioSessionMapping`). */
internal object AudioSessionMapping {
    private val bluetoothInputs = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET)
    private val headphoneOutputs = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_HEARING_AID,
    )

    /**
     * iOS decodes `AVAudioSession.interruptionNotification`. Android's equivalent is an audio focus change:
     * a loss begins an interruption, and focus coming back ends it with "should resume". A duckable loss is
     * no interruption: the system lowers the volume by itself, as iOS mixes alerts over the session.
     */
    fun interruptionEvent(focusChange: Int?): AudioGraphEvent? = when (focusChange) {
        AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> AudioGraphEvent.InterruptionBegan
        AudioManager.AUDIOFOCUS_GAIN -> AudioGraphEvent.InterruptionEnded(shouldResume = true)
        else -> null
    }

    /** Device types (`AudioDeviceInfo.TYPE_*`) of the current inputs and outputs, as iOS maps port types. */
    fun route(inputs: List<Int>, outputs: List<Int>): AudioRoute = AudioRoute(
        isBluetoothMic = inputs.any { it in bluetoothInputs },
        usesHeadphones = outputs.any { it in headphoneOutputs },
        hasInput = inputs.isNotEmpty(),
    )
}

/**
 * The process-wide audio session, the Android stand-in for `AVAudioSession.sharedInstance()`.
 * Android has no session object: what iOS activates is audio focus, held here with one listener whose
 * changes reach every [AudioGraphObserver].
 */
internal object SharedAudioSession {
    private const val TAG = "AudioSession"

    /** USAGE_MEDIA + CONTENT_TYPE_SPEECH: speech practice, never a call, so no voice-call processing. */
    val mediaAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val listeners = CopyOnWriteArraySet<(AudioGraphEvent) -> Unit>()
    private var audioManager: AudioManager? = null
    private var request: AudioFocusRequest? = null
    private var prefersNoSystemAlerts = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        val event = AudioSessionMapping.interruptionEvent(change) ?: return@OnAudioFocusChangeListener
        for (listener in listeners) listener(event)
    }

    fun addListener(listener: (AudioGraphEvent) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (AudioGraphEvent) -> Unit) {
        listeners -= listener
    }

    /**
     * iOS `.playAndRecord` interrupts other audio and `.notifyOthersOnDeactivation` lets it carry on later:
     * a transient focus gain does both. While a take runs, the gain is exclusive, so notification sounds
     * stay quiet (iOS `setPrefersNoInterruptionsFromSystemAlerts`).
     */
    @get:Synchronized
    val focusGain: Int
        get() = if (prefersNoSystemAlerts) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT

    @get:Synchronized
    val holdsFocus: Boolean get() = request != null

    /** Takes audio focus. False when the system refuses it, during a phone call for example. */
    @Synchronized
    fun activate(context: Context): Boolean {
        val manager = manager(context)
        val focus = makeRequest()
        if (manager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
        request = focus
        return true
    }

    @Synchronized
    fun deactivate() {
        request?.let { audioManager?.abandonAudioFocusRequest(it) }
        request = null
    }

    /** Asking again with the same listener changes the gain without losing focus to ourselves. */
    @Synchronized
    fun setPrefersNoInterruptionsFromSystemAlerts(prefers: Boolean) {
        if (prefers == prefersNoSystemAlerts) return
        prefersNoSystemAlerts = prefers
        val manager = audioManager ?: return
        if (request == null) return
        val focus = makeRequest()
        if (manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            request = focus
        } else {
            Log.e(TAG, "System alert preference failed: focus request refused")
        }
    }

    private fun manager(context: Context): AudioManager =
        audioManager ?: context.applicationContext.getSystemService(AudioManager::class.java).also { audioManager = it }

    private fun makeRequest(): AudioFocusRequest = AudioFocusRequest.Builder(focusGain)
        .setAudioAttributes(mediaAttributes)
        .setOnAudioFocusChangeListener(focusListener, Handler(Looper.getMainLooper()))
        .setWillPauseWhenDucked(false)
        .build()
}
