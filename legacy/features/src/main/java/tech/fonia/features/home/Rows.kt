package tech.fonia.features.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import tech.fonia.design.ArtTile
import tech.fonia.design.ArtTileMark
import tech.fonia.design.ContinuousRoundedRectangle
import tech.fonia.design.Field
import tech.fonia.design.FoniButtonStyle
import tech.fonia.design.FoniaIcon
import tech.fonia.design.FoniaIconView
import tech.fonia.design.FoniaLabel
import tech.fonia.design.FoniaText
import tech.fonia.design.Palette
import tech.fonia.design.SessionBar
import tech.fonia.design.StyledButton
import tech.fonia.design.Surface
import tech.fonia.design.TextRole
import tech.fonia.design.UIWeight
import tech.fonia.design.fBodyStrong
import tech.fonia.design.fCaption
import tech.fonia.design.isLargeText
import tech.fonia.design.monospacedDigit
import tech.fonia.design.ui
import tech.fonia.features.app.SpeechHub

// Rows.swift. `RowGroup` and `TopicRow` are not used by any iOS screen, so they are not ported.

/** A lesson or a unit in a list: its own clear card, with a white tile and something on the right. */
@Composable
fun CardRow(tile: ArtTileMark, title: String, detail: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit) {
    Surface(modifier, padding = 10.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            ArtTile(tile, side = 56f)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                FoniaText(title, style = fBodyStrong, color = Palette.ink, textAlign = TextAlign.Start)
                // Two lines keep the rows even; with large text the detail runs on rather than being cut.
                FoniaText(detail, style = fCaption, color = Palette.inkSoft, textAlign = TextAlign.Start, maxLines = if (isLargeText) Int.MAX_VALUE else 2)
            }
            Box(Modifier.padding(end = 6.dp)) { trailing() }
        }
    }
}

/** The small round mark at the end of a row: finished, or still to do. */
@Composable
fun DoneDot(isDone: Boolean) {
    Box(Modifier.size(26.dp).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        if (isDone) {
            Box(Modifier.size(26.dp).background(Palette.accent, CircleShape))
            FoniaIconView(FoniaIcon.tick, size = 13f, tint = Color.White)
        } else {
            Box(Modifier.size(8.dp).background(Palette.ink, CircleShape))
        }
    }
}

/** A number with its name, and a small object above it. */
@Composable
fun StatTile(icon: FoniaIcon, tint: Color, value: String, label: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .background(Palette.cloud, ContinuousRoundedRectangle(16.dp))
            .padding(12.dp)
            .clearAndSetSemantics { contentDescription = "$value, $label" },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FoniaIconView(icon, size = 32f, relativeTo = TextRole.title2, tint = tint)
        Column {
            FoniaText(value, style = ui(22, UIWeight.black, TextRole.title2).monospacedDigit(), color = Palette.ink, maxLines = 1, minimumScaleFactor = 0.6f)
            FoniaText(label, style = fCaption, color = Palette.inkSoft, maxLines = 1, minimumScaleFactor = 0.8f)
        }
        Spacer(Modifier.weight(1f))
    }
}

/** Plain about what the download is, how big, and that nothing goes the other way. */
@Composable
fun ModelNotice(speech: SpeechHub) {
    when (val state = speech.state) {
        SpeechHub.State.Ready -> Unit
        SpeechHub.State.Absent, SpeechHub.State.Failed -> Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FoniaText(
                "Ca să înțeleagă ce spui, telefonul are nevoie de un pachet de ${speech.megabytes} MB. Se descarcă o singură dată și nu trimite nimic despre tine.",
                style = fCaption,
                color = Palette.inkSoft,
                textAlign = TextAlign.Center,
            )
            if (state == SpeechHub.State.Failed) {
                FoniaText("Descărcarea n-a mers. Verifică internetul și mai încearcă.", style = fCaption, color = Palette.warmDeep, textAlign = TextAlign.Center)
            }
            StyledButton(onClick = { speech.download() }, style = FoniButtonStyle(field = Field.paper, size = FoniButtonStyle.Size.regular)) {
                FoniaLabel("Descarcă pachetul", FoniaIcon.download)
            }
        }
        is SpeechHub.State.Downloading -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            FoniaText("Se descarcă pachetul…", style = fCaption, color = Palette.inkSoft)
            SessionBar(state.fraction, color = Palette.ink, track = Palette.line)
        }
    }
}
