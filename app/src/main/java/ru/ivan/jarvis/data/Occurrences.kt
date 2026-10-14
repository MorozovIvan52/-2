package ru.ivan.jarvis.data

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

fun Long.toLocalDateTime(): LocalDateTime =
    LocalDateTime.ofInstant(Instant.ofEpochMilli(this), ZoneId.systemDefault())

fun LocalDateTime.toMillis(): Long = atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

/** Попадает ли напоминание (с учётом повтора) на этот день. */
fun Item.occursOn(date: LocalDate): Boolean {
    val start = dueAt?.toLocalDateTime()?.toLocalDate() ?: return false
    return when (repeat) {
        Repeat.NONE -> start == date
        Repeat.DAILY -> !date.isBefore(start)
        Repeat.WEEKLY -> !date.isBefore(start) && date.dayOfWeek == start.dayOfWeek
    }
}

/** Следующее срабатывание повторяющегося напоминания после [after]. */
fun nextOccurrence(due: LocalDateTime, repeat: Repeat, after: LocalDateTime): LocalDateTime? {
    if (repeat == Repeat.NONE) return null
    var next = due
    while (!next.isAfter(after)) {
        next = if (repeat == Repeat.DAILY) next.plusDays(1) else next.plusWeeks(1)
    }
    return next
}
