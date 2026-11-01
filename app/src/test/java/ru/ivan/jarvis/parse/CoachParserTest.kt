package ru.ivan.jarvis.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoachParserTest {
    private fun p(s: String, active: Boolean = false) = CoachParser.parse(s, active)

    @Test fun breakdown() {
        assertEquals(CoachCommand.Breakdown("уборку гаража"), p("Разбей уборку гаража на шаги"))
        assertEquals(CoachCommand.Breakdown("отчёт для налоговой"), p("Джарвис, разбей задачу отчёт для налоговой на маленькие шаги"))
        assertEquals(CoachCommand.Breakdown("ремонт Соляриса"), p("разложи ремонт Соляриса"))
        assertEquals(CoachCommand.Breakdown("отчёта"), p("с чего начать отчёта?"))
        assertEquals(CoachCommand.Breakdown("сделать сайт"), p("помоги начать сделать сайт"))
        assertEquals(CoachCommand.Breakdown(""), p("разбей на шаги"))
        assertEquals(CoachCommand.Breakdown(""), p("с чего начать"))
    }

    @Test fun steps() {
        assertEquals(CoachCommand.StepDone, p("сделал шаг"))
        assertEquals(CoachCommand.StepDone, p("Следующий шаг"))
        assertEquals(CoachCommand.StepDone, p("шаг готов"))
        // «дальше» и «готово» — про шаг только когда идёт разбор по шагам
        assertEquals(CoachCommand.StepDone, p("дальше", active = true))
        assertEquals(CoachCommand.StepDone, p("Готово, дальше", active = true))
        assertNull(p("дальше"))
        assertNull(p("готово"))
        assertEquals(CoachCommand.StepWhere, p("какой шаг"))
        assertEquals(CoachCommand.StepsAll, p("все шаги"))
        assertEquals(CoachCommand.StepsStop, p("хватит шагов"))
    }

    @Test fun dump() {
        assertEquals(CoachCommand.Dump(""), p("выгрузка мыслей"))
        assertEquals(CoachCommand.Dump("купить масло, позвонить Пете"), p("Выгрузка: купить масло, позвонить Пете"))
        assertEquals(CoachCommand.Dump("купить хлеб"), p("в голове купить хлеб"))
        assertEquals(CoachCommand.Dump(""), p("разгрузи голову"))
    }

    @Test fun unload() {
        assertEquals(CoachCommand.Unload(false), p("перенеси всё несделанное на завтра"))
        assertEquals(CoachCommand.Unload(false), p("перенеси все просроченное"))
        assertEquals(CoachCommand.Unload(true), p("перенеси всё оставшееся на завтра"))
        assertEquals(CoachCommand.Unload(true), p("разгрузи день"))
        assertEquals(CoachCommand.Unload(true), p("начнём день заново"))
        // обычный перенос одной задачи — не наш
        assertNull(p("перенеси встречу на завтра"))
    }

    @Test fun stuck() {
        assertEquals(CoachCommand.Stuck(""), p("не могу начать"))
        assertEquals(CoachCommand.Stuck("отчёт"), p("Я не могу заставить себя отчёт"))
        assertEquals(CoachCommand.Stuck(""), p("прокрастинирую"))
        assertEquals(CoachCommand.Stuck(""), p("залипла"))
    }

    @Test fun notOurs() {
        assertNull(p("напомни завтра в 9 позвонить"))
        assertNull(p("фокус 25 минут"))
        assertNull(p("сделал позвонить врачу"))
        assertNull(p("что мне сейчас делать"))
    }

    @Test fun parseSteps() {
        val ai = """
            Вот шаги:
            1. Открой ноутбук и файл отчёта (2 мин)
            2) Выпиши цифры за месяц (10 мин)
            - Шаг 3: Заполни таблицу (15 мин)
            **4.** Отправь бухгалтеру (2 мин)
        """.trimIndent()
        assertEquals(
            listOf("Открой ноутбук и файл отчёта (2 мин)", "Выпиши цифры за месяц (10 мин)", "Заполни таблицу (15 мин)", "Отправь бухгалтеру (2 мин)"),
            CoachParser.parseSteps(ai),
        )
        assertEquals(5, CoachParser.fallbackSteps("отчёт").size)
    }

    @Test fun splitDump() {
        val parts = CoachParser.splitDump("купить масло, позвонить Пете завтра в 10 и ещё забрать колодки. Идея: сделать скидку на шиномонтаж")
        assertEquals(listOf("купить масло", "позвонить Пете завтра в 10", "забрать колодки", "Идея: сделать скидку на шиномонтаж"), parts)
        assertTrue(CoachParser.splitDump("а, и, ну").isEmpty())
    }

    @Test fun stepPlan() {
        val plan = StepPlan("отчёт", listOf("открыть", "написать", "отправить"), 0, 42)
        val back = StepPlan.decode(plan.encode())!!
        assertEquals(plan, back)
        assertEquals("шаг 2 из 3", back.next().progress)
        assertTrue(back.next().next().next().finished)
        assertNull(StepPlan.decode(""))
        assertEquals("1 ч 20 мин", minutesLabel(80))
        assertEquals("45 мин", minutesLabel(45))
    }
}
