package tech.fonia.audio

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaRecorder
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Port of AudioSessionControllerTests.swift ("Audio session controller"), on the device's real audio focus.
 * Nothing here is audible.
 */
@RunWith(AndroidJUnit4::class)
class AudioSessionControllerTests {
    @Before
    fun allowMicrophone() {
        // iOS runs `lifecycle` only when the microphone is not denied; the test APK grants it.
        grantMicrophone()
    }

    /** Activation and deactivation reach the state stream. */
    @Test(timeout = 60_000)
    fun lifecycle() = runBlocking {
        val controller = AudioSessionController(targetContext)
        val states = controller.state.produceIn(this)
        assertEquals(AudioState.Inactive, states.receive())

        controller.configure()
        controller.activate()
        assertTrue("Expected ready after activating", states.receive() is AudioState.Ready)

        controller.deactivate()
        assertEquals(AudioState.Inactive, states.receive())
        states.cancel()
    }

    /**
     * Configuring and activating work on the real session. iOS checks `.playAndRecord`, `.measurement`,
     * `.defaultToSpeaker` and `.allowBluetoothA2DP`; the Android equivalents are a transient media focus for
     * speech, the normal (non-call) audio mode, and a capture preset that is never voice communication.
     */
    @Test(timeout = 60_000)
    fun configuresSession() {
        val controller = AudioSessionController(targetContext)
        controller.configure()
        controller.activate()

        val audioManager = targetContext.getSystemService(AudioManager::class.java)
        assertEquals(AudioManager.MODE_NORMAL, audioManager.mode)
        assertTrue(SharedAudioSession.holdsFocus)
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, SharedAudioSession.focusGain)
        assertEquals(AudioAttributes.USAGE_MEDIA, SharedAudioSession.mediaAttributes.usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_SPEECH, SharedAudioSession.mediaAttributes.contentType)
        assertFalse(controller.isPlaybackOnly)
        assertNotEquals(MediaRecorder.AudioSource.VOICE_COMMUNICATION, InputPreset.preferred(audioManager).audioSource)

        controller.deactivate()
        assertFalse(SharedAudioSession.holdsFocus)
    }

    /** Every subscriber gets the current state first. */
    @Test(timeout = 60_000)
    fun subscribers() = runBlocking {
        val controller = AudioSessionController(targetContext)
        val first = controller.state.produceIn(this)
        val second = controller.state.produceIn(this)

        val one = first.receive()
        val two = second.receive()
        assertNotNull(one)
        assertEquals(one, two)
        first.cancel()
        second.cancel()
    }
}
