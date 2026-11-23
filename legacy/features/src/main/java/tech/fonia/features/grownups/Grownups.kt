package tech.fonia.features.grownups

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
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
import tech.fonia.design.Palette
import tech.fonia.design.fBody
import tech.fonia.design.fCaption
import tech.fonia.design.fHeadline
import tech.fonia.dsp.DSPThresholds
import tech.fonia.features.adaptive.AdaptiveStore
import tech.fonia.features.app.ActionRole
import tech.fonia.features.app.AppModel
import tech.fonia.features.app.ButtonRow
import tech.fonia.features.app.Destination
import tech.fonia.features.app.DialogAction
import tech.fonia.features.app.FoniaConfirmationDialog
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
import tech.fonia.features.app.rememberStoredBoolean
import tech.fonia.features.home.DestinationScreen
import tech.fonia.features.home.displayName
import tech.fonia.features.home.rememberPushDestination
import tech.fonia.features.pilot.PilotLog
import tech.fonia.features.session.Settings
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
    var keepRecordings by rememberStoredBoolean(app.preferences, Settings.keepRecordings)
    var effectsOff by rememberStoredBoolean(app.preferences, Settings.effectsOff)
    var addingChild by remember { mutableStateOf(false) }
    var confirmWipe by remember { mutableStateOf(false) }
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
            ModelSections(app)
            ListSection {
                row { ButtonRow("Ce păstrăm pe telefon", onClick = { showLegal = true }) }
                row { ButtonRow("Șterge tot de pe telefon", onClick = { confirmWipe = true }, destructive = true) }
            }
        }
    }
    AddChildSheet(app, isPresented = addingChild, onDone = { addingChild = false })
    FoniaSheet(isPresented = showLegal, onDismiss = { showLegal = false }) {
        PrivacyNote(onDone = { showLegal = false })
    }
    FoniaConfirmationDialog(
        isPresented = confirmWipe,
        onDismiss = { confirmWipe = false },
        title = "Ștergi tot?",
        message = "Profilurile, încercările și înregistrările dispar de pe telefon. Nu se pot recupera.",
        actions = listOf(
            DialogAction("Șterge tot", ActionRole.Destructive) {
                app.scope.launch {
                    try {
                        app.repository.wipeAll()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                    }
                    // The pilot log goes with everything else.
                    try {
                        app.pilot?.wipe()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                    }
                    onDone()
                }
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
    /** `fonia-<the name's letters and digits>.json`, or `fonia-profil.json`. */
    fun fileName(name: String?): String {
        val source = name ?: "profil"
        val kept = StringBuilder()
        var index = 0
        while (index < source.length) {
            val scalar = source.codePointAt(index)
            // CharacterSet.alphanumerics: letters, marks and numbers.
            val type = Character.getType(scalar)
            val keep = Character.isLetterOrDigit(scalar) ||
                type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt() ||
                type == Character.ENCLOSING_MARK.toInt() || type == Character.LETTER_NUMBER.toInt() || type == Character.OTHER_NUMBER.toInt()
            if (keep) kept.appendCodePoint(scalar)
            index += Character.charCount(scalar)
        }
        return "fonia-${kept.ifEmpty { "profil" }}.json"
    }

    /**
     * With [pilot] and [profile], the pilot log's summary for the logoped (`fonia-exersare-<pseudonym>.json`, 03 §4.3)
     * goes along as a second file; the first file stays FoniaStore's export, byte for byte the iOS format. A profile
     * that was in the adaptive pilot ([adaptive]) has its modes, steps and estimates at the end of that summary.
     */
    suspend fun share(
        context: Context,
        repository: FoniaRepository,
        profileID: UUID,
        name: String?,
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
            val main = File(folder, fileName(name)).also { it.writeBytes(bytes) }
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

private val paragraphs = listOf(
    "Vocea" to "O ascultăm aici, pe telefon. Nu pleacă nicăieri. Înregistrările copiilor se păstrează doar dacă pornești tu opțiunea. Ale tale rămân aici, pe telefon.",
    "Camera" to "Se uită la gură doar cât ești la oglindă. Nu păstrăm nicio imagine.",
    "Internetul" to "Îl folosim doar ca să descărcăm pachetele de ascultare (vorbirea și sunetele), dacă vrei tu. Nu trimitem nimic despre tine.",
    "Fișierul pentru logoped" to "Îl trimiți doar tu, dacă vrei. Are încercările și notițele, fără voce.",
    "Jurnalul de exersare" to "Telefonul notează câte încercări faceți, la ce sunet, cât durează o lecție și ce a răspuns telefonul, fără voce și fără cuvinte scrise. Detaliile stau cel mult 90 de zile, apoi rămân doar totalurile pe zi. Pleacă de pe telefon doar ca rezumat, lângă fișierul pentru logoped, sub un nume inventat.",
    "Probele de citire" to "Păstrăm data, lista citită, timpul și cuvintele marcate de tine. Raportul îl faci și îl trimiți doar tu. Se șterg odată cu profilul.",
    "Modul adaptiv" to "Dacă îl pornești, telefonul ține minte, pe fiecare sunet, cât de sigur iese, ca să aleagă exercițiile următoare. Sunt doar cifre, fără voce. Se șterg odată cu profilul.",
    "Ce nu face Fonia" to "Nu pune diagnostice și nu ține locul logopedului. Ce spune telefonul e doar o părere, iar tu o poți schimba. Vârstele din ghiduri vin din studii pe alte limbi, pentru română nu avem încă date.",
)

/** "Ce păstrăm pe telefon". */
@Composable
private fun PrivacyNote(onDone: () -> Unit) {
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
            for ((title, text) in paragraphs) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FoniaText(title, Modifier.semantics { heading() }, style = fHeadline, color = Palette.ink)
                    FoniaText(text, style = fBody, color = Palette.inkSoft)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FoniaText("Mulțumiri", Modifier.semantics { heading() }, style = fHeadline, color = Palette.ink)
                // The libraries named at the end are the Android ones: WhisperKit and GRDB are not in this app.
                FoniaText(
                    "Fonturi: Baloo 2 (Ek Type, OFL) și Allo (OFL). Imagini: Fluent Emoji (Microsoft, MIT) și Noto Emoji (Google, OFL și Apache 2.0). Iconițe: Phosphor (MIT). Sunete: UI SFX (CC0). Recunoaștere vocală: Whisper și whisper.cpp (MIT). Date: SQLite (domeniu public) și AndroidX SQLite (Apache 2.0).",
                    style = fCaption,
                    color = Palette.inkSoft,
                )
                // CC BY 4.0 asks for the authors, the licence, the source and whether it was changed (phones/MODELS.md).
                FoniaText(
                    "Ascultare pe sunete: ZIPA-small CR-CTC (© autorii ZIPA, Jian Zhu și alții, CC BY 4.0, huggingface.co/anyspeech/zipa-small-crctc-500k, folosit nemodificat) și ONNX Runtime (Microsoft, MIT).",
                    style = fCaption,
                    color = Palette.inkSoft,
                )
            }
        }
    }
}
