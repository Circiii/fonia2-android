package tech.fonia.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch

/** The system refused to activate the session (iOS: the error `AVAudioSession.setActive` throws). */
class AudioSessionException(message: String) : Exception(message)

/**
 * Owns the shared audio session and funnels everything that happens to it into one [AudioState] stream.
 *
 * iOS is an actor; here every method is thread-safe and the state changes under one lock.
 * Android has no session category: [configure] records what is wanted, [activate] takes audio focus,
 * and the capture stream itself asks for 48 kHz and the input preset (see [MicrophoneSource]).
 */
class AudioSessionController(context: Context) {
    private companion object {
        const val TAG = "AudioSession"
    }

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val lock = Any()
    private val machine = AudioStateMachine()
    private val subscribers = mutableListOf<Channel<AudioState>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wantsActive = false
    private var observer: AudioGraphObserver? = null

    /** Whether the last [configure] asked for playback only (the recorded-take mode). */
    @Volatile
    var isPlaybackOnly: Boolean = false
        private set

    /**
     * Current state first, then every change. Each read of this property makes an independent stream,
     * which keeps the newest 8 states when its collector falls behind.
     */
    val state: Flow<AudioState>
        get() {
            startObserving()
            val channel = Channel<AudioState>(8, BufferOverflow.DROP_OLDEST)
            synchronized(lock) {
                subscribers += channel
                channel.trySend(machine.state)
            }
            return channel.consumeAsFlow().onCompletion { synchronized(lock) { subscribers -= channel } }
        }

    /**
     * Do this before capture starts. `playbackOnly` is for recorded-take runs: nothing asks for the
     * microphone, so no permission prompt appears. iOS also sets 48 kHz and a 5 ms I/O buffer here;
     * on Android those are properties of each Oboe stream ([MicrophoneSource]).
     */
    fun configure(playbackOnly: Boolean = false) {
        startObserving()
        isPlaybackOnly = playbackOnly
    }

    /** Takes audio focus. Throws [AudioSessionException] when the system refuses it, during a call for example. */
    fun activate() {
        startObserving()
        synchronized(lock) { wantsActive = true }
        if (!SharedAudioSession.activate(appContext)) {
            throw AudioSessionException("Audio focus was refused")
        }
        apply(AudioSessionEvent.Activated(currentRoute()))
    }

    /** Stop capture and playback first, so nothing is left playing without focus. */
    fun deactivate() {
        synchronized(lock) { wantsActive = false }
        SharedAudioSession.deactivate()
        apply(AudioSessionEvent.Deactivated)
    }

    /**
     * iOS shows the system prompt itself. On Android the prompt needs an Activity, so the caller passes
     * [prompt] (an `ActivityResultLauncher` for `RECORD_AUDIO`); it is skipped when the permission is held.
     */
    suspend fun requestMicrophone(prompt: suspend () -> Boolean = { false }): Boolean {
        startObserving()
        val granted = hasMicrophonePermission() || prompt()
        apply(AudioSessionEvent.MicrophonePermission(granted))
        return granted
    }

    private fun hasMicrophonePermission(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startObserving() {
        val created: AudioGraphObserver
        synchronized(lock) {
            if (observer != null) return
            created = AudioGraphObserver(appContext)
            observer = created
            // iOS starts from `recordPermission != .denied`. Android cannot tell "not asked yet" without an
            // Activity, so a permission that is not granted counts as denied.
            machine.apply(AudioSessionEvent.MicrophonePermission(hasMicrophonePermission()))
            machine.apply(AudioSessionEvent.RouteChanged(currentRoute()))
        }
        scope.launch {
            for (event in created.events) handle(event)
        }
    }

    private fun handle(event: AudioGraphEvent) {
        when (event) {
            AudioGraphEvent.ConfigurationChanged -> Unit
            AudioGraphEvent.InterruptionBegan -> apply(AudioSessionEvent.InterruptionBegan)
            is AudioGraphEvent.InterruptionEnded -> {
                apply(AudioSessionEvent.InterruptionEnded)
                if (event.shouldResume && synchronized(lock) { wantsActive }) restore()
            }
            AudioGraphEvent.RouteChanged -> apply(AudioSessionEvent.RouteChanged(currentRoute()))
            AudioGraphEvent.MediaServicesLost -> apply(AudioSessionEvent.MediaServicesLost)
            AudioGraphEvent.MediaServicesReset -> {
                apply(AudioSessionEvent.MediaServicesReset)
                restore()
            }
        }
    }

    /** Reapplies the configuration after the system took the session away. Like iOS, always the full one. */
    private fun restore() {
        try {
            configure()
            if (synchronized(lock) { wantsActive }) activate()
        } catch (e: AudioSessionException) {
            Log.e(TAG, "Could not restore the audio session: ${e.message}")
        }
    }

    private fun apply(event: AudioSessionEvent) {
        synchronized(lock) {
            val previous = machine.state
            machine.apply(event)
            val current = machine.state
            if (current == previous) return
            subscribers.removeAll { it.trySend(current).isClosed }
        }
    }

    /**
     * iOS reads `currentRoute`. Android names no active route before a stream opens, so this is the route
     * a recording and a media stream would take now: a Bluetooth microphone only while SCO is on (a
     * recording never moves there otherwise), then a wired or USB microphone, then the built-in one.
     * The capture stream reports the device it really got ([CaptureInfo]).
     */
    private fun currentRoute(): AudioRoute {
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).map { it.type }
        @Suppress("DEPRECATION")
        val scoOn = audioManager.isBluetoothScoOn
        val input = when {
            scoOn && AudioDeviceInfo.TYPE_BLUETOOTH_SCO in inputs -> AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            AudioDeviceInfo.TYPE_WIRED_HEADSET in inputs -> AudioDeviceInfo.TYPE_WIRED_HEADSET
            AudioDeviceInfo.TYPE_USB_HEADSET in inputs -> AudioDeviceInfo.TYPE_USB_HEADSET
            AudioDeviceInfo.TYPE_USB_DEVICE in inputs -> AudioDeviceInfo.TYPE_USB_DEVICE
            AudioDeviceInfo.TYPE_BUILTIN_MIC in inputs -> AudioDeviceInfo.TYPE_BUILTIN_MIC
            else -> inputs.firstOrNull()
        }
        return AudioSessionMapping.route(listOfNotNull(input), currentOutputs())
    }

    private fun currentOutputs(): List<Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return audioManager.getAudioDevicesForAttributes(SharedAudioSession.mediaAttributes).map { it.type }
        }
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
        val preferred = listOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_HEARING_AID,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        )
        return listOfNotNull(preferred.firstOrNull { it in outputs } ?: outputs.firstOrNull())
    }
}
