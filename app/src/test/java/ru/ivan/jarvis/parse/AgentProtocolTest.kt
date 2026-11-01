package ru.ivan.jarvis.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentProtocolTest {
    private fun g(s: String) = AgentProtocol.goal(s)

    @Test fun goals() {
        assertEquals(AgentGoal("открой озон и найди фильтр Mann", "озон"), g("Джарвис, открой озон и найди фильтр Mann"))
        assertEquals(AgentGoal("в ютубе включи Высоцкого", "ютубе"), g("в ютубе включи Высоцкого"))
        assertEquals(AgentGoal("напиши Саше в телеграм, что задерживаюсь", null), g("сделай сам: напиши Саше в телеграм, что задерживаюсь"))
        assertEquals(AgentGoal("закажи такси до дома", null), g("закажи такси до дома"))
        // В понедельник — не приложение: VM проверит app по установленным
        assertEquals("понедельник", g("в понедельник напиши отчёт")?.app)
        assertNull(g("открой ютуб"))
        assertNull(g("напомни завтра в 9 позвонить"))
        assertNull(g("найди цену колодок на Солярис"))
    }

    @Test fun parseActions() {
        assertEquals(AgentAction.Tap(5), AgentProtocol.parse("нажми 5"))
        assertEquals(AgentAction.Tap(12), AgentProtocol.parse("Действие: нажми [12]."))
        assertEquals(AgentAction.Type(3, "фильтр Mann W 75/3"), AgentProtocol.parse("введи 3: фильтр Mann W 75/3"))
        assertEquals(AgentAction.Scroll(true), AgentProtocol.parse("листай вниз"))
        assertEquals(AgentAction.Scroll(false), AgentProtocol.parse("листай вверх"))
        assertEquals(AgentAction.Open("Озон"), AgentProtocol.parse("открой «Озон»"))
        assertEquals(AgentAction.Back, AgentProtocol.parse("назад"))
        assertEquals(AgentAction.Done("Нашёл, цена 540 ₽"), AgentProtocol.parse("готово: Нашёл, цена 540 ₽"))
        assertEquals(AgentAction.Ask("Какой адрес?"), AgentProtocol.parse("спроси: Какой адрес?"))
        assertEquals(AgentAction.Tap(2), AgentProtocol.parse("Думаю, надо нажать поиск\nнажми 2"))
        assertNull(AgentProtocol.parse("не знаю"))
    }

    @Test fun risky() {
        assertTrue(AgentProtocol.risky("Отправить"))
        assertTrue(AgentProtocol.risky("Оплатить 1 200 ₽"))
        assertTrue(AgentProtocol.risky("Оформить заказ"))
        assertTrue(AgentProtocol.risky("Удалить чат"))
        assertFalse(AgentProtocol.risky("Поиск"))
        assertFalse(AgentProtocol.risky("Корзина"))
    }

    @Test fun render() {
        val els = listOf(UiElement(1, "поле", "Поиск"), UiElement(2, "кнопка", "Найти"))
        assertEquals("[1] поле: Поиск\n[2] кнопка: Найти", AgentProtocol.render(els))
        assertEquals("нажимаю «Найти»", AgentProtocol.describe(AgentAction.Tap(2), els))
        assertEquals("Позвонить •••67", AgentProtocol.maskPhones("Позвонить +7 912 345-45-67"))
    }
}
