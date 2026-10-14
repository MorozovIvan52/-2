package ru.ivan.jarvis.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ru.ivan.jarvis.JarvisApp
import ru.ivan.jarvis.R
import ru.ivan.jarvis.data.Item
import ru.ivan.jarvis.data.ItemType
import ru.ivan.jarvis.data.toLocalDateTime
import ru.ivan.jarvis.settings.Prefs
import ru.ivan.jarvis.ui.MainActivity
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Виджет на рабочий стол: следующее дело, кнопка микрофона и «Что делать?». */
class JarvisWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { render(context, manager, ids) } finally { pending.finish() }
        }
    }

    companion object {
        private val HM = DateTimeFormatter.ofPattern("HH:mm")
        private val DM = DateTimeFormatter.ofPattern("d.MM HH:mm")

        /** Перерисовать все виджеты (после любых изменений дел). */
        fun update(context: Context) {
            val app = context.applicationContext
            val manager = AppWidgetManager.getInstance(app)
            val ids = manager.getAppWidgetIds(ComponentName(app, JarvisWidget::class.java))
            if (ids.isEmpty()) return
            CoroutineScope(Dispatchers.IO).launch { runCatching { render(app, manager, ids) } }
        }

        private suspend fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val items = (context.applicationContext as JarvisApp).db.items().all()
            val (title, subtitle) = nextText(items, Prefs(context))
            val views = RemoteViews(context.packageName, R.layout.widget_jarvis).apply {
                setTextViewText(R.id.widget_title, title)
                setTextViewText(R.id.widget_subtitle, subtitle)
                setOnClickPendingIntent(R.id.widget_mic, activity(context, MainActivity.ACTION_LISTEN, 2001))
                setOnClickPendingIntent(R.id.widget_now, activity(context, MainActivity.ACTION_WHAT_NOW, 2002))
                setOnClickPendingIntent(R.id.widget_body, activity(context, Intent.ACTION_MAIN, 2003))
            }
            manager.updateAppWidget(ids, views)
        }

        private fun nextText(items: List<Item>, prefs: Prefs): Pair<String, String> {
            val now = System.currentTimeMillis()
            val active = items.filter { it.type == ItemType.REMINDER && !it.done && it.dueAt != null }
            val overdue = active.filter { !it.allDay && it.dueAt!! < now }.maxByOrNull { it.dueAt!! }
            val next = active.filter { it.dueAt!! >= now || it.allDay }.minByOrNull { it.dueAt!! }
            val done = prefs.doneToday.takeIf { it > 0 }?.let { " · сделано сегодня: $it" } ?: ""
            return when {
                overdue != null -> overdue.title to "Просрочено$done"
                next != null -> {
                    val due = next.dueAt!!.toLocalDateTime()
                    val whenText = when {
                        next.allDay -> "Сегодня"
                        due.toLocalDate() == LocalDate.now() -> "Сегодня в ${due.format(HM)}"
                        due.toLocalDate() == LocalDate.now().plusDays(1) -> "Завтра в ${due.format(HM)}"
                        else -> due.format(DM)
                    }
                    next.title to "$whenText$done"
                }
                else -> "Дел нет" to "Нажмите микрофон и скажите, что сделать$done"
            }
        }

        private fun activity(context: Context, action: String, code: Int) = PendingIntent.getActivity(
            context, code,
            Intent(context, MainActivity::class.java).setAction(action)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
