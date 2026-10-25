package ru.ivan.jarvis.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageParserTest {
    private fun p(s: String) = MessageParser.parse(s)

    @Test fun read() {
        assertEquals(MessageCommand.Read(""), p("Прочитай сообщения"))
        assertEquals(MessageCommand.Read(""), p("прочитай новые сообщения"))
        assertEquals(MessageCommand.Read("Саши"), p("прочитай сообщения от Саши"))
        assertEquals(MessageCommand.Read(""), p("кто мне писал?"))
        assertEquals(MessageCommand.Read("Саша"), p("что пишет Саша"))
        assertEquals(MessageCommand.Read(""), p("есть новые сообщения?"))
    }

    @Test fun reply() {
        assertEquals(MessageCommand.Reply("Саше", "буду через час"), p("ответь Саше: буду через час"))
        assertEquals(MessageCommand.Reply("Саше", "скоро буду"), p("ответь Саше что скоро буду"))
        assertEquals(MessageCommand.Reply("", "ок"), p("ответь: ок"))
        assertEquals(MessageCommand.Reply("", ""), p("ответь"))
    }

    @Test fun notMessages() {
        assertNull(p("напиши маме что я задержусь"))
        assertNull(p("прочитай заметки"))
    }
}
