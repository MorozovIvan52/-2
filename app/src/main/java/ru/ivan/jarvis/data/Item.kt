package ru.ivan.jarvis.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

enum class ItemType { NOTE, REMINDER }

enum class Repeat { NONE, DAILY, WEEKLY }

/**
 * Одна запись: заметка (без времени) или напоминание/событие (с dueAt).
 * dueAt — epoch millis. allDay = событие на день без точного времени
 * (уведомление приходит в 9:00 этого дня).
 */
@Entity(tableName = "items")
data class Item(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: ItemType,
    val title: String,
    val source: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val dueAt: Long? = null,
    val allDay: Boolean = false,
    val repeat: Repeat = Repeat.NONE,
    val done: Boolean = false,
    /** 1 — «срочно/важно»: первым в «что делать» и в утреннем плане. */
    @ColumnInfo(defaultValue = "0") val priority: Int = 0,
)
