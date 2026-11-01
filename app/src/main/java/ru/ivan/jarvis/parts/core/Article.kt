package ru.ivan.jarvis.parts.core

/**
 * Запрос проценки: «фильтр масляный Манн W 75/3» → артикул W753, бренд MANN-FILTER, деталь «фильтр масляный».
 * Только чистые функции — покрыто тестами.
 */
data class PartQuery(val article: String, val brand: String?, val part: String) {
    /** Ключ кэша. */
    val key: String get() = article + "|" + (brand ?: "")
    /** Как произнести: «Манн W753». */
    val spoken: String get() = listOfNotNull(part.takeIf { it.isNotBlank() }, brand, article).joinToString(" ")
}

object Article {
    /** Бренды: варианты написания и произношения → каноническое имя (как на ЗетЗапе). */
    private val brands: List<Pair<Regex, String>> = listOf(
        "манн|ман\\s+фильтр|mann(-filter)?" to "MANN-FILTER",
        "бош|bosch" to "BOSCH",
        "мал[еэ]|mahle" to "MAHLE",
        "кнехт|knecht" to "KNECHT",
        "ф[иі]л?ьтрон|филтрон|filtron" to "FILTRON",
        "хенгст|hengst" to "HENGST",
        "уфи|ufi" to "UFI",
        "сакура|sakura" to "SAKURA",
        "текстар|textar" to "TEXTAR",
        "брембо|brembo" to "BREMBO",
        "феродо|ferodo" to "FERODO",
        "trw|тэ\\s?эр\\s?вэ|трв" to "TRW",
        "лемф[её]рдер|lemforder|lemförder" to "LEMFORDER",
        "сакс|закс|sachs" to "SACHS",
        "монро|monroe" to "MONROE",
        "кайаба|каяба|kyb|kayaba" to "KYB",
        "гейтс|gates" to "GATES",
        "дайко|dayco" to "DAYCO",
        "скф|skf" to "SKF",
        "нгк|ngk" to "NGK",
        "денсо|denso" to "DENSO",
        "валео|valeo" to "VALEO",
        "фебест|febest" to "FEBEST",
        "кортеко|corteco" to "CORTECO",
        "эльринг|elring" to "ELRING",
        "лук|luk" to "LUK",
        "тойота|toyota" to "TOYOTA",
        "ниссан|nissan" to "NISSAN",
        "х[её]нд[аэ]й|хундай|хюндай|hyundai|киа|kia|мобис|mobis" to "HYUNDAI/KIA",
        "фольксваген|ваг|vag|vw|ауди|шкода" to "VAG",
        "рено|renault" to "RENAULT",
        "лада|ваз|lada|автоваз" to "LADA",
        "стеллокс|stellox" to "STELLOX",
        "джапанпартс|japanparts|джапан\\s+партс" to "JAPANPARTS",
        "блю\\s?принт|blue\\s?print" to "BLUE PRINT",
        "маннол|mannol" to "MANNOL",
        "мобил|mobil" to "MOBIL",
        "кастрол|castrol" to "CASTROL",
        "шелл|shell" to "SHELL",
        "лукойл|lukoil" to "LUKOIL",
        "мицубиси|мицубиши|mitsubishi" to "MITSUBISHI",
        "хонда|honda" to "HONDA",
        "мазда|mazda" to "MAZDA",
        "форд|ford" to "FORD",
        "шевроле|опель|gm|дженерал" to "GENERAL MOTORS",
    ).map { (p, b) -> Regex("(?<![\\p{L}\\d])($p)(?![\\p{L}\\d])", RegexOption.IGNORE_CASE) to b }

    /** Все известные бренды — чтобы узнавать их в строках выдачи сайтов. */
    val knownBrands: List<String> = brands.map { it.second }.distinct()

    private val cyr2lat = mapOf(
        'а' to 'A', 'в' to 'B', 'е' to 'E', 'к' to 'K', 'м' to 'M', 'н' to 'H', 'о' to 'O', 'р' to 'P',
        'с' to 'C', 'т' to 'T', 'у' to 'Y', 'х' to 'X',
    )

    private val partWords = Regex(
        "(фильтр|колодк|диск|амортизатор|стойк|ремень|ролик|помп|свеч|подшипник|ступиц|рычаг|сайлентблок|наконечник|тяг|шрус|пыльник|" +
            "прокладк|сальник|датчик|катушк|масло|антифриз|лампа|щетк|радиатор|термостат|сцеплени|опор|втулк|пружин|насос|форсунк|клапан|цеп|натяжител)\\p{L}*",
        RegexOption.IGNORE_CASE,
    )

    /** Распознать бренд в тексте. */
    fun brand(text: String): String? = brands.firstOrNull { it.first.containsMatchIn(text) }?.second

    /** Артикул в чистом виде: W 75/3 → W753, кириллица-двойник → латиница, О между цифрами → 0. */
    fun normalize(raw: String): String {
        val up = raw.lowercase().map { cyr2lat[it] ?: it.uppercaseChar() }.joinToString("")
        val clean = up.filter { it.isLetterOrDigit() && it.code < 128 }
        // «O» между цифрами почти всегда ноль
        return clean.replace(Regex("(?<=\\d)O(?=\\d)"), "0")
    }

    /**
     * Достаёт артикул и бренд из фразы. null — артикула нет (нет ни одного токена с цифрой длиной 3+).
     * Соседние куски склеиваются: «W 75/3», «OC 90», «1J0 698 151».
     */
    fun parse(phrase: String): PartQuery? {
        var t = " " + phrase.trim() + " "
        t = t.replace(Regex("\\s(дробь|слэш|слеш)\\s", RegexOption.IGNORE_CASE), "/")
            .replace(Regex("\\s(тире|дефис)\\s", RegexOption.IGNORE_CASE), "-")
        val brand = brand(t)
        // убираем бренды, чтобы «Манн» не склеился с артикулом
        var rest = t
        brands.forEach { (rx, _) -> rest = rx.replace(rest, " ") }
        val tokens = rest.split(Regex("\\s+")).map { it.trim(',', '.', ':', ';', '«', '»', '"', '(', ')') }.filter { it.isNotEmpty() }
        // Ищем самый длинный отрезок «артикульных» токенов, в котором есть цифра
        fun articleLike(s: String) = s.any { it.isDigit() } || (s.length <= 3 && s.all { it.isLetter() && (it.code < 128 || it.lowercaseChar() in cyr2lat) } && s.any { it.isUpperCase() || it.code < 128 })
        var best: List<String> = emptyList()
        var cur = mutableListOf<String>()
        for (tok in tokens) {
            if (articleLike(tok) && tok.length <= 20) cur.add(tok) else {
                if (cur.any { s -> s.any { it.isDigit() } } && cur.joinToString("").length > best.joinToString("").length) best = cur
                cur = mutableListOf()
            }
        }
        if (cur.any { s -> s.any { it.isDigit() } } && cur.joinToString("").length > best.joinToString("").length) best = cur
        // Хвостовые/ведущие короткие слова без цифр, если это русские предлоги, не берём
        best = best.dropWhile { it.none(Char::isDigit) && it.lowercase() in STOP }.dropLastWhile { it.none(Char::isDigit) && it.lowercase() in STOP }
        val article = normalize(best.joinToString(""))
        if (article.length < 3 || article.none { it.isDigit() } || article.length > 20) return null
        val part = partWords.findAll(phrase).map { it.value.lowercase() }.toList().let { found ->
            if (found.isEmpty()) "" else {
                // «фильтр масляный»: деталь + следующее слово-прилагательное
                val m = Regex("(${found.first()})(\\s+[\\p{L}]+(ый|ий|ой|ая|яя|ое|ие|ые))?", RegexOption.IGNORE_CASE).find(phrase)
                m?.value?.lowercase()?.trim() ?: found.first()
            }
        }
        return PartQuery(article, brand, part)
    }

    private val STOP = setOf("на", "в", "до", "от", "для", "и", "с", "по", "к", "у", "мне", "а")

    /** Есть ли в фразе название детали (для фраз без глагола: «фильтр масляный Манн W753»). */
    fun mentionsPart(phrase: String) = partWords.containsMatchIn(phrase)
}
