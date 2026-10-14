package ru.ivan.jarvis.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import ru.ivan.jarvis.data.Item
import ru.ivan.jarvis.data.ItemType

object ReminderScheduler {
    const val ACTION_FIRE = "ru.ivan.jarvis.FIRE"
    const val ACTION_SNOOZE_FIRE = "ru.ivan.jarvis.SNOOZE_FIRE"
    const val ACTION_DONE = "ru.ivan.jarvis.DONE"
    const val ACTION_SNOOZE = "ru.ivan.jarvis.SNOOZE"
    const val EXTRA_ID = "id"

    fun itemUri(id: Long): Uri = Uri.parse("jarvis://item/$id")

    private fun pending(context: Context, id: Long, action: String, flags: Int = PendingIntent.FLAG_UPDATE_CURRENT): PendingIntent? =
        PendingIntent.getBroadcast(
            context, 0,
            Intent(context, ReminderReceiver::class.java).setAction(action).setData(itemUri(id)).putExtra(EXTRA_ID, id),
            flags or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Ставит (или снимает) будильник для записи. */
    fun schedule(context: Context, item: Item) {
        cancel(context, item.id)
        val at = item.dueAt ?: return
        if (item.type != ItemType.REMINDER || item.done || at <= System.currentTimeMillis()) return
        setAlarm(context, at, pending(context, item.id, ACTION_FIRE)!!)
    }

    fun snooze(context: Context, id: Long, minutes: Int = 10) {
        setAlarm(context, System.currentTimeMillis() + minutes * 60_000L, pending(context, id, ACTION_SNOOZE_FIRE)!!)
    }

    fun cancel(context: Context, id: Long) {
        val am = context.getSystemService(AlarmManager::class.java)
        listOf(ACTION_FIRE, ACTION_SNOOZE_FIRE).forEach { action ->
            pending(context, id, action, PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it); it.cancel() }
        }
    }

    private fun setAlarm(context: Context, at: Long, pi: PendingIntent) {
        val am = context.getSystemService(AlarmManager::class.java)
        val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (exactAllowed) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }
}
