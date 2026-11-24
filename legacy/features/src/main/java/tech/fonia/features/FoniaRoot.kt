package tech.fonia.features

import android.Manifest
import android.app.Application
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.fonia.design.FoniaText
import tech.fonia.design.FoniaTheme
import tech.fonia.design.LocalReduceMotion
import tech.fonia.design.MascotPose
import tech.fonia.design.MascotView
import tech.fonia.design.SceneCache
import tech.fonia.design.fBody
import tech.fonia.design.fTitle
import tech.fonia.features.app.AppModel
import tech.fonia.features.app.FoniaPlatform
import tech.fonia.features.app.FoniaSheet
import tech.fonia.features.app.FullScreenCover
import tech.fonia.features.app.LaunchArguments
import tech.fonia.features.app.LocalStillMeters
import tech.fonia.features.app.MicrophonePermission
import tech.fonia.features.app.SessionTicket
import tech.fonia.features.grownups.GrownupsHome
import tech.fonia.features.grownups.ProfileExport
import tech.fonia.features.home.MainTabs
import tech.fonia.features.home.WhoView
import tech.fonia.features.session.SessionScreen
import tech.fonia.features.session.Settings
import tech.fonia.features.setup.FirstRunFlow

/**
 * `RootView`: the whole app. [launchArguments] are the activity's intent extras, the launch arguments of the iOS
 * app (see [LaunchArguments]). [platform] brings the audio, speech and face engines; the defaults are the real ones.
 */
@Composable
fun FoniaRoot(launchArguments: Bundle?, platform: FoniaPlatform = FoniaPlatform()) {
    val arguments = remember(launchArguments) { LaunchArguments.from(launchArguments) }
    FoniaTheme {
        if (arguments.mouths || arguments.gallery != null) {
            // `-foniaMouths`: the gallery instead of the app.
            DebugGallery(arguments.gallery ?: "guri")
            return@FoniaTheme
        }
        CompositionLocalProvider(
            LocalReduceMotion provides (LocalReduceMotion.current || arguments.reduceMotion),
            LocalStillMeters provides arguments.reduceMotion,
        ) {
            AppRoot(arguments, platform)
        }
    }
}

@Composable
private fun AppRoot(arguments: LaunchArguments, platform: FoniaPlatform) {
    run {
        val context = LocalContext.current
        val model = viewModel { FoniaViewModel(context.applicationContext as Application, arguments, platform) }
        val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> model.microphone.deliver(granted) }
        DisposableEffect(ask) {
            model.microphone.attach { ask.launch(Manifest.permission.RECORD_AUDIO) }
            onDispose { model.microphone.attach(null) }
        }
        val app = model.app
        when {
            app != null -> AppContent(app)
            model.failed -> CannotStart()
            else -> Launching()
        }
    }
}

/** Holds the app for as long as the activity's screen lives, rotation and theme changes included. */
internal class FoniaViewModel(application: Application, arguments: LaunchArguments, platform: FoniaPlatform) : AndroidViewModel(application) {
    val microphone = MicrophonePermission(application)
    var app: AppModel? by mutableStateOf(null)
        private set
    var failed: Boolean by mutableStateOf(false)
        private set

    init {
        SceneCache.preloadHome(application)
        ProfileExport.clean(application)
        viewModelScope.launch {
            val made = withContext(Dispatchers.IO) { AppModel.live(application, arguments, platform, microphone, viewModelScope) }
            if (made == null) {
                failed = true
                return@launch
            }
            app = made
            made.audio.effectsMuted = made.preferences?.bool(Settings.effectsOff) ?: false
            made.start()
        }
    }

    override fun onCleared() {
        app?.close()
    }
}

/** Until the profiles are read: Foni on a cloud, on white. */
@Composable
private fun Launching() {
    Box(Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.Center) {
        MascotView(MascotPose.cloud, Modifier.width(200.dp))
    }
}

/** When the content or the database can't be opened. */
@Composable
private fun CannotStart() {
    Box(Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            FoniaText("Fonia nu poate porni", style = fTitle)
            FoniaText(
                "Închide Fonia și deschide-o din nou. Dacă tot nu merge, reinstaleaz-o. Se șterg profilurile.",
                style = fBody,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun AppContent(app: AppModel) {
    var showGrownups by remember { mutableStateOf(false) }
    // Three minutes away from the app close the adult check again.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { app.gate.onBackground() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { app.gate.onForeground() }
    val ticket = app.ticket
    val profile = app.activeProfile
    // The cover keeps showing the session it had while it slides away.
    var lastTicket by remember { mutableStateOf<SessionTicket?>(null) }
    SideEffect { if (ticket != null) lastTicket = ticket }
    val covering = ticket != null && profile != null
    tech.fonia.design.SfxHost()
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().then(if (covering) Modifier.clearAndSetSemantics {} else Modifier)) {
            when {
                !app.hasLoadedProfiles -> Launching()
                app.profiles.isEmpty() -> FirstRunFlow(app)
                profile != null -> key(profile.id) {
                    // `.id(profile.id)`: another profile gets new tabs, a new stack and a new PathModel.
                    MainTabs(profile, app, showGrownups = showGrownups, onShowGrownups = { showGrownups = it })
                }
                else -> WhoView(app, onShowGrownups = { showGrownups = true })
            }
        }
        FullScreenCover(isPresented = covering) {
            val shown = ticket ?: lastTicket
            if (shown != null && profile != null) {
                key(shown.id) {
                    SessionScreen(ticket = shown, profile = profile, app = app, onDone = { app.ticket = null })
                }
            }
        }
    }
    FoniaSheet(isPresented = showGrownups, onDismiss = { showGrownups = false }) {
        GrownupsHome(app, onDone = { showGrownups = false })
    }
}
