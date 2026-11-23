package tech.fonia.features.speaking

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.fonia.audio.CapturedClip
import tech.fonia.design.FoniButtonStyle
import tech.fonia.design.FoniaIcon
import tech.fonia.design.FoniaLabel
import tech.fonia.design.FoniaText
import tech.fonia.design.Palette
import tech.fonia.design.StyledButton
import tech.fonia.design.TextRole
import tech.fonia.design.UIWeight
import tech.fonia.design.fBody
import tech.fonia.design.fBodyStrong
import tech.fonia.design.swiftSpring
import tech.fonia.design.ui
import tech.fonia.design.Field
import tech.fonia.features.app.AppModel
import tech.fonia.features.app.dspGroup
import tech.fonia.features.app.calibration
import tech.fonia.live.Clip
import tech.fonia.speaking.CleanResult
import tech.fonia.speaking.SpeakingAnalysis
import tech.fonia.speaking.SpeakingAudio
import tech.fonia.speaking.SpeakingPipeline
import tech.fonia.speaking.SpeakingText
import tech.fonia.speech.Transcript
import tech.fonia.store.Profile
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The `SpeakingAudio` port of „Tu, fără ăăă” on what the app's [AudioHub][tech.fonia.features.app.AudioHub] already
 * offers, without changing it: the take through `startListening` / `stopListening`, the levels from its meters, and
 * playback through `play(CapturedClip)` / `stopSpeaking`. The hub does not say where playback is, so
 * [playbackSeconds] is the time since the clip started (latency not included).
 */
class HubSpeakingAudio(private val app: AppModel, private val profile: Profile) : SpeakingAudio {
    @Volatile private var stopRec = false
    @Volatile private var playStartNanos = 0L
    @Volatile private var playFrom = Double.NaN

    override suspend fun record(maxSeconds: Double, onLevel: (level: Float, speaking: Boolean) -> Unit): Clip {
        stopRec = false
        if (!app.audio.requestMicrophone()) return Clip.Empty
        app.audio.startListening(group = profile.dspGroup, calibration = profile.calibration)
        val start = System.nanoTime()
        try {
            while (currentCoroutineContext().isActive && !stopRec && (System.nanoTime() - start) / 1e9 < maxSeconds) {
                val level = app.audio.levels.level
                onLevel(level, level > 0.12f)
                delay(20)
            }
        } finally {
            if (!currentCoroutineContext().isActive) withContext(kotlinx.coroutines.NonCancellable) { app.audio.cancelListening() }
        }
        val clip = app.audio.stopListening() ?: return Clip.Empty
        return Clip(clip.samples, clip.sampleRate, clip.dropped)
    }

    override fun stopRecording() {
        stopRec = true
    }

    override suspend fun play(samples: FloatArray, sampleRate: Double, fromSeconds: Double): Boolean {
        if (samples.isEmpty() || sampleRate <= 0) return false
        val from = fromSeconds.coerceIn(0.0, samples.size / sampleRate)
        playFrom = from
        playStartNanos = System.nanoTime()
        return try {
            val clip = CapturedClip(samples, sampleRate)
            if (from > 0.0) app.audio.play(clip, from, clip.duration) else app.audio.play(clip)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        } finally {
            playFrom = Double.NaN
        }
    }

    override fun stopPlayback() {
        playFrom = Double.NaN
        app.scope.launch { app.audio.stopSpeaking() }
    }

    override fun playbackSeconds(): Double {
        val from = playFrom
        if (from.isNaN()) return Double.NaN
        return from + (System.nanoTime() - playStartNanos) / 1e9
    }
}

/** One take, with its fillers found and cut out: what „Ascultă-te fără ăăă” plays and marks. */
class NoUmTake(val analysis: SpeakingAnalysis, val clean: CleanResult) {
    val fillers: Int get() = clean.stats.fillersRemoved + clean.stats.repetitionsRemoved + clean.stats.falseStartsRemoved
    val secondsShorter: Double get() = clean.stats.secondsRemoved

    /** „4 umpluturi · 6 s mai scurt”: the one number line. */
    val line: String
        get() {
            val s = clean.stats
            val parts = mutableListOf<String>()
            if (s.fillersRemoved > 0) parts += SpeakingText.fillers(s.fillersRemoved)
            if (s.repetitionsRemoved > 0) parts += SpeakingText.repetitions(s.repetitionsRemoved)
            if (s.falseStartsRemoved > 0) parts += SpeakingText.falseStarts(s.falseStartsRemoved)
            if (parts.isEmpty() && s.pausesShortened > 0) parts += SpeakingText.pauses(s.pausesShortened) + " scurtate"
            val shorter = secondsShorter
            if (shorter >= 0.5) parts += "${if (shorter >= 10) shorter.roundToInt().toString() else String.format(Locale.ROOT, "%.1f", shorter).replace('.', ',')} s mai scurt"
            return parts.joinToString(" · ")
        }

    /** True when there is something to hear: at least one cut or a shortened pause. */
    val worthPlaying: Boolean get() = clean.edits.isNotEmpty()

    /** The words Whisper heard, with the stretches that were cut out marked (struck through, in the action green). */
    fun markedTranscript(cleanView: Boolean): AnnotatedString? {
        val words = analysis.transcript.words
        if (!analysis.usedTranscript || words.isEmpty()) return null
        val cutWords = analysis.spans.filter { it.id in clean.cutIds }.flatMap { it.wordIndices }.toSet()
        return buildAnnotatedString {
            words.forEachIndexed { i, w ->
                if (i > 0) append(' ')
                if (i in cutWords) {
                    val style = if (cleanView) SpanStyle(color = Palette.inkSoft.copy(alpha = 0.55f), textDecoration = TextDecoration.LineThrough)
                    else SpanStyle(color = Palette.warm, background = Palette.warmSoft)
                    withStyle(style) { append(w.text) }
                } else {
                    append(w.text)
                }
            }
        }
    }

    companion object {
        /**
         * Finds and cuts the fillers of [clip] (CPU only). With [transcript] (Whisper's verbatim words, already made for
         * the report) markers, repetitions and false starts are found too; without it, the sound alone finds the „ăăă”.
         */
        suspend fun make(clip: CapturedClip, transcript: Transcript?): NoUmTake? = withContext(Dispatchers.Default) {
            try {
                val take = Clip(clip.samples, clip.sampleRate, clip.dropped)
                val pipeline = SpeakingPipeline()
                val usable = transcript?.takeIf { it.words.isNotEmpty() && it.noSpeechProb < 0.8 }
                val analysis = pipeline.analyzer.analyze(take, usable)
                NoUmTake(analysis, pipeline.clean(take, analysis))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * „Ascultă-te fără ăăă” in the report: the one number line, one button that plays the take (cleaned, or as it was),
 * and the A/B toggle „cum a fost” / „fără ăăă”.
 */
@Composable
internal fun NoUmBlock(take: NoUmTake, cleanView: Boolean, playing: Boolean, onView: (Boolean) -> Unit, onPlay: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FoniaText(take.line, style = fBodyStrong, color = Palette.ink)
        Row(
            Modifier
                .fillMaxWidth()
                .background(Palette.cloud, RoundedCornerShape(50))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Segment("cum a fost", selected = !cleanView, Modifier.weight(1f)) { onView(false) }
            Segment("fără ăăă", selected = cleanView, Modifier.weight(1f)) { onView(true) }
        }
        StyledButton(onClick = onPlay, style = FoniButtonStyle(field = Field.paper, size = FoniButtonStyle.Size.regular, kind = FoniButtonStyle.Kind.secondary)) {
            AnimatedContent(playing to cleanView, transitionSpec = { fadeIn(swiftSpring(0.25, 0.0)) togetherWith fadeOut(swiftSpring(0.2, 0.0)) }, label = "play") { (isPlaying, clean) ->
                FoniaLabel(
                    if (isPlaying) "Oprește" else if (clean) "Ascultă-te fără ăăă" else "Ascultă-te",
                    if (isPlaying) FoniaIcon.close else FoniaIcon.play,
                    iconSize = 16f,
                )
            }
        }
    }
}

@Composable
private fun Segment(title: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val background by animateColorAsState(if (selected) Palette.sheet else Palette.cloud, swiftSpring(0.3, 0.1), label = "segment")
    Box(
        modifier
            .heightIn(min = 40.dp)
            .background(background, RoundedCornerShape(50))
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics { this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        FoniaText(title, style = ui(15, if (selected) UIWeight.bold else UIWeight.medium, TextRole.subheadline), color = if (selected) Palette.ink else Palette.inkSoft)
    }
}

/** The transcript with the cut stretches marked; null when Whisper did not run. */
@Composable
internal fun NoUmTranscript(text: AnnotatedString) {
    FoniaText(text, style = fBody, color = Palette.ink)
}
