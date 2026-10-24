package ru.ivan.jarvis.briefing

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ru.ivan.jarvis.JarvisApp
import ru.ivan.jarvis.R
import ru.ivan.jarvis.data.Item
import ru.ivan.jarvis.data.ItemType
import ru.ivan.jarvis.data.occursOn
import ru.ivan.jarvis.data.toLocalDateTime
import ru.ivan.jarvis.settings.Prefs
import ru.ivan.jarvis.ui.MainActivity
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Утренний план и вечерний итог — уведомления раз в день. */
object Briefing {
    const val ACTION_MORNING = "ru.ivan.jarvis.MORNING"
    const val ACTION_EVENING = "ru.ivan.jarvis.EVENING"
    private const val NOTIF_MORNING = -100
    private const val NOTIF_EVENING = -101
    private val HM = DateTimeFormatter.ofPattern("HH:mm")

    /** Ставит оба будильника на ближайшее время (идемпотентно). */
    fun scheduleAll(context: Context) {
        val prefs = Prefs(context)
        schedule(context, ACTION_MORNING, prefs.morningOn, prefs.morningHour)
        schedule(context, ACTION_EVENING, prefs.eveningOn, prefs.eveningHour)
    }

    private fun schedule(context: Context, action: String, on: Boolean, hour: Int) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            context, action.hashCode(),
            Intent(context, BriefingReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        am.cancel(pi)
        if (!on) return
        val now = LocalDateTime.now()
        var at = now.toLocalDate().atTime(hour, 0)
        if (!at.isAfter(now)) at = at.plusDays(1)
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), pi)
    }

    private fun todayItems(items: List<Item>, today: LocalDate) = items
        .filter { it.type == ItemType.REMINDER && !it.done && it.dueAt != null && it.occursOn(today) }
        .sortedWith(compareBy<Item>({ !it.allDay }, { it.dueAt }))

    private fun line(it: Item) = if (it.allDay) it.title else "${it.dueAt!!.toLocalDateTime().format(HM)} ${it.title}"

    fun morningText(items: List<Item>): String {
        val today = todayItems(items, LocalDate.now())
        if (today.isEmpty()) return "На сегодня дел нет. Выберите одно важное дело и скажите «фокус»."
        val first = today.firstOrNull { it.priority > 0 } ?: today.first()
        return "Сегодня ${today.size} ${plural(today.size)}. Главное: ${first.title}.\n" +
            today.take(6).joinToString("\n") { "• " + line(it) }
    }

    fun eveningText(items: List<Item>, prefs: Prefs): String {
        val now = System.currentTimeMillis()
        val left = todayItems(items, LocalDate.now()).filter { it.allDay || it.dueAt!! < now }
        val done = prefs.doneToday
        val head = when {
            done == 0 && left.isEmpty() -> "Спокойный день."
            done == 0 -> "Сегодня ничего не отмечено как сделанное. Ничего страшного, завтра новый день."
            else -> "Сегодня сделано: $done. Отличная работа!" + (if (prefs.streak > 1) " Серия: ${prefs.streak} дн. подряд." else "")
        }
        val inbox = items.count { it.type == ItemType.NOTE && !it.done }
        val inboxLine = if (inbox > 0) "\nВо входящих: $inbox. Скажите «разбери входящие»." else ""
        if (left.isEmpty()) return head + inboxLine
        return "$head\nНе сделано:\n" + left.take(5).joinToString("\n") { "• " + it.title } +
            "\nСкажите «перенеси … на завтра» или «сделал …»." + inboxLine
    }

    fun plural(n: Int): String = when {
        n % 10 == 1 && n % 100 != 11 -> "дело"
        n % 10 in 2..4 && n % 100 !in 12..14 -> "дела"
        else -> "дел"
    }

    internal fun show(context: Context, morning: Boolean, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val open = PendingIntent.getActivity(
            context, if (morning) 1001 else 1002,
            Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_WHAT_NOW)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, JarvisApp.CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (morning) "План на сегодня" else "Итоги дня")
            .setContentText(text.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        NotificationManagerCompat.from(context).notify(if (morning) NOTIF_MORNING else NOTIF_EVENING, n)
    }
}

class BriefingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as JarvisApp
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val items = app.db.items().all()
                val prefs = Prefs(context)
                when (intent.action) {
                    Briefing.ACTION_MORNING -> Briefing.show(context, true, Briefing.morningText(items))
                    Briefing.ACTION_EVENING -> Briefing.show(context, false, Briefing.eveningText(items, prefs))
                }
                Briefing.scheduleAll(context)
            } finally {
                pending.finish()
            }
        }
    }
}
