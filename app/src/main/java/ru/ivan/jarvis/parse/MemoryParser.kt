package ru.ivan.jarvis.parse

/** Долгая память: «запомни, что мой поставщик — Автодок», «что ты обо мне знаешь», «забудь про Автодок». */
sealed interface MemoryCommand {
    data class Remember(val fact: String) : MemoryCommand
    data class Forget(val query: String) : MemoryCommand
    data object Recall : MemoryCommand
}

object MemoryParser {
    private val OPT = setOf(RegexOption.IGNORE_CASE)
    private val wake = Regex("^\\s*(джарвис|jarvis|жарвис)[\\s,.!]*", OPT)
    // «запомни купить масло» — это заметка; факт — только «запомни, что …»
    private val remember = Regex("^(запомни|запомнить|учти|имей\\s+в\\s+виду|знай|заруби\\s+на\\s+носу)[,:]?\\s+(что|:)\\s*(.+)$", OPT)
    private val rememberSoft = Regex("^(учти|имей\\s+в\\s+виду|знай)[,:]?\\s+(.+)$", OPT)
    private val forget = Regex("^(забудь|забыть|удали\\s+из\\s+памяти)[,:]?\\s+(что|про|о|об)?\\s*(.+)$", OPT)
    private val recall = Regex("^(что\\s+ты\\s+(обо\\s+мне\\s+|про\\s+меня\\s+)?(знаешь|помнишь|запомнил)(\\s+обо\\s+мне)?|твоя\\s+память|покажи\\s+память)$", OPT)

    fun parse(raw: String): MemoryCommand? {
        val s = raw.replace(wake, "").trim().trimEnd('.', '!', '?').trim()
        if (recall.matches(s)) return MemoryCommand.Recall
        remember.matchEntire(s)?.let { return MemoryCommand.Remember(clean(it.groupValues[3])) }
        rememberSoft.matchEntire(s)?.let { return MemoryCommand.Remember(clean(it.groupValues[2])) }
        forget.matchEntire(s)?.let { m ->
            val q = m.groupValues[3].trim()
            // «забудь про напоминание о банке» — это удаление дела, не памяти
            if (Regex("^(напоминани|задач|дело|заметк|будильник)", OPT).containsMatchIn(q)) return null
            return MemoryCommand.Forget(q)
        }
        return null
    }

    private fun clean(s: String) = s.trim().trim(',', '.', ' ').replaceFirstChar { it.uppercase() }
}
