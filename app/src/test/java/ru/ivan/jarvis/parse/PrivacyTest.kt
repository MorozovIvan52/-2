package ru.ivan.jarvis.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PrivacyTest {
    @Test fun phones() {
        assertEquals("позвони •••67", Privacy.mask("позвони +7 912 345-45-67"))
        assertEquals("номер •••90", Privacy.mask("номер 89161234590"))
        assertEquals("приход 15000", Privacy.mask("приход 15000"))
    }

    @Test fun plates() {
        assertEquals("Солярис а•••вс готов", Privacy.mask("Солярис а123вс готов"))
        assertEquals("номер х•••кр", Privacy.mask("номер х777кр 77"))
    }

    @Test fun names() {
        assertEquals("новый клиент П., Солярис", Privacy.mask("новый клиент Петров, Солярис"))
        assertEquals("напиши И. что машина готова", Privacy.mask("напиши Ивановой что машина готова"))
        assertEquals("Магазин закрыт", Privacy.mask("Магазин закрыт"))
        val m = Privacy.mask("поручи Саше поменять масло на Камри")
        assertFalse(m.contains("•"))
        assertEquals("поручи Саше поменять масло на Камри", m)
    }
}
