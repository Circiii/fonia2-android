package tech.fonia.features.app

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import tech.fonia.features.R
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * The moment of the day the practice hangs on (iOS `PracticeMoment`). Tying it to something the family already does
 * each day works better than a bare hour.
 */
enum class PracticeMoment(val rawValue: String, val title: String, val minutes: Int) {
    Morning("morning", "După micul dejun", 8 * 60),
    Afternoon("afternoon", "După-amiaza, acasă", 17 * 60),
    Evening("evening", "După cină", 19 * 60 + 30),
    Bedtime("bedtime", "Înainte de culcare", 20 * 60 + 30),
    ;

    companion object {
        fun of(rawValue: String): PracticeMoment? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

/**
 * One local notification a day, at the hour an adult chose in the settings (iOS `PracticeReminder`). Off until it is
 * turned on; nothing leaves the phone. The wording only invites: no streak, nothing missed, nothing owed.
 *
 * The alarm is inexact ([AlarmManager.setWindow]), so no exact-alarm permission is needed, and it is set again for the
 * next day each time it fires, after a restart and when the clock or the time zone changes.
 */
object PracticeReminder {
    const val ON_KEY = "fonia.reminder.on"
    const val MINUTES_KEY = "fonia.reminder.minutes"
    const val MOMENT_KEY = "fonia.reminder.moment"
    val DEFAULT_MINUTES = PracticeMoment.Evening.minutes

    const val TITLE = "Fonia"
    const val BODY = "E momentul pentru câteva minute cu Foni."

    /** The settings row and its footer, and what to say when the phone doesn't let the app notify. */
    const val ROW_TITLE = "Amintire zilnică"
    const val ROW_DETAIL = "O notificare pe zi, la ora aleasă. Rămâne pe telefon."
    const val TIME_TITLE = "Ora"
    const val DENIED = "Notificările pentru Fonia sunt oprite din Setările telefonului."
    const val OPEN_SETTINGS = "Deschide Setările"

    const val CHANNEL_ID = "fonia.reminder"
    const val ACTION = "tech.fonia.reminder.DAILY"
    private const val NOTIFICATION_ID = 1730
    private const val WINDOW_MS = 10 * 60 * 1000L

    fun isOn(preferences: FoniaPreferences): Boolean = preferences.bool(ON_KEY)

    fun minutes(preferences: FoniaPreferences): Int = preferences.int(MINUTES_KEY, DEFAULT_MINUTES)

    fun moment(preferences: FoniaPreferences): PracticeMoment? = PracticeMoment.of(preferences.string(MOMENT_KEY))

    /** The moment and its hour are kept; the reminder itself stays as it was. */
    fun remember(moment: PracticeMoment, preferences: FoniaPreferences) {
        preferences.setString(MOMENT_KEY, moment.rawValue)
        preferences.setInt(MINUTES_KEY, moment.minutes)
    }

    fun clock(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

    /** The next time [minutes] after midnight comes round after [now]: today if it is still ahead, else tomorrow. */
    fun nextTrigger(now: ZonedDateTime, minutes: Int): ZonedDateTime {
        val today = now.with(LocalTime.of(minutes / 60 % 24, minutes % 60))
        return if (today.isAfter(now)) today else today.plusDays(1)
    }

    /** Whether the phone lets the app show notifications at all. */
    fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() ?: false
    }

    fun turnOn(context: Context, preferences: FoniaPreferences, minutes: Int = minutes(preferences)) {
        preferences.setInt(MINUTES_KEY, minutes)
        preferences.setBool(ON_KEY, true)
        schedule(context, minutes)
    }

    fun move(context: Context, preferences: FoniaPreferences, minutes: Int) {
        preferences.setInt(MINUTES_KEY, minutes)
        if (isOn(preferences)) schedule(context, minutes)
    }

    fun turnOff(context: Context, preferences: FoniaPreferences) {
        preferences.setBool(ON_KEY, false)
        cancel(context)
    }

    /** The alarm and a notification still showing go; the settings stay. Also "Șterge tot", before they are cleared. */
    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(pending(context))
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    /** After a restart or a clock change: the alarm again, if the reminder is on. */
    fun restore(context: Context) {
        val preferences = FoniaPreferences(context)
        if (isOn(preferences)) schedule(context, minutes(preferences))
    }

    internal fun schedule(context: Context, minutes: Int) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val at = nextTrigger(ZonedDateTime.now(), minutes).toInstant().toEpochMilli()
        alarms.setWindow(AlarmManager.RTC_WAKEUP, at, WINDOW_MS, pending(context))
    }

    internal fun show(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!canNotify(context)) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, ROW_TITLE, NotificationManager.IMPORTANCE_DEFAULT))
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val notification = android.app.Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.fonia_reminder)
            .setContentTitle(TITLE)
            .setContentText(BODY)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, PracticeReminderReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** The daily alarm, a restart and a clock change: each leaves tomorrow's reminder set, if it is on. */
class PracticeReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val preferences = FoniaPreferences(context)
        if (!PracticeReminder.isOn(preferences)) return
        if (intent.action == PracticeReminder.ACTION) PracticeReminder.show(context)
        PracticeReminder.restore(context)
    }
}
