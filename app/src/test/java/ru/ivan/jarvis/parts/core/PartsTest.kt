package ru.ivan.jarvis.parts.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PartsTest {
    @Test fun normalizeArticle() {
        assertEquals("W753", Article.normalize("W 75/3"))
        assertEquals("OC90", Article.normalize("ОС 90")) // кириллица
        assertEquals("1J0698151", Article.normalize("1J0-698-151"))
        assertEquals("15208AA100", Article.normalize("15208-AA1OO".replace("OO", "O0")))
        assertEquals("102", Article.normalize("1O2"))
    }

    @Test fun parsePhrase() {
        val q = Article.parse("фильтр масляный Манн W 75/3")!!
        assertEquals("W753", q.article)
        assertEquals("MANN-FILTER", q.brand)
        assertEquals("фильтр масляный", q.part)
        assertEquals(PartQuery("OC90", "KNECHT", ""), Article.parse("кнехт ОС 90"))
        assertEquals("W753", Article.parse("W 75 дробь 3")!!.article)
        assertEquals("GDB1330", Article.parse("колодки TRW GDB1330")!!.article)
        assertEquals("TRW", Article.parse("колодки TRW GDB1330")!!.brand)
        assertNull(Article.parse("фильтр на солярис"))
        assertNull(Article.parse("колодки на 15"))
    }

    @Test fun commands() {
        assertEquals("W753", (PartsParser.parse("Проценка Манн W753") as PartsCommand.Price).q.article)
        assertEquals("GDB1330", (PartsParser.parse("где купить колодки TRW GDB1330") as PartsCommand.Price).q.article)
        assertEquals(PartsCommand.AskArticle, PartsParser.parse("проценка"))
        assertNull(PartsParser.parse("где купить колодки на Солярис"))
        assertEquals("W753", (PartsParser.parse("фильтр масляный Манн W753") as PartsCommand.Price).q.article)
        assertTrue((PartsParser.parse("обнови проценку W753") as PartsCommand.Price).refresh)
        assertEquals(PartsCommand.Sores("Солярис 2015"), PartsParser.parse("найди болячку Солярис 2015"))
        assertEquals(PartsCommand.Sores("Kia Rio"), PartsParser.parse("слабые места у Kia Rio"))
        assertNull(PartsParser.parse("напомни завтра заказать фильтр W753"))
        assertNull(PartsParser.parse("напомни завтра в 9 позвонить"))
    }

    @Test fun parsePrices() {
        assertEquals(1234, Html.parsePrice("Цена 1 234 ₽"))
        assertEquals(540, Html.parsePrice("540 руб."))
        assertEquals(12500, Html.parsePrice("12 500,00 р."))
        assertEquals(0, Html.parseDays("В наличии"))
        assertEquals(3, Html.parseDays("2-3 дня"))
        assertEquals(1, Html.parseDays("завтра"))
        assertEquals(">10 шт", Html.parseStock("более 10 шт"))
    }

    private val fake = object : PageFetcher { override suspend fun get(url: String, js: Boolean) = Page(200, "") }

    @Test fun zzapRows() {
        val html = """
            <table>
            <tr class="dxgvHeader"><td>Производитель</td><td>Номер</td><td>Наименование</td><td>Цена</td></tr>
            <tr class="dxgvDataRow"><td>MANN-FILTER</td><td>W 75/3</td><td>Фильтр масляный</td><td class="supplier">Автопитер НН</td><td>в наличии 6 шт</td><td>612 р.</td></tr>
            <tr class="dxgvDataRow"><td>MANN-FILTER</td><td>W753</td><td>Фильтр масляный</td><td class="supplier">ЕМЕКС</td><td>3 дн.</td><td>540 р.</td></tr>
            <tr class="dxgvDataRow"><td>FILTRON</td><td>OP 641</td><td>Фильтр масляный</td><td class="supplier">Склад 2</td><td>1 дн.</td><td>390 р.</td></tr>
            </table>""".trimIndent()
        val offers = ZzapProvider(fake).parseOffers(html, "W753")
        assertEquals(3, offers.size)
        assertTrue(offers.all { it.main && it.site == "ЗетЗап" })
        val t = OfferSort.table(offers)
        assertEquals(listOf(540, 612), t.originals.map { it.price })
        assertEquals("ЕМЕКС", t.originals.first().supplier)
        assertEquals(3, t.originals.first().days)
        // Аналог дешевле оригинала — показываем
        assertEquals("OP641", t.analogs.single().article)
        assertEquals("FILTRON", t.analogs.single().brand)
    }

    @Test fun genericCards() {
        val html = """
            <div class="list">
              <div class="card"><span>Фильтр масляный MANN-FILTER W753</span><b>587 ₽</b><i>Срок 2 дня</i></div>
              <div class="card"><span>Фильтр масляный BOSCH 0451103316</span><b>455 ₽</b><i>Сегодня</i></div>
              <div class="banner">Скидка 10% на всё — 500 ₽ при заказе</div>
            </div>""".trimIndent()
        val offers = TopdetalProvider(fake).parseOffers(html, "W753")
        assertEquals(2, offers.size)
        val own = offers.first { !it.analog }
        assertEquals(587, own.price)
        assertEquals(2, own.days)
        assertTrue(offers.first { it.analog }.article.startsWith("0451103316"))
    }

    @Test fun blocked() {
        assertNotNull(Blocked.reason(Page(200, "<html><body>Подтвердите, что вы не робот <div class=smartcaptcha></div></body></html>")))
        assertNotNull(Blocked.reason(Page(403, "")))
        assertNotNull(Blocked.reason(Page(0, "", "таймаут")))
        assertNull(Blocked.reason(Page(200, "<html><body>Ничего не найдено</body></html>")))
    }

    @Test fun fallbackOrder() = runBlocking {
        val calls = mutableListOf<String>()
        val f = object : PageFetcher {
            override suspend fun get(url: String, js: Boolean): Page {
                synchronized(calls) { calls += url }
                return when {
                    "zzap" in url -> Page(200, "<div>captcha</div>")
                    "topdetal" in url -> Page(200, "<table><tr><td>MANN-FILTER W753 фильтр</td><td>2 дня</td><td>600 ₽</td></tr></table>")
                    else -> Page(200, "<html><body>Ничего не найдено</body></html>")
                }
            }
        }
        val debug = mutableListOf<String>()
        val r = Pricing.run(PartQuery("W753", "MANN-FILTER", ""), Sites.all(f), onDebug = { debug += it.provider.id })
        assertTrue(r.status.first().second.startsWith("сейчас недоступен"))
        assertEquals(1, calls.count { "zzap" in it }) // капча — повторно не ломимся
        assertEquals(600, r.offers.single().price)
        assertEquals(4, debug.size)
        assertTrue(Pricing.speech(r).contains("600"))
        assertFalse(r.offers.single().main)
    }
}

class PartsConflictsTest {
    @Test fun notArticles() {
        assertNull(PartsParser.parse("поменять масло в 15:30"))
        assertNull(PartsParser.parse("колодки Солярис 2017"))
        assertNull(PartsParser.parse("Цена от 1500 руб."))
        assertEquals("0451103316", (PartsParser.parse("проценка 0451103316") as PartsCommand.Price).q.article)
        assertNull(ru.ivan.jarvis.parse.AgentProtocol.goal("закажи фильтр салона"))
        assertEquals("закажи такси до дома", ru.ivan.jarvis.parse.AgentProtocol.goal("закажи такси до дома")?.text)
    }

    @Test fun noisyReplies() {
        val ok = ru.ivan.jarvis.parse.ReplyBrain.drafts("ок", "Друг", null)
        assertFalse(ru.ivan.jarvis.parse.ReplyBrain.worthDrafting(ok, knownClient = false))
        val q = ru.ivan.jarvis.parse.ReplyBrain.drafts("машина готова?", "Петров", null)
        assertTrue(ru.ivan.jarvis.parse.ReplyBrain.worthDrafting(q, knownClient = false))
    }
}

class PartsRefreshTest {
    @Test fun refreshButton() {
        assertEquals(PartsCommand.RefreshLast, PartsParser.parse("Обновить проценку"))
    }
}
