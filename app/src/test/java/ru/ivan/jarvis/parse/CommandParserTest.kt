package ru.ivan.jarvis.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.ivan.jarvis.data.ItemType
import ru.ivan.jarvis.data.Repeat
import java.time.LocalDateTime

class CommandParserTest {
    // Среда, 14 октября 2026, 17:15
    private val now = LocalDateTime.of(2026, 10, 14, 17, 15)

    private fun save(text: String) = CommandParser.parse(text, now) as Command.Save

    @Test fun tomorrowAtNine() {
        val c = save("Напомни завтра в 9 позвонить врачу")
        assertEquals(ItemType.REMINDER, c.type)
        assertEquals(LocalDateTime.of(2026, 10, 15, 9, 0), c.due)
        assertEquals("Позвонить врачу", c.title)
    }

    @Test fun wordsAndMinutes() {
        val c = save("через пять минут выключить духовку")
        assertEquals(now.plusMinutes(5), c.due)
        assertEquals("Выключить духовку", c.title)
    }

    @Test fun halfHour() {
        assertEquals(now.plusMinutes(30), save("напомни через полчаса проверить почту").due)
    }

    @Test fun eveningTime() {
        val c = save("сегодня в 8 вечера тренировка")
        assertEquals(LocalDateTime.of(2026, 10, 14, 20, 0), c.due)
        assertEquals("Тренировка", c.title)
    }

    @Test fun timeWithMinutes() {
        assertEquals(LocalDateTime.of(2026, 10, 15, 10, 30), save("завтра в 10:30 встреча с Петром").due)
        assertEquals("Встреча с Петром", save("завтра в 10:30 встреча с Петром").title)
    }

    @Test fun timeOnlyPastMovesToTomorrow() {
        assertEquals(LocalDateTime.of(2026, 10, 15, 9, 0), save("в 9 утра выпить таблетку").due)
        assertEquals(LocalDateTime.of(2026, 10, 14, 18, 0), save("в 6 забрать сына").due)
    }

    @Test fun monthDate() {
        val c = save("20 ноября день рождения мамы")
        assertEquals(LocalDateTime.of(2026, 11, 20, 9, 0), c.due)
        assertTrue(c.allDay)
        assertEquals("День рождения мамы", c.title)
    }

    @Test fun ordinalDateWithTime() {
        assertEquals(LocalDateTime.of(2026, 10, 25, 15, 0), save("двадцать пятого октября в 3 дня стоматолог").due)
    }

    @Test fun pastDateGoesToNextYear() {
        assertEquals(2027, save("5 марта поздравить Олю").due!!.year)
    }

    @Test fun numericDate() {
        assertEquals(LocalDateTime.of(2026, 12, 31, 23, 0), save("31.12 в 23:00 загадать желание").due)
    }

    @Test fun weekday() {
        val c = save("в пятницу в 18:00 кино")
        assertEquals(LocalDateTime.of(2026, 10, 16, 18, 0), c.due)
        assertEquals("Кино", c.title)
    }

    @Test fun sameWeekdayNextWeek() {
        assertEquals(LocalDateTime.of(2026, 10, 21, 9, 0).toLocalDate(), save("в среду сдать отчёт").due!!.toLocalDate())
    }

    @Test fun weeklyRepeat() {
        val c = save("каждый понедельник в 10 планерка")
        assertEquals(Repeat.WEEKLY, c.repeat)
        assertEquals(LocalDateTime.of(2026, 10, 19, 10, 0), c.due)
        assertEquals("Планерка", c.title)
    }

    @Test fun dailyMorning() {
        val c = save("каждое утро зарядка")
        assertEquals(Repeat.DAILY, c.repeat)
        assertEquals(LocalDateTime.of(2026, 10, 15, 9, 0), c.due)
    }

    @Test fun note() {
        val c = save("Джарвис, запиши идею: подарить Ане книгу")
        assertEquals(ItemType.NOTE, c.type)
        assertNull(c.due)
        assertEquals("Идею: подарить Ане книгу", c.title)
    }

    @Test fun notePlain() {
        val c = save("запиши в заметки купить молоко и хлеб")
        assertEquals(ItemType.NOTE, c.type)
        assertEquals("Купить молоко и хлеб", c.title)
    }

    @Test fun inHours() {
        assertEquals(now.plusHours(2), save("через 2 часа забрать посылку").due)
        assertEquals(now.plusHours(1), save("через час позвонить Ивану").due)
    }

    @Test fun inDays() {
        val c = save("через 3 дня оплатить интернет")
        assertEquals(LocalDateTime.of(2026, 10, 17, 9, 0), c.due)
        assertEquals("Оплатить интернет", c.title)
    }

    @Test fun hourWord() {
        assertEquals(LocalDateTime.of(2026, 10, 15, 13, 0), save("завтра в час дня обед с командой").due)
    }

    @Test fun query() {
        val q = CommandParser.parse("что у меня завтра", now) as Command.Query
        assertEquals(now.toLocalDate().plusDays(1), q.from)
        val q2 = CommandParser.parse("Какие планы на неделю?", now) as Command.Query
        assertEquals(7, q2.days)
    }
}
