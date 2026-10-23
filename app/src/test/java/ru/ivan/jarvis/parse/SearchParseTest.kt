package ru.ivan.jarvis.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchParseTest {
    @Test fun needsWeb() {
        assertTrue(SearchParse.needsWeb("Сколько стоит ремкомплект суппорта на Камри 40?"))
        assertTrue(SearchParse.needsWeb("какой момент затяжки ГБЦ на Логан 1.6"))
        assertTrue(SearchParse.needsWeb("что значит код ошибки P0300"))
        assertTrue(SearchParse.needsWeb("сколько масла в двигатель Соляриса 1.6"))
        assertTrue(SearchParse.needsWeb("какая погода завтра"))
        assertFalse(SearchParse.needsWeb("как перестать откладывать дела?"))
    }

    @Test fun cleanQuery() {
        assertEquals("момент затяжки ГБЦ Логан", SearchParse.cleanQuery("Джарвис, найди в интернете момент затяжки ГБЦ Логан?"))
    }

    @Test fun yandexXml() {
        val xml = """<yandexsearch><response><results><grouping><group><doc id="1"><url>https://www.drive2.ru/l/123/</url>
            <title>Момент затяжки <hlword>ГБЦ</hlword> Логан</title><passages><passage>Затяжка в 4 этапа: 20 Н·м, потом 90°</passage></passages></doc></group>
            <group><doc><url>https://example.com/a</url><title>Второй</title><headline>Заголовок</headline></doc></group></grouping></results></response></yandexsearch>"""
        val hits = SearchParse.parseYandexXml(xml)
        assertEquals(2, hits.size)
        assertEquals("Момент затяжки ГБЦ Логан", hits[0].title)
        assertEquals("drive2.ru", hits[0].domain)
        assertEquals("Затяжка в 4 этапа: 20 Н·м, потом 90°", hits[0].snippet)
        assertEquals("Заголовок", hits[1].snippet)
    }

    @Test fun ddgHtml() {
        val html = """<div class="result__body"><h2><a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexist.ru%2Fpart&amp;rut=x">Колодки <b>Солярис</b></a></h2>
            <a class="result__snippet" href="x">Цена от <b>1500</b> руб.</a></div>"""
        val hits = SearchParse.parseDdgHtml(html)
        assertEquals(1, hits.size)
        assertEquals("https://exist.ru/part", hits[0].url)
        assertEquals("Колодки Солярис", hits[0].title)
        assertEquals("Цена от 1500 руб.", hits[0].snippet)
    }
}
