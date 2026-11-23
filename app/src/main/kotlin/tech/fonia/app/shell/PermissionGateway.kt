package tech.fonia.app.shell

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The microphone permission as the lesson sees it (docs/v2/02 §8). */
enum class MicPermission { NotAsked, Granted, Denied, DeniedForever }

/**
 * Owns the microphone permission: asks only when a lesson needs it, after a friendly pre-prompt for the adult. A refusal
 * never blocks the path: the lesson falls back to the adult's thumb ([declinedForNow] or [MicPermission.Denied]).
 * The system dialog itself is launched by the screen (`rememberLauncherForActivityResult`), which reports back here.
 */
class PermissionGateway(private val context: Context) {
    private val state = MutableStateFlow(read(asked = false))
    val mic: StateFlow<MicPermission> = state.asStateFlow()

    /** The adult chose "Fără microfon" in this run: do not ask again until the app restarts. */
    @Volatile var declinedForNow = false

    private var asked = false

    val granted: Boolean get() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun read(asked: Boolean): MicPermission = when {
        granted -> MicPermission.Granted
        asked -> MicPermission.Denied
        else -> MicPermission.NotAsked
    }

    /** Back from the system dialog. */
    fun onResult(granted: Boolean, activity: Activity?) {
        asked = true
        state.value = when {
            granted -> MicPermission.Granted
            activity != null && !activity.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> MicPermission.DeniedForever
            else -> MicPermission.Denied
        }
    }

    /** Re-reads the system state (the adult may have changed it in Settings). */
    fun refresh() {
        val now = read(asked)
        if (now == MicPermission.Granted || state.value == MicPermission.Granted) state.value = now
    }

    /** True when a lesson should show the pre-prompt before opening the mic. */
    val shouldAsk: Boolean get() = !granted && !declinedForNow && state.value != MicPermission.DeniedForever

    // ---- the camera (MIRROR only; docs/v2/04 §11: never asked a second time without the adult's gesture)

    val cameraGranted: Boolean get() = context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /** The adult chose „Fără cameră” (or refused the dialog) in this run: MIRROR opens without the camera. */
    @Volatile var cameraDeclinedForNow = false

    /** The system will not show the camera dialog again; only the phone's settings can turn it on. */
    @Volatile var cameraDeniedForever = false

    /** Back from the camera dialog (the mirror's [tech.fonia.facecamera.FaceCameraView] asked). */
    fun onCameraResult(granted: Boolean, activity: Activity?) {
        if (granted) {
            cameraDeclinedForNow = false
            cameraDeniedForever = false
        } else {
            cameraDeclinedForNow = true
            cameraDeniedForever = activity != null && !activity.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        }
    }

    fun openSettings(activity: Activity) {
        activity.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
