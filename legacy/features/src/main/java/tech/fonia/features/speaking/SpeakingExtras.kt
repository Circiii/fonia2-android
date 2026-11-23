package tech.fonia.features.speaking

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import tech.fonia.design.ArtTileMark
import tech.fonia.design.FoniaIcon
import tech.fonia.design.FoniaIconView
import tech.fonia.design.FoniaText
import tech.fonia.design.Palette
import tech.fonia.design.PressableStyle
import tech.fonia.design.StyledButton
import tech.fonia.design.fHeadline
import tech.fonia.domain.Language
import tech.fonia.features.app.AppModel
import tech.fonia.features.app.FullScreenCover
import tech.fonia.features.home.CardRow
import tech.fonia.features.speaking.bac.BacOralScreen
import tech.fonia.features.speaking.emphasis.EmphasisScreen
import tech.fonia.store.Profile

// The two speaking exercises that are not on iOS (fonia-android-plan/03 §1.5): V2, the emphasis exercise, and V6, the
// BAC oral simulation. `SpeakingHome` shows their rows and covers through the three calls below, so the page ported
// from iOS keeps its own code.

/** Which of the two exercises covers the speaking page. */
class SpeakingExtras {
    var emphasis by mutableStateOf(false)
    var bac by mutableStateOf(false)
}

@Composable
fun rememberSpeakingExtras(): SpeakingExtras = remember { SpeakingExtras() }

/** "Citește cu voce tare": the emphasis exercise, and the BAC simulation when the page is in Romanian. */
@Composable
fun SpeakingExtraRows(extras: SpeakingExtras, language: Language) {
    FoniaText("Citește cu voce tare", Modifier.padding(start = 4.dp, top = 4.dp).semantics { heading() }, style = fHeadline, color = Palette.ink)
    ExtraRow(
        ArtTileMark.Art("art-weights"),
        "Cuvântul important",
        "Răspunzi la o întrebare și apeși pe cuvântul marcat. Telefonul măsoară dacă a ieșit în față.",
    ) { extras.emphasis = true }
    if (language == Language.Ro) {
        ExtraRow(
            ArtTileMark.Art("art-notes"),
            "Simulare BAC oral",
            "Proba A: bilet, pregătire, citire, răspunsuri și opinie. Nota o dau profesorii.",
        ) { extras.bac = true }
    }
}

/** The covers, placed in the page's outer box next to the talk's own cover. */
@Composable
fun SpeakingExtraCovers(extras: SpeakingExtras, language: Language, profile: Profile, app: AppModel) {
    // The language the exercise opened with stays while it slides away.
    var opened by remember { mutableStateOf(language) }
    SideEffect { if (!extras.emphasis) opened = language }
    FullScreenCover(isPresented = extras.emphasis) {
        EmphasisScreen(opened, profile, app, onDone = { extras.emphasis = false })
    }
    FullScreenCover(isPresented = extras.bac) {
        BacOralScreen(profile, app, onDone = { extras.bac = false })
    }
}

@Composable
private fun ExtraRow(tile: ArtTileMark, title: String, detail: String, onClick: () -> Unit) {
    StyledButton(onClick = onClick, style = PressableStyle()) {
        CardRow(tile, title, detail) {
            Box(Modifier.size(32.dp).background(Palette.accent, CircleShape), contentAlignment = Alignment.Center) {
                FoniaIconView(FoniaIcon.play, size = 14f, tint = Palette.buttonLabel)
            }
        }
    }
}
