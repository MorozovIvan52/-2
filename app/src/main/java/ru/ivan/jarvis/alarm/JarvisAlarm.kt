package ru.ivan.jarvis.alarm

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import ru.ivan.jarvis.R
import ru.ivan.jarvis.ui.JarvisTheme
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Собственный будильник Джарвиса: громкий звонок по каналу будильника, экран поверх блокировки,
 * «Выключить» / «Ещё 5 минут». Работает и для конкретной даты, и по дням недели.
 */
object JarvisAlarm {
    const val CHANNEL = "alarms"
    private const val ACTION_RING = "ru.ivan.jarvis.ALARM_RING"
    private const val ACTION_DISMISS = "ru.ivan.jarvis.ALARM_DISMISS"
    private const val ACTION_SNOOZE = "ru.ivan.jarvis.ALARM_SNOOZE"
    const val EXTRA_KEY = "key"
    private const val PREFS = "jarvis_alarms"

    /** Сохранённый будильник: key|час|минута|дата(или пусто)|дни(1..7 через запятую)|подпись */
    data class Spec(val key: Int, val hour: Int, val minute: Int, val date: LocalDate?, val days: List<DayOfWeek>, val label: String) {
        fun encode() = listOf(key, hour, minute, date?.toString().orEmpty(), days.joinToString(",") { it.value.toString() }, label.replace("|", " "))
            .joinToString("|")

        fun nextAt(now: LocalDateTime = LocalDateTime.now()): LocalDateTime? {
            val t = LocalTime.of(hour, minute)
            if (date != null) return date.atTime(t).takeIf { it.isAfter(now) }
            var d = now.toLocalDate()
            repeat(8) {
                val at = d.atTime(t)
                if (at.isAfter(now) && (days.isEmpty() || d.dayOfWeek in days)) return at
                d = d.plusDays(1)
            }
            return null
        }

        companion object {
            fun decode(s: String): Spec? = runCatching {
                val p = s.split("|")
                Spec(
                    p[0].toInt(), p[1].toInt(), p[2].toInt(),
                    p[3].takeIf { it.isNotBlank() }?.let(LocalDate::parse),
                    p[4].split(",").filter { it.isNotBlank() }.map { DayOfWeek.of(it.toInt()) },
                    p.getOrElse(5) { "" },
                )
            }.getOrNull()
        }
    }

    fun ensureChannel(context: Context) {
        val ch = NotificationChannel(CHANNEL, "Будильники", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Будильники Джарвиса"
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 800, 600, 800, 600)
            setBypassDnd(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun load(context: Context) = prefs(context).getStringSet("list", emptySet())!!.mapNotNull(Spec::decode)
    private fun store(context: Context, list: List<Spec>) = prefs(context).edit().putStringSet("list", list.map { it.encode() }.toSet()).apply()

    /** Ставит будильник; возвращает время срабатывания или null, если оно в прошлом. */
    fun set(context: Context, hour: Int, minute: Int, date: LocalDate?, days: List<DayOfWeek>, label: String): LocalDateTime? {
        val key = (System.currentTimeMillis() / 1000 % 1_000_000).toInt()
        val spec = Spec(key, hour, minute, date, days, label)
        val at = spec.nextAt() ?: return null
        store(context, load(context).filter { !(it.hour == hour && it.minute == minute && it.date == date && it.days == days) } + spec)
        schedule(context, spec, at)
        return at
    }

    fun all(context: Context): List<Spec> = load(context)

    fun remove(context: Context, key: Int) {
        val spec = load(context).firstOrNull { it.key == key } ?: return
        context.getSystemService(AlarmManager::class.java).cancel(ringIntent(context, spec.key))
        store(context, load(context).filter { it.key != key })
    }

    /** После перезагрузки. */
    fun rescheduleAll(context: Context) {
        val keep = load(context).filter { spec -> spec.nextAt()?.also { schedule(context, spec, it) } != null }
        store(context, keep)
    }

    private fun ringIntent(context: Context, key: Int) = PendingIntent.getBroadcast(
        context, 50_000 + key,
        Intent(context, AlarmReceiver::class.java).setAction(ACTION_RING).putExtra(EXTRA_KEY, key),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun schedule(context: Context, spec: Spec, at: LocalDateTime) = scheduleAt(context, spec.key, at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())

    private fun scheduleAt(context: Context, key: Int, millis: Long) {
        val am = context.getSystemService(AlarmManager::class.java)
        val show = PendingIntent.getActivity(
            context, 60_000 + key, Intent(context, ru.ivan.jarvis.ui.MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // setAlarmClock — самый надёжный способ: не откладывается экономией батареи, значок в строке состояния
        am.setAlarmClock(AlarmManager.AlarmClockInfo(millis, show), ringIntent(context, key))
    }

    internal fun ring(context: Context, key: Int) {
        val spec = load(context).firstOrNull { it.key == key }
        val label = spec?.label?.ifBlank { null } ?: "Будильник"
        // Следующий раз для повторяющихся, разовые удаляем
        if (spec != null) {
            if (spec.days.isEmpty()) store(context, load(context).filter { it.key != key })
            else spec.nextAt(LocalDateTime.now().plusMinutes(1))?.let { schedule(context, spec, it) }
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel(context)
        val full = PendingIntent.getActivity(
            context, 70_000 + key,
            Intent(context, AlarmActivity::class.java).putExtra(EXTRA_KEY, key).putExtra("label", label)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        fun action(a: String) = PendingIntent.getBroadcast(
            context, a.hashCode() + key,
            Intent(context, AlarmReceiver::class.java).setAction(a).putExtra(EXTRA_KEY, key),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("⏰ $time · $label")
            .setContentText("Пора вставать!")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .setOngoing(true)
            .addAction(0, "Выключить", action(ACTION_DISMISS))
            .addAction(0, "Ещё 5 мин", action(ACTION_SNOOZE))
            .build()
        n.flags = n.flags or android.app.Notification.FLAG_INSISTENT
        NotificationManagerCompat.from(context).notify(notifId(key), n)
    }

    private fun notifId(key: Int) = 80_000 + key

    fun dismiss(context: Context, key: Int) = NotificationManagerCompat.from(context).cancel(notifId(key))

    fun snooze(context: Context, key: Int) {
        dismiss(context, key)
        // Отдельный ключ для повтора, чтобы не сбить расписание
        val snoozeKey = key + 500_000
        val spec = load(context).firstOrNull { it.key == key }
        val at = LocalDateTime.now().plusMinutes(5).withSecond(0)
        store(context, load(context).filter { it.key != snoozeKey } +
            Spec(snoozeKey, at.hour, at.minute, at.toLocalDate(), emptyList(), spec?.label.orEmpty()))
        scheduleAt(context, snoozeKey, at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
    }

    internal const val DISMISS = ACTION_DISMISS
    internal const val SNOOZE = ACTION_SNOOZE
    internal const val RING = ACTION_RING
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getIntExtra(JarvisAlarm.EXTRA_KEY, -1)
        if (key < 0) return
        when (intent.action) {
            JarvisAlarm.RING -> JarvisAlarm.ring(context, key)
            JarvisAlarm.DISMISS -> JarvisAlarm.dismiss(context, key)
            JarvisAlarm.SNOOZE -> JarvisAlarm.snooze(context, key)
        }
    }
}

/** Экран будильника поверх блокировки. */
class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        val key = intent.getIntExtra(JarvisAlarm.EXTRA_KEY, -1)
        val label = intent.getStringExtra("label") ?: "Будильник"
        val time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
        setContent {
            JarvisTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(time, fontSize = 72.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text(label, style = MaterialTheme.typography.headlineSmall)
                        Text("Доброе утро! Скажите «что мне сейчас делать», когда проснётесь.", style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = { JarvisAlarm.dismiss(this@AlarmActivity, key); finish() }, Modifier.fillMaxWidth().height(64.dp)) {
                            Text("Выключить", fontSize = 20.sp)
                        }
                        OutlinedButton(onClick = { JarvisAlarm.snooze(this@AlarmActivity, key); finish() }, Modifier.fillMaxWidth().height(56.dp)) {
                            Text("Ещё 5 минут")
                        }
                    }
                }
            }
        }
    }
}
