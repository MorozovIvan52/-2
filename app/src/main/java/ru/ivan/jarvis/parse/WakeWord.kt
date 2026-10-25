package ru.ivan.jarvis.parse

/** Слово-активатор «Джарвис» (с типичными ошибками распознавания). */
object WakeWord {
    private val rx = Regex(
        "^(?:эй|окей|ок|слушай|алло)?[\\s,]*(джарвис|джервис|жарвис|дарвис|джарвиз|джарви|jarvis|чарвис|джавис)[\\s,.!?:]*(.*)$",
        RegexOption.IGNORE_CASE,
    )

    /** null — активатора нет; "" — только «Джарвис»; иначе — команда после него. */
    fun command(heard: String): String? {
        val m = rx.find(heard.trim()) ?: return null
        return m.groupValues[2].trim()
    }
}
