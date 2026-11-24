package tech.fonia.features.grownups

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.fonia.design.FoniaText
import tech.fonia.design.QuietButtonStyle
import tech.fonia.design.StyledButton
import tech.fonia.design.fBodyStrong
import tech.fonia.design.Palette
import tech.fonia.design.fBody
import tech.fonia.design.fCaption
import tech.fonia.design.fHeadline
import tech.fonia.dsp.DSPThresholds
import tech.fonia.features.adaptive.AdaptiveStore
import tech.fonia.features.app.ActionRole
import tech.fonia.features.app.AdultGateScreen
import tech.fonia.features.app.needsAdultGate
import tech.fonia.features.app.AppModel
import tech.fonia.features.app.ButtonRow
import tech.fonia.features.app.Destination
import tech.fonia.features.app.DialogAction
import tech.fonia.features.app.FoniaAlert
import tech.fonia.features.app.FoniaConfirmationDialog
import tech.fonia.features.app.FullWipe
import tech.fonia.features.app.FoniaSheet
import tech.fonia.features.app.InsetGroupedList
import tech.fonia.features.app.LocalDismiss
import tech.fonia.features.app.NavStack
import tech.fonia.features.app.NavigationHost
import tech.fonia.features.app.NavigationRow
import tech.fonia.features.app.ListSection
import tech.fonia.features.app.ProgressRow
import tech.fonia.features.app.SpeechHub
import tech.fonia.features.app.SystemNavigationBar
import tech.fonia.features.app.ToggleRow
import tech.fonia.features.app.ValueRow
import tech.fonia.features.app.rememberStoredBoolean
import tech.fonia.features.guide.AdultCardsSeen
import tech.fonia.features.home.DestinationScreen
import tech.fonia.features.home.displayName
import tech.fonia.features.home.rememberPushDestination
import tech.fonia.features.pilot.PilotLog
import tech.fonia.features.session.Settings
import tech.fonia.features.speaking.SpeakingMemory
import tech.fonia.store.FoniaRepository
import tech.fonia.store.Profile
import java.io.File
import java.util.UUID

/**
 * The "Setări" sheet: who practises, the sound switches, the speech package, what stays on the phone, and wiping
 * everything. It has its own navigation stack, as on iOS, so a profile opens inside the sheet.
 */
@Composable
fun GrownupsHome(app: AppModel, onDone: () -> Unit) {
    // With a child on the phone, the settings are the adult's (iOS AdultGate): the check comes first.
    if (app.needsAdultGate) {
        AdultGateScreen(app.gate, onPass = {}, onClose = onDone)
        return
    }
    val stack = remember { NavStack() }
    NavigationHost(stack) { destination ->
        if (destination == Destination.Root) {
            GrownupsList(app, onDone)
        } else {
            DestinationScreen(destination, app, path = null, onShowGrownups = {})
        }
    }
}

@Composable
private fun GrownupsList(app: AppModel, onDone: () -> Unit) {
    val push = rememberPushDestination()
    val context = LocalContext.current
    var keepRecordings by rememberStoredBoolean(app.preferences, Settings.keepRecordings)
    var effectsOff by rememberStoredBoolean(app.preferences, Settings.effectsOff)
    var addingChild by remember { mutableStateOf(false) }
    var confirmWipe by remember { mutableStateOf(false) }
    /** What stayed on the phone after "Șterge tot" (null: no failure to show). */
    var wipeLeft by remember { mutableStateOf<List<String>?>(null) }
    var isWiping by remember { mutableStateOf(false) }
    /** Everything goes, or the adult hears what didn't. Once it worked the app is back at its first run. */
    fun wipe() {
        if (isWiping) return
        isWiping = true
        app.scope.launch {
            val left = try {
                app.wipeEverything()
            } finally {
                isWiping = false
            }
            if (left.isEmpty()) onDone() else wipeLeft = left
        }
    }
    var showLegal by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(Palette.paper)) {
        SystemNavigationBar("Setări", onDone = onDone)
        InsetGroupedList(Modifier.weight(1f)) {
            ListSection(header = "Cine exersează") {
                for (profile in app.profiles) {
                    row { NavigationRow(profile.displayName, onClick = { push(Destination.ProfileDetail(profile.id)) }) }
                }
                row { ButtonRow("Adaugă un copil", onClick = { addingChild = true }) }
            }
            ListSection(footer = "Dacă o lași oprită, telefonul ascultă și uită. Dacă o pornești, păstrăm ultimele 3 înregistrări la fiecare sunet, cel mult 90 de zile.") {
                row {
                    ToggleRow("Sunete în aplicație", !effectsOff) { on ->
                        effectsOff = !on
                        app.audio.effectsMuted = !on
                    }
                }
                row { ToggleRow("Păstrează înregistrările copiilor", keepRecordings) { keepRecordings = it } }
            }
            ListSection(header = "Recunoașterea vorbirii", footer = "Fără ea, telefonul nu înțelege engleza și propozițiile.") {
                row { ModelRow(app.speech) }
            }
            ListSection(
                header = "Pentru probă",
                footer = "Ține minte, ca numere, câte încercări au fost în fiecare zi, pentru logoped. Fără voce. Pornit doar dacă îl pornești tu.",
            ) {
                row { ToggleRow("Jurnalul de exersare", app.isPilotLogOn) { app.setPilotLog(it) } }
            }
            ModelSections(app)
            ListSection {
                row { ButtonRow("Ce păstrăm pe telefon", onClick = { showLegal = true }) }
                row { NavigationRow("Licențe", onClick = { push(Destination.Licences) }) }
                row { ButtonRow("Ajutor", onClick = { LegalInfo.open(context, LegalInfo.SUPPORT_URL) }) }
                row { ValueRow("Versiunea", LegalInfo.version(context)) }
                row { ButtonRow("Șterge tot de pe telefon", onClick = { confirmWipe = true }, destructive = true) }
            }
        }
    }
    AddChildSheet(app, isPresented = addingChild, onDone = { addingChild = false })
    FoniaSheet(isPresented = showLegal, onDismiss = { showLegal = false }) {
        PrivacyNote(onDone = { showLegal = false }, withReading = app.arguments.drafts, onOpenLink = { LegalInfo.open(context, it) })
    }
    FoniaConfirmationDialog(
        isPresented = confirmWipe,
        onDismiss = { confirmWipe = false },
        title = "Ștergi tot?",
        message = "Profilurile, încercările, înregistrările, jurnalul și alegerile din Setări dispar de pe telefon. Nu se pot recupera.",
        actions = listOf(
            DialogAction("Șterge tot", ActionRole.Destructive) { wipe() },
            DialogAction("Anulează", ActionRole.Cancel),
        ),
    )
    FoniaAlert(
        isPresented = wipeLeft != null,
        onDismiss = { wipeLeft = null },
        title = "Nu am putut șterge tot",
        message = wipeLeft?.let { FullWipe.leftLine(it) },
        actions = listOf(
            DialogAction("Încearcă din nou") {
                wipeLeft = null
                wipe()
            },
            DialogAction("Anulează", ActionRole.Cancel),
        ),
    )
}

@Composable
private fun ModelRow(speech: SpeechHub) {
    when (val state = speech.state) {
        SpeechHub.State.Ready -> ButtonRow("Șterge pachetul (${speech.megabytes} MB)", onClick = { speech.remove() }, destructive = true)
        is SpeechHub.State.Downloading -> ProgressRow("Se descarcă…", state.fraction)
        SpeechHub.State.Absent, SpeechHub.State.Failed -> ButtonRow("Descarcă pachetul (${speech.megabytes} MB)", onClick = { speech.download() })
    }
}

/** One person, as the settings list them. */
@Composable
fun ProfileDetail(profileID: UUID, app: AppModel) {
    val profile = app.profiles.firstOrNull { it.id == profileID } ?: return
    val push = rememberPushDestination()
    val dismiss = LocalDismiss.current
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(Palette.paper)) {
        SystemNavigationBar(profile.name ?: "Profil", onBack = dismiss)
        InsetGroupedList(Modifier.weight(1f)) {
            ListSection {
                profileActivityRows(profile, app, context, push)
                profileSetupRows(profile, push)
            }
            ListSection {
                row { ButtonRow("Șterge profilul", onClick = { confirmDelete = true }, destructive = true) }
            }
        }
    }
    FoniaConfirmationDialog(
        isPresented = confirmDelete,
        onDismiss = { confirmDelete = false },
        title = "Ștergi profilul?",
        message = "Dispar și încercările și înregistrările acestui profil. Nu se pot recupera.",
        actions = listOf(
            DialogAction("Șterge", ActionRole.Destructive) {
                app.scope.launch {
                    try {
                        app.repository.deleteProfile(profileID)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                    }
                    AdultCardsSeen.forget(profileID, app.preferences)
                    SpeakingMemory.forget(profileID, app.preferences)
                    // And every row of the pilot log about this profile.
                    try {
                        app.pilot?.deleteProfile(profileID)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                    }
                    dismiss()
                }
            },
            DialogAction("Anulează", ActionRole.Cancel),
        ),
    )
}

/**
 * `ProfileExport` with `ShareLink`: the JSON is built when it's shared, so a note written a moment ago is in it.
 * Android shares a file through a `FileProvider`, so the file is written to the cache's `export` folder first; the
 * folder is emptied before the next export, which is as close as Android gets to "the file doesn't stay behind",
 * since the app can't know when the receiving app has finished reading it.
 */
internal object ProfileExport {
    /** The file names no one (iOS 2b32a40): the name stays on the phone, and the file says "Profil". */
    const val FILE_NAME = "fonia-profil.json"

    /**
     * With [pilot] and [profile], the pilot log's summary for the logoped (`fonia-exersare-<pseudonym>.json`, 03 §4.3)
     * goes along as a second file; the first file stays FoniaStore's export, byte for byte the iOS format. A profile
     * that was in the adaptive pilot ([adaptive]) has its modes, steps and estimates at the end of that summary.
     */
    suspend fun share(
        context: Context,
        repository: FoniaRepository,
        profileID: UUID,
        pilot: PilotLog? = null,
        profile: Profile? = null,
        adaptive: AdaptiveStore? = null,
    ) {
        val bytes = try {
            repository.exportJSON(profileID)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return
        }
        val adaptiveSummary = try {
            adaptive?.summary(profileID)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        val summary = if (pilot != null && profile != null) {
            try {
                pilot.export(profile, dspThresholdsVersion = DSPThresholds.v1.version, adaptive = adaptiveSummary)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        val files = withContext(Dispatchers.IO) {
            val folder = File(context.cacheDir, "export")
            folder.deleteRecursively()
            folder.mkdirs()
            val main = File(folder, FILE_NAME).also { it.writeBytes(bytes) }
            val extra = summary?.let { (summaryName, content) -> File(folder, summaryName).also { it.writeBytes(content) } }
            listOfNotNull(main, extra)
        }
        val uris = files.map { FileProvider.getUriForFile(context, "${context.packageName}.export", it) }
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        send.setType("application/json").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(send, "Fonia")
        if (context !is android.app.Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(chooser)
            // The access log of the pilot (03 §2.3): that a summary was handed to the share sheet, and when.
            if (summary != null) pilot?.exported(profileID)
        } catch (_: ActivityNotFoundException) {
        }
    }

    /** Old exports go when the app starts. */
    fun clean(context: Context) {
        File(context.cacheDir, "export").deleteRecursively()
    }
}

/** "Ce păstrăm pe telefon": [PrivacyText], the policy on the web, and the credits. */
@Composable
internal fun PrivacyNote(onDone: () -> Unit, withReading: Boolean, onOpenLink: (String) -> Unit) {
    Column(Modifier.fillMaxSize().background(Palette.paper)) {
        SystemNavigationBar("Ce păstrăm pe telefon", onDone = onDone)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            for (section in PrivacyText.sections(withReading)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FoniaText(section.title, Modifier.semantics { heading() }, style = fHeadline, color = Palette.ink)
                    FoniaText(section.body, style = fBody, color = Palette.inkSoft)
                }
            }
            StyledButton(
                onClick = { onOpenLink(LegalInfo.PRIVACY_URL) },
                style = QuietButtonStyle(Palette.accentText),
                modifier = Modifier.heightIn(min = 44.dp),
            ) { FoniaText("Politica de confidențialitate", style = fBodyStrong) }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FoniaText("Mulțumiri", Modifier.semantics { heading() }, style = fHeadline, color = Palette.ink)
                for (line in Credits.lines) {
                    FoniaText(line, style = fCaption, color = Palette.inkSoft)
                }
            }
        }
    }
}
