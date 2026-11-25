package tech.fonia.features.home

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
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import tech.fonia.design.ChoicePillStyle
import tech.fonia.design.ContinuousRoundedRectangle
import tech.fonia.design.FoniButtonStyle
import tech.fonia.design.Foni
import tech.fonia.design.FoniMood
import tech.fonia.design.FoniaIcon
import tech.fonia.design.FoniaIconView
import tech.fonia.design.FoniaText
import tech.fonia.design.MiniPillStyle
import tech.fonia.design.Palette
import tech.fonia.design.Radius
import tech.fonia.design.SceneBackdrop
import tech.fonia.design.SceneName
import tech.fonia.design.StyledButton
import tech.fonia.design.TextRole
import tech.fonia.design.ToolButtonStyle
import tech.fonia.design.UIWeight
import tech.fonia.design.fBody
import tech.fonia.design.fTitle
import tech.fonia.design.ui
import tech.fonia.domain.Plural
import tech.fonia.features.app.FoniaSheet
import tech.fonia.features.app.HelpButton
import tech.fonia.features.app.LocalDismiss
import tech.fonia.features.app.SheetDetent
import tech.fonia.features.app.domainAge
import tech.fonia.features.app.isChild
import tech.fonia.features.app.minutes
import tech.fonia.features.app.shortLetter
import tech.fonia.features.guide.AdultCard
import tech.fonia.features.guide.AdultCardPage
import tech.fonia.features.guide.AdultCards

/** What the big button opens: what comes next and how long it takes. Nothing happens until it's tapped. */
@Composable
fun StartSheet(path: PathModel, onClose: () -> Unit, onPick: (NextStep) -> Unit) {
    var showHelp by remember { mutableStateOf(false) }
    var readingCard by remember { mutableStateOf<AdultCard?>(null) }
    // Kept apart from the card so the sheet still has its text while it slides away.
    var readsCard by remember { mutableStateOf(false) }
    val step = path.continuing
    val isChild = path.profile.isChild
    val intro = run {
        if (step == null) return@run "Ai terminat tot ce ai ales. Dacă vrei, mai facem o încălzire."
        val range = path.profile.domainAge.attemptRange
        val minutes = path.profile.domainAge.minutes((range.first + range.last) / 2)
        "Urmează „${step.lesson.title}”. Durează cam ${Plural.format(minutes, Plural.Noun.Minut)}."
    }
    val parentBrief: String? = null
    // The card for the kind of lesson the big button opens, read again. Never for an adult practising alone.
    val nextCard = step?.lesson?.focus?.let(AdultCards::kind)?.let { AdultCards.card(it, isChild) }

    Box(Modifier.fillMaxSize()) {
        SceneBackdrop(SceneName.start)
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .widthIn(max = 620.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .padding(top = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(Modifier.fillMaxWidth()) {
                    StyledButton(onClick = onClose, style = ToolButtonStyle(), modifier = Modifier.semantics { contentDescription = "Închide" }) {
                        FoniaIconView(FoniaIcon.close, size = 16f)
                    }
                    Spacer(Modifier.weight(1f))
                    if (isChild) HelpButton { showHelp = true }
                }
                FoniaText(
                    "Hai să exersăm",
                    Modifier
                        .padding(horizontal = 58.dp)
                        .semantics { heading() },
                    style = fTitle,
                    color = Palette.ink,
                    maxLines = 1,
                    minimumScaleFactor = 0.7f,
                )
            }
            Spacer(Modifier.weight(1f).heightIn(min = 12.dp))
            Foni(Modifier.height(210.dp), mood = FoniMood.hello, reactsToTap = true)
            Spacer(Modifier.weight(1f).heightIn(min = 12.dp))
            Column(
                Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 10.dp)
                    .fillMaxWidth()
                    .background(Palette.sheet, ContinuousRoundedRectangle(Radius.panel))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                FoniaText(intro, style = ui(19, UIWeight.semibold, TextRole.title3), color = Palette.ink, textAlign = TextAlign.Center)
                if (parentBrief != null) {
                    FoniaText(parentBrief, style = fBody, color = Palette.inkSoft, textAlign = TextAlign.Center)
                }
                if (nextCard != null) {
                    StyledButton(onClick = { readingCard = nextCard; readsCard = true }, style = MiniPillStyle(), modifier = Modifier.heightIn(min = 48.dp)) {
                        FoniaText(AdultCards.START_SHEET_BUTTON)
                    }
                }
                StyledButton(onClick = { step?.let(onPick) }, style = FoniButtonStyle(), enabled = step != null) {
                    FoniaText(if (path.hasStarted) "Continui" else "Încep")
                }
                if (isChild) {
                    StyledButton(onClick = { onPick(path.warmingUp) }, style = ChoicePillStyle(ChoicePillStyle.Tone.quiet)) {
                        FoniaText("Mă încălzesc")
                    }
                }
            }
        }
    }
    FoniaSheet(isPresented = showHelp, onDismiss = { showHelp = false }, detents = listOf(SheetDetent.medium), showsDragIndicator = true) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Palette.paper)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FoniaText("Cum începi", Modifier.semantics { heading() }, style = fTitle, color = Palette.ink)
            FoniaText("„Mă încălzesc” înseamnă câteva mișcări de gură și limbă, ca să-ți iasă mai ușor.", style = fBody, color = Palette.ink)
            FoniaText("„${if (path.hasStarted) "Continui" else "Încep"}” te duce direct la lecția următoare.", style = fBody, color = Palette.ink)
        }
    }
    FoniaSheet(isPresented = readsCard, onDismiss = { readsCard = false }, detents = listOf(SheetDetent.large), showsDragIndicator = true) {
        readingCard?.let { card ->
            CompositionLocalProvider(LocalDismiss provides { readsCard = false }) {
                AdultCardPage(card, closes = true)
            }
        }
    }
}
