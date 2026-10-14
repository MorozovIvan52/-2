package ru.ivan.jarvis.parse

/** Находит установленное приложение по названию, сказанному голосом («телеграм» → Telegram). */
object AppMatcher {
    private val aliases = mapOf(
        "ютуб" to "youtube", "ютюб" to "youtube", "телеграм" to "telegram", "телега" to "telegram",
        "ватсап" to "whatsapp", "вотсап" to "whatsapp", "вацап" to "whatsapp", "воцап" to "whatsapp",
        "вк" to "vk", "вконтакте" to "vk", "инстаграм" to "instagram", "инста" to "instagram",
        "хром" to "chrome", "гугл" to "google", "спотифай" to "spotify", "тикток" to "tiktok",
        "гмейл" to "gmail", "джимейл" to "gmail", "яндекс" to "yandex", "сбер" to "сбербанк",
        "тинькофф" to "т-банк", "вайбер" to "viber", "скайп" to "skype", "зум" to "zoom",
        "дискорд" to "discord", "нетфликс" to "netflix", "плей маркет" to "play store",
        "плеймаркет" to "play store", "галерея" to "gallery", "фото" to "photos",
        "калькулятор" to "calculator", "часы" to "clock", "контакты" to "contacts",
        "сообщения" to "messages", "телефон" to "phone", "карты" to "maps", "почта" to "mail",
    )
    private val translit = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ж' to "zh", 'з' to "z",
        'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p",
        'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "h", 'ц' to "ts", 'ч' to "ch",
        'ш' to "sh", 'щ' to "sch", 'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya",
    )

    fun normalize(s: String): String = s.lowercase().replace('ё', 'е').filter { it.isLetterOrDigit() }

    private fun variants(query: String): List<String> {
        val q = query.lowercase().replace('ё', 'е').trim()
        val out = mutableListOf(normalize(q))
        aliases[q]?.let { out += normalize(it) }
        aliases.entries.filter { q.startsWith(it.key) }.forEach { out += normalize(it.value) }
        out += normalize(q.map { translit[it] ?: it.toString() }.joinToString(""))
        return out.filter { it.isNotEmpty() }.distinct()
    }

    /** apps: пары (подпись, packageName). Возвращает packageName лучшего совпадения. */
    fun best(query: String, apps: List<Pair<String, String>>): String? {
        val vs = variants(query)
        var bestScore = 0
        var bestPkg: String? = null
        for ((label, pkg) in apps) {
            val n = normalize(label)
            val score = vs.maxOf { v ->
                when {
                    n == v -> 100
                    n.startsWith(v) -> 80
                    v.length >= 3 && n.contains(v) -> 60
                    v.length >= 4 && pkg.lowercase().contains(v) -> 40
                    else -> 0
                }
            } - n.length / 10
            if (score > bestScore) { bestScore = score; bestPkg = pkg }
        }
        return bestPkg
    }
}
