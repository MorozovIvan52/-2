package ru.ivan.jarvis.service

import ru.ivan.jarvis.actions.PhoneController
import ru.ivan.jarvis.parse.OrderStatus
import ru.ivan.jarvis.parse.ServiceCommand
import ru.ivan.jarvis.parse.TaskMatcher
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** Выполняет команды режима «Автосервис» и готовит ответ Джарвиса. */
class ServiceAssistant(private val dao: ServiceDao) {

    /**
     * text — что сказать; task — фраза для обычного разбора (напоминание/задача);
     * sms — предложить отправить клиенту; null-ответ — команда не про сервис, разбирать дальше.
     */
    data class Reply(
        val text: String,
        val task: String? = null,
        val sms: PhoneController.PendingSms? = null,
        val quick: List<String> = emptyList(),
    )

    suspend fun handle(cmd: ServiceCommand): Reply? = when (cmd) {
        is ServiceCommand.NewOrder -> newOrder(cmd)
        is ServiceCommand.SetStatus -> setStatus(cmd)
        is ServiceCommand.Parts -> parts(cmd)
        is ServiceCommand.ListOrders -> list(cmd.status)
        is ServiceCommand.Money -> money(cmd)
        is ServiceCommand.MoneySummary -> summary(cmd.days)
    }

    private suspend fun find(query: String, includeDone: Boolean = false): Order? {
        if (query.isBlank()) return null
        val all = dao.orders()
        val active = all.filter { it.status != OrderStatus.DONE }
        TaskMatcher.best(query, active.map { it.id to it.searchText })?.let { id -> return active.first { it.id == id } }
        if (!includeDone) return null
        return TaskMatcher.best(query, all.map { it.id to it.searchText })?.let { id -> all.first { it.id == id } }
    }

    /** Телефон клиента из заказов — для «напиши Петрову…», если его нет в контактах. */
    fun phoneFor(orders: List<Order>, name: String): Pair<String, String>? {
        val withPhone = orders.filter { it.phone.isNotBlank() }
        val id = TaskMatcher.best(name, withPhone.map { it.id to "${it.client} ${it.car}" }) ?: return null
        val o = withPhone.first { it.id == id }
        return o.client.ifBlank { o.car } to o.phone
    }

    private suspend fun newOrder(c: ServiceCommand.NewOrder): Reply {
        val o = Order(client = c.client, phone = c.phone, car = c.car, plate = c.plate, work = c.work)
        val id = dao.insert(o)
        val saved = o.copy(id = id)
        val work = if (c.work.isNotBlank()) " Работа: ${c.work.lowercase()}." else " Что делаем?"
        val phone = if (c.phone.isBlank()) "" else " Телефон записал."
        return Reply("Принял: ${saved.title}.$work$phone", quick = listOf("${saved.car.ifBlank { saved.client }} в работе", "Что по сервису"))
    }

    private suspend fun setStatus(c: ServiceCommand.SetStatus): Reply? {
        val o = find(c.query, includeDone = c.status == OrderStatus.DONE) ?: return null
        val updated = o.copy(status = c.status, updatedAt = System.currentTimeMillis())
        dao.update(updated)
        return when (c.status) {
            OrderStatus.READY -> if (o.phone.isNotBlank()) {
                val car = listOf("автомобиль", o.car, if (o.plate.isNotBlank()) "(${o.plate})" else "").filter { it.isNotBlank() }.joinToString(" ")
                val text = "Здравствуйте! Ваш $car готов, можно забирать."
                Reply("${o.title}: готов.", sms = PhoneController.PendingSms(o.phone, o.client.ifBlank { "клиенту" }, text, ""))
            } else Reply("${o.title}: готов. Телефона клиента нет, сообщите ему сами.")
            OrderStatus.WAIT_PARTS -> Reply(
                "${o.title}: ждём запчасти. Напомню завтра в 10 проверить поставку.",
                task = "напомни завтра в 10 проверить запчасти: ${o.title}",
            )
            OrderStatus.DONE -> Reply("${o.title}: выдан." + if (o.price > 0) "" else " Сколько заплатили? Скажите «приход … за ${o.car.ifBlank { o.client }}».")
            else -> Reply("${o.title}: ${c.status.label.lowercase()}.")
        }
    }

    private suspend fun parts(c: ServiceCommand.Parts): Reply {
        val o = find(c.query)
        val forWhat = o?.title ?: c.query
        val label = if (forWhat.isBlank()) c.part else "${c.part} для $forWhat"
        if (!c.ordered) return Reply("", task = "заказать $label")
        if (o != null) {
            dao.update(o.copy(status = OrderStatus.WAIT_PARTS, parts = listOf(o.parts, c.part).filter { it.isNotBlank() }.joinToString(", "), updatedAt = System.currentTimeMillis()))
        }
        return Reply("Записал: заказаны $label. Напомню завтра в 10 проверить поставку.", task = "напомни завтра в 10 проверить поставку: $label")
    }

    private suspend fun list(status: OrderStatus?): Reply {
        val active = dao.orders().filter { it.status != OrderStatus.DONE && (status == null || it.status == status) }
        val what = status?.label?.lowercase() ?: "в сервисе"
        if (active.isEmpty()) return Reply(if (status == null) "В сервисе сейчас машин нет." else "Сейчас нет машин со статусом «$what».")
        val lines = active.take(6).joinToString("; ") { o ->
            o.title + (if (status == null) " — ${o.status.label.lowercase()}" else "") + (if (o.work.isNotBlank()) ", ${o.work.lowercase()}" else "")
        }
        val more = if (active.size > 6) " И ещё ${active.size - 6}." else ""
        return Reply("${if (status == null) "В сервисе" else what.replaceFirstChar { it.uppercase() }} ${active.size}: $lines.$more")
    }

    private suspend fun money(c: ServiceCommand.Money): Reply {
        val order = if (c.note.isNotBlank()) find(c.note, includeDone = true) else null
        dao.insert(MoneyEntry(amount = c.amount, income = c.income, note = c.note, orderId = order?.id))
        if (order != null && c.income) dao.update(order.copy(price = order.price + c.amount, updatedAt = System.currentTimeMillis()))
        val (inc, out) = totals(1)
        val kind = if (c.income) "приход" else "расход"
        val note = if (c.note.isNotBlank()) " (${c.note})" else ""
        return Reply("Записал $kind ${rub(c.amount)}$note. Сегодня: приход ${rub(inc)}, расход ${rub(out)}.")
    }

    private suspend fun summary(days: Int): Reply {
        val (inc, out) = totals(days)
        val period = when (days) { 1 -> "Сегодня"; 7 -> "За неделю"; else -> "За месяц" }
        return Reply("$period: приход ${rub(inc)}, расход ${rub(out)}, итого ${rub(inc - out)}.")
    }

    suspend fun totals(days: Int): Pair<Long, Long> {
        val from = LocalDate.now().minusDays((days - 1).toLong()).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val list = dao.moneySince(from)
        return list.filter { it.income }.sumOf { it.amount } to list.filter { !it.income }.sumOf { it.amount }
    }

    companion object {
        fun rub(n: Long): String = String.format(Locale("ru"), "%,d ₽", n)
    }
}
