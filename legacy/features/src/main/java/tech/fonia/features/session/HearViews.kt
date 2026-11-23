package tech.fonia.features.session

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import tech.fonia.design.Field
import tech.fonia.design.FoniaIcon
import tech.fonia.design.FoniaIconView
import tech.fonia.design.FoniaText
import tech.fonia.design.Palette
import tech.fonia.design.fBody
import tech.fonia.design.fButton
import tech.fonia.features.app.AppModel

/**
 * The loudness envelope of the take, written once per frame by [FeedLetter] and read only while drawing, so the "Te aud"
 * ring follows the voice without recomposing anything (the label flips through [isHeard], once per change).
 */
@Stable
class HeardLevel {
    var value by mutableFloatStateOf(0f)

    val isHeard by derivedStateOf { HearRules.hears(value) }
}

/**
 * The microphone with its "I hear you" ring: two rings that swell and brighten with the voice the instant any sound
 * comes in, right or not, in the family colour.
 */
@Composable
fun HearMic(heard: HeardLevel, color: Color, modifier: Modifier = Modifier, size: Int = 22) {
    Box(
        modifier
            .size((size + 16).dp)
            .drawBehind {
                val e = heard.value
                if (e <= 0.005f) return@drawBehind
                val r0 = this.size.minDimension / 2f
                val k = (e * 1.8f).coerceAtMost(1f)
                drawCircle(color.copy(alpha = 0.22f + 0.4f * k), radius = r0 * (0.8f + 0.55f * k))
                drawCircle(color.copy(alpha = 0.55f * k), radius = r0 * (1.05f + 0.7f * k), style = Stroke(width = 3.dp.toPx()))
            },
        contentAlignment = Alignment.Center,
    ) {
        FoniaIconView(FoniaIcon.microphone, size = size.toFloat(), tint = color)
    }
}

/** The capsule's line while the microphone is open: "Te aud" while sound comes in, "Acum tu" otherwise. */
@Composable
fun HearLabel(heard: HeardLevel, color: Color = Palette.ink) {
    FoniaText(if (heard.isHeard) "Te aud" else "Acum tu", style = fButton, color = color, maxLines = 1)
}

/** Running on an Android emulator (its virtual microphone is silent unless the host's is switched on). */
val isEmulator: Boolean by lazy {
    runCatching {
        Build.FINGERPRINT.startsWith("generic") || Build.FINGERPRINT.contains("emulator") || Build.HARDWARE == "ranchu" ||
            Build.HARDWARE == "goldfish" || Build.PRODUCT.contains("sdk")
    }.getOrDefault(false)
}

/** One line on what to do about a silent microphone, for this device. */
fun noMicHint(emulator: Boolean = isEmulator): String =
    if (emulator) "Pe emulator, pornește microfonul gazdei (Extended controls › Microphone)."
    else "Verifică dacă Fonia are voie la microfon, în Setări."

/**
 * "Nu te aud": shown while the microphone has been open two seconds and delivered nothing near a voice, so a dead
 * microphone is never taken for a broken app. A crossed microphone, two words, one line of what to do.
 */
@Composable
fun NoMicCue(app: AppModel, field: Field, listening: Boolean, modifier: Modifier = Modifier) {
    var silent by remember { mutableStateOf(false) }
    LaunchedEffect(listening) {
        if (!listening) {
            silent = false
            return@LaunchedEffect
        }
        while (true) {
            val now = app.audio.micSeemsSilent
            if (now != silent) silent = now
            delay(200)
        }
    }
    if (!silent) return
    NoMicBox(field, modifier)
}

/** The cue itself, also shown in the result panel when a take ended with a silent microphone. */
@Composable
fun NoMicBox(field: Field, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Palette.sheet, RoundedCornerShape(22.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics { liveRegion = LiveRegionMode.Polite; contentDescription = "Nu te aud. ${noMicHint()}" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.drawWithContent {
                    drawContent()
                    drawLine(
                        field.deep, Offset(size.width * 0.12f, size.height * 0.12f), Offset(size.width * 0.88f, size.height * 0.88f),
                        strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round,
                    )
                },
            ) { FoniaIconView(FoniaIcon.microphone, size = 26f, tint = field.deep) }
            FoniaText("Nu te aud", style = fButton, color = Palette.ink)
        }
        FoniaText(noMicHint(), style = fBody, color = Palette.inkSoft, textAlign = TextAlign.Center)
    }
}
