package ru.ivan.jarvis.parse

import java.time.LocalDateTime

/** Голосовая правка уже записанных дел. query — слова из названия задачи («» — последняя/ближайшая). */
sealed interface TaskEdit {
    data class Done(val query: String) : TaskEdit
    data class Delete(val query: String) : TaskEdit
    data class Move(val query: String, val due: LocalDateTime, val allDay: Boolean) : TaskEdit
    /** «Что я сделал сегодня», «итоги дня». */
    data object Summary : TaskEdit
    /** «Что я записал», «мои заметки». */
    data object Notes : TaskEdit
}

object TaskEditParser {
    private const val R = "(?![\\p{L}\\d])"
    private val OPT = setOf(RegexOption.IGNORE_CASE)
    private fun rx(p: String) = Regex(p, OPT)

    private val wake = rx("^\\s*(джарвис|jarvis|жарвис)[\\s,.!]*")
    private val filler = rx("^(напоминание|напоминалку|задачу|задача|дело|заметку|запись|встречу\\s+)?\\s*(про|о|об|что|с)?\\s+")

    private val done = rx("^(я\\s+)?(сделал[аи]?|сделано|выполнил[аи]?|выполнено|готово|закончил[аи]?|отметь(\\s+как)?\\s+(сделанн\\p{L}*|выполненн\\p{L}*)|отметь)$R\\s*(.*)$")
    private val doneTail = rx("\\s+(как\\s+)?(сделанн\\p{L}*|выполненн\\p{L}*|сделано|выполнено)$")
    private val delete = rx("^(удали|удалить|сотри|стереть|убери|отмени)$R\\s*(.*)$")
    private val move = rx("^(перенеси|перенести|передвинь|сдвинь|отложи|отложить)$R\\s*(.*)$")
    private val summary = rx("^(что\\s+я\\s+(сегодня\\s+)?(сделал\\p{L}*|успел\\p{L}*)(\\s+сегодня)?|итоги?(\\s+дня)?|подведи\\s+итоги?(\\s+дня)?|мой\\s+прогресс|как\\s+мои\\s+успехи)$R")
    private val notes = rx("^(что\\s+я\\s+записал\\p{L}*|мои\\s+заметки|покажи\\s+заметки|прочитай\\s+заметки|какие\\s+(у\\s+меня\\s+)?заметки)$R")

    fun parse(raw: String, now: LocalDateTime = LocalDateTime.now()): TaskEdit? {
        val s = raw.replace(wake, "").trim().trimEnd('.', '!', '?').trim()
        if (s.isEmpty()) return null
        if (summary.containsMatchIn(s)) return TaskEdit.Summary
        if (notes.containsMatchIn(s)) return TaskEdit.Notes

        done.find(s)?.let { m ->
            return TaskEdit.Done(cleanQuery(m.groupValues.last().replace(doneTail, "")))
        }
        // «позвонить врачу сделано»
        doneTail.find(s)?.let { m ->
            return TaskEdit.Done(cleanQuery(s.substring(0, m.range.first)))
        }
        delete.find(s)?.let { m ->
            // «отмени фокус» / «отмени будильник» — не наше
            val rest = m.groupValues[2]
            if (Regex("^(фокус|будильник|таймер)", OPT).containsMatchIn(rest)) return null
            return TaskEdit.Delete(cleanQuery(rest))
        }
        move.find(s)?.let { m -> return parseMove(m.groupValues[1].lowercase(), m.groupValues[2], now) }
        return null
    }

    /** «перенеси встречу на завтра в 10», «отложи на полчаса», «отложи звонок на 20 минут». */
    private fun parseMove(verb: String, rest: String, now: LocalDateTime): TaskEdit? {
        val words = rest.split(Regex("\\s+")).filter { it.isNotBlank() }
        // Ищем самую левую точку, после которой идёт разбираемое время
        for (i in 0..words.size) {
            val task = words.take(i).joinToString(" ")
            var time = words.drop(i).joinToString(" ")
            if (time.isBlank()) continue
            if (verb.startsWith("отлож")) {
                // «на 20 минут» → «через 20 минут»
                time = time.replace(Regex("^на\\s+(?=(\\d+\\s*)?(минут|мин|час|полчаса|полтора|сутки|день|дн|недел))", OPT), "через ")
            }
            time = time.replace(Regex("^на\\s+(?=(завтра|послезавтра|сегодня))", OPT), "")
            val c = CommandParser.parse("напомни $time ЗАДАЧА", now) as? Command.Save ?: continue
            if (c.due == null) continue
            // Время должно «съесть» всю правую часть: заголовок остался только из маркера
            if (!c.title.equals("ЗАДАЧА", ignoreCase = true) && !c.title.equals("задача", ignoreCase = true)) continue
            return TaskEdit.Move(cleanQuery(task), c.due, c.allDay)
        }
        return null
    }

    private fun cleanQuery(q: String): String =
        q.trim().replace(filler, "").trim().trimEnd(',', '.').trim()
}

/** Выбор задачи по словам из фразы: «врачу» найдёт «позвонить врачу». */
object TaskMatcher {
    private fun stems(s: String) = s.lowercase().replace('ё', 'е')
        .split(Regex("[^\\p{L}\\d]+")).filter { it.length >= 3 }
        .map { if (it.length > 5) it.take(it.length - 2) else it.take(4.coerceAtMost(it.length)) }

    /** Возвращает id лучшего совпадения или null. */
    fun best(query: String, items: List<Pair<Long, String>>): Long? {
        val q = stems(query)
        if (q.isEmpty()) return null
        var bestId: Long? = null
        var bestScore = 0.0
        for ((id, title) in items) {
            val t = stems(title)
            if (t.isEmpty()) continue
            val hits = q.count { w -> t.any { it.startsWith(w) || w.startsWith(it) } }
            val score = hits.toDouble() / q.size + hits * 0.01
            if (hits > 0 && score > bestScore) { bestScore = score; bestId = id }
        }
        return if (bestScore >= 0.5) bestId else null
    }
}
