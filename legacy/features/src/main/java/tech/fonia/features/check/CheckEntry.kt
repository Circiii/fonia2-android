package tech.fonia.features.check

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.fonia.design.CTALabel
import tech.fonia.design.QuietButtonStyle
import tech.fonia.design.ContinuousRoundedRectangle
import tech.fonia.design.Foni
import tech.fonia.design.FoniButtonStyle
import tech.fonia.design.FoniMood
import tech.fonia.design.FoniaIcon
import tech.fonia.design.FoniaIconView
import tech.fonia.design.FoniaText
import tech.fonia.design.LocalForeground
import tech.fonia.design.Palette
import tech.fonia.design.PressableStyle
import tech.fonia.design.SceneBackdrop
import tech.fonia.design.SceneName
import tech.fonia.design.StyledButton
import tech.fonia.design.TextRole
import tech.fonia.design.UIWeight
import tech.fonia.design.fLead
import tech.fonia.design.softShadow
import tech.fonia.design.ui
import tech.fonia.designsystem.letter.StickerText
import tech.fonia.designsystem.tokens.FoniaClassicSticker
import tech.fonia.features.app.AppModel
import tech.fonia.features.app.shortLetter

/**
 * The door to the quick check, on the home and on Progres: before the first check it invites; after one, it shows the
 * last result as small stickers and opens the check again, to see what moved.
 */
@Composable
fun CheckCard(last: CheckRecord?, app: AppModel, modifier: Modifier = Modifier, isChild: Boolean = false) {
    val card = remember { ContinuousRoundedRectangle(24.dp) }
    StyledButton(
        onClick = { app.startCheck() },
        style = PressableStyle(),
        modifier = modifier.semantics { contentDescription = if (last == null) "Verificare rapidă" else "Verificare rapidă, din nou" },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .softShadow(card)
                .background(Palette.sheet, card)
                .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FoniaText("Verificare rapidă", style = ui(18, UIWeight.bold, TextRole.headline), color = Palette.ink, maxLines = 1)
                // A child sees no verdicts, only the invitation, as on iOS.
                if (last == null || isChild) {
                    FoniaText(if (last == null) "Hai să vedem ce sunete exersăm" else "Hai să vedem ce s-a schimbat", style = ui(15, UIWeight.medium, TextRole.subheadline), color = Palette.inkSoft, maxLines = 2)
                } else {
                    Row(
                        Modifier.clearAndSetSemantics {},
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        for (id in QuickCheckRules.logopedicOrder) {
                            val verdict = last.results[id]?.verdict ?: continue
                            val sticker = when (verdict) {
                                CheckVerdict.Good -> FoniaClassicSticker.Good
                                CheckVerdict.Practice -> FoniaClassicSticker.Tip
                                CheckVerdict.Unsure -> FoniaClassicSticker.Ink
                            }
                            val letter = app.content.sound(id).shortLetter
                            StickerText(letter, sticker, fontSize = if (letter.length > 1) 15.sp else 20.sp, weight = 900f)
                        }
                    }
                }
            }
            Box(Modifier.size(48.dp).background(Palette.accentSoft, CircleShape), contentAlignment = Alignment.Center) {
                CompositionLocalProvider(LocalForeground provides Palette.accentText) {
                    FoniaIconView(if (last == null) FoniaIcon.ear else FoniaIcon.retry, size = 24f)
                }
            }
        }
    }
}

/** Offered once, when the first run ends: Foni invites to the quick check, which sets what to practise. */
@Composable
fun CheckOffer(onStart: () -> Unit, onLater: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        SceneBackdrop(SceneName.start)
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f).heightIn(min = 12.dp))
            Foni(Modifier.height(210.dp), mood = FoniMood.hello, reactsToTap = true)
            Spacer(Modifier.weight(1f).heightIn(min = 12.dp))
            Column(
                Modifier
                    .padding(bottom = 10.dp)
                    .fillMaxWidth()
                    .widthIn(max = 620.dp)
                    .background(Palette.sheet, ContinuousRoundedRectangle(26.dp))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                FoniaText(
                    "Hai să vedem ce sunete exersăm",
                    Modifier.semantics { heading() },
                    style = ui(24, UIWeight.black, TextRole.title2),
                    color = Palette.ink,
                    textAlign = TextAlign.Center,
                )
                FoniaText("Două minute. Asculți cuvântul și îl spui după Foni.", style = fLead, color = Palette.inkSoft, textAlign = TextAlign.Center)
                StyledButton(onClick = onStart, style = FoniButtonStyle()) { CTALabel("Încep") }
                StyledButton(onClick = onLater, style = QuietButtonStyle(Palette.accentText)) { FoniaText("Mai târziu") }
            }
        }
    }
}
