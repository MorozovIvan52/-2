package ru.ivan.jarvis.ui

import ru.ivan.jarvis.data.Item
import ru.ivan.jarvis.data.Repeat
import ru.ivan.jarvis.data.toLocalDateTime
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

val RU: Locale = Locale("ru", "RU")
private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
private val dayFmt = DateTimeFormatter.ofPattern("d MMMM", RU)
private val dayYearFmt = DateTimeFormatter.ofPattern("d MMMM yyyy", RU)

fun dayLabel(date: LocalDate, today: LocalDate = LocalDate.now()): String = when (date) {
    today -> "сегодня"
    today.plusDays(1) -> "завтра"
    today.plusDays(2) -> "послезавтра"
    else -> date.format(if (date.year == today.year) dayFmt else dayYearFmt)
}

fun weekdayName(d: DayOfWeek): String = d.getDisplayName(TextStyle.FULL, RU)

/** «завтра в 09:00», «каждый день в 08:00», «20 ноября». */
fun describeDue(item: Item): String {
    val dt = item.dueAt?.toLocalDateTime() ?: return ""
    val time = dt.format(timeFmt)
    return when (item.repeat) {
        Repeat.DAILY -> "каждый день в $time"
        Repeat.WEEKLY -> "еженедельно, ${weekdayName(dt.dayOfWeek)} в $time"
        Repeat.NONE -> if (item.allDay) dayLabel(dt.toLocalDate()) else "${dayLabel(dt.toLocalDate())} в $time"
    }
}

fun timeOf(item: Item): String =
    if (item.allDay) "весь день" else item.dueAt?.toLocalDateTime()?.format(timeFmt).orEmpty()

fun createdLabel(item: Item): String =
    item.createdAt.toLocalDateTime().let { "${dayLabel(it.toLocalDate())}, ${it.format(timeFmt)}" }
