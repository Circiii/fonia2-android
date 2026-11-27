package tech.fonia.features.grownups

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import tech.fonia.design.ContinuousRoundedRectangle
import tech.fonia.design.FoniaIcon
import tech.fonia.design.FoniaIconView
import tech.fonia.design.FoniaText
import tech.fonia.design.LocalReduceMotion
import tech.fonia.design.MascotPose
import tech.fonia.design.MascotView
import tech.fonia.design.Palette
import tech.fonia.design.PressableStyle
import tech.fonia.design.Radius
import tech.fonia.design.StyledButton
import tech.fonia.design.fBodyStrong
import tech.fonia.design.fCaption
import tech.fonia.design.swiftSpring
import tech.fonia.features.IS_DEBUG_BUILD
import tech.fonia.features.app.AppModel
import tech.fonia.features.app.InstallCount
import tech.fonia.features.app.SpeechHub
import tech.fonia.features.app.rememberStoredBoolean
import tech.fonia.features.ml.ModelCatalog
import tech.fonia.features.ml.ModelEntry
import tech.fonia.features.ml.ModelsHub
import tech.fonia.features.session.Settings
import tech.fonia.features.session.ShadowBadgeSettings

// MARK: The app

/** Once the code has gone the switch has nothing left to stop, and the line says so. */
@Composable
internal fun InstallCountRow(app: AppModel) {
    var countInstall by rememberStoredBoolean(app.preferences, InstallCount.ON_KEY, InstallCount.DEFAULT_ON)
    val installSent by rememberStoredBoolean(app.preferences, InstallCount.SENT_KEY)
    SwitchRow(
        FoniaIcon.chart.rawValue,
        "Numără instalarea",
        countInstall,
        if (installSent) {
            "Codul a plecat. Nu se mai trimite altul, decât după „Șterge tot”."
        } else {
            "La o zi după primul profil, trimite o dată un cod făcut la întâmplare, ca să știm câte instalări sunt. Fără nume și fără nimic din exerciții."
        },
    ) { countInstall = it }
}

@Composable
internal fun AppGroup(app: AppModel, modifier: Modifier) {
    var keepRecordings by rememberStoredBoolean(app.preferences, Settings.keepRecordings)
    var effectsOff by rememberStoredBoolean(app.preferences, Settings.effectsOff)
    SettingsGroup("Aplicația", SettingsTint.Gold, modifier) {
        SwitchRow(FoniaIcon.speaker.rawValue, "Sunete în aplicație", !effectsOff) { on ->
            effectsOff = !on
            app.audio.effectsMuted = !on
        }
        RowDivider()
        SwitchRow(
            FoniaIcon.microphone.rawValue,
            "Păstrează înregistrările copiilor",
            keepRecordings,
            "Dacă o lași oprită, telefonul ascultă și uită. Dacă o pornești, păstrăm ultimele 3 înregistrări la fiecare sunet, cel mult 90 de zile.",
        ) { keepRecordings = it }
        RowDivider()
        SpeechModelRow(app.speech)
        if (app.preferences != null) {
            RowDivider()
            ReminderRow(app)
        }
    }
}

/** The speech package (Android: it comes as a download): what it's for, and its download or delete. */
@Composable
private fun SpeechModelRow(speech: SpeechHub) {
    ActionRow(FoniaIcon.waves.rawValue, "Recunoașterea vorbirii", "Fără ea, telefonul nu înțelege engleza și propozițiile.") {
        when (val state = speech.state) {
            SpeechHub.State.Ready -> Pill("Șterge pachetul (${speech.megabytes} MB)") { speech.remove() }
            is SpeechHub.State.Downloading -> DownloadBar(state.fraction)
            SpeechHub.State.Absent, SpeechHub.State.Failed -> Pill("Descarcă pachetul (${speech.megabytes} MB)") { speech.download() }
        }
    }
}

// MARK: The study and the experiments

/**
 * The trial's own switches and the experiments, folded away until an adult opens them. All of them start off, and none
 * changes what a child sees or hears. The group stays in release builds, because the pilot runs on them.
 */
@Composable
internal fun MoreGroup(app: AppModel, modifier: Modifier) {
    var showsMore by remember { mutableStateOf(false) }
    var faceGuides by rememberStoredBoolean(app.preferences, Settings.faceGuides)
    var cameraCoach by rememberStoredBoolean(app.preferences, Settings.cameraCoach)
    val reduceMotion = LocalReduceMotion.current
    // How many of the folded switches are on, so nothing turned on stays out of sight unnoticed.
    val on = listOf(app.isPilotLogOn, faceGuides, cameraCoach).count { it }
    val summary = when (on) {
        0 -> "Toate sunt oprite"
        1 -> "Una e pornită"
        else -> "$on sunt pornite"
    }
    val turn by animateFloatAsState(if (showsMore) 180f else 0f, swiftSpring(0.4, 0.15), label = "moreChevron")
    val shape = ContinuousRoundedRectangle(Radius.card)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StyledButton(
            onClick = { showsMore = !showsMore },
            style = PressableStyle(),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { stateDescription = if (showsMore) "Deschis" else "Închis" },
            onClickLabel = "Opțiunile pentru studiul cu logopezii și cele experimentale",
        ) {
            Row(
                Modifier.fillMaxWidth().background(Palette.sheet, shape).padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CompositionLocalProvider(LocalSettingsTint provides SettingsTint.Grey) { SettingsIconTile(FoniaIcon.target.rawValue) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    FoniaText("Studiu și funcții noi", style = fBodyStrong, color = Palette.ink)
                    FoniaText(summary, style = fCaption, color = Palette.inkSoft)
                }
                FoniaIconView(FoniaIcon.down, Modifier.rotate(turn), size = 13f, tint = Palette.inkSoft)
            }
        }
        AnimatedVisibility(
            visible = showsMore,
            enter = if (reduceMotion) fadeIn() else fadeIn() + expandVertically(swiftSpring(0.4, 0.15)) + slideInVertically(swiftSpring(0.4, 0.15)) { -it / 6 },
            exit = if (reduceMotion) fadeOut() else fadeOut(tween(150)) + shrinkVertically(swiftSpring(0.4, 0.15)),
        ) {
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                PilotGroup(app)
                SettingsGroup("Experimental", SettingsTint.Grey) {
                    SwitchRow(FoniaIcon.mirror.rawValue, "Ghidaje pe față în oglindă", faceGuides, "Folosește camera din față. Nimic nu pleacă de pe telefon.") { faceGuides = it }
                    RowDivider()
                    SwitchRow(
                        FoniaIcon.loud.rawValue,
                        "Vorbit în public în fața camerei",
                        cameraCoach,
                        "Un pas nou la „Vorbit în public”: te vezi pe ecran și primești semne pe loc. Folosește camera din față. Nimic nu pleacă de pe telefon.",
                    ) { cameraCoach = it }
                }
            }
        }
    }
}

/**
 * The trial's switches: the practice log, the phone scorer of shadow mode with its size before anything is fetched and
 * a delete, and in debug builds the switch for the agreement badge in lessons.
 */
@Composable
private fun PilotGroup(app: AppModel) {
    SettingsGroup("Pentru studiul cu logopezii", SettingsTint.Grey) {
        SwitchRow(
            SettingsIcons.NOTE,
            "Jurnalul de exersare",
            app.isPilotLogOn,
            "Notează pentru logoped câte încercări au fost în fiecare zi, doar ca numere. Fără voce. Pornit doar dacă îl pornești tu.",
        ) { app.setPilotLog(it) }
        val ml = app.ml
        if (ml != null) {
            val entry = ModelCatalog.ZIPA
            val state = ml.models.state(entry)
            // The shipped app has no internet permission (app/src/main/AndroidManifest.xml), so it can't fetch the
            // package: the row would only fail. It shows where the permission is there (a build that asks for it).
            val context = LocalContext.current
            val canFetch = remember { context.checkSelfPermission(android.Manifest.permission.INTERNET) == android.content.pm.PackageManager.PERMISSION_GRANTED }
            if (canFetch || state != ModelsHub.State.Absent) {
                val detail = buildString {
                    append("Un al doilea fel de a asculta, tot pe telefon. Notează ce a auzit doar în jurnalul pentru logoped, fără voce. Copilul vede și aude la fel. Se descarcă doar pe Wi-Fi.")
                    if (!app.isPilotLogOn) append(" Notează doar cu jurnalul de exersare pornit.")
                    if (state is ModelsHub.State.Failed && state.noNetwork) append(" Acum nu e Wi-Fi.")
                }
                RowDivider()
                ActionRow(FoniaIcon.download.rawValue, "Ascultare pe sunete, în probă", detail) { ShadowModelAction(ml.models, entry) }
            }
            if (IS_DEBUG_BUILD) {
                var badge by rememberStoredBoolean(app.preferences, ShadowBadgeSettings.KEY)
                RowDivider()
                SwitchRow(
                    FoniaIcon.check.rawValue,
                    "Acordul cu ZIPA în lecție",
                    badge,
                    "Doar în varianta de test. În lecție apar, sus, verdictul ZIPA lângă al telefonului și acordul dintre ele.",
                ) { badge = it }
            }
        }
    }
}

@Composable
private fun ShadowModelAction(models: ModelsHub, entry: ModelEntry) {
    if (!models.isSupported) {
        FoniaText("Nu merge pe acest telefon", style = fCaption, color = Palette.inkSoft)
        return
    }
    when (val state = models.state(entry)) {
        ModelsHub.State.Ready -> Pill("Șterge pachetul (${entry.megabytes} MB)") { models.remove(entry) }
        is ModelsHub.State.Downloading -> DownloadBar(state.fraction)
        ModelsHub.State.Absent, is ModelsHub.State.Failed -> Pill("Descarcă pachetul (${entry.megabytes} MB)") { models.download(entry) }
    }
}

// MARK: The footer

@Composable
internal fun Footer(version: String) {
    Column(
        Modifier.fillMaxWidth().padding(top = 4.dp).semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(36.dp).alpha(0.8f).clearAndSetSemantics {}) { MascotView(MascotPose.easy, Modifier.size(36.dp)) }
        FoniaText("Fonia $version", style = fCaption, color = Palette.inkSoft)
        FoniaText("Totul rămâne pe telefon.", style = fCaption, color = Palette.inkSoft)
    }
}
