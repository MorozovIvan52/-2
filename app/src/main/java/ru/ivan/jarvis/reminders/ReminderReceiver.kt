package ru.ivan.jarvis.reminders

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ru.ivan.jarvis.JarvisApp
import ru.ivan.jarvis.R
import ru.ivan.jarvis.data.Item
import ru.ivan.jarvis.data.Repeat
import ru.ivan.jarvis.data.nextOccurrence
import ru.ivan.jarvis.data.toLocalDateTime
import ru.ivan.jarvis.data.toMillis
import ru.ivan.jarvis.settings.Prefs
import ru.ivan.jarvis.ui.MainActivity
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(ReminderScheduler.EXTRA_ID, -1)
        if (id < 0) return
        val app = context.applicationContext as JarvisApp
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = app.db.items()
                val item = dao.get(id) ?: return@launch
                when (intent.action) {
                    ReminderScheduler.ACTION_FIRE -> {
                        show(context, item)
                        if (item.repeat != Repeat.NONE && item.dueAt != null) {
                            val next = nextOccurrence(item.dueAt.toLocalDateTime(), item.repeat, LocalDateTime.now())
                            if (next != null) {
                                val updated = item.copy(dueAt = next.toMillis())
                                dao.update(updated)
                                ReminderScheduler.schedule(context, updated)
                            }
                        }
                        nagAgain(context, item, 1)
                        if (Prefs(context).speakReminders) ru.ivan.jarvis.voice.ReminderVoice.say(context, "Напоминаю: ${item.title}")
                    }
                    ReminderScheduler.ACTION_SNOOZE_FIRE -> {
                        if (item.done) return@launch
                        show(context, item)
                        nagAgain(context, item, intent.getIntExtra(ReminderScheduler.EXTRA_NAG, 0) + 1)
                        if (Prefs(context).speakReminders) ru.ivan.jarvis.voice.ReminderVoice.say(context, "Всё ещё жду: ${item.title}")
                    }
                    ReminderScheduler.ACTION_DONE -> {
                        NotificationManagerCompat.from(context).cancel(id.toInt())
                        ReminderScheduler.cancelSnooze(context, id)
                        if (item.repeat == Repeat.NONE) dao.update(item.copy(done = true))
                        Prefs(context).markDone()
                    }
                    ReminderScheduler.ACTION_SNOOZE -> {
                        NotificationManagerCompat.from(context).cancel(id.toInt())
                        ReminderScheduler.snooze(context, id)
                    }
                }
                ru.ivan.jarvis.widget.JarvisWidget.update(context)
            } finally {
                pending.finish()
            }
        }
    }

    /** Настойчивый режим: повторять каждые 10 минут, пока не нажали «Готово». */
    private fun nagAgain(context: Context, item: Item, nag: Int) {
        if (Prefs(context).nag && nag <= ReminderScheduler.MAX_NAGS) ReminderScheduler.snooze(context, item.id, 10, nag)
    }

    private fun show(context: Context, item: Item) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= 33
        ) return

        val open = PendingIntent.getActivity(
            context, item.id.toInt(),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        fun action(action: String) = PendingIntent.getBroadcast(
            context, 0,
            Intent(context, ReminderReceiver::class.java).setAction(action)
                .setData(ReminderScheduler.itemUri(item.id)).putExtra(ReminderScheduler.EXTRA_ID, item.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val time = item.dueAt?.toLocalDateTime()?.let {
            if (item.allDay) "Сегодня" else it.format(DateTimeFormatter.ofPattern("HH:mm"))
        } ?: ""

        val n = NotificationCompat.Builder(context, JarvisApp.CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(item.title)
            .setContentText("Напоминание · $time")
            .setStyle(NotificationCompat.BigTextStyle().bigText(item.title))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(0, context.getString(R.string.action_done), action(ReminderScheduler.ACTION_DONE))
            .addAction(0, context.getString(R.string.action_snooze), action(ReminderScheduler.ACTION_SNOOZE))
            .build()
        NotificationManagerCompat.from(context).notify(item.id.toInt(), n)
    }
}
