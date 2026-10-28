package ru.ivan.jarvis.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSearchTest {
    @Test fun jargon() {
        assertEquals("Солярис свистит при запуске натяжитель ремня ремень генератора", AutoSearch.expand("Солярис свистит при запуске"))
        assertTrue(AutoSearch.expand("Логан жрёт масло").endsWith("повышенный расход масла"))
        assertTrue(AutoSearch.expand("где на Солярисе стучит под капотом").contains("стук в двигателе"))
        assertTrue(AutoSearch.expand("Приора троит").contains("пропуски зажигания"))
        assertEquals("масляный фильтр Mann W 75/3", AutoSearch.expand("масляный фильтр Mann W 75/3"))
    }

    @Test fun rankAndNames() {
        val hits = listOf(
            SearchHit("a", "https://example.com/x", ""),
            SearchHit("b", "https://www.youtube.com/watch?v=1", ""),
            SearchHit("c", "https://www.drive2.ru/l/1/", ""),
            SearchHit("d", "https://forums.drom.ru/hyundai/t1.html", ""),
            SearchHit("e", "https://www.drive2.ru/l/1/#comments", ""),
        )
        val r = AutoSearch.rank(hits)
        assertEquals(listOf("c", "d", "b", "a"), r.map { it.title })
        assertEquals("Drive2", AutoSearch.sourceName("www.drive2.ru"))
        assertEquals("Форум Drom", AutoSearch.sourceName("forums.drom.ru"))
        assertEquals("YouTube", AutoSearch.sourceName("m.youtube.com"))
        assertTrue(AutoSearch.hasProfile(r))
    }
}
