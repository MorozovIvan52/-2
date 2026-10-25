package ru.ivan.jarvis.data

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import ru.ivan.jarvis.JarvisApp
import ru.ivan.jarvis.parse.OrderStatus
import ru.ivan.jarvis.reminders.ReminderScheduler
import ru.ivan.jarvis.service.MoneyEntry
import ru.ivan.jarvis.service.Order
import ru.ivan.jarvis.service.ServiceDb

/** Резервная копия всех данных в один JSON-файл и восстановление из него. */
object Backup {
    suspend fun export(context: Context, uri: Uri): Int {
        val items = (context.applicationContext as JarvisApp).db.items().all()
        val sdao = ServiceDb.get(context).dao()
        val orders = sdao.orders()
        val money = sdao.moneySince(0)
        val root = JSONObject().put("app", "jarvis").put("version", 1).put("at", System.currentTimeMillis())
            .put("items", JSONArray().apply {
                items.forEach {
                    put(JSONObject().put("type", it.type.name).put("title", it.title).put("source", it.source).put("createdAt", it.createdAt)
                        .put("dueAt", it.dueAt ?: JSONObject.NULL).put("allDay", it.allDay).put("repeat", it.repeat.name)
                        .put("done", it.done).put("priority", it.priority))
                }
            })
            .put("orders", JSONArray().apply {
                orders.forEach {
                    put(JSONObject().put("client", it.client).put("phone", it.phone).put("car", it.car).put("plate", it.plate)
                        .put("work", it.work).put("parts", it.parts).put("status", it.status.name).put("price", it.price)
                        .put("createdAt", it.createdAt).put("updatedAt", it.updatedAt))
                }
            })
            .put("money", JSONArray().apply {
                money.forEach { put(JSONObject().put("amount", it.amount).put("income", it.income).put("note", it.note).put("at", it.at)) }
            })
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(root.toString(1).toByteArray()) } ?: return -1
        return items.size + orders.size + money.size
    }

    /** Добавляет записи из копии (дубликаты по названию и времени пропускаются). */
    suspend fun import(context: Context, uri: Uri): Int {
        val text = context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() } ?: return -1
        val root = JSONObject(text)
        if (root.optString("app") != "jarvis") return -1
        val dao = (context.applicationContext as JarvisApp).db.items()
        val existing = dao.all().map { it.title to it.dueAt }.toSet()
        var n = 0
        root.optJSONArray("items")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val due = if (o.isNull("dueAt")) null else o.getLong("dueAt")
                if ((o.getString("title") to due) in existing) continue
                val item = Item(
                    type = runCatching { ItemType.valueOf(o.getString("type")) }.getOrDefault(ItemType.NOTE),
                    title = o.getString("title"), source = o.optString("source"), createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                    dueAt = due, allDay = o.optBoolean("allDay"), repeat = runCatching { Repeat.valueOf(o.optString("repeat")) }.getOrDefault(Repeat.NONE),
                    done = o.optBoolean("done"), priority = o.optInt("priority"),
                )
                val saved = item.copy(id = dao.insert(item))
                ReminderScheduler.schedule(context, saved)
                n++
            }
        }
        val sdao = ServiceDb.get(context).dao()
        val haveOrders = sdao.orders().map { it.createdAt }.toSet()
        root.optJSONArray("orders")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optLong("createdAt") in haveOrders) continue
                sdao.insert(Order(
                    client = o.optString("client"), phone = o.optString("phone"), car = o.optString("car"), plate = o.optString("plate"),
                    work = o.optString("work"), parts = o.optString("parts"),
                    status = runCatching { OrderStatus.valueOf(o.optString("status")) }.getOrDefault(OrderStatus.ACCEPTED),
                    price = o.optLong("price"), createdAt = o.optLong("createdAt"), updatedAt = o.optLong("updatedAt"),
                ))
                n++
            }
        }
        val haveMoney = sdao.moneySince(0).map { it.at }.toSet()
        root.optJSONArray("money")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optLong("at") in haveMoney) continue
                sdao.insert(MoneyEntry(amount = o.optLong("amount"), income = o.optBoolean("income"), note = o.optString("note"), at = o.optLong("at")))
                n++
            }
        }
        return n
    }
}
