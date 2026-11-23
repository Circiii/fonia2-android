package tech.fonia.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.fonia.designsystem.theme.FoniaTheme
import tech.fonia.designsystem.tokens.FoniaCorner

/*
 * THE ONE SEAM for the art the design package delivers (`tech.fonia.designsystem.foni`): the real Foni parrot
 * (`FoniMascot(mood, modifier, size, voiceLevel, lookAt)`), the word pictures (`WordPicture(key, modifier)`) and the scene
 * backdrops (`SceneBackdrop(scene, modifier)`). Every screen of :app draws Foni, a word picture or a backdrop ONLY
 * through the three composables below. To swap the real art in, edit this file only:
 *
 *   Foni(...)            -> tech.fonia.designsystem.foni.FoniMascot(FoniMood.valueOf(mood.name), modifier, size, voiceLevel, lookAt = null)
 *   WordPictureSlot(...) -> tech.fonia.designsystem.foni.WordPicture(key = word, modifier)
 *   SceneSlot(...)       -> tech.fonia.designsystem.foni.SceneBackdrop(scene, modifier)
 *
 * and delete the app-local [FoniMood] (import the design one instead; the names are the same).
 */

/** Foni's moods (the same ten names as `tech.fonia.designsystem.foni.FoniMood`). */
enum class FoniMood { Idle, Listening, Talking, Thinking, Cheering, Encouraging, Unsure, Pointing, Sleepy, Waving }

/** The old placeholder poses, mapped to the moods (for screens written before the seam). */
fun FoniPose.mood(): FoniMood = when (this) {
    FoniPose.Idle -> FoniMood.Idle
    FoniPose.Listens -> FoniMood.Listening
    FoniPose.Cheers -> FoniMood.Cheering
    FoniPose.Points -> FoniMood.Pointing
    FoniPose.AsksAdult -> FoniMood.Unsure
}

/** Foni: the real parrot of the design system; [voiceLevel] (0..1) drives his beak. */
@Composable
fun Foni(mood: FoniMood, modifier: Modifier = Modifier, size: Dp = 64.dp, voiceLevel: () -> Float = { 0f }) {
    tech.fonia.designsystem.foni.FoniMascot(
        mood = tech.fonia.designsystem.foni.FoniMood.valueOf(mood.name),
        modifier = modifier,
        size = size,
        voiceLevel = voiceLevel,
    )
}

/**
 * The picture of a practice word. [word] is the content's picture key (`icon`, e.g. "sanie"), [hint] its emoji. Placeholder:
 * the emoji on a white sticker card.
 */
@Composable
fun WordPictureSlot(word: String, hint: String?, modifier: Modifier = Modifier) {
    // The real picture when the catalogue has one for the word (or for its emoji); the emoji card otherwise.
    val context = androidx.compose.ui.platform.LocalContext.current
    val key by androidx.compose.runtime.produceState<String?>(null, word, hint) {
        value = when {
            tech.fonia.designsystem.art.FoniaArtAssets.pictureCode(context, word) != null -> word
            !hint.isNullOrBlank() && tech.fonia.designsystem.art.FoniaArtAssets.pictureCode(context, hint) != null -> hint
            else -> ""
        }
    }
    val found = key
    if (found == null) { Box(modifier); return } // still looking: an empty slot, no flash of the fallback
    if (!found.isNullOrEmpty()) {
        tech.fonia.designsystem.art.WordPicture(key = found, modifier = modifier, contentDescription = word)
        return
    }
    val c = FoniaTheme.colors
    Box(
        modifier
            .semantics { contentDescription = word }
            .background(c.surface, RoundedCornerShape(FoniaCorner.card))
            .drawBehind {
                drawRoundRect(c.separator, cornerRadius = androidx.compose.ui.geometry.CornerRadius(FoniaCorner.card.toPx()),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
            },
        contentAlignment = Alignment.Center,
    ) {
        if (!hint.isNullOrBlank()) {
            BasicText(hint, style = TextStyle(fontSize = 64.sp, textAlign = TextAlign.Center))
        } else {
            BasicText(word, style = FoniaTheme.type.title.copy(color = c.ink, textAlign = TextAlign.Center))
        }
    }
}

/** A scene backdrop ("house_s", "hold_s", "path"…). Placeholder: the Stage blue with two soft hills (tokens only). */
@Composable
fun SceneSlot(scene: String, modifier: Modifier = Modifier) {
    val c = FoniaTheme.colors
    Box(
        modifier
            .fillMaxSize()
            .background(c.accent)
            .drawBehind {
                drawCircle(c.surface.copy(alpha = 0.08f), size.width * 0.55f, Offset(size.width * 0.15f, size.height * 1.05f))
                drawCircle(c.surface.copy(alpha = 0.06f), size.width * 0.5f, Offset(size.width * 0.95f, size.height * 1.12f))
                drawCircle(c.surface.copy(alpha = 0.10f), size.minDimension * 0.08f, Offset(size.width * 0.82f, size.height * 0.16f))
            },
    )
}
