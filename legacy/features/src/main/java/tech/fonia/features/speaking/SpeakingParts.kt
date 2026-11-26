package tech.fonia.features.speaking

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import tech.fonia.audio.CapturedClip
import tech.fonia.design.ContinuousRoundedRectangle
import tech.fonia.design.Field
import tech.fonia.design.FoniaIcon
import tech.fonia.design.FoniaIconView
import tech.fonia.design.FoniaText
import tech.fonia.design.Palette
import tech.fonia.design.StyledButton
import tech.fonia.design.TextRole
import tech.fonia.design.ToolButtonStyle
import tech.fonia.design.UIWeight
import tech.fonia.design.VoiceBars
import tech.fonia.design.WrapLayout
import tech.fonia.design.fBody
import tech.fonia.design.fBodyStrong
import tech.fonia.design.fHeadline
import tech.fonia.design.monospacedDigit
import tech.fonia.design.pressable
import tech.fonia.design.ui
import tech.fonia.domain.Language
import tech.fonia.features.app.ActivityIndicator
import tech.fonia.features.app.AppModel
import tech.fonia.features.app.LocalStillMeters
import tech.fonia.features.app.calibration
import tech.fonia.features.app.dspGroup
import tech.fonia.store.Profile
import java.util.Locale

// Pieces shared by the emphasis exercise and the BAC simulation (not on iOS): the clock, the microphone takes, and
// a text drawn word by word so single words can be marked.

/** Monotonic nanoseconds. The UI tests move their own clock by hand, so a 30-minute preparation takes no time. */
fun interface SpeakingClock {
    fun nanos(): Long

    companion object {
        val System: SpeakingClock = SpeakingClock { java.lang.System.nanoTime() }
    }
}

/** "4:05". */
fun clockText(seconds: Int): String = String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)

/**
 * One microphone take at a time, as the talk screen starts and stops them: the permission, the recorded clip in the
 * recorded-take mode (`foniaFixtures`), and what to say when the microphone can't start. Main thread only.
 */
class TakeRecorder(private val app: AppModel, private val profile: Profile) {
    var micOff by mutableStateOf(false)
        private set
    var startFailed by mutableStateOf(false)
        private set
    var isRecording by mutableStateOf(false)
        private set

    /** True when the take runs. [fixtureText] picks the recorded clip played in place of the microphone in the emulator. */
    suspend fun start(fixtureText: String?, language: Language): Boolean {
        if (isRecording) return true
        if (!app.audio.requestMicrophone()) {
            micOff = true
            return false
        }
        micOff = false
        try {
            app.audio.startListening(
                group = profile.dspGroup,
                calibration = profile.calibration,
                fixtureText = fixtureText,
                fixtureLanguage = language,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            startFailed = true
            return false
        }
        startFailed = false
        isRecording = true
        return true
    }

    /** The whole take; null when nothing usable was captured (and [startFailed] then says so). */
    suspend fun stop(): CapturedClip? {
        if (!isRecording) return null
        isRecording = false
        val clip = app.audio.stopListening() ?: return null
        if (!(clip.sampleRate > 0) || clip.samples.isEmpty()) {
            startFailed = true
            return null
        }
        return clip
    }

    suspend fun cancel() {
        if (!isRecording) return
        isRecording = false
        app.audio.cancelListening()
    }

    /** What to tell the reader when the microphone did not start; null when it did. */
    val problem: String?
        get() = when {
            micOff -> "Microfonul e oprit. Pornește-l din Setări, la Fonia."
            startFailed -> "Nu pot porni microfonul acum. Închide alte aplicații care îl folosesc și mai încearcă."
            else -> null
        }
}

/** The X at the top left, and whatever the page wants at the top right. */
@Composable
fun SpeakingTopBar(onClose: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        StyledButton(onClick = onClose, style = ToolButtonStyle(field = Field.paper), modifier = Modifier.semantics { contentDescription = "Închide" }) {
            FoniaIconView(FoniaIcon.close, size = 18f)
        }
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

@Composable
fun SpeakingHeading(title: String, modifier: Modifier = Modifier) {
    FoniaText(title, modifier.semantics { heading() }, style = fHeadline, color = Palette.ink)
}

/** How one word is drawn. */
data class WordPaint(
    val color: Color = Palette.ink,
    val bold: Boolean = false,
    /** A bar under the word: the marked words. */
    val underline: Color? = null,
    /** A soft pill behind the word: where the reader's voice stood out, or a word worth a listen. */
    val fill: Color? = null,
    /** Read by the screen reader after the word. */
    val state: String? = null,
)

/**
 * A text laid out word by word, wrapping like a paragraph, so each word can carry its own mark. [onTap] makes every
 * word a button (marking key words while preparing, listening to one word in the report).
 */
@Composable
fun WordFlow(
    words: List<TextWord>,
    modifier: Modifier = Modifier,
    style: TextStyle = ui(22, UIWeight.medium, TextRole.title3),
    centred: Boolean = false,
    paint: (TextWord) -> WordPaint = { WordPaint(bold = it.isKey, underline = if (it.isKey) Palette.accent else null) },
    tapLabel: String? = null,
    onTap: ((TextWord) -> Unit)? = null,
) {
    val size = style.fontSize.value
    WrapLayout(modifier.fillMaxWidth(), spacing = (size * 0.26f).dp, lineSpacing = (size * 0.18f).dp, centred = centred) {
        for (word in words) {
            val look = paint(word)
            val shape = ContinuousRoundedRectangle(6.dp)
            var base = Modifier
                .testTag("text-word-${word.index}")
                .heightIn(min = if (onTap != null) 36.dp else 0.dp)
            if (onTap != null) {
                base = base.pressable(onClickLabel = tapLabel) { onTap(word) }
            }
            Box(
                base.semantics { look.state?.let { stateDescription = it } },
                contentAlignment = Alignment.CenterStart,
            ) {
                FoniaText(
                    word.shown,
                    Modifier
                        .drawBehind {
                            look.fill?.let { drawRoundRect(it, cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx())) }
                            look.underline?.let {
                                val h = 2.5.dp.toPx()
                                drawRect(it, topLeft = Offset(0f, this.size.height - h), size = Size(this.size.width, h))
                            }
                        }
                        .padding(horizontal = if (look.fill != null) 3.dp else 0.dp),
                    style = style.copy(fontWeight = if (look.bold) FontWeight.Bold else style.fontWeight),
                    color = look.color,
                )
            }
        }
    }
}

/** While a take runs: the clock, the voice level and the one way to end it. */
@Composable
fun ListeningPanel(app: AppModel, elapsed: Int, stopTitle: String, onStop: () -> Unit, modifier: Modifier = Modifier) {
    val levels = app.audio.levels
    val still = LocalStillMeters.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FoniaIconView(FoniaIcon.microphone, size = 20f, tint = Palette.accentText)
            FoniaText(
                clockText(elapsed),
                Modifier
                    .background(Palette.sheet, RoundedCornerShape(50))
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                style = ui(20, UIWeight.bold, TextRole.title3).monospacedDigit(),
                color = Palette.ink,
            )
        }
        VoiceBars(isLive = !still, color = Palette.ink, level = { levels.level }, modifier = Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 12.dp))
        StyledButton(onClick = onStop, style = tech.fonia.design.FoniButtonStyle(field = Field.paper)) { FoniaText(stopTitle) }
    }
}

/** The analysis running: a spinner and one line. */
@Composable
fun ThinkingPanel(text: String, detail: String? = null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            ActivityIndicator()
            FoniaText(text, style = fBodyStrong, color = Palette.inkSoft, textAlign = TextAlign.Center)
            if (detail != null) FoniaText(detail, style = fBody, color = Palette.inkSoft, textAlign = TextAlign.Center)
        }
    }
}

/** A short measured value with what it is compared with: "Reper: cel puțin 95 %". */
@Composable
fun MeasureBlock(title: String, value: String, reference: String?, note: String? = null, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Palette.sheet, ContinuousRoundedRectangle(16.dp))
            .padding(14.dp)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FoniaText(title, style = fBodyStrong, color = Palette.inkSoft)
        FoniaText(value, style = ui(18, UIWeight.bold, TextRole.title3), color = Palette.ink)
        if (reference != null) FoniaText(reference, style = tech.fonia.design.fCaption, color = Palette.inkSoft)
        if (note != null) FoniaText(note, style = fBody, color = Palette.ink)
    }
}
