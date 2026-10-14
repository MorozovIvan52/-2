package ru.ivan.jarvis.focus

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import ru.ivan.jarvis.JarvisApp
import ru.ivan.jarvis.R
import ru.ivan.jarvis.settings.Prefs
import ru.ivan.jarvis.ui.MainActivity

/** Фокус-сессия в стиле «помодоро»: работа → перерыв → сигнал вернуться. */
object Focus {
    const val WORK = "work"
    const val BREAK = "break"
    private const val NOTIFICATION_ID = 777_001

    private fun pending(context: Context, flags: Int = PendingIntent.FLAG_UPDATE_CURRENT): PendingIntent? =
        PendingIntent.getBroadcast(
            context, 1, Intent(context, FocusReceiver::class.java),
            flags or PendingIntent.FLAG_IMMUTABLE,
        )

    fun start(context: Context, minutes: Int) {
        val prefs = Prefs(context)
        prefs.focusPhase = WORK
        prefs.focusEndAt = System.currentTimeMillis() + minutes * 60_000L
        schedule(context, prefs.focusEndAt)
    }

    fun stop(context: Context) {
        val prefs = Prefs(context)
        prefs.focusPhase = ""
        prefs.focusEndAt = 0
        pending(context, PendingIntent.FLAG_NO_CREATE)?.let {
            context.getSystemService(AlarmManager::class.java).cancel(it)
            it.cancel()
        }
    }

    private fun schedule(context: Context, at: Long) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = pending(context)!!
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun onAlarm(context: Context) {
        val prefs = Prefs(context)
        when (prefs.focusPhase) {
            WORK -> {
                prefs.focusPhase = BREAK
                prefs.focusEndAt = System.currentTimeMillis() + prefs.breakMinutes * 60_000L
                schedule(context, prefs.focusEndAt)
                notify(context, "Время перерыва", "Отлично поработали! Отдохните ${prefs.breakMinutes} минут: встаньте, попейте воды.")
            }
            BREAK -> {
                prefs.focusPhase = ""
                prefs.focusEndAt = 0
                notify(context, "Перерыв окончен", "Возвращайтесь к делу. Скажите «фокус», чтобы начать ещё один заход.")
            }
        }
    }

    private fun notify(context: Context, title: String, text: String) {
        val open = PendingIntent.getActivity(
            context, 2, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, JarvisApp.CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, n) }
    }
}

class FocusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Focus.onAlarm(context)
}
