package tech.fonia.features.grownups

import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import tech.fonia.design.FoniaIcon
import tech.fonia.design.FoniaText
import tech.fonia.design.Palette
import tech.fonia.design.fBody
import tech.fonia.design.fCaption
import tech.fonia.design.monospacedDigit
import tech.fonia.features.app.AppModel
import tech.fonia.features.app.PracticeReminder

/**
 * The daily reminder, as one row of "Aplicația" (iOS `reminderRow`): off until the adult turns it on, then the hour.
 * Turning it on is when the phone asks about notifications; when they are off for Fonia, the switch stays off and the
 * row says where to turn them back on.
 */
@Composable
internal fun ReminderRow(app: AppModel) {
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
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SwitchRow(FoniaIcon.hourglass.rawValue, PracticeReminder.ROW_TITLE, isOn && canNotify, PracticeReminder.ROW_DETAIL, inset = false) { on ->
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
        if (isOn && canNotify) {
            // iOS's DatePicker row: the name, and the hour that opens the clock.
            Row(
                Modifier
                    .padding(start = 50.dp)
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .clickable(role = Role.Button) {
                        TimePickerDialog(context, { _, hour, minute ->
                            minutes = hour * 60 + minute
                            PracticeReminder.move(context, preferences, minutes)
                        }, minutes / 60, minutes % 60, true).show()
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FoniaText(PracticeReminder.TIME_TITLE, Modifier.weight(1f), style = fBody, color = Palette.ink)
                FoniaText(PracticeReminder.clock(minutes), style = fBody.monospacedDigit(), color = Palette.accentText)
            }
        }
        if (showsDenied) {
            Column(Modifier.padding(start = 50.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FoniaText(PracticeReminder.DENIED, style = fCaption, color = Palette.inkSoft)
                Pill(PracticeReminder.OPEN_SETTINGS) {
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    try {
                        context.startActivity(intent)
                    } catch (_: ActivityNotFoundException) {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null)))
                    }
                }
            }
        }
    }
}
