package ru.ivan.jarvis.parse

/** Команды про входящие сообщения мессенджеров. */
sealed interface MessageCommand {
    /** «прочитай сообщения», «что пишет Саша». from = "" — от всех. */
    data class Read(val from: String) : MessageCommand
    /** «ответь Саше: буду через час». who = "" — последнему написавшему. */
    data class Reply(val who: String, val text: String) : MessageCommand
}

object MessageParser {
    private val OPT = setOf(RegexOption.IGNORE_CASE)
    private fun rx(p: String) = Regex(p, OPT)
    private const val R = "(?![\\p{L}\\d])"

    private val wake = rx("^\\s*(джарвис|jarvis|жарвис)[\\s,.!]*")
    private val read = rx(
        "^(?:прочитай|прочти|зачитай|покажи|какие|есть\\s+ли|есть|новые|что\\s+в)\\s+(?:мне\\s+)?(?:новые\\s+|последние\\s+|непрочитанные\\s+)?" +
            "(?:сообщени\\p{L}*|смс|сообщения|мессенджер\\p{L}*|уведомлени\\p{L}*|ватсап\\p{L}*|телеграм\\p{L}*)(?:\\s+от\\s+(.+))?$"
    )
    private val whoWrote = rx("^(?:кто\\s+(?:мне\\s+)?(?:писал|написал)|что\\s+(?:мне\\s+)?пишут|что\\s+нового\\s+в\\s+сообщениях)$R")
    private val whatSays = rx("^что\\s+(?:мне\\s+)?(?:пишет|написал[аи]?|прислал[аи]?)\\s+(.+)$")
    private val reply = rx("^(?:ответь|ответить|отвечай)$R\\s*(.*)$")

    fun parse(raw: String): MessageCommand? {
        val s = raw.replace(wake, "").trim().trimEnd('.', '!', '?').replace(Regex("\\s+"), " ")
        if (s.isEmpty()) return null
        read.find(s)?.let { return MessageCommand.Read(it.groupValues[1].trim()) }
        if (whoWrote.containsMatchIn(s)) return MessageCommand.Read("")
        whatSays.find(s)?.let { return MessageCommand.Read(it.groupValues[1].trim()) }
        reply.find(s)?.let { m ->
            val rest = m.groupValues[1].trim()
            if (rest.isEmpty()) return MessageCommand.Reply("", "")
            // «ответь: ок», «ответь что скоро буду» — последнему
            Regex("^(?:[:,]\\s*|что\\s+|текст\\s+)(.+)$", OPT).find(rest)?.let { return MessageCommand.Reply("", it.groupValues[1].trim()) }
            // «ответь Саше: ок», «ответь Саше что скоро буду», «ответь Саше ок»
            val m2 = Regex("^(\\p{L}+)\\s*(?:[:,]\\s*|\\s+что\\s+|\\s+)(.*)$", OPT).find(rest) ?: return MessageCommand.Reply(rest, "")
            return MessageCommand.Reply(m2.groupValues[1], m2.groupValues[2].trim())
        }
        return null
    }
}
