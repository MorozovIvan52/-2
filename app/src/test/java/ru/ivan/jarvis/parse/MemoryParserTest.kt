package ru.ivan.jarvis.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MemoryParserTest {
    @Test fun remember() {
        assertEquals(MemoryCommand.Remember("Мой поставщик запчастей — Автодок"), MemoryParser.parse("Джарвис, запомни, что мой поставщик запчастей — Автодок"))
        assertEquals(MemoryCommand.Remember("Сервис работает с 9 до 20"), MemoryParser.parse("учти: сервис работает с 9 до 20"))
        assertEquals(MemoryCommand.Remember("Саша — мастер по ходовой"), MemoryParser.parse("имей в виду, Саша — мастер по ходовой"))
        assertNull(MemoryParser.parse("запомни купить масло"))
    }

    @Test fun forgetAndRecall() {
        assertEquals(MemoryCommand.Forget("Автодок"), MemoryParser.parse("забудь про Автодок"))
        assertNull(MemoryParser.parse("забудь про напоминание о банке"))
        assertEquals(MemoryCommand.Recall, MemoryParser.parse("что ты обо мне знаешь?"))
        assertEquals(MemoryCommand.Recall, MemoryParser.parse("что ты помнишь"))
    }

    @Test fun screenRead() {
        assertEquals(ScreenCommand.Read(""), ScreenParser.parse("что на экране"))
        assertEquals(ScreenCommand.Read("о чем сообщение"), ScreenParser.parse("прочитай экран, о чём сообщение"))
        assertEquals(ScreenCommand.Read(""), ScreenParser.parse("Джарвис, что тут написано?"))
    }
}
