package tech.fonia.app.end

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.fonia.app.text.T
import tech.fonia.app.ui.FoniMascot
import tech.fonia.app.ui.FoniPose
import tech.fonia.designsystem.components.ContinueButton
import tech.fonia.designsystem.components.ContinueStyle
import tech.fonia.designsystem.letter.StickerText
import tech.fonia.designsystem.theme.FoniaTheme
import tech.fonia.designsystem.tokens.FoniaCorner
import tech.fonia.designsystem.tokens.FoniaSpace
import tech.fonia.designsystem.tokens.FoniaSticker
import tech.fonia.navigation.EndKey

/**
 * END (chapter 01 §7): under 10 seconds, nothing in a loop. The praise as a sticker, the one written number, one
 * sentence for the adult, „Gata” (filled) and „Încă una?” (outlined).
 */
@Composable
fun EndScreen(key: EndKey, onDone: () -> Unit, onAgain: () -> Unit, onPickOther: () -> Unit) {
    val c = FoniaTheme.colors
    BackHandler(onBack = onDone)
    Column(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = FoniaSpace.pageKid),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Spacer(Modifier.height(FoniaSpace.xl))
            Box(contentAlignment = Alignment.Center) {
                tech.fonia.app.ui.Foni(tech.fonia.app.ui.FoniMood.Cheering, size = 160.dp)
            }
            Spacer(Modifier.height(FoniaSpace.m))
            StickerText(
                if (key.short) key.sound.letter else T.endPraise,
                FoniaSticker.Parrot,
                fontSize = 56.sp,
                tilt = -3f,
                contentDescription = if (key.short) T.endShort else T.endPraise,
            )
            Spacer(Modifier.height(FoniaSpace.s))
            BasicText(
                if (key.short) T.endShort else T.endDone,
                style = FoniaTheme.type.title.copy(color = c.ink, textAlign = TextAlign.Center),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (key.attempts > 0) {
                Spacer(Modifier.height(FoniaSpace.xs))
                BasicText(T.endCount(key.attempts), style = FoniaTheme.type.title2.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
            }
            Spacer(Modifier.height(FoniaSpace.l))
            // the sentence for the adult: sober type, no sticker
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(c.surface, RoundedCornerShape(FoniaCorner.card))
                    .padding(FoniaSpace.m)
                    .semantics(mergeDescendants = true) { contentDescription = T.endAdultLabel + ". " + T.endAdult(key.attempts, key.sound, key.bestHoldMillis / 1000.0) },
            ) {
                BasicText(T.endAdultLabel, style = FoniaTheme.type.label.copy(color = c.inkSecondary))
                Spacer(Modifier.height(4.dp))
                BasicText(T.endAdult(key.attempts, key.sound, key.bestHoldMillis / 1000.0), style = FoniaTheme.type.body.copy(color = c.ink))
            }
            Spacer(Modifier.height(FoniaSpace.l))
        }
        ContinueButton(T.endFinish, onClick = onDone, arrow = false)
        Spacer(Modifier.height(FoniaSpace.s))
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(FoniaSpace.s)) {
            ContinueButton(T.endOneMore, onClick = onAgain, style = ContinueStyle.Outlined, arrow = false, modifier = Modifier.weight(1f))
            ContinueButton(T.endPickOther, onClick = onPickOther, style = ContinueStyle.Outlined, arrow = false, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(FoniaSpace.l))
    }
}
