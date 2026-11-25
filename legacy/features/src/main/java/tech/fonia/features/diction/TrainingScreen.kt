package tech.fonia.features.diction

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.fonia.design.ContinuousRoundedRectangle
import tech.fonia.design.FoniaIcon
import tech.fonia.design.FoniaIconView
import tech.fonia.design.FoniaText
import tech.fonia.design.Palette
import tech.fonia.design.PressableStyle
import tech.fonia.design.StyledButton
import tech.fonia.design.TextRole
import tech.fonia.design.UIWeight
import tech.fonia.design.fCaption
import tech.fonia.design.softShadow
import tech.fonia.design.ui
import tech.fonia.designsystem.letter.StickerText
import tech.fonia.designsystem.motion.staggeredEntrance
import tech.fonia.domain.SoundID
import tech.fonia.features.app.AppModel
import tech.fonia.features.app.AppRoute
import tech.fonia.features.app.HeaderPage
import tech.fonia.features.app.field
import tech.fonia.features.app.shortLetter
import tech.fonia.features.session.stickerFor
import java.util.UUID

/** The best results of a sound's drills, read again whenever the app comes back from a run. */
@Composable
private fun rememberBest(app: AppModel, profileID: UUID, sound: SoundID): Map<Int, Double> {
    var best by remember { mutableStateOf<Map<Int, Double>>(emptyMap()) }
    LaunchedEffect(sound, app.ticket) {
        if (app.ticket == null) best = runCatching { app.drills.best(profileID, sound) }.getOrDefault(emptyMap())
    }
    return best
}

/**
 * On a sound's page, next to the lessons: the second path, „Antrenament”. A child hears no count of the steps that
 * went well ([showsCount] false), as on iOS.
 */
@Composable
fun TrainingCard(sound: SoundID, app: AppModel, profileID: UUID, push: (AppRoute) -> Unit, modifier: Modifier = Modifier, showsCount: Boolean = true) {
    val drills = remember(sound) { app.drills(sound) }
    if (drills.isEmpty()) return
    val best = rememberBest(app, profileID, sound)
    val passed = drills.count { (best[it.index] ?: -1.0) >= DictionDrills.PASS_SHARE }
    val card = remember { ContinuousRoundedRectangle(22.dp) }
    val def = app.content.sound(sound)
    StyledButton(
        onClick = { push(AppRoute.Training(sound)) },
        style = PressableStyle(),
        modifier = modifier.semantics {
            contentDescription = "Antrenament de dicție"
            if (showsCount) stateDescription = "$passed din ${drills.size}"
        },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .softShadow(card)
                .background(Palette.sheet, card)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StickerText(def.shortLetter, stickerFor(sound.family.field), fontSize = if (def.shortLetter.length > 1) 30.sp else 40.sp, weight = 900f)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FoniaText("Antrenament", style = ui(19, UIWeight.bold, TextRole.headline), color = Palette.ink)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (drill in drills) {
                        Box(Modifier.size(10.dp).background(if ((best[drill.index] ?: -1.0) >= DictionDrills.PASS_SHARE) Palette.progress else Palette.progressTrack, CircleShape))
                    }
                }
            }
            FoniaIconView(FoniaIcon.play, size = 18f, tint = Palette.accentText)
        }
    }
}

/**
 * The ladder of a sound's drills, one under the other. A drill opens when the one before came out well enough
 * (his own success); a tap on an open one starts it.
 */
@Composable
fun TrainingScreen(sound: SoundID, app: AppModel, profileID: UUID) {
    val drills = remember(sound) { app.drills(sound) }
    val best = rememberBest(app, profileID, sound)
    val def = app.content.sound(sound)
    HeaderPage("Antrenament ${def.shortLetter}") {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .fillMaxWidth()
                .wrapContentWidth()
                .widthIn(max = 620.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            for (drill in drills) {
                val open = DictionDrills.isOpen(drill.index, best)
                val done = (best[drill.index] ?: -1.0) >= DictionDrills.PASS_SHARE
                DrillRow(drill, open, done, Modifier.staggeredEntrance(drill.index)) { app.startDrill(sound, drill.index) }
            }
            FoniaText(
                "Fiecare pas se deschide când ți-a ieșit bine cel de dinainte.",
                Modifier.padding(top = 4.dp, bottom = 24.dp),
                style = fCaption,
                color = Palette.inkSoft,
            )
        }
    }
}

@Composable
private fun DrillRow(drill: Drill, open: Boolean, done: Boolean, modifier: Modifier, onStart: () -> Unit) {
    val card = remember { ContinuousRoundedRectangle(20.dp) }
    StyledButton(
        onClick = onStart,
        enabled = open,
        style = PressableStyle(),
        modifier = modifier.semantics { stateDescription = if (done) "Făcut" else if (open) "Deschis" else "Se deschide după pasul de dinainte" },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Palette.sheet, card)
                .then(if (open && !done) Modifier.border(2.dp, Palette.accent, card) else Modifier)
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .alpha(if (open) 1f else 0.55f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(36.dp).background(if (done) Palette.progress else Palette.accentSoft, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (done) {
                    FoniaIconView(FoniaIcon.check, size = 18f, tint = Palette.sheet)
                } else {
                    FoniaText("${drill.index + 1}", style = ui(17, UIWeight.black, TextRole.headline), color = Palette.accentText)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                FoniaText(drill.title, style = ui(17, UIWeight.bold, TextRole.headline), color = Palette.ink)
                FoniaText(drill.detail, style = fCaption, color = Palette.inkSoft, maxLines = 2)
            }
            // Not open yet: no lock, just quieter; it opens by his own success on the step before.
            if (open) FoniaIconView(FoniaIcon.play, size = 16f, tint = Palette.accentText)
        }
    }
}
