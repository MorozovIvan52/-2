package ru.ivan.jarvis.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenParserTest {
    private fun g(s: String) = (ScreenParser.parse(s) as? ScreenCommand.Global)?.action

    @Test fun globals() {
        assertEquals(ScreenGlobal.BACK, g("назад"))
        assertEquals(ScreenGlobal.BACK, g("Джарвис, вернись назад"))
        assertEquals(ScreenGlobal.HOME, g("на главный экран"))
        assertEquals(ScreenGlobal.HOME, g("сверни всё"))
        assertEquals(ScreenGlobal.RECENTS, g("покажи недавние приложения"))
        assertEquals(ScreenGlobal.NOTIFICATIONS, g("открой шторку"))
        assertEquals(ScreenGlobal.QUICK_SETTINGS, g("быстрые настройки"))
        assertEquals(ScreenGlobal.LOCK, g("заблокируй экран"))
        assertEquals(ScreenGlobal.SCREENSHOT, g("сделай скриншот"))
    }

    @Test fun clickAndScroll() {
        assertEquals(ScreenCommand.Click("Отправить"), ScreenParser.parse("нажми на кнопку «Отправить»"))
        assertEquals(ScreenCommand.Click("принять"), ScreenParser.parse("нажми принять"))
        assertEquals(ScreenCommand.Scroll(true), ScreenParser.parse("прокрути вниз"))
        assertEquals(ScreenCommand.Scroll(false), ScreenParser.parse("листай вверх"))
    }

    @Test fun notOurs() {
        assertNull(ScreenParser.parse("напомни завтра позвонить"))
        assertNull(ScreenParser.parse("открой ватсап"))
        assertNull(ScreenParser.parse("поехали домой"))
        assertNull(ScreenParser.parse("прочитай сообщения"))
    }
}
