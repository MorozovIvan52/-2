package ru.ivan.jarvis.parse

/** Превращает числительные словами в цифры: «через пять минут» → «через 5 минут». */
object NumberWords {
    private const val L = "(?<![\\p{L}\\d])"
    private const val R = "(?![\\p{L}\\d])"
    private val OPT = setOf(RegexOption.IGNORE_CASE)

    private val units = mapOf(
        "один" to 1, "одна" to 1, "одну" to 1, "одного" to 1,
        "два" to 2, "две" to 2, "двух" to 2,
        "три" to 3, "трех" to 3, "трёх" to 3,
        "четыре" to 4, "четырех" to 4, "четырёх" to 4,
        "пять" to 5, "пяти" to 5, "шесть" to 6, "шести" to 6,
        "семь" to 7, "семи" to 7, "восемь" to 8, "восьми" to 8,
        "девять" to 9, "девяти" to 9,
    )
    private val teens = mapOf(
        "десять" to 10, "одиннадцать" to 11, "двенадцать" to 12, "тринадцать" to 13,
        "четырнадцать" to 14, "пятнадцать" to 15, "шестнадцать" to 16,
        "семнадцать" to 17, "восемнадцать" to 18, "девятнадцать" to 19,
    )
    private val tens = mapOf("двадцать" to 20, "тридцать" to 30, "сорок" to 40, "пятьдесят" to 50)

    private val ordUnits = mapOf(
        "первого" to 1, "второго" to 2, "третьего" to 3, "четвертого" to 4, "четвёртого" to 4,
        "пятого" to 5, "шестого" to 6, "седьмого" to 7, "восьмого" to 8, "девятого" to 9,
    )
    private val ordTeens = mapOf(
        "десятого" to 10, "одиннадцатого" to 11, "двенадцатого" to 12, "тринадцатого" to 13,
        "четырнадцатого" to 14, "пятнадцатого" to 15, "шестнадцатого" to 16,
        "семнадцатого" to 17, "восемнадцатого" to 18, "девятнадцатого" to 19,
        "двадцатого" to 20, "тридцатого" to 30,
    )

    private fun alt(keys: Collection<String>) = keys.sortedByDescending { it.length }.joinToString("|")

    private val compound = Regex("$L(${alt(tens.keys)})\\s+(${alt(units.keys)})$R", OPT)
    private val ordCompound = Regex("$L(двадцать|тридцать)\\s+(${alt(ordUnits.keys)})$R", OPT)
    private val single = Regex("$L(${alt(units.keys + teens.keys + tens.keys + ordUnits.keys + ordTeens.keys)})$R", OPT)
    private val hourWord = Regex("$L(в|к|на)\\s+час$R", OPT)

    fun replace(text: String): String {
        var s = text
        s = ordCompound.replace(s) { m ->
            (tens.getValue(m.groupValues[1].lowercase()) + ordUnits.getValue(m.groupValues[2].lowercase().replace('ё', 'е'))).toString()
        }
        s = compound.replace(s) { m ->
            (tens.getValue(m.groupValues[1].lowercase()) + units.getValue(m.groupValues[2].lowercase())).toString()
        }
        s = single.replace(s) { m ->
            val w = m.value.lowercase()
            (units[w] ?: teens[w] ?: tens[w] ?: ordUnits[w] ?: ordTeens[w] ?: return@replace m.value).toString()
        }
        // «в час дня» → «в 1 час дня»
        s = hourWord.replace(s) { m -> "${m.groupValues[1]} 1 час" }
        return s
    }
}
