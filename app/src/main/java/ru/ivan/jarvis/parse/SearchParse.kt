package ru.ivan.jarvis.parse

/** Один результат поиска. */
data class SearchHit(val title: String, val url: String, val snippet: String) {
    val domain: String get() = url.substringAfter("://").substringBefore('/').removePrefix("www.")
}

/** Разбор ответов поисковиков и решение, нужен ли интернет. Без Android — покрыто тестами. */
object SearchParse {
    private val OPT = setOf(RegexOption.IGNORE_CASE)

    /** Вопросы, где нужны свежие данные из сети, а не память ИИ. */
    private val webWords = Regex(
        "(цен[аыу]|стоит|стоимост|почём|почем|купить|заказать|где\\s+(купить|найти|находится|взять)|адрес|телефон\\s+(сервиса|магазина)|" +
            "часы\\s+работы|работает\\s+ли|расписани|погод|курс\\s+(доллара|евро|юаня|валют)|новост|сегодня\\s+в\\s+мире|отзыв|рейтинг|" +
            "момент\\s+затяжки|затяжк|объ[её]м\\s+(масла|антифриза|жидкости)|сколько\\s+(масла|литров)|допуск|артикул|каталожн|" +
            "код\\s+ошибки|ошибк[аи]\\s+[pбcu]\\d{3,4}|\\b[pcbu]\\d{4}\\b|vin|вин\\s*код|аналог|оригинал|подходит\\s+ли|" +
            "регламент\\s+то|интервал\\s+замены|какое\\s+масло|какой\\s+антифриз|какие\\s+(свечи|колодки|шины))",
        OPT,
    )

    fun needsWeb(text: String): Boolean = webWords.containsMatchIn(text)

    /** Чистит вопрос для поисковика: убирает «джарвис», «найди», «в интернете». */
    fun cleanQuery(text: String): String = text.trim()
        .replace(Regex("^(джарвис|jarvis)[\\s,.!]*", OPT), "")
        .replace(Regex("^(найди|поищи|загугли|погугли|посмотри|узнай|подскажи)\\s+(мне\\s+)?(в\\s+интернете\\s+|в\\s+яндексе\\s+|в\\s+гугле\\s+)?", OPT), "")
        .replace(Regex("\\s+в\\s+интернете$", OPT), "")
        .trim().trimEnd('?', '.', '!')

    private fun stripTags(s: String) = s.replace(Regex("<[^>]+>"), "")
        .replace("&quot;", "\"").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&#39;", "'").replace("&nbsp;", " ").replace(Regex("&#x?[0-9a-f]+;", OPT), " ")
        .replace(Regex("\\s+"), " ").trim()

    /** XML Yandex Search API (v2, FORMAT_XML). */
    fun parseYandexXml(xml: String, limit: Int = 5): List<SearchHit> {
        val out = mutableListOf<SearchHit>()
        Regex("<doc\\b[^>]*>(.*?)</doc>", setOf(RegexOption.DOT_MATCHES_ALL)).findAll(xml).forEach { m ->
            if (out.size >= limit) return@forEach
            val doc = m.groupValues[1]
            val url = Regex("<url>(.*?)</url>", setOf(RegexOption.DOT_MATCHES_ALL)).find(doc)?.groupValues?.get(1)?.trim() ?: return@forEach
            val title = Regex("<title>(.*?)</title>", setOf(RegexOption.DOT_MATCHES_ALL)).find(doc)?.groupValues?.get(1)?.let(::stripTags).orEmpty()
            val passages = Regex("<passage>(.*?)</passage>", setOf(RegexOption.DOT_MATCHES_ALL)).findAll(doc).map { stripTags(it.groupValues[1]) }.toList()
            val headline = Regex("<headline>(.*?)</headline>", setOf(RegexOption.DOT_MATCHES_ALL)).find(doc)?.groupValues?.get(1)?.let(::stripTags).orEmpty()
            val snippet = (passages.ifEmpty { listOf(headline) }).joinToString(" … ").take(400)
            out += SearchHit(title.ifBlank { url }, stripTags(url), snippet)
        }
        return out
    }

    /** HTML-версия DuckDuckGo (html.duckduckgo.com/html) — запасной вариант без ключа. */
    fun parseDdgHtml(html: String, limit: Int = 5): List<SearchHit> {
        val out = mutableListOf<SearchHit>()
        val blocks = html.split("class=\"result__body\"").drop(1)
        for (b in blocks) {
            if (out.size >= limit) break
            val a = Regex("class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", setOf(RegexOption.DOT_MATCHES_ALL)).find(b) ?: continue
            var url = a.groupValues[1].replace("&amp;", "&")
            // Ссылки вида //duckduckgo.com/l/?uddg=<url>
            Regex("[?&]uddg=([^&]+)").find(url)?.let { url = java.net.URLDecoder.decode(it.groupValues[1], "UTF-8") }
            if (url.startsWith("//")) url = "https:$url"
            if (url.contains("duckduckgo.com/y.js")) continue // реклама
            val snippet = Regex("class=\"result__snippet\"[^>]*>(.*?)</a>", setOf(RegexOption.DOT_MATCHES_ALL)).find(b)?.groupValues?.get(1)?.let(::stripTags).orEmpty()
            out += SearchHit(stripTags(a.groupValues[2]), url, snippet.take(400))
        }
        return out
    }

    /** Выдержки для ИИ: коротко, чтобы экономить токены. */
    fun digest(hits: List<SearchHit>, maxChars: Int = 1800): String {
        val sb = StringBuilder()
        hits.forEachIndexed { i, h ->
            val line = "[${i + 1}] ${h.domain}: ${h.title}. ${h.snippet}\n"
            if (sb.length + line.length > maxChars) return sb.toString()
            sb.append(line)
        }
        return sb.toString()
    }

    fun cacheKey(query: String): String = query.lowercase().replace('ё', 'е').replace(Regex("[^\\p{L}\\d]+"), " ").trim()
}
