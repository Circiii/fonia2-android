package tech.fonia.audio

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.text.BreakIterator
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * Speaks texts that have no recorded clip, with the system's `TextToSpeech` (iOS: `AVSpeechSynthesizer`).
 * Like the iOS `@MainActor` class, its state lives on the main thread.
 */
class SpeechFallback internal constructor(context: Context, private val volume: Float) {
    constructor(context: Context) : this(context, 1f)

    enum class Language(val rawValue: String) {
        Romanian("ro-RO"),
        English("en-US"),
    }

    private enum class Engine { Starting, Ready, Failed }

    private class Utterance(val text: String, val language: Language, val id: String)

    private companion object {
        /** `AVSpeechUtteranceDefaultSpeechRate * 0.9`: Android's normal rate is 1. */
        const val SPEECH_RATE = 0.9f
        const val MOUTH_INTERVAL_MS = 16L
    }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var engine = Engine.Starting
    private var finish: CancellableContinuation<Unit>? = null
    private var currentUtterance: String? = null
    private var pending: Utterance? = null
    private var watchdog: Job? = null
    private var mouth: Job? = null

    private val synthesizer: TextToSpeech by lazy {
        TextToSpeech(context.applicationContext) { status -> main.post { engineReady(status == TextToSpeech.SUCCESS) } }
    }

    private fun attachListener() {
        synthesizer.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) = ended(utteranceId)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = ended(utteranceId)

            override fun onError(utteranceId: String?, errorCode: Int) = ended(utteranceId)

            override fun onStop(utteranceId: String?, interrupted: Boolean) = ended(utteranceId)

            private fun ended(utteranceId: String?) {
                main.post { utteranceEnded(utteranceId) }
            }
        })
    }

    /**
     * Suspends until the text has been spoken or [stop] is called. With no signal to measure, `levels.talk`
     * follows a made-up talking rhythm so a mouth still moves. Cancelling the caller stops the voice.
     */
    suspend fun speak(text: String, language: Language, levels: LiveLevels? = null) {
        // The phone's synthetic voice is switched off for good (owner's order, 2026-11-21): only generated voices play.
        if (TTS_DISABLED) return
        withContext(Dispatchers.Main.immediate) {
            stopNow()
            val id = UUID.randomUUID().toString()
            try {
                suspendCancellableCoroutine { continuation ->
                    finish = continuation
                    currentUtterance = id
                    startMouth(levels)
                    startWatchdog(characterCount(text))
                    say(Utterance(text, language, id))
                }
            } finally {
                if (currentUtterance == id) stopNow()
                levels?.publishTalk(0f)
            }
        }
    }

    /** Stops speaking. From another thread the stop is posted to the main thread, where the state lives. */
    fun stop() {
        if (Looper.myLooper() == Looper.getMainLooper()) stopNow() else main.post { stopNow() }
    }

    /** Frees the engine. Not on iOS, where the synthesizer goes away with its owner. */
    fun release() {
        main.post {
            stopNow()
            synthesizer.shutdown()
            scope.cancel()
        }
    }

    private fun stopNow() {
        if (currentUtterance != null && engine == Engine.Ready) synthesizer.stop()
        pending = null
        complete()
    }

    private fun complete() {
        watchdog?.cancel()
        mouth?.cancel()
        watchdog = null
        mouth = null
        currentUtterance = null
        finish?.let { if (it.isActive) it.resume(Unit) }
        finish = null
    }

    /** Callbacks arrive later, so one for an already replaced utterance must not end the new one. */
    private fun utteranceEnded(id: String?) {
        if (id != null && id == currentUtterance) complete()
    }

    private fun engineReady(success: Boolean) {
        engine = if (success) Engine.Ready else Engine.Failed
        if (success) {
            synthesizer.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
        }
        val waiting = pending ?: return
        pending = null
        if (success && waiting.id == currentUtterance) speakNow(waiting)
    }

    private fun say(utterance: Utterance) {
        when (engine) {
            Engine.Ready -> speakNow(utterance)
            Engine.Starting -> pending = utterance
            // No engine: nothing is said and the watchdog ends the wait, as with a stuck voice on iOS.
            Engine.Failed -> Unit
        }
    }

    private fun speakNow(utterance: Utterance) {
        // A missing voice leaves the engine's default one, as AVSpeechSynthesisVoice(language:) returning nil does.
        synthesizer.setLanguage(Locale.forLanguageTag(utterance.language.rawValue))
        synthesizer.setSpeechRate(SPEECH_RATE)
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume) }
        if (synthesizer.speak(utterance.text, TextToSpeech.QUEUE_FLUSH, params, utterance.id) != TextToSpeech.SUCCESS) {
            utteranceEnded(utterance.id)
        }
    }

    /** A voice that is missing or stuck may never report finishing, and that must not hang a lesson. */
    private fun startWatchdog(characterCount: Int) {
        val limit = max(1.5, characterCount * 0.14)
        watchdog = scope.launch {
            delay((limit * 1000).toLong())
            stopNow()
        }
    }

    private fun startMouth(levels: LiveLevels?) {
        levels ?: return
        mouth = scope.launch {
            val started = System.nanoTime()
            while (isActive) {
                val t = ((System.nanoTime() - started) / 1e9).toFloat()
                levels.publishTalk(0.35f + 0.3f * abs(sin(t * 9)) * abs(sin(t * 3.1f)))
                delay(MOUTH_INTERVAL_MS)
            }
        }
    }

    /** Swift's `String.count`: extended grapheme clusters, not UTF-16 units. */
    private fun characterCount(text: String): Int {
        val iterator = BreakIterator.getCharacterInstance()
        iterator.setText(text)
        var count = 0
        while (iterator.next() != BreakIterator.DONE) count++
        return count
    }
}

/** True: [SpeechFallback.speak] never makes a sound and the TextToSpeech engine is never created (owner, 2026-11-21). */
const val TTS_DISABLED: Boolean = true
