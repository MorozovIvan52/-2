package ru.ivan.jarvis.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchPlanTest {
    @Test fun routesToWeb() {
        assertTrue(SearchParse.needsWeb("найди схему ремня ГРМ Ларгус"))
        assertTrue(SearchParse.needsWeb("узнай есть ли в наличии колодки на Солярис"))
        assertTrue(SearchParse.needsWeb("что пишут на драйве про стук в подвеске Тигуана"))
        assertTrue(SearchParse.needsWeb("болячки Калины 1.6"))
        assertTrue(SearchParse.needsWeb("как снять стартер на Фокус 2"))
        assertFalse(SearchParse.needsWeb("узнай у Саши когда придёт"))
        assertFalse(SearchParse.needsWeb("напомни завтра позвонить поставщику"))
    }

    @Test fun drive2() {
        val p = SearchParse.plan("Джарвис, что пишут на драйв 2 про стук в подвеске Тигуана")
        assertEquals(SearchParse.Mode.FORUM, p.mode)
        assertEquals("стук в подвеске Тигуана site:drive2.ru", p.query)
    }

    @Test fun forum() {
        val p = SearchParse.plan("найди на форумах почему троит Логан")
        assertEquals(SearchParse.Mode.FORUM, p.mode)
        assertEquals("почему троит Логан форум отзывы", p.query)
    }

    @Test fun stock() {
        val p = SearchParse.plan("узнай есть ли в наличии колодки на Солярис")
        assertEquals(SearchParse.Mode.STOCK, p.mode)
        assertEquals("колодки на Солярис купить в наличии цена", p.query)
    }

    @Test fun general() {
        val p = SearchParse.plan("найди момент затяжки ГБЦ Логан")
        assertEquals(SearchParse.Mode.GENERAL, p.mode)
        assertEquals("момент затяжки ГБЦ Логан", p.query)
    }
}
