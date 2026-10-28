package ru.ivan.jarvis.parse

/**
 * Маскировка персональных данных перед записью в журнал и экспортом:
 * телефоны → «•••12», госномера → «а•••вс», фамилии клиентов → «П.».
 */
object Privacy {
    private const val L = "(?<![\\p{L}\\d])"
    private const val R = "(?![\\p{L}\\d])"
    private val phone = Regex("$L\\+?\\d[\\d\\s()\\-]{8,}\\d$R")
    private const val PL = "[авекмнорстухabekmhopctyx]"
    private val plate = Regex("$L($PL)\\s?\\d{3}\\s?($PL{2})(\\s?\\d{2,3})?$R", RegexOption.IGNORE_CASE)
    // Фамилия по окончанию (Петров, Иванова, Сидоренко, Кузнецкий) или любое слово после «клиент»
    private val surname = Regex("$L(\\p{Lu}\\p{Ll}+(?:ов|ова|ев|ева|ёв|ёва|ин|ина|ын|ына|ский|ская|цкий|цкая|енко|ук|юк|ян|дзе|швили)(?:а|у|ым|ой|е|ым|ых)?)$R")
    private val afterClient = Regex("(?i)(клиент\\p{L}*\\s+)(\\p{L}+)")

    fun mask(text: String): String {
        var t = phone.replace(text) { m -> "•••" + m.value.filter { it.isDigit() }.takeLast(2) }
        t = plate.replace(t) { m -> m.groupValues[1] + "•••" + m.groupValues[2] }
        t = afterClient.replace(t) { m -> m.groupValues[1] + initial(m.groupValues[2]) }
        // Первое слово фразы распознаватель пишет с заглавной («Магазин…») — его не трогаем
        t = surname.replace(t) { m -> if (m.range.first == 0) m.value else initial(m.value) }
        return t
    }

    private fun initial(word: String) = if (word.endsWith(".")) word else word.first().uppercase() + "."
}
