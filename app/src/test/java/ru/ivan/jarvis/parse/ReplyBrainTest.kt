package ru.ivan.jarvis.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyBrainTest {
    @Test fun intents() {
        assertEquals(ReplyBrain.Intent.READY, ReplyBrain.intent("Добрый день, машина готова?"))
        assertEquals(ReplyBrain.Intent.PRICE, ReplyBrain.intent("Сколько будет стоить замена колодок"))
        assertEquals(ReplyBrain.Intent.BOOK, ReplyBrain.intent("Можно приехать завтра?"))
        assertEquals(ReplyBrain.Intent.WHERE, ReplyBrain.intent("Скиньте адрес"))
        assertEquals(ReplyBrain.Intent.COMPLAINT, ReplyBrain.intent("Опять стучит после вас"))
        assertEquals(ReplyBrain.Intent.THANKS, ReplyBrain.intent("Спасибо!"))
        assertTrue(ReplyBrain.urgent("Срочно, не заводится"))
        assertFalse(ReplyBrain.urgent("Когда забирать?"))
    }

    @Test fun draftsUseOrder() {
        val o = ReplyBrain.OrderInfo("Солярис", "колодки", OrderStatus.READY, 4500)
        val a = ReplyBrain.drafts("Когда можно забрать машину?", "Петров Иван", o)
        assertEquals(ReplyBrain.Intent.READY, a.intent)
        assertEquals("Иван, добрый день! Машина Солярис готова, можно забирать.", a.drafts[0])
        assertEquals(2, a.drafts.size)
        val p = ReplyBrain.drafts("сколько по деньгам?", "Саша", o)
        assertTrue(p.drafts[0].contains("4500 ₽"))
        val wait = ReplyBrain.drafts("что с машиной?", "Саша", o.copy(status = OrderStatus.WAIT_PARTS))
        assertTrue(wait.drafts[0].contains("Ждём запчасти"))
    }

    @Test fun names() {
        assertEquals("Иван", ReplyBrain.firstName("Петров Иван"))
        assertEquals("Саша", ReplyBrain.firstName("Саша"))
        assertEquals("Олег", ReplyBrain.firstName("Гараж чат (Олег)"))
        assertEquals("", ReplyBrain.firstName("+7 912 000"))
    }

    @Test fun parseAi() {
        assertEquals(listOf("Добрый день! Машина готова.", "Можно забирать после 17:00."),
            ReplyBrain.parseAi("1) Добрый день! Машина готова.\n2) «Можно забирать после 17:00.»"))
    }
}
