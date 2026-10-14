package ru.ivan.jarvis.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class ActionParserTest {
    private val now = LocalDateTime.of(2026, 10, 14, 21, 15)
    private fun p(s: String) = ActionParser.parse(s, now)

    @Test fun openApp() {
        assertEquals(PhoneAction.OpenApp("Телеграм"), p("Открой Телеграм"))
        assertEquals(PhoneAction.OpenApp("ютуб"), p("Джарвис, запусти ютуб"))
    }

    @Test fun call() {
        assertEquals(PhoneAction.Call("маме"), p("позвони маме"))
        assertEquals(PhoneAction.Call("+79161234567"), p("набери +79161234567"))
    }

    @Test fun callWithTimeIsReminder() {
        assertNull(p("позвони маме завтра в 9"))
    }

    @Test fun sms() {
        assertEquals(PhoneAction.Sms("Саше", "буду через 10 минут"), p("напиши сообщение Саше: буду через 10 минут"))
        assertEquals(PhoneAction.Sms("маме", "я опаздываю"), p("отправь смс маме что я опаздываю"))
    }

    @Test fun alarm() {
        assertEquals(PhoneAction.Alarm(7, 0, ""), p("разбуди меня в 7"))
        assertEquals(PhoneAction.Alarm(6, 30, ""), p("поставь будильник на 6:30"))
        assertEquals(PhoneAction.Alarm(null, 0, ""), p("будильник"))
    }

    @Test fun timer() {
        assertEquals(PhoneAction.Timer(600), p("поставь таймер на 10 минут"))
        assertEquals(PhoneAction.Timer(1800), p("таймер полчаса"))
        assertEquals(PhoneAction.Timer(300), p("засеки пять минут"))
    }

    @Test fun flashlightAndVolume() {
        assertEquals(PhoneAction.Flashlight(true), p("включи фонарик"))
        assertEquals(PhoneAction.Flashlight(false), p("выключи фонарик"))
        assertEquals(PhoneAction.Volume(VolumeChange.UP), p("сделай громче"))
        assertEquals(PhoneAction.Volume(VolumeChange.MUTE), p("выключи звук"))
    }

    @Test fun media() {
        assertEquals(PhoneAction.PlayMusic("Кино"), p("включи музыку Кино"))
        assertEquals(PhoneAction.Media(MediaKey.NEXT), p("следующий трек"))
        assertEquals(PhoneAction.Media(MediaKey.PAUSE), p("пауза"))
    }

    @Test fun navigateSearchSettings() {
        assertEquals(PhoneAction.Navigate("работы"), p("маршрут до работы"))
        assertEquals(PhoneAction.WebSearch("рецепт борща"), p("найди рецепт борща"))
        assertEquals(PhoneAction.Settings(SettingsPanel.WIFI), p("включи вайфай"))
        assertEquals(PhoneAction.Settings(SettingsPanel.BLUETOOTH), p("открой блютуз"))
        assertEquals(PhoneAction.Camera, p("сделай фото"))
    }

    @Test fun notActions() {
        assertNull(p("напомни завтра в 9 позвонить врачу"))
        assertNull(p("запиши идею подарок маме"))
        assertNull(p("купить молоко"))
    }

    @Test fun help() {
        assertEquals(PhoneAction.Help, p("что ты умеешь?"))
    }

    @Test fun adhd() {
        assertEquals(PhoneAction.Focus(25), p("фокус"))
        assertEquals(PhoneAction.Focus(50), p("режим фокуса на 50 минут"))
        assertEquals(PhoneAction.FocusStop, p("стоп фокус"))
        assertEquals(PhoneAction.WhatNow, p("что мне сейчас делать?"))
    }
}

class AppMatcherTest {
    private val apps = listOf(
        "Telegram" to "org.telegram.messenger",
        "YouTube" to "com.google.android.youtube",
        "YouTube Music" to "com.google.android.apps.youtube.music",
        "WhatsApp" to "com.whatsapp",
        "Калькулятор" to "com.miui.calculator",
        "Яндекс Карты" to "ru.yandex.yandexmaps",
    )

    @Test fun matches() {
        assertEquals("org.telegram.messenger", AppMatcher.best("Телеграм", apps))
        assertEquals("com.google.android.youtube", AppMatcher.best("ютуб", apps))
        assertEquals("com.whatsapp", AppMatcher.best("ватсап", apps))
        assertEquals("com.miui.calculator", AppMatcher.best("калькулятор", apps))
        assertEquals("ru.yandex.yandexmaps", AppMatcher.best("яндекс карты", apps))
        assertNull(AppMatcher.best("фотошоп", apps))
    }
}

class AssistantTest {
    @Test fun routing() {
        org.junit.Assert.assertTrue(ru.ivan.jarvis.ai.Assistant.shouldAsk("Как быстро заснуть?"))
        org.junit.Assert.assertTrue(ru.ivan.jarvis.ai.Assistant.shouldAsk("разбей задачу сделать отчёт"))
        org.junit.Assert.assertTrue(ru.ivan.jarvis.ai.Assistant.shouldAsk("не могу начать работать"))
        org.junit.Assert.assertFalse(ru.ivan.jarvis.ai.Assistant.shouldAsk("купить молоко"))
    }

    @Test fun parseAnswer() {
        val p = ru.ivan.jarvis.ai.Assistant.parse("Давай по шагам.\nCMD: запиши шаг 1: открыть файл\n- CMD: напомни через 20 минут сделать перерыв")
        assertEquals("Давай по шагам.", p.speech)
        assertEquals(listOf("запиши шаг 1: открыть файл", "напомни через 20 минут сделать перерыв"), p.commands)
    }
}
