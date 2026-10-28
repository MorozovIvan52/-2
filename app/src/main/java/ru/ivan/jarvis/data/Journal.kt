package ru.ivan.jarvis.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Журнал «что сказал → что ответил Джарвис» (последние 100) и пометка «неправильно понял». */
class Journal(context: Context) {
    data class Entry(val time: Long, val said: String, val reply: String, val wrong: Boolean)

    private val p = context.applicationContext.getSharedPreferences("jarvis_journal", Context.MODE_PRIVATE)

    fun all(): List<Entry> = runCatching {
        val arr = JSONArray(p.getString("list", "[]"))
        (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            Entry(it.getLong("t"), it.getString("s"), it.getString("r"), it.optBoolean("w"))
        }
    }.getOrDefault(emptyList())

    private fun save(list: List<Entry>) {
        val arr = JSONArray()
        list.take(100).forEach { arr.put(JSONObject().put("t", it.time).put("s", it.said).put("r", it.reply).put("w", it.wrong)) }
        p.edit().putString("list", arr.toString()).apply()
    }

    // Телефоны, госномера и фамилии клиентов в журнал не попадают в открытом виде
    fun add(said: String, reply: String) = save(listOf(
        Entry(System.currentTimeMillis(), ru.ivan.jarvis.parse.Privacy.mask(said), ru.ivan.jarvis.parse.Privacy.mask(reply.take(300)), false)
    ) + all())

    fun markLastWrong(): Entry? {
        val list = all().toMutableList()
        val first = list.firstOrNull() ?: return null
        list[0] = first.copy(wrong = true)
        save(list)
        return first
    }

    /** Текст для копирования разработчику. */
    fun export(): String = all().joinToString("\n") { e ->
        val t = java.time.Instant.ofEpochMilli(e.time).atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm"))
        // mask повторно — для записей, сделанных до версии 10
        (if (e.wrong) "❌ " else "") + "$t «${ru.ivan.jarvis.parse.Privacy.mask(e.said)}» → ${ru.ivan.jarvis.parse.Privacy.mask(e.reply)}"
    }
}
