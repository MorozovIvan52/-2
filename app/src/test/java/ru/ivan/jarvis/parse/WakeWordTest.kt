package ru.ivan.jarvis.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WakeWordTest {
    @Test fun wake() {
        assertEquals("что мне сейчас делать", WakeWord.command("Джарвис, что мне сейчас делать"))
        assertEquals("", WakeWord.command("Джарвис"))
        assertEquals("открой ютуб", WakeWord.command("эй жарвис открой ютуб"))
        assertNull(WakeWord.command("привет как дела"))
        assertNull(WakeWord.command("мы говорили про джарвиса"))
    }
}
