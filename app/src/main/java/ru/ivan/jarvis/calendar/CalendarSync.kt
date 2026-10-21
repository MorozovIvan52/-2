package ru.ivan.jarvis.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import ru.ivan.jarvis.data.Item
import ru.ivan.jarvis.data.ItemType
import ru.ivan.jarvis.data.Repeat
import ru.ivan.jarvis.data.toLocalDateTime
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone

/** Событие из календаря телефона (для «что у меня завтра»). */
data class PhoneEvent(val title: String, val begin: Long, val allDay: Boolean)

/**
 * Дублирует напоминания Джарвиса в календарь телефона (Google/Samsung/Xiaomi —
 * любой, что синхронизируется с аккаунтом), и читает оттуда события.
 */
object CalendarSync {
    val PERMISSIONS = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
    private const val PREFS = "jarvis_calendar"

    fun granted(context: Context) = PERMISSIONS.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun map(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun eventId(context: Context, itemId: Long) = map(context).getLong("ev_$itemId", -1).takeIf { it > 0 }
    private fun ourEventIds(context: Context): Set<Long> =
        map(context).all.filterKeys { it.startsWith("ev_") }.values.mapNotNull { (it as? Long) }.toSet()

    /** Основной календарь с правом записи: сначала основной Google-аккаунт. */
    private fun calendarId(context: Context): Long? {
        val cr = context.contentResolver
        val proj = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
        )
        val sel = "${CalendarContract.Calendars.VISIBLE} = 1 AND ${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}"
        val list = mutableListOf<Triple<Long, Boolean, String>>()
        runCatching {
            cr.query(CalendarContract.Calendars.CONTENT_URI, proj, sel, null, null)?.use { c ->
                while (c.moveToNext()) list += Triple(c.getLong(0), c.getInt(1) == 1, c.getString(2).orEmpty())
            }
        }
        return list.sortedWith(compareByDescending<Triple<Long, Boolean, String>> { it.second && it.third == "com.google" }
            .thenByDescending { it.third == "com.google" }
            .thenByDescending { it.second })
            .firstOrNull()?.first
    }

    /** Создать или обновить событие для напоминания. Заметки без даты не трогаем. */
    fun upsert(context: Context, item: Item): Boolean {
        if (!granted(context)) return false
        if (item.type != ItemType.REMINDER || item.dueAt == null) { delete(context, item.id); return false }
        val cal = calendarId(context) ?: return false
        val v = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, cal)
            put(CalendarContract.Events.TITLE, item.title)
            put(CalendarContract.Events.DESCRIPTION, "Записано Джарвисом: «${item.source.ifBlank { item.title }}»")
            if (item.allDay) {
                // Для событий на весь день календарь требует полночь UTC
                val day = item.dueAt.toLocalDateTime().toLocalDate()
                val start = day.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
                put(CalendarContract.Events.ALL_DAY, 1)
                put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
                put(CalendarContract.Events.DTSTART, start)
                if (item.repeat == Repeat.NONE) put(CalendarContract.Events.DTEND, start + 86_400_000L)
                else put(CalendarContract.Events.DURATION, "P1D")
            } else {
                put(CalendarContract.Events.ALL_DAY, 0)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                put(CalendarContract.Events.DTSTART, item.dueAt)
                if (item.repeat == Repeat.NONE) put(CalendarContract.Events.DTEND, item.dueAt + 30 * 60_000L)
                else put(CalendarContract.Events.DURATION, "PT30M")
            }
            when (item.repeat) {
                Repeat.NONE -> putNull(CalendarContract.Events.RRULE)
                Repeat.DAILY -> put(CalendarContract.Events.RRULE, "FREQ=DAILY")
                Repeat.WEEKLY -> put(CalendarContract.Events.RRULE, "FREQ=WEEKLY")
            }
            if (item.repeat != Repeat.NONE) putNull(CalendarContract.Events.DTEND)
        }
        return runCatching {
            val cr = context.contentResolver
            val existing = eventId(context, item.id)
            val updated = existing != null &&
                cr.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, existing), v, null, null) > 0
            if (!updated) {
                val uri = cr.insert(CalendarContract.Events.CONTENT_URI, v) ?: return false
                map(context).edit().putLong("ev_${item.id}", ContentUris.parseId(uri)).apply()
            }
            true
        }.getOrDefault(false)
    }

    fun delete(context: Context, itemId: Long) {
        val ev = eventId(context, itemId) ?: return
        if (granted(context)) runCatching {
            context.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, ev), null, null)
        }
        map(context).edit().remove("ev_$itemId").apply()
    }

    /** События телефона за период, кроме созданных Джарвисом. */
    fun events(context: Context, from: LocalDate, days: Int): List<PhoneEvent> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val zone = ZoneId.systemDefault()
        val begin = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = from.plusDays(days.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, begin); ContentUris.appendId(it, end)
        }.build()
        val ours = ourEventIds(context)
        val out = mutableListOf<PhoneEvent>()
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY),
                "${CalendarContract.Instances.VISIBLE} = 1", null, "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    if (c.getLong(0) in ours) continue
                    val title = c.getString(1).orEmpty()
                    if (title.isNotBlank()) out += PhoneEvent(title, c.getLong(2), c.getInt(3) == 1)
                }
            }
        }
        return out
    }
}
