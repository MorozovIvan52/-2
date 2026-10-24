package ru.ivan.jarvis.parse

/**
 * «Поручи Саше поменять масло на Ларгусе» — задание человеку с последующей проверкой.
 * who — как сказали («Саше»), для поиска контакта; name — обращение («Саша»);
 * subj — сказано через «чтобы» («скажи Ивану, чтобы проверил развал»).
 */
data class Delegation(val who: String, val task: String, val name: String = who, val subj: Boolean = false)

/** «Жду ответа от поставщика» — Джарвис сам спросит, пришёл ли ответ. */
data class Waiting(val what: String)

object DelegateParser {
    private val OPT = setOf(RegexOption.IGNORE_CASE)
    private fun rx(p: String) = Regex(p, OPT)
    private const val R = "(?![\\p{L}\\d])"

    private val wake = rx("^\\s*(джарвис|jarvis|жарвис)[\\s,.!]*")
    private val delegate = rx(
        "^(?:поручи|поручить|дай\\s+задание|дай\\s+задачу|скажи|передай|попроси|пусть)$R\\s+" +
            "(?:мастеру\\s+|механику\\s+|сотруднику\\s+)?(\\p{L}+)\\s*(?:[,:]\\s*|\\s+(?:чтобы|чтоб)\\s+|\\s+)(.+)$"
    )
    private val waiting = rx("^(?:я\\s+)?(?:жду|ждем|ожидаю|ожидаем)$R\\s+(.+)$")
    // «скажи мне», «скажи который час» — это не поручение
    private val notPerson = rx("^(мне|нам|будет|будут|это|он|она|они|все|всё|так|тебе|себе|который|сколько|как|что|где|когда|почему|пожалуйста|время|погоду|ему|ей|им)$")

    fun delegation(raw: String): Delegation? {
        val s = raw.replace(wake, "").trim().trimEnd('.', '!', '?')
        val m = delegate.find(s) ?: return null
        val who = m.groupValues[1]
        if (notPerson.matches(who)) return null
        val verb = s.substringBefore(' ').lowercase()
        val task = m.groupValues[2].trim()
        // «скажи/передай Саше что …» — это сообщение, его разбирает ActionParser
        if (verb in setOf("скажи", "передай") && !Regex("(^|\\s)(чтобы|чтоб)\\s", OPT).containsMatchIn(s)) return null
        if (task.isBlank()) return null
        val name = who.replaceFirstChar { it.uppercase() }
        // «попроси Сашу» — винительный падеж
        val nom = if (verb == "попроси") when {
            name.endsWith("у") -> name.dropLast(1) + "а"
            name.endsWith("ю") -> name.dropLast(1) + "я"
            else -> name
        } else if (verb == "пусть") name else nominative(name)
        val subj = Regex("(^|\\s)(чтобы|чтоб)\\s", OPT).containsMatchIn(s)
        return Delegation(name, task.replaceFirstChar { it.lowercase() }, nom, subj)
    }

    fun waiting(raw: String): Waiting? {
        val s = raw.replace(wake, "").trim().trimEnd('.', '!', '?')
        val m = waiting.find(s) ?: return null
        val what = m.groupValues[1].trim()
        // «жду запчасти на Камри» — это статус машины (режим «Автосервис»)
        if (what.isBlank()) return null
        return Waiting(what)
    }

    /** Имя в дательном падеже → именительный для обращения: «Саше» → «Саша», «Ивану» → «Иван». */
    fun nominative(name: String): String {
        val n = name.trim()
        return when {
            n.endsWith("е") && n.length > 3 -> n.dropLast(1) + "а"
            n.endsWith("у") && n.length > 3 -> n.dropLast(1)
            n.endsWith("ю") && n.length > 3 -> n.dropLast(1) + "й"
            else -> n
        }
    }

    /** Текст поручения для SMS. */
    fun message(d: Delegation): String =
        if (d.subj) "${d.name}, нужно, чтобы ты ${d.task}. Напиши, когда сделаешь."
        else "${d.name}, нужно: ${d.task}. Напиши, когда сделаешь."

    private val priority = rx("(^|[\\s,])(срочно|очень\\s+важно|важно|обязательно|приоритет\\p{L}*|горит)(?=$|[\\s,.!])")

    /** Есть ли пометка важности; возвращает фразу без неё. */
    fun priority(text: String): Pair<Boolean, String> {
        if (!priority.containsMatchIn(text)) return false to text
        val clean = text.replace(priority, " ").replace(Regex("\\s+"), " ").trim(' ', ',', '.')
        return true to clean
    }
}
