package tech.fonia.features.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tech.fonia.design.Field
import tech.fonia.design.Foni
import tech.fonia.design.FoniButtonStyle
import tech.fonia.design.FoniMood
import tech.fonia.design.FoniaIcon
import tech.fonia.design.FoniaLabel
import tech.fonia.design.FoniaText
import tech.fonia.design.IconOverTitle
import tech.fonia.design.IconTileButtonStyle
import tech.fonia.design.Palette
import tech.fonia.design.PillButtonStyle
import tech.fonia.design.Rating
import tech.fonia.design.RatingChoice
import tech.fonia.design.RatingMark
import tech.fonia.design.RatingRow
import tech.fonia.design.StyledButton
import tech.fonia.design.TextRole
import tech.fonia.design.UIWeight
import tech.fonia.design.fBody
import tech.fonia.design.fBodyStrong
import tech.fonia.design.shine
import tech.fonia.design.ui
import tech.fonia.domain.FeedbackLevel
import tech.fonia.domain.Prosody
import tech.fonia.features.app.isChild
import tech.fonia.features.speaking.GuidedCompare

/** The result, sized to the feedback level: a mark, a line, and only what's needed next. */
@Composable
internal fun ResultPanel(
    verdict: TakeVerdict,
    judged: SoundJudge.Judged?,
    soundId: tech.fonia.domain.SoundID?,
    field: Field,
    onHow: (tech.fonia.judge.HintKey) -> Unit,
    feedback: FeedbackLevel,
    adult: Rating?,
    canReplay: Boolean,
    onPick: (Rating) -> Unit,
    onReplay: () -> Unit,
    onModel: () -> Unit,
    onSlow: (() -> Unit)?,
    onRetry: (() -> Unit)?,
    onNext: () -> Unit,
    /** Set when "Compară" is offered. */
    compare: CompareState? = null,
    onCompare: () -> Unit = {},
    isChild: Boolean = false,
) {
    // Only the learner's own rating is asked (self-judge trials); never an adult.
    val asks = feedback == FeedbackLevel.SelfJudge
    val question = "Cum ți s-a părut?"
    val pillHeight = DockMetrics.tapHeight(isChild)
    val rowHeight = DockMetrics.rowHeight(isChild)
    val stacked = LocalDensity.current.fontScale >= 1.5f
    // Flips once the card is up, so the light on "Mai departe" plays after it lands rather than with it.
    var landed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { landed = true }
    DockSheet {
        if (!asks || feedback != FeedbackLevel.SelfJudge) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Foni(Modifier.size(62.dp).foniReaction(verdict.rating), mood = if (verdict.rating == Rating.yes) FoniMood.happy else FoniMood.listening)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        verdict.rating?.let { RatingMark(it, Modifier.size(24.dp)) }
                        // One word says how it went ("Aproape"); the letter shows the rest.
                        PraiseTitle(if (verdict.rating == Rating.nearly) verdict.title.substringBefore(". ") else verdict.title, popping = verdict.rating == Rating.yes)
                    }
                    verdict.detail?.let { FoniaText(it, style = fBody, color = Palette.inkSoft) }
                }
            }
        }
        verdict.tip?.let { FoniaText(it, style = ui(18, UIWeight.medium, TextRole.body), color = Palette.ink, textAlign = TextAlign.Center) }
        // The one hint for a take that isn't there yet (only when the policy allows it): seen, not said.
        val hint = judged?.hint
        if (hint != null && verdict.rating != Rating.yes && !asks) HintCard(hint, field, onHow = { onHow(hint) })

        // Three small equals in one row; stacked when the text is too large to share a line.
        if (stacked) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (canReplay) Pill("Ascultă-te", FoniaIcon.listenBack, pillHeight, onReplay)
                Pill("Așa se spune", FoniaIcon.speaker, pillHeight, onModel)
                if (onSlow != null) Pill("Mai rar", FoniaIcon.glide, pillHeight, onSlow)
            }
        } else {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val tile = Modifier.weight(1f).fillMaxHeight()
                if (canReplay) Tile("Ascultă-te", FoniaIcon.listenBack, maxOf(pillHeight, 54.dp), onReplay, tile)
                Tile("Așa se spune", FoniaIcon.speaker, maxOf(pillHeight, 54.dp), onModel, tile)
                if (onSlow != null) Tile("Mai rar", FoniaIcon.glide, maxOf(pillHeight, 54.dp), onSlow, tile)
            }
        }

        if (compare != null) CompareRow(compare, onCompare)

        // What the take looked like, right above the last row: where the live meter was while listening.
        val visual = judged?.visual
        if (visual != null && soundId != null && !asks) TakeStrip(visual, soundId, field, correct = verdict.rating == Rating.yes)

        if (asks) {
            FoniaText(question, style = fBodyStrong, color = Palette.ink, textAlign = TextAlign.Center)
            RatingRow(
                choices = listOf(RatingChoice(Rating.yes, "Bine"), RatingChoice(Rating.nearly, "Aproape"), RatingChoice(Rating.notYet, "Încă nu")),
                onPick = onPick,
                suggested = if (feedback == FeedbackLevel.SelfJudge) null else verdict.rating,
                selected = adult,
            )
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (onRetry != null) {
                    StyledButton(
                        onClick = onRetry,
                        style = FoniButtonStyle(field = Field.paper, size = FoniButtonStyle.Size.regular, kind = FoniButtonStyle.Kind.secondary, minHeight = rowHeight),
                        modifier = Modifier.weight(1f),
                    ) { FoniaText("Încă o dată") }
                }
                StyledButton(
                    onClick = onNext,
                    style = FoniButtonStyle(field = Field.paper, size = FoniButtonStyle.Size.regular, minHeight = rowHeight),
                    // A light passing over the way on, once, after a take that came out right.
                    modifier = Modifier.weight(1f).shine(landed, enabled = verdict.rating == Rating.yes, delayMillis = 550),
                ) { FoniaText("Mai departe") }
            }
        }
    }
}

/** What the "Compară" line shows: whether it is playing, and the question once it has. */
internal data class CompareState(val isPlaying: Boolean, val question: String?)

/** Its own line under the listening pills: one action, then the question it leads to. */
@Composable
private fun CompareRow(compare: CompareState, onCompare: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        StyledButton(
            onClick = onCompare,
            style = PillButtonStyle(),
            enabled = !compare.isPlaying,
            onClickLabel = "Întâi exemplul, apoi tu",
            modifier = Modifier.semantics { if (compare.isPlaying) stateDescription = "Se aude" },
        ) { FoniaLabel(GuidedCompare.TITLE, FoniaIcon.ear, iconSize = 20f) }
        compare.question?.let {
            FoniaText(
                it,
                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = fBodyStrong,
                color = Palette.ink,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun Pill(title: String, icon: FoniaIcon, minHeight: Dp, onClick: () -> Unit) {
    StyledButton(onClick = onClick, style = PillButtonStyle(minHeight)) { FoniaLabel(title, icon, iconSize = 20f) }
}

@Composable
private fun Tile(title: String, icon: FoniaIcon, minHeight: Dp, onClick: () -> Unit, modifier: Modifier) {
    StyledButton(onClick = onClick, style = IconTileButtonStyle(minHeight), modifier = modifier) { IconOverTitle(title, icon) }
}

/** Mixed practice asks for the same word in different ways. Neutral says nothing. */
val Prosody.cue: String?
    get() = when (this) {
        Prosody.Neutral -> null
        Prosody.Loud -> "Tare"
        Prosody.Slow -> "Rar"
        Prosody.Fast -> "Repede"
        Prosody.Question -> "Ca o întrebare"
        Prosody.Exclamation -> "Cu bucurie"
    }
