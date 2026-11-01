package ru.ivan.jarvis.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskCreateTest {
    @Test fun parse() {
        assertEquals("позвонить Пете", TaskCreate.parse("Поставь задачу позвонить Пете"))
        assertEquals("на завтра заказать масло", TaskCreate.parse("Джарвис, поставь задачу на завтра заказать масло"))
        assertEquals("сделать отчёт", TaskCreate.parse("новая задача: сделать отчёт"))
        assertEquals("купить фильтр", TaskCreate.parse("задача купить фильтр"))
        assertEquals("", TaskCreate.parse("создай задачу"))
        assertEquals("Саше поменять масло", TaskCreate.parse("поставь задачу Саше поменять масло"))
        assertNull(TaskCreate.parse("какие задачи на завтра?"))
        assertNull(TaskCreate.parse("разбей задачу отчёт на шаги"))
        assertNull(TaskCreate.parse("удали задачу про банк"))
        assertEquals("Заказать масло", TaskCreate.cleanTitle("на заказать масло"))
    }
}
