package ru.ivan.jarvis.parts.core

/** Голосовые приказы этапа «проценка». */
sealed interface PartsCommand {
    /** «проценка W753», «где купить фильтр Манн W 75/3», «сколько стоит OC90». */
    data class Price(val q: PartQuery, val refresh: Boolean = false) : PartsCommand
    /** «проценка» без артикула — переспросить. */
    data object AskArticle : PartsCommand
    /** «найди болячку Солярис 2015» → форумы, Drive2, Drom. */
    data class Sores(val model: String) : PartsCommand
}

object PartsParser {
    private val OPT = setOf(RegexOption.IGNORE_CASE)
    private val wake = Regex("^\\s*(джарвис|jarvis|жарвис)[\\s,.!]*", OPT)
    private val price = Regex(
        "^(проценк\\p{L}*|процени|проценить|оцени\\s+цену|сколько\\s+стоит|почём|почем|цена\\s+на|цены\\s+на|цена|где\\s+купить|где\\s+взять|найди\\s+запчаст\\p{L}*|найди\\s+артикул|артикул)(?![\\p{L}])[\\s:,]*(.*)$",
        OPT,
    )
    private val refresh = Regex("^(обнови(ть)?\\s+проценк\\p{L}*|проценк\\p{L}*\\s+заново|обнови\\s+цены)(?![\\p{L}])[\\s:,]*(.*)$", OPT)
    private val sores = Regex("^(найди\\s+)?(болячк\\p{L}*|слабые\\s+места|типичные\\s+(проблемы|неисправности|поломки))(\\s+(у|на|для))?\\s+(.{2,})$", OPT)

    fun parse(raw: String): PartsCommand? {
        val s = raw.replace(wake, "").trim().trimEnd('.', '!', '?').trim()
        if (s.isEmpty()) return null
        sores.find(s)?.let { return PartsCommand.Sores(it.groupValues.last().trim()) }
        refresh.find(s)?.let { m ->
            val q = Article.parse(m.groupValues.last()) ?: return PartsCommand.AskArticle
            return PartsCommand.Price(q, refresh = true)
        }
        price.find(s)?.let { m ->
            val rest = m.groupValues[2]
            val q = Article.parse(rest)
            val verb = m.groupValues[1].lowercase()
            return when {
                q != null -> PartsCommand.Price(q)
                verb.startsWith("процен") -> PartsCommand.AskArticle
                else -> null // «где купить колодки на Солярис» без артикула — обычный поиск в интернете
            }
        }
        // «фильтр масляный Манн W753» — деталь + артикул без глагола
        if (Article.mentionsPart(s) && s.split(' ').size <= 7) {
            Article.parse(s)?.takeIf { it.article.count(Char::isDigit) >= 2 && !Regex("(напомни|запиши|заказал|поручи|клиент)", OPT).containsMatchIn(s) }
                ?.let { return PartsCommand.Price(it) }
        }
        return null
    }
}
