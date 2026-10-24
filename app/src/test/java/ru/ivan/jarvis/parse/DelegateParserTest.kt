package ru.ivan.jarvis.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DelegateParserTest {
    @Test fun delegation() {
        assertEquals(Delegation("Саше", "поменять масло на Ларгусе", "Саша"), DelegateParser.delegation("Поручи Саше поменять масло на Ларгусе"))
        assertEquals(Delegation("Сашу", "забрать запчасти", "Саша"), DelegateParser.delegation("попроси Сашу забрать запчасти"))
        val d = DelegateParser.delegation("скажи Ивану чтобы проверил развал")!!
        assertEquals(Delegation("Ивану", "проверил развал", "Иван", subj = true), d)
        assertEquals("Иван, нужно, чтобы ты проверил развал. Напиши, когда сделаешь.", DelegateParser.message(d))
        assertEquals(Delegation("Петя", "помоет машину", "Петя"), DelegateParser.delegation("пусть Петя помоет машину"))
        assertNull(DelegateParser.delegation("скажи мне который час"))
        assertNull(DelegateParser.delegation("передай Саше что я задержусь"))
        assertEquals("Саша, нужно: поменять масло. Напиши, когда сделаешь.", DelegateParser.message(Delegation("Саше", "поменять масло", "Саша")))
    }

    @Test fun waiting() {
        assertEquals(Waiting("ответа от поставщика"), DelegateParser.waiting("жду ответа от поставщика"))
        assertEquals(Waiting("звонка от Петрова до завтра"), DelegateParser.waiting("Джарвис, жду звонка от Петрова до завтра"))
        assertNull(DelegateParser.waiting("купить молоко"))
    }

    @Test fun priority() {
        assertEquals(true to "позвонить в банк", DelegateParser.priority("срочно позвонить в банк"))
        assertEquals(true to "Отчёт до обеда", DelegateParser.priority("Отчёт до обеда, важно"))
        assertEquals(false to "купить хлеб", DelegateParser.priority("купить хлеб"))
    }
}
