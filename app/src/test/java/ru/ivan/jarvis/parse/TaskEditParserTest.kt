package ru.ivan.jarvis.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class TaskEditParserTest {
    private val now = LocalDateTime.of(2026, 10, 14, 21, 15)
    private fun p(s: String) = TaskEditParser.parse(s, now)

    @Test fun done() {
        assertEquals(TaskEdit.Done("позвонить врачу"), p("Сделал позвонить врачу"))
        assertEquals(TaskEdit.Done(""), p("готово"))
        assertEquals(TaskEdit.Done("отчёт"), p("отметь отчёт выполненным"))
        assertEquals(TaskEdit.Done("купить молоко"), p("купить молоко сделано"))
    }

    @Test fun delete() {
        assertEquals(TaskEdit.Delete("врач"), p("удали напоминание про врач"))
        assertEquals(TaskEdit.Delete("планёрку"), p("отмени планёрку"))
        assertNull(p("отмени фокус"))
    }

    @Test fun move() {
        val m = p("перенеси звонок врачу на завтра в 10") as TaskEdit.Move
        assertEquals("звонок врачу", m.query)
        assertEquals(LocalDateTime.of(2026, 10, 15, 10, 0), m.due)
        val s = p("отложи на 20 минут") as TaskEdit.Move
        assertEquals("", s.query)
        assertEquals(now.plusMinutes(20), s.due)
        val t = p("отложи отчёт на час") as TaskEdit.Move
        assertEquals("отчёт", t.query)
        assertEquals(now.plusHours(1), t.due)
    }

    @Test fun summaryAndNotes() {
        assertEquals(TaskEdit.Summary, p("Что я сделал сегодня?"))
        assertEquals(TaskEdit.Summary, p("итоги дня"))
        assertEquals(TaskEdit.Notes, p("мои заметки"))
    }

    @Test fun notEdits() {
        assertNull(p("напомни завтра в 9 позвонить врачу"))
        assertNull(p("купить молоко"))
    }
}

class TaskMatcherTest {
    @Test fun matches() {
        val items = listOf(1L to "Позвонить врачу", 2L to "Купить молоко", 3L to "Сделать отчёт для Пети")
        assertEquals(1L, TaskMatcher.best("врачу", items))
        assertEquals(1L, TaskMatcher.best("звонок врачу", items))
        assertEquals(3L, TaskMatcher.best("отчёт", items))
        assertEquals(2L, TaskMatcher.best("молоко", items))
        assertNull(TaskMatcher.best("собака", items))
        assertTrue(TaskMatcher.best("", items) == null)
    }
}
