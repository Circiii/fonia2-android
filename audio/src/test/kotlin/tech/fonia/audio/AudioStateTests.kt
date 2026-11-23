package tech.fonia.audio

import android.media.AudioDeviceInfo
import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of AudioStateTests.swift, suite "Audio state machine". */
class AudioStateMachineTests {
    private val speaker = AudioRoute(hasInput = true)
    private val bluetooth = AudioRoute(isBluetoothMic = true, usesHeadphones = true, hasInput = true)

    private fun state(events: List<AudioSessionEvent>): AudioState {
        val machine = AudioStateMachine()
        events.forEach(machine::apply)
        return machine.state
    }

    /** Starts inactive. */
    @Test
    fun initialState() {
        assertEquals(AudioState.Inactive, AudioStateMachine().state)
    }

    /** Activation carries the route, and deactivation clears it. */
    @Test
    fun activation() {
        assertEquals(AudioState.Ready(speaker), state(listOf(AudioSessionEvent.Activated(speaker))))
        assertEquals(AudioState.Inactive, state(listOf(AudioSessionEvent.Activated(speaker), AudioSessionEvent.Deactivated)))
    }

    /** A route change while active updates the Bluetooth flag. */
    @Test
    fun routeChange() {
        val result = state(listOf(AudioSessionEvent.Activated(speaker), AudioSessionEvent.RouteChanged(bluetooth)))
        assertEquals(AudioState.Ready(bluetooth), result)
        val ready = result as? AudioState.Ready ?: error("Expected ready")
        assertTrue(ready.route.isBluetoothMic)
    }

    /** A route change while inactive shows on the next activation only. */
    @Test
    fun routeChangeWhileInactive() {
        assertEquals(AudioState.Inactive, state(listOf(AudioSessionEvent.RouteChanged(bluetooth))))
    }

    /** An interruption leaves the session inactive until it is reactivated. */
    @Test
    fun interruption() {
        val began = listOf(AudioSessionEvent.Activated(speaker), AudioSessionEvent.InterruptionBegan)
        assertEquals(AudioState.Interrupted, state(began))
        assertEquals(AudioState.Interrupted, state(began + AudioSessionEvent.RouteChanged(bluetooth)))
        assertEquals(AudioState.Inactive, state(began + AudioSessionEvent.InterruptionEnded))
        assertEquals(
            AudioState.Ready(speaker),
            state(began + listOf(AudioSessionEvent.InterruptionEnded, AudioSessionEvent.Activated(speaker))),
        )
    }

    /** A denied microphone is reported, and survives an interruption. */
    @Test
    fun permission() {
        val denied = listOf<AudioSessionEvent>(AudioSessionEvent.MicrophonePermission(granted = false))
        val activated = AudioSessionEvent.Activated(speaker)
        assertEquals(AudioState.MicrophoneDenied(AudioRoute()), state(denied))
        assertEquals(AudioState.MicrophoneDenied(speaker), state(denied + activated))
        assertEquals(
            AudioState.MicrophoneDenied(bluetooth),
            state(denied + listOf(activated, AudioSessionEvent.RouteChanged(bluetooth))),
        )
        assertEquals(AudioState.Interrupted, state(denied + listOf(activated, AudioSessionEvent.InterruptionBegan)))
        assertEquals(
            AudioState.MicrophoneDenied(speaker),
            state(denied + listOf(activated, AudioSessionEvent.InterruptionBegan, AudioSessionEvent.InterruptionEnded)),
        )
        assertEquals(
            AudioState.Ready(speaker),
            state(denied + listOf(activated, AudioSessionEvent.MicrophonePermission(granted = true))),
        )
    }

    /** Losing media services outranks everything until the reset. */
    @Test
    fun mediaServices() {
        val lost = listOf(
            AudioSessionEvent.Activated(speaker),
            AudioSessionEvent.InterruptionBegan,
            AudioSessionEvent.MicrophonePermission(granted = false),
            AudioSessionEvent.MediaServicesLost,
        )
        assertEquals(AudioState.MediaServicesLost, state(lost))
        assertEquals(AudioState.MediaServicesLost, state(lost + AudioSessionEvent.RouteChanged(bluetooth)))
        assertEquals(
            AudioState.Inactive,
            state(listOf(AudioSessionEvent.Activated(speaker), AudioSessionEvent.MediaServicesLost, AudioSessionEvent.MediaServicesReset)),
        )
        assertEquals(
            AudioState.Ready(speaker),
            state(
                listOf(
                    AudioSessionEvent.Activated(speaker),
                    AudioSessionEvent.MediaServicesLost,
                    AudioSessionEvent.MediaServicesReset,
                    AudioSessionEvent.Activated(speaker),
                ),
            ),
        )
    }
}

/**
 * Port of AudioStateTests.swift, suite "Audio session mapping". iOS decodes interruption notifications and
 * maps port types; Android decodes audio focus changes and maps `AudioDeviceInfo` types (see PARITY.md).
 */
class AudioSessionMappingTests {
    /** Interruption notifications decode (here: focus changes). */
    @Test
    fun interruptions() {
        val cases = listOf(
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT to AudioGraphEvent.InterruptionBegan,
            AudioManager.AUDIOFOCUS_LOSS to AudioGraphEvent.InterruptionBegan,
            AudioManager.AUDIOFOCUS_GAIN to AudioGraphEvent.InterruptionEnded(shouldResume = true),
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK to null,
            null to null,
            99 to null,
        )
        for ((change, expected) in cases) {
            assertEquals("focus change $change", expected, AudioSessionMapping.interruptionEvent(change))
        }
    }

    /** Ports map to route flags. */
    @Test
    fun routes() {
        val cases = listOf(
            Triple(listOf(AudioDeviceInfo.TYPE_BUILTIN_MIC), listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER), AudioRoute(false, false, true)),
            Triple(listOf(AudioDeviceInfo.TYPE_BUILTIN_MIC), listOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP), AudioRoute(false, true, true)),
            Triple(listOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO), listOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO), AudioRoute(true, true, true)),
            Triple(listOf(AudioDeviceInfo.TYPE_BLE_HEADSET), listOf(AudioDeviceInfo.TYPE_BLE_HEADSET), AudioRoute(true, true, true)),
            Triple(listOf(AudioDeviceInfo.TYPE_WIRED_HEADSET), listOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES), AudioRoute(false, true, true)),
            Triple(listOf(AudioDeviceInfo.TYPE_USB_DEVICE), listOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE), AudioRoute(false, false, true)),
            Triple(emptyList(), listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER), AudioRoute(false, false, false)),
        )
        for ((inputs, outputs, expected) in cases) {
            assertEquals("$inputs / $outputs", expected, AudioSessionMapping.route(inputs, outputs))
        }
    }
}
