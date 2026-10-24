package ru.ivan.jarvis.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServiceParserTest {
    private fun p(s: String) = ServiceParser.parse(s)

    @Test fun newOrder() {
        assertEquals(
            ServiceCommand.NewOrder("Петров", "Солярис", "А123ВС", "Замена колодок", ""),
            p("новый клиент Петров, Солярис, номер а123вс, замена колодок"),
        )
        val o = p("Джарвис, принял Логан Иванова на ТО, телефон 8 916 123 45 67") as ServiceCommand.NewOrder
        assertEquals("Логан", o.car)
        assertEquals("Иванова", o.client)
        assertEquals("ТО", o.work)
        assertEquals("89161234567", o.phone)
        val c = p("заехала Камри а 777 ко 197 стук в подвеске") as ServiceCommand.NewOrder
        assertEquals("Камри", c.car)
        assertEquals("А777КО197", c.plate)
        assertEquals("Стук в подвеске", c.work)
    }

    @Test fun status() {
        assertEquals(ServiceCommand.SetStatus("Солярис Петрова", OrderStatus.READY), p("Солярис Петрова готов"))
        assertEquals(ServiceCommand.SetStatus("Петрова", OrderStatus.READY), p("машина Петрова готова"))
        assertEquals(ServiceCommand.SetStatus("Камри", OrderStatus.WAIT_PARTS), p("Камри ждёт запчасти"))
        assertEquals(ServiceCommand.SetStatus("Логан", OrderStatus.IN_WORK), p("Логан в работе"))
        assertEquals(ServiceCommand.SetStatus("Солярис", OrderStatus.DONE), p("выдал Солярис"))
        assertNull(p("я готов"))
        assertNull(p("готово"))
    }

    @Test fun parts() {
        assertEquals(ServiceCommand.Parts("колодки", "Солярис", true), p("заказал колодки на Солярис"))
        assertEquals(ServiceCommand.Parts("фильтр салона", "", false), p("закажи фильтр салона"))
    }

    @Test fun lists() {
        assertEquals(ServiceCommand.ListOrders(null), p("что по сервису"))
        assertEquals(ServiceCommand.ListOrders(OrderStatus.IN_WORK), p("что в работе"))
        assertEquals(ServiceCommand.ListOrders(OrderStatus.WAIT_PARTS), p("какие машины ждут запчасти"))
        assertNull(p("что у меня завтра"))
    }

    @Test fun money() {
        assertEquals(ServiceCommand.Money(15000, true, "Петрова"), p("приход 15 тысяч от Петрова"))
        assertEquals(ServiceCommand.Money(3200, false, "Запчасти"), p("расход 3200 на запчасти"))
        assertEquals(ServiceCommand.Money(1500, false, "Бензин"), p("потратил полторы тысячи на бензин"))
        assertEquals(ServiceCommand.Money(8000, true, "Солярис"), p("получил 8000 рублей за Солярис"))
        assertNull(p("купил хлеб"))
        assertEquals(ServiceCommand.MoneySummary(7), p("касса за неделю"))
        assertEquals(ServiceCommand.MoneySummary(1), p("сколько заработал сегодня"))
    }
}
