package tech.fonia.features.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import tech.fonia.design.FoniButtonStyle
import tech.fonia.design.FoniaText
import tech.fonia.design.TiedText
import tech.fonia.design.Palette
import tech.fonia.design.PracticeSheet
import tech.fonia.design.StyledButton
import tech.fonia.design.fBody
import tech.fonia.design.fCaption
import tech.fonia.design.fHeadline
import tech.fonia.design.fTitle
import tech.fonia.domain.SessionFocus
import tech.fonia.features.app.AppModel
import tech.fonia.features.app.DetailRow
import tech.fonia.features.app.Destination
import tech.fonia.features.app.HeaderPage
import tech.fonia.features.app.InsetGroupedList
import tech.fonia.features.app.ListSection
import tech.fonia.features.app.LocalDismiss
import tech.fonia.features.app.isChild
import tech.fonia.features.home.rememberPushDestination
import tech.fonia.store.Profile

/** The card, if any, to put in front of the adult before something opens for this profile. */
object FirstTimeCard {
    /** A lesson, when this child profile hasn't met its kind yet. */
    fun lesson(focus: SessionFocus, profile: Profile, app: AppModel): AdultCard? =
        if (app.arguments.hidesAdultCards) null else AdultCards.firstTimeCard(focus, profile.isChild, AdultCardsSeen.seen(profile.id, app.preferences))

    fun mirror(profile: Profile, app: AppModel): AdultCard? =
        if (app.arguments.hidesAdultCards) null else AdultCards.firstTimeMirrorCard(profile.isChild, AdultCardsSeen.seen(profile.id, app.preferences))
}

/**
 * One card on a white sheet: its name, then the target, what a good try is like and what to do after a miss. It looks
 * like the other notes for the adult, because it is the same kind of thing.
 */
@Composable
fun AdultCardSheet(card: AdultCard, showsReopenHint: Boolean = false) {
    PracticeSheet {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            FoniaText(card.title, Modifier.semantics { heading() }, style = fTitle, color = Palette.ink)
            Part(AdultCards.TARGET_HEADING, card.target)
            Part(AdultCards.GOOD_TRY_HEADING, card.goodTry)
            Part(AdultCards.AFTER_MISS_HEADING, card.afterMiss)
            if (showsReopenHint) FoniaText(AdultCards.REOPEN_HINT, style = fCaption, color = Palette.inkSoft)
        }
    }
}

@Composable
private fun Part(heading: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FoniaText(heading, Modifier.semantics { heading() }, style = fHeadline, color = Palette.ink)
        TiedText(text, style = fBody, color = Palette.ink)
    }
}

/** The card in a scrolling column, centred and no wider than a reading line. */
@Composable
private fun CardScroll(card: AdultCard, showsReopenHint: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = 620.dp).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            AdultCardSheet(card, showsReopenHint)
        }
    }
}

/** A card read again, on a page of its own: from the start sheet or the settings. */
@Composable
fun AdultCardPage(card: AdultCard, closes: Boolean = false) {
    HeaderPage(AdultCards.PAGE_TITLE, closes = closes) {
        CardScroll(card, showsReopenHint = false, Modifier.weight(1f).windowInsetsPadding(WindowInsets.navigationBars))
    }
}

/**
 * Shows the card in place of [content] until the adult has read it, then marks it as met for this profile and goes
 * on to [content] (iOS `AdultCardGate`). There is no card, and so no stop, for an adult practising alone, for a lesson
 * with no card and for a kind that profile has already met. [onClose] is the page's cross or back arrow.
 */
@Composable
fun AdultCardGate(profile: Profile, card: AdultCard?, app: AppModel, closes: Boolean = true, onClose: () -> Unit, content: @Composable () -> Unit) {
    // Read once, when the gate appears: a card shown is not taken back by the page redrawing.
    var pending by remember { mutableStateOf(card) }
    val shown = pending
    if (shown == null) {
        content()
        return
    }
    CompositionLocalProvider(LocalDismiss provides onClose) {
        HeaderPage(AdultCards.PAGE_TITLE, closes = closes) {
            CardScroll(shown, showsReopenHint = true, Modifier.weight(1f))
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Palette.paper)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                StyledButton(
                    onClick = {
                        AdultCardsSeen.markSeen(shown.kind, profile.id, app.preferences)
                        pending = null
                    },
                    style = FoniButtonStyle(),
                    modifier = Modifier.widthIn(max = 620.dp),
                ) { FoniaText(AdultCards.UNDERSTOOD) }
            }
        }
    }
}

/** Every card in a list, for the settings: whoever takes over finds the same texts here, with nothing to set up. */
@Composable
fun AdultCardsScreen() {
    val push = rememberPushDestination()
    HeaderPage(AdultCards.SCREEN_TITLE) {
        InsetGroupedList(Modifier.weight(1f)) {
            FoniaText(AdultCards.SCREEN_LEAD, Modifier.padding(horizontal = 16.dp), style = fBody, color = Palette.inkSoft)
            ListSection {
                for (kind in AdultCardKind.entries) {
                    row {
                        DetailRow(AdultCards.card(kind).title, AdultCards.detail(kind), onClick = { push(Destination.AdultCard(kind.rawValue)) })
                    }
                }
            }
        }
    }
}
