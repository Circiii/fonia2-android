package tech.fonia.features.home

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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import tech.fonia.design.Foni
import tech.fonia.design.FoniaText
import tech.fonia.design.PageBackdrop
import tech.fonia.design.Palette
import tech.fonia.design.QuietButtonStyle
import tech.fonia.design.StyledButton
import tech.fonia.design.TextRole
import tech.fonia.design.TileButtonStyle
import tech.fonia.design.UIWeight
import tech.fonia.design.fHeadline
import tech.fonia.design.ui
import tech.fonia.features.app.AppModel
import tech.fonia.features.app.isChild
import tech.fonia.store.Profile

/** "Who's practising" is a place, not a setting. New profiles are made in the grown-ups' area. */
@Composable
fun WhoView(app: AppModel, onShowGrownups: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        PageBackdrop()
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 20.dp)
                .padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FoniaText("Cine exersează?", Modifier.padding(top = 40.dp), style = ui(34, UIWeight.black, TextRole.largeTitle), color = Palette.ink)
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                for (pair in app.profiles.chunked(2)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        for (profile in pair) {
                            StyledButton(onClick = { app.select(profile) }, style = TileButtonStyle(radius = 28.dp), modifier = Modifier.weight(1f)) {
                                ProfileTile(profile)
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            StyledButton(onClick = onShowGrownups, style = QuietButtonStyle(Palette.ink)) { FoniaText("Setări") }
        }
    }
}

@Composable
private fun ProfileTile(profile: Profile) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 176.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Foni(Modifier.height(100.dp))
        FoniaText(profile.displayName, style = fHeadline, color = Palette.ink, maxLines = 1, minimumScaleFactor = 0.7f)
    }
}

/** The name shown everywhere: `profile.name ?? (profile.isChild ? "Copil" : "Eu")`. */
val Profile.displayName: String get() = name ?: if (isChild) "Copil" else "Eu"
