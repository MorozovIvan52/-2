package ru.ivan.jarvis.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ru.ivan.jarvis.JarvisApp
import ru.ivan.jarvis.data.Repeat
import ru.ivan.jarvis.data.nextOccurrence
import ru.ivan.jarvis.data.toLocalDateTime
import ru.ivan.jarvis.data.toMillis
import java.time.LocalDateTime

/** После перезагрузки телефона (или смены времени) заново ставит все будильники. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as JarvisApp
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = app.db.items()
                val now = LocalDateTime.now()
                dao.activeReminders().forEach { item ->
                    var current = item
                    val due = item.dueAt!!.toLocalDateTime()
                    if (item.repeat != Repeat.NONE && !due.isAfter(now)) {
                        nextOccurrence(due, item.repeat, now)?.let {
                            current = item.copy(dueAt = it.toMillis())
                            dao.update(current)
                        }
                    }
                    ReminderScheduler.schedule(context, current)
                }
                ru.ivan.jarvis.briefing.Briefing.scheduleAll(context)
                ru.ivan.jarvis.widget.JarvisWidget.update(context)
            } finally {
                pending.finish()
            }
        }
    }
}
