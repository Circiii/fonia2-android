package tech.fonia.audio

/** The parts of the current audio route the app acts on. */
data class AudioRoute(
    /** The input is a Bluetooth microphone, whose narrow bandwidth hurts sibilant analysis. */
    val isBluetoothMic: Boolean = false,
    /** Output goes to headphones, so a model voice will not leak into the microphone. */
    val usesHeadphones: Boolean = false,
    val hasInput: Boolean = false,
)

/** What the rest of the app needs to know about the audio session. */
sealed interface AudioState {
    data object Inactive : AudioState

    data class Ready(val route: AudioRoute) : AudioState

    /** The microphone is not allowed. Playback still works once the session is active. */
    data class MicrophoneDenied(val route: AudioRoute) : AudioState

    /** A call or another app has taken the session (on Android: audio focus). */
    data object Interrupted : AudioState

    /** The media server died. It is rebuilt when the system resets it. */
    data object MediaServicesLost : AudioState
}

internal sealed interface AudioSessionEvent {
    data class Activated(val route: AudioRoute) : AudioSessionEvent

    data object Deactivated : AudioSessionEvent

    data object InterruptionBegan : AudioSessionEvent

    data object InterruptionEnded : AudioSessionEvent

    data class RouteChanged(val route: AudioRoute) : AudioSessionEvent

    data object MediaServicesLost : AudioSessionEvent

    data object MediaServicesReset : AudioSessionEvent

    data class MicrophonePermission(val granted: Boolean) : AudioSessionEvent
}

/** The single place that turns session events into an [AudioState]. */
internal class AudioStateMachine {
    private var isActive = false
    private var isInterrupted = false
    private var servicesLost = false
    private var microphoneDenied = false
    private var route = AudioRoute()

    val state: AudioState
        get() = when {
            servicesLost -> AudioState.MediaServicesLost
            isInterrupted -> AudioState.Interrupted
            microphoneDenied -> AudioState.MicrophoneDenied(route)
            isActive -> AudioState.Ready(route)
            else -> AudioState.Inactive
        }

    fun apply(event: AudioSessionEvent) {
        when (event) {
            is AudioSessionEvent.Activated -> {
                isActive = true
                isInterrupted = false
                route = event.route
            }
            AudioSessionEvent.Deactivated -> isActive = false
            AudioSessionEvent.InterruptionBegan -> {
                isActive = false
                isInterrupted = true
            }
            AudioSessionEvent.InterruptionEnded -> isInterrupted = false
            is AudioSessionEvent.RouteChanged -> route = event.route
            AudioSessionEvent.MediaServicesLost -> {
                isActive = false
                servicesLost = true
            }
            AudioSessionEvent.MediaServicesReset -> {
                isActive = false
                isInterrupted = false
                servicesLost = false
            }
            is AudioSessionEvent.MicrophonePermission -> microphoneDenied = !event.granted
        }
    }
}
