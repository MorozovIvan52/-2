package ru.ivan.jarvis.ai

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Решает, когда звать ИИ, и разбирает его ответ.
 * Экономия токенов: короткий системный промпт, лимит ответа, без длинной истории,
 * ИИ вызывается только для вопросов и просьб, которые не поняли локальные разборщики.
 */
object Assistant {
    private val OPT = setOf(RegexOption.IGNORE_CASE)

    private val askAi = Regex(
        "^(как|что|чем|почему|зачем|сколько|где|когда|какой|какая|какое|какие|кто|можно ли|стоит ли|" +
            "посоветуй|помоги|расскажи|объясни|разбей|раздели|придумай|составь|подскажи|переведи|сформулируй|" +
            "напиши\\s+(текст|письмо|пост|ответ)|спроси|скажи|мне\\s+(лень|скучно|плохо|тяжело|грустно)|не\\s+могу|" +
            "с\\s+чего\\s+начать|я\\s+не\\s+знаю|я\\s+(устал|запутался|отвлекаюсь|прокрастинирую))(?![\\p{L}])",
        OPT,
    )

    /** Стоит ли отправлять фразу ИИ (а не сохранять как заметку). */
    fun shouldAsk(text: String): Boolean {
        val t = text.trim().replace(Regex("^(джарвис|jarvis)[\\s,.!]*", OPT), "")
        return t.endsWith("?") || askAi.containsMatchIn(t)
    }

    fun systemPrompt(now: LocalDateTime = LocalDateTime.now()): String {
        val date = now.format(DateTimeFormatter.ofPattern("EEEE d MMMM, HH:mm", Locale("ru")))
        return """Ты Джарвис, голосовой помощник человека с СДВГ. Сейчас $date.
Отвечай по-русски, 1-3 коротких предложения, без markdown и эмодзи. Поддерживай, будь конкретен.
Если нужно записать или напомнить, добавь отдельные строки:
CMD: напомни <когда> <что>
CMD: запиши <текст>
Когда пиши как человек: «завтра в 9», «через 20 минут».
Если просят разбить задачу, дай до 5 маленьких шагов строками CMD: запиши шаг N: <шаг>."""
    }

    data class Parsed(val speech: String, val commands: List<String>)

    fun parse(answer: String): Parsed {
        val commands = mutableListOf<String>()
        val speech = mutableListOf<String>()
        answer.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            val m = Regex("^[*\\-\\s]*CMD\\s*:\\s*(.+)$", OPT).find(line)
            if (m != null) commands += m.groupValues[1].trim() else speech += line.replace(Regex("[*#_`]"), "")
        }
        return Parsed(speech.joinToString(" ").trim(), commands)
    }
}
