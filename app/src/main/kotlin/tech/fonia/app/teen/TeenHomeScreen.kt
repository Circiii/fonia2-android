package tech.fonia.app.teen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.fonia.app.adult.AdultCaption
import tech.fonia.app.adult.AdultHeading
import tech.fonia.app.adult.AdultPage
import tech.fonia.app.adult.AdultRow
import tech.fonia.app.text.T
import tech.fonia.designsystem.components.QuietButton
import tech.fonia.designsystem.letter.StickerText
import tech.fonia.designsystem.theme.FoniaTheme
import tech.fonia.designsystem.tokens.FoniaCorner
import tech.fonia.designsystem.tokens.FoniaSpace
import tech.fonia.designsystem.tokens.FoniaSticker

/**
 * TEEN-HOME („Doar eu”, chapter 01 §10), grown shell: *Azi* — one filled card (the next step: NO-UM); *Exersează* —
 * NO-UM and MINUTE (with its step on the 7-topic ladder); a quiet link to the sounds PATH; settings; the privacy line.
 * Whether the transcript is on (Whisper model on the phone) or the analysis is by sound only is said in one caption.
 */
@Composable
fun TeenHomeScreen(
    reduceMotion: Boolean,
    minuteStep: Int,
    minuteSteps: Int,
    whisperReady: Boolean,
    root: Boolean,
    onNoUm: () -> Unit,
    onMinute: () -> Unit,
    onSounds: () -> Unit,
    onSettings: () -> Unit,
    onBack: () -> Unit,
) {
    AdultPage(T.teenHomeTitle, onBack, reduceMotion, showBack = !root) {
        val c = FoniaTheme.colors
        AdultHeading(T.teenToday)
        TodayCard(onNoUm)
        Spacer(Modifier.height(FoniaSpace.xs))
        AdultHeading(T.teenPractice)
        AdultRow(T.teenNoUm, T.teenNoUmHelp, onClick = onNoUm)
        AdultRow(T.teenMinute, "${T.teenMinuteHelp} ${T.teenMinuteStep(minuteStep + 1, minuteSteps)}", onClick = onMinute)
        AdultCaption(if (whisperReady) T.teenWhisperOn else T.teenWhisperOff)
        Spacer(Modifier.height(FoniaSpace.xs))
        QuietButton(T.teenSoundsLink, onClick = onSounds, modifier = Modifier.fillMaxWidth())
        AdultRow(T.teenSettings, onClick = onSettings)
        BasicText(T.setPrivacy, style = FoniaTheme.type.caption.copy(color = c.inkSecondary))
    }
}

/** The one filled colour of the page: today's step, with a struck „ăăă” sticker. */
@Composable
private fun TodayCard(onClick: () -> Unit) {
    val c = FoniaTheme.colors
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 168.dp)
            .background(c.accent, RoundedCornerShape(FoniaCorner.card))
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "${T.teenTodayCard}. ${T.teenTodayHelp}" }
            .padding(FoniaSpace.m),
    ) {
        Column(Modifier.align(Alignment.CenterStart).padding(end = 128.dp), verticalArrangement = Arrangement.spacedBy(FoniaSpace.xs)) {
            BasicText(T.teenTodayCard, style = FoniaTheme.type.title2.copy(color = c.surface))
            BasicText(T.teenTodayHelp, style = FoniaTheme.type.body.copy(color = c.surface.copy(alpha = 0.85f)))
            Spacer(Modifier.height(FoniaSpace.xxs))
            Row(
                Modifier.background(c.surface, RoundedCornerShape(50)).padding(horizontal = FoniaSpace.m, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(T.teenStart, style = FoniaTheme.type.headline.copy(color = c.accentDeep))
            }
        }
        Box(Modifier.align(Alignment.CenterEnd)) {
            StickerText("ăăă", FoniaSticker.Sun, fontSize = 36.sp, tilt = -8f, contentDescription = "")
            // the strike: the cut the screen makes, drawn as a shape (never red)
            Box(
                Modifier.matchParentSize().drawBehind {
                    val y = size.height * 0.55f
                    drawLine(c.surface, Offset(size.width * 0.04f, y + 6.dp.toPx()), Offset(size.width * 0.96f, y - 6.dp.toPx()), 6.dp.toPx(), StrokeCap.Round)
                },
            )
        }
    }
}
