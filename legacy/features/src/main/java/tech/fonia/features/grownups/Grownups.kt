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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.fonia.design.FoniaText
import tech.fonia.design.QuietButtonStyle
import tech.fonia.design.StyledButton
import tech.fonia.design.fBodyStrong
import tech.fonia.design.Palette
import tech.fonia.design.fBody
import tech.fonia.design.fCaption
import tech.fonia.design.fHeadline
import tech.fonia.domain.ProbeSummary
import tech.fonia.dsp.DSPThresholds
import tech.fonia.features.adaptive.AdaptiveStore
import tech.fonia.features.app.AdultGateBody
import tech.fonia.features.app.needsAdultGate
import tech.fonia.features.app.AppModel
import tech.fonia.features.app.Destination
import tech.fonia.features.app.LocalDismiss
import tech.fonia.features.app.NavStack
import tech.fonia.features.app.NavigationHost
import tech.fonia.features.app.SystemNavigationBar
import tech.fonia.features.home.DestinationScreen
import tech.fonia.features.pilot.PilotLog
import tech.fonia.features.probe.ProbeStore
import tech.fonia.store.FoniaRepository
import tech.fonia.store.Profile
import java.io.File
import java.util.UUID

/**
 * The "Setări" sheet when nobody is chosen yet (iOS `GrownupsHome`): the profiles, the app, what stays on the phone,
 * and wiping everything. It has its own navigation stack, as on iOS, so a profile opens inside the sheet.
 */
@Composable
fun GrownupsHome(app: AppModel, onDone: () -> Unit) {
    // With a child on the phone, the settings are the adult's (iOS AdultGate): the check comes first.
    if (app.needsAdultGate) {
        Column(Modifier.fillMaxSize().background(Palette.paper)) {
            SystemNavigationBar("Setări", onDone = onDone)
            AdultGateBody(app.gate, onPass = {}, Modifier.weight(1f), lead = "Aici intră doar adulții.")
        }
        return
    }
    val stack = remember { NavStack() }
    NavigationHost(stack) { destination ->
        if (destination == Destination.Root) {
            Column(Modifier.fillMaxSize().background(Palette.paper)) {
                SystemNavigationBar("Setări", onDone = onDone)
                SettingsPage(null, app, onClose = onDone, modifier = Modifier.weight(1f))
            }
        } else {
            DestinationScreen(destination, app, path = null, onShowGrownups = {})
        }
    }
}

/** One person, as the settings list them: the same page, for them. */
@Composable
fun ProfileDetail(profileID: UUID, app: AppModel) {
    val profile = app.profiles.firstOrNull { it.id == profileID } ?: return
    val dismiss = LocalDismiss.current
    Column(Modifier.fillMaxSize().background(Palette.paper)) {
        SystemNavigationBar(settingsName(profile), onBack = dismiss)
        SettingsPage(profile, app, onClose = dismiss, modifier = Modifier.weight(1f))
    }
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
     * The „Cuvinte noi” [probes], kept beside the database, go in as counts (`ProbeSummary`), never their words.
     */
    suspend fun share(
        context: Context,
        repository: FoniaRepository,
        profileID: UUID,
        pilot: PilotLog? = null,
        profile: Profile? = null,
        adaptive: AdaptiveStore? = null,
        probes: ProbeStore? = null,
    ) {
        val bytes = try {
            val summaries = probes?.records(profileID)?.map { ProbeSummary(it) } ?: emptyList()
            repository.exportJSON(profileID, probes = summaries)
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
