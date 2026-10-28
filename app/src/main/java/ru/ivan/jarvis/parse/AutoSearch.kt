package ru.ivan.jarvis.parse

/**
 * Поиск по автоинтернету: расширение жаргона, приоритет профильных источников, названия сайтов.
 * Без Android — покрыто тестами.
 */
object AutoSearch {
    private val OPT = setOf(RegexOption.IGNORE_CASE)

    /** Жаргон → термины, по которым пишут на Drive2 и форумах. Добавляются к запросу, исходные слова остаются. */
    private val jargon = listOf(
        "свист" to "натяжитель ремня ремень генератора",
        "жр[её]т\\s+масло|ест\\s+масло|уходит\\s+масло" to "повышенный расход масла",
        "жр[её]т\\s+антифриз|уходит\\s+антифриз|уходит\\s+тосол" to "расход антифриза",
        "троит" to "пропуски зажигания",
        "стучит\\s+под\\s+капотом|стук\\s+под\\s+капотом|стучит\\s+мотор|стучит\\s+двигатель" to "стук в двигателе гидрокомпенсаторы",
        "стучит\\s+(в\\s+)?подвеск|стук\\s+(в\\s+)?подвеск|стучит\\s+спереди|стучит\\s+сзади" to "стук в подвеске стойки стабилизатора",
        "пинается|пинки|дергается\\s+коробка|д[её]ргается\\s+коробка" to "рывки при переключении АКПП",
        "д[её]ргается|дергает" to "рывки при разгоне",
        "греется|кипит|закипел" to "перегрев двигателя",
        "не\\s+заводится|не\\s+заводиться" to "не запускается двигатель",
        "гудит|воет" to "гул подшипник ступицы",
        "скрипит|скрип" to "скрип подвески",
        "плавают\\s+обороты|скачут\\s+обороты" to "плавающие обороты холостого хода",
        "тро[её]т\\s+на\\s+холодную|троит\\s+на\\s+холодную" to "пропуски зажигания на холодную",
        "ч[её]рный\\s+дым|сизый\\s+дым|дымит" to "дым из выхлопной трубы причины",
        "не\\s+крутит\\s+стартер|щелкает\\s+стартер|щ[её]лкает\\s+стартер" to "не крутит стартер",
        "горит\\s+ч[её]к|загорелся\\s+ч[её]к|ч[её]к\\s+горит" to "горит check engine ошибка",
        "печка\\s+не\\s+греет|холодная\\s+печка" to "печка дует холодным",
        "т[её]чет|подтекает|течь" to "течь",
    ).map { (k, v) -> Regex("(?<![\\p{L}])($k)", OPT) to v }

    fun expand(query: String): String {
        val add = jargon.filter { it.first.containsMatchIn(query) }.map { it.second }
            .filter { a -> !query.contains(a, ignoreCase = true) }.distinct().take(2)
        return if (add.isEmpty()) query else "$query ${add.joinToString(" ")}"
    }

    /** Профильные сайты в порядке приоритета (0 — первыми). */
    fun tier(domain: String): Int {
        val d = domain.lowercase().removePrefix("www.").removePrefix("m.")
        return when {
            d.endsWith("drive2.ru") || d.endsWith("drive2.com") || d.endsWith("drom.ru") -> 0
            d.endsWith("auto.ru") || d.endsWith("etuners.ru") || d.contains("club") || d.contains("forum") ||
                d.endsWith("autoreview.ru") || d.endsWith("zr.ru") -> 1
            d.endsWith("youtube.com") || d.endsWith("youtu.be") || d.endsWith("rutube.ru") -> 2
            else -> 3
        }
    }

    fun sourceName(domain: String): String {
        val d = domain.lowercase().removePrefix("www.").removePrefix("m.")
        return when {
            d.endsWith("drive2.ru") || d.endsWith("drive2.com") -> "Drive2"
            d == "forums.drom.ru" -> "Форум Drom"
            d.endsWith("drom.ru") -> "Drom"
            d.endsWith("auto.ru") -> "Авто.ру"
            d.endsWith("etuners.ru") -> "eTuners"
            d.endsWith("youtube.com") || d.endsWith("youtu.be") -> "YouTube"
            d.endsWith("rutube.ru") -> "Rutube"
            d.contains("forum") || d.contains("club") -> "Форум $d"
            else -> d
        }
    }

    /** Сначала профильные источники; внутри уровня — как отдал поисковик. Повторы ссылок убираются. */
    fun rank(hits: List<SearchHit>): List<SearchHit> =
        hits.distinctBy { it.url.substringBefore('#').trimEnd('/') }.sortedBy { tier(it.domain) }

    /** Запрос только по профильным сайтам. Оператор «|» — «ИЛИ» в языке запросов Яндекса. */
    fun profileQuery(query: String): String =
        "$query (site:drive2.ru | site:drom.ru | site:auto.ru | site:etuners.ru | site:youtube.com)"

    fun hasProfile(hits: List<SearchHit>) = hits.count { tier(it.domain) <= 2 } >= 2
}
