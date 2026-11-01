package ru.ivan.jarvis.parse

/**
 * «Поставь задачу …», «добавь задачу …», «задача: …» — шеф явно ставит задачу.
 * Такая задача создаётся сразу (без вопросов и без ИИ): со сроком — на срок, без срока — на сегодня.
 */
object TaskCreate {
    private val rx = Regex(
        "^(джарвис[\\s,.!]*)?(поставь|поставить|добавь|добавить|создай|создать|заведи|запиши|новая|новую)?\\s*(мне\\s+)?(задач\\p{L}*)(?![\\p{L}])[\\s:,.-]*(.*)$",
        RegexOption.IGNORE_CASE,
    )

    /** Текст задачи (может быть пустым — тогда спросить «какую?»), или null — это не постановка задачи. */
    fun parse(raw: String): String? {
        val m = rx.find(raw.trim().trimEnd('.', '!')) ?: return null
        val verb = m.groupValues[2]
        val word = m.groupValues[4].lowercase()
        // Без глагола — только «задача: …»/«задача …» в начале, не «задачи на завтра?» (это вопрос о делах)
        if (verb.isEmpty() && (word != "задача" || raw.trim().endsWith("?"))) return null
        return m.groupValues[5].trim()
    }

    /** Убрать хвосты разбора даты: «на заказать масло» → «заказать масло». */
    fun cleanTitle(t: String): String = t.trim()
        .replace(Regex("^(на|в|к|до|с)\\s+", RegexOption.IGNORE_CASE), "")
        .replace(Regex("^(задач\\p{L}*)\\s+", RegexOption.IGNORE_CASE), "")
        .trim(' ', ',', ':', '-')
        .replaceFirstChar { it.uppercase() }
}
