package tech.fonia.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import tech.fonia.designsystem.letter.StickerText
import tech.fonia.designsystem.theme.FoniaTheme
import tech.fonia.designsystem.tokens.FoniaSticker

/** The five verdict looks of §2.3 (shape + glyph + word; never colour alone, never red). */
enum class StampKind { Correct, Almost, Instruction, Unsure, Retake }

/**
 * The verdict, said once (`liveRegion`): Correct = „Bravo!” ✓ on `Parrot`; Almost = „Aproape!” ● ○ on `Sky`;
 * Instruction = → toward the target ([word] = the target letter, on `Sun`); Unsure = a flat „?” (the letter trembles);
 * Retake = a flat ↻. [line], when given, is a plain text row under the sticker; in the lesson Foni's bubble carries the
 * words instead, so the stamp stays a sign.
 */
@Composable
fun VerdictStamp(kind: StampKind, word: String, modifier: Modifier = Modifier, line: String? = null) {
    val content = FoniaTheme.content
    Column(
        modifier.semantics(mergeDescendants = true) {
            contentDescription = listOfNotNull(word, line).joinToString(". ")
            liveRegion = LiveRegionMode.Polite
        },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when (kind) {
            StampKind.Correct -> StickerText("$word ✓", FoniaSticker.Parrot, fontSize = 34.sp, tilt = -3f, contentDescription = "")
            StampKind.Almost -> StickerText("$word ● ○", FoniaSticker.Sky, fontSize = 30.sp, tilt = 2f, contentDescription = "")
            StampKind.Instruction -> StickerText("→ $word", FoniaSticker.Sun, fontSize = 30.sp, contentDescription = "")
            StampKind.Unsure -> StickerText("?", FoniaSticker.FlatOnStage, fontSize = 30.sp, tilt = 4f, contentDescription = "")
            StampKind.Retake -> StickerText("↻", FoniaSticker.FlatOnStage, fontSize = 30.sp, contentDescription = "")
        }
        if (line != null) {
            BasicText(line, style = FoniaTheme.type.title2.copy(color = content, textAlign = TextAlign.Center))
        }
    }
}
