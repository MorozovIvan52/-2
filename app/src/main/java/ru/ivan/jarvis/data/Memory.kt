package ru.ivan.jarvis.data

import android.content.Context
import org.json.JSONArray

/**
 * Долгая память Джарвиса: факты о пользователе и его работе («мой поставщик — Автодок»).
 * Хранится только на телефоне; в ИИ уходит короткой строкой в системном промпте.
 */
class Memory(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("jarvis_memory", Context.MODE_PRIVATE)

    fun all(): List<String> = runCatching {
        val arr = JSONArray(p.getString("facts", "[]"))
        (0 until arr.length()).map { arr.getString(it) }
    }.getOrDefault(emptyList())

    private fun save(list: List<String>) {
        p.edit().putString("facts", JSONArray(list.take(MAX)).toString()).apply()
    }

    fun add(fact: String) {
        val f = fact.trim()
        if (f.isBlank()) return
        save(listOf(f) + all().filter { !it.equals(f, ignoreCase = true) })
    }

    /** Удаляет самый похожий факт; возвращает его или null. */
    fun forget(query: String): String? {
        val list = all()
        val id = ru.ivan.jarvis.parse.TaskMatcher.best(query, list.mapIndexed { i, s -> i.toLong() to s }) ?: return null
        val removed = list[id.toInt()]
        save(list.filterIndexed { i, _ -> i != id.toInt() })
        return removed
    }

    fun remove(fact: String) = save(all().filter { it != fact })

    /** Для промпта: самые свежие факты, не длиннее [maxChars]. */
    fun forPrompt(maxChars: Int = 600): String {
        val sb = StringBuilder()
        for (f in all()) {
            if (sb.length + f.length + 2 > maxChars) break
            if (sb.isNotEmpty()) sb.append("; ")
            sb.append(f)
        }
        return sb.toString()
    }

    companion object { private const val MAX = 40 }
}
