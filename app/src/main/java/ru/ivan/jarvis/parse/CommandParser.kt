package ru.ivan.jarvis.parse

import ru.ivan.jarvis.data.ItemType
import ru.ivan.jarvis.data.Repeat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

sealed interface Command {
    /** Сохранить заметку (due == null) или напоминание. */
    data class Save(
        val type: ItemType,
        val title: String,
        val due: LocalDateTime?,
        val allDay: Boolean,
        val repeat: Repeat,
    ) : Command

    /** «Что у меня завтра?» — показать/озвучить дела за период. */
    data class Query(val from: LocalDate, val days: Int, val label: String) : Command
}

/**
 * Разбирает русскую фразу: «напомни завтра в 9 позвонить врачу»,
 * «через 15 минут выключить духовку», «запиши идею для подарка»,
 * «каждый понедельник в 10 планёрка», «что у меня завтра».
 */
object CommandParser {
    private const val L = "(?<![\\p{L}\\d])"
    private const val R = "(?![\\p{L}\\d])"
    private val OPT = setOf(RegexOption.IGNORE_CASE)
    private fun rx(p: String) = Regex(p, OPT)

    val DEFAULT_TIME: LocalTime = LocalTime.of(9, 0)

    private val months = listOf("январ", "феврал", "март", "апрел", "ма", "июн", "июл", "август", "сентябр", "октябр", "ноябр", "декабр")
    private const val MONTH_RX = "(январ[яь]|феврал[яь]|марта?|апрел[яь]|ма[яй]|июн[яь]|июл[яь]|августа?|сентябр[яь]|октябр[яь]|ноябр[яь]|декабр[яь])"

    private val weekdays = linkedMapOf(
        "понедельник" to DayOfWeek.MONDAY,
        "вторник" to DayOfWeek.TUESDAY,
        "сред" to DayOfWeek.WEDNESDAY,
        "четверг" to DayOfWeek.THURSDAY,
        "пятниц" to DayOfWeek.FRIDAY,
        "суббот" to DayOfWeek.SATURDAY,
        "воскресень" to DayOfWeek.SUNDAY,
    )
    private const val WEEKDAY_RX = "(понедельник|вторник|сред[ау]|четверг|пятниц[ау]|суббот[ау]|воскресенье)"

    private val wakeWord = rx("^\\s*(джарвис|jarvis|жарвис)[\\s,.!]*")
    private val query = rx("^\\s*(что|какие|какое|покажи|расскажи|скажи)$R.*(у меня|планы|дела|напоминани|событи|запланирован|расписани)")

    private val repeatWeekday = rx("${L}(каждый|каждую|каждое|по)\\s+$WEEKDAY_RX(?:ам)?$R")
    private val repeatDaily = rx("${L}(каждый день|ежедневно|каждое утро|каждый вечер|каждую ночь|каждый вечер)$R")
    private val repeatWeekly = rx("${L}(каждую неделю|еженедельно|раз в неделю)$R")

    private val relHalfHour = rx("${L}через\\s+полчаса$R")
    private val relHourHalf = rx("${L}через\\s+полтора\\s+часа$R")
    private val relative = rx("${L}через\\s+(?:(\\d+)\\s*)?(минут[уы]?|мин|час(?:а|ов)?|ч|сутки|дн(?:я|ей)|день|недел[юиь]|месяц(?:а|ев)?)$R")

    private val dayWord = rx("${L}(послезавтра|завтра|сегодня)$R")
    private val weekday = rx("${L}(?:(?:в|во)\\s+|на\\s+)?(?:следующ(?:ий|ую|ее)\\s+|эт(?:от|у|о)\\s+)?$WEEKDAY_RX$R")
    private val monthDate = rx("${L}(?:на\\s+)?(\\d{1,2})(?:-?(?:го|е|ое))?\\s+$MONTH_RX(?:\\s+(\\d{4})(?:\\s*(?:года|год|г\\.?))?)?$R")
    private val numericDate = rx("(?<!в\\s)(?<![\\d:.])(\\d{1,2})\\.(\\d{1,2})(?:\\.(\\d{2,4}))?(?![\\d:.])")
    private val dayOfMonth = rx("${L}(\\d{1,2})(?:-?го)?\\s+числа$R")

    private val noon = rx("${L}(?:в|к)\\s+полдень$R")
    private val midnight = rx("${L}(?:в|к)\\s+полночь$R")
    private val halfPast = rx("${L}в\\s+половин[еу]\\s+(\\d{1,2})(?:-?го)?(?:\\s+(утра|дня|вечера|ночи))?$R")
    private val time = rx(
        "${L}(?:в|к)\\s+(\\d{1,2})(?:[:.](\\d{2}))?(?!\\d)(?:\\s*(?:час(?:а|ов)?|ч\\.?)$R)?" +
            "(?:\\s+(\\d{1,2})\\s*минут\\p{L}*)?(?:\\s+(утра|дня|вечера|ночи))?$R"
    )
    private val bareTime = rx("(?<![\\d.])(\\d{1,2}):(\\d{2})(?!\\d)(?:\\s+(утра|дня|вечера|ночи))?$R")
    private val dayPart = rx("${L}(утром|днем|днём|вечером|ночью)$R")

    private val prefixes = rx(
        "^(пожалуйста|напомни(?:те)?|напоминай|поставь|создай|добавь|запиши(?:те)?|запомни(?:те)?|сохрани|" +
            "новую|новое|новая|заметк[ауи]|напоминание|событие|в заметки|в заметку|в календарь|в список|" +
            "мне|о том,? что|о том|что|чтобы|про|о|об|:|,|-|—)$R[\\s,:.\\-—]*"
    )
    private val noteHint = rx("${L}(заметк|запиши|запомни|сохрани|идея|иде[юи])")

    fun parse(raw: String, now: LocalDateTime = LocalDateTime.now()): Command {
        var s = NumberWords.replace(raw.trim()).replace('ё', 'е').replace('Ё', 'Е')
        s = wakeWord.replace(s, "")

        if (query.containsMatchIn(s)) return parseQuery(s, now.toLocalDate())

        val today = now.toLocalDate()
        var date: LocalDate? = null
        var explicitTime: LocalTime? = null
        var exact: LocalDateTime? = null
        var repeat = Repeat.NONE
        var partTime: LocalTime? = null

        fun take(r: Regex): MatchResult? {
            val m = r.find(s) ?: return null
            s = s.removeRange(m.range).let { it.substring(0, m.range.first) + " " + it.substring(m.range.first) }
            return m
        }

        // Повторы
        take(repeatWeekday)?.let { m ->
            repeat = Repeat.WEEKLY
            date = nextWeekday(today, weekdayOf(m.groupValues[2]), allowToday = true)
        }
        if (repeat == Repeat.NONE) take(repeatDaily)?.let { m ->
            repeat = Repeat.DAILY
            when {
                m.value.contains("утро", true) -> partTime = LocalTime.of(9, 0)
                m.value.contains("вечер", true) -> partTime = LocalTime.of(19, 0)
                m.value.contains("ночь", true) -> partTime = LocalTime.of(23, 0)
            }
        }
        if (repeat == Repeat.NONE) take(repeatWeekly)?.let { repeat = Repeat.WEEKLY }

        // Относительное время
        take(relHourHalf)?.let { exact = now.plusMinutes(90) }
        if (exact == null) take(relHalfHour)?.let { exact = now.plusMinutes(30) }
        if (exact == null) take(relative)?.let { m ->
            val n = m.groupValues[1].toLongOrNull() ?: 1L
            val unit = m.groupValues[2].lowercase()
            when {
                unit.startsWith("мин") -> exact = now.plusMinutes(n)
                unit.startsWith("ч") -> exact = now.plusHours(n)
                unit.startsWith("сут") || unit.startsWith("д") -> date = today.plusDays(n)
                unit.startsWith("нед") -> date = today.plusWeeks(n)
                unit.startsWith("мес") -> date = today.plusMonths(n)
            }
        }

        // Дата
        if (exact == null && date == null) {
            take(dayWord)?.let { m ->
                date = when (m.groupValues[1].lowercase()) {
                    "сегодня" -> today
                    "завтра" -> today.plusDays(1)
                    else -> today.plusDays(2)
                }
            }
        }
        if (exact == null && date == null) take(monthDate)?.let { m ->
            val day = m.groupValues[1].toInt()
            val month = monthOf(m.groupValues[2])
            val year = m.groupValues[3].toIntOrNull()
            date = safeDate(year ?: today.year, month, day)?.let {
                if (year == null && it.isBefore(today)) it.plusYears(1) else it
            }
        }
        if (exact == null && date == null) numericDate.find(s)?.let { m ->
            val day = m.groupValues[1].toInt()
            val month = m.groupValues[2].toInt()
            val year = m.groupValues[3].toIntOrNull()?.let { if (it < 100) 2000 + it else it }
            val d = safeDate(year ?: today.year, month, day)
            if (d != null) {
                take(numericDate)
                date = if (year == null && d.isBefore(today)) d.plusYears(1) else d
            }
        }
        if (exact == null && date == null) take(dayOfMonth)?.let { m ->
            val day = m.groupValues[1].toInt()
            var d = safeDate(today.year, today.monthValue, day)
            if (d == null || d.isBefore(today)) d = today.plusMonths(1).let { safeDate(it.year, it.monthValue, day) }
            date = d
        }
        var weekdayDate: DayOfWeek? = null
        if (exact == null && date == null) take(weekday)?.let { m ->
            weekdayDate = weekdayOf(m.groupValues[1])
        }

        // Время
        if (exact == null) {
            take(noon)?.let { explicitTime = LocalTime.NOON }
            if (explicitTime == null) take(midnight)?.let { explicitTime = LocalTime.of(23, 59) }
            if (explicitTime == null) take(halfPast)?.let { m ->
                val h = m.groupValues[1].toInt()
                explicitTime = adjustHour((h - 1).mod(24), 30, m.groupValues[2], halfPast = true)
            }
            if (explicitTime == null) {
                val m = time.find(s)
                if (m != null && (m.groupValues[1].toIntOrNull() ?: 99) in 0..23) {
                    take(time)
                    val h = m.groupValues[1].toInt()
                    val min = (m.groupValues[2].ifEmpty { m.groupValues[3] }).toIntOrNull() ?: 0
                    explicitTime = adjustHour(h, min.coerceIn(0, 59), m.groupValues[4])
                }
            }
            if (explicitTime == null) {
                val m = bareTime.find(s)
                if (m != null && m.groupValues[1].toInt() in 0..23) {
                    take(bareTime)
                    explicitTime = adjustHour(m.groupValues[1].toInt(), m.groupValues[2].toInt().coerceIn(0, 59), m.groupValues[3], bare = true)
                }
            }
            take(dayPart)?.let { m ->
                if (explicitTime == null) {
                    partTime = when (m.groupValues[1].lowercase()) {
                        "утром" -> LocalTime.of(9, 0)
                        "днем" -> LocalTime.of(13, 0)
                        "вечером" -> LocalTime.of(19, 0)
                        else -> LocalTime.of(23, 0)
                    }
                } else if (m.groupValues[1].lowercase() in setOf("вечером", "днем") && explicitTime!!.hour < 12) {
                    explicitTime = explicitTime!!.plusHours(12)
                }
            }
        }

        val t = explicitTime ?: partTime
        weekdayDate?.let { dow ->
            val candidate = nextWeekday(today, dow, allowToday = true)
            date = if (candidate == today && (t == null || !t.isAfter(now.toLocalTime()))) candidate.plusWeeks(1) else candidate
        }

        var allDay = false
        val due: LocalDateTime? = when {
            exact != null -> exact!!.withSecond(0).withNano(0)
            date != null && t != null -> date!!.atTime(t)
            date != null -> {
                if (repeat == Repeat.NONE) allDay = true
                date!!.atTime(DEFAULT_TIME)
            }
            t != null -> today.atTime(t).let { if (it.isAfter(now)) it else it.plusDays(1) }
            repeat != Repeat.NONE -> today.atTime(DEFAULT_TIME).let { if (it.isAfter(now)) it else it.plusDays(1) }
            else -> null
        }

        val type = if (due != null) ItemType.REMINDER else ItemType.NOTE
        val isNote = noteHint.containsMatchIn(raw)
        return Command.Save(type, cleanTitle(s, fallback = if (type == ItemType.NOTE || isNote) "Заметка" else "Напоминание"), due, allDay, repeat)
    }

    private fun parseQuery(s: String, today: LocalDate): Command.Query {
        val lower = s.lowercase()
        return when {
            lower.contains("послезавтра") -> Command.Query(today.plusDays(2), 1, "послезавтра")
            lower.contains("завтра") -> Command.Query(today.plusDays(1), 1, "завтра")
            lower.contains("недел") -> Command.Query(today, 7, "на неделю")
            else -> {
                val wd = weekday.find(s)
                if (wd != null) {
                    val d = nextWeekday(today, weekdayOf(wd.groupValues[1]), allowToday = true)
                    Command.Query(d, 1, wd.value.trim())
                } else Command.Query(today, 1, "сегодня")
            }
        }
    }

    private fun adjustHour(h: Int, min: Int, part: String, halfPast: Boolean = false, bare: Boolean = false): LocalTime {
        var hour = h
        when (part.lowercase()) {
            "утра" -> if (hour == 12) hour = 0
            "дня" -> if (hour in 1..6 || (halfPast && hour == 0)) hour += 12
            "вечера" -> if (hour < 12) hour += 12
            "ночи" -> if (hour in 9..11) hour += 12 else if (hour == 12) hour = 0
            else -> if (!bare && hour in 1..6) hour += 12 // «в 5 позвонить» обычно про 17:00
        }
        return LocalTime.of(hour.mod(24), min)
    }

    private fun weekdayOf(word: String): DayOfWeek {
        val w = word.lowercase()
        return weekdays.entries.first { w.startsWith(it.key) }.value
    }

    private fun monthOf(word: String): Int {
        val w = word.lowercase()
        if (w.startsWith("ма") && !w.startsWith("мар")) return 5
        return months.indexOfFirst { it != "ма" && w.startsWith(it) } + 1
    }

    private fun nextWeekday(today: LocalDate, dow: DayOfWeek, allowToday: Boolean): LocalDate =
        if (allowToday) today.with(TemporalAdjusters.nextOrSame(dow)) else today.with(TemporalAdjusters.next(dow))

    private fun safeDate(y: Int, m: Int, d: Int): LocalDate? =
        runCatching { LocalDate.of(y, m, d) }.getOrNull()

    private fun cleanTitle(text: String, fallback: String): String {
        var s = text.replace(Regex("\\s+"), " ").trim()
        while (true) {
            val m = prefixes.find(s) ?: break
            if (m.value.isEmpty()) break
            s = s.substring(m.range.last + 1).trim()
        }
        s = s.trim(' ', ',', '.', ':', ';', '-', '—', '!', '?')
            .replace(Regex("\\s+([,.!?])"), "$1")
            .replace(Regex("\\s+"), " ")
        if (s.isEmpty()) return fallback
        return s.replaceFirstChar { it.uppercase() }
    }
}
