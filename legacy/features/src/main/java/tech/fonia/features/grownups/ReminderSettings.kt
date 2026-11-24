package tech.fonia.features.grownups

import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import tech.fonia.features.app.AppModel
import tech.fonia.features.app.ButtonRow
import tech.fonia.features.app.ListSection
import tech.fonia.features.app.PracticeReminder
import tech.fonia.features.app.ToggleRow
import tech.fonia.features.app.ValueRow

/**
 * The daily reminder in the settings (iOS `SettingsPage`): off until the adult turns it on, then the hour. Turning it on
 * is when the phone asks about notifications; when they are off for Fonia, the switch stays off and the row says where
 * to turn them back on.
 */
@Composable
internal fun ReminderSection(app: AppModel) {
    val preferences = app.preferences ?: return
    val context = LocalContext.current
    var isOn by remember { mutableStateOf(PracticeReminder.isOn(preferences)) }
    var minutes by remember { mutableIntStateOf(PracticeReminder.minutes(preferences)) }
    var canNotify by remember { mutableStateOf(PracticeReminder.canNotify(context)) }
    var wasRefused by remember { mutableStateOf(false) }
    // Back from the phone's settings: what was changed there shows here.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        canNotify = PracticeReminder.canNotify(context)
        if (canNotify) wasRefused = false
    }
    fun turnOn() {
        PracticeReminder.turnOn(context, preferences, minutes)
        isOn = true
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        canNotify = PracticeReminder.canNotify(context)
        if (granted && canNotify) turnOn() else wasRefused = true
    }
    val showsDenied = wasRefused || (isOn && !canNotify)
    ListSection(footer = if (showsDenied) PracticeReminder.DENIED else PracticeReminder.ROW_DETAIL) {
        row {
            ToggleRow(PracticeReminder.ROW_TITLE, isOn && canNotify) { on ->
                when {
                    !on -> {
                        PracticeReminder.turnOff(context, preferences)
                        isOn = false
                    }
                    canNotify -> turnOn()
                    Build.VERSION.SDK_INT >= 33 && !wasRefused -> ask.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    else -> wasRefused = true
                }
            }
        }
        if (isOn && canNotify) {
            row {
                ValueRow(PracticeReminder.TIME_TITLE, PracticeReminder.clock(minutes), onClick = {
                    TimePickerDialog(context, { _, hour, minute ->
                        minutes = hour * 60 + minute
                        PracticeReminder.move(context, preferences, minutes)
                    }, minutes / 60, minutes % 60, true).show()
                })
            }
        }
        if (showsDenied) {
            row {
                ButtonRow(PracticeReminder.OPEN_SETTINGS, onClick = {
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    try {
                        context.startActivity(intent)
                    } catch (_: ActivityNotFoundException) {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null)))
                    }
                })
            }
        }
    }
}
