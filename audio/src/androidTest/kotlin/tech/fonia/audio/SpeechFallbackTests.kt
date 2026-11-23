package tech.fonia.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Port of SpeechFallbackTests.swift ("Speech fallback"). Volume is zero so these stay silent. Whether a voice for
 * the language exists depends on the device, so they check that `speak` always returns, not what was said.
 */
@RunWith(AndroidJUnit4::class)
class SpeechFallbackTests {
    private val longText = "Soarele strălucește pe cerul albastru. ".repeat(20)

    private fun silentSpeech() = SpeechFallback(targetContext, volume = 0f)

    /** Speaking returns once the text has been spoken (Romanian, then English). */
    @Test(timeout = 60_000)
    fun speakReturns() = runBlocking {
        for (language in listOf(SpeechFallback.Language.Romanian, SpeechFallback.Language.English)) {
            val speech = silentSpeech()
            speech.speak("Soare", language)
            speech.release()
        }
    }

    /** Stop ends a long text early and closes the mouth. */
    @Test(timeout = 60_000)
    fun stopEndsEarly() = runBlocking {
        val speech = silentSpeech()
        val levels = LiveLevels()

        val speaking = launch { speech.speak(longText, SpeechFallback.Language.Romanian, levels) }
        delay(200)
        assertTrue("talk ${levels.talk}", levels.talk > 0f)

        val began = System.nanoTime()
        speech.stop()
        speaking.join()

        assertTrue((System.nanoTime() - began) < 1_000_000_000L)
        assertEquals(0f, levels.talk, 0f)
        speech.release()
    }

    /** A new text ends the one before it. */
    @Test(timeout = 60_000)
    fun newTextReplaces() = runBlocking {
        val speech = silentSpeech()
        val first = launch { speech.speak(longText, SpeechFallback.Language.Romanian) }
        delay(100)
        speech.speak("Da", SpeechFallback.Language.Romanian)
        first.join()
        speech.release()
    }

    /** Stop with nothing being said does nothing. */
    @Test(timeout = 60_000)
    fun stopWhenIdle() {
        val speech = silentSpeech()
        speech.stop()
        speech.release()
    }
}
