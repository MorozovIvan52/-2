package ru.ivan.jarvis.settings

/** Достаёт ключи Яндекса из скопированного текста вида «YANDEX_API_KEY=… YANDEX_FOLDER_ID=…». */
object KeyImport {
    data class Keys(val apiKey: String?, val folderId: String?)

    fun parse(text: String): Keys {
        val api = Regex("YANDEX_API_KEY\\s*[=:]\\s*([A-Za-z0-9_\\-.]+)").find(text)?.groupValues?.get(1)
            ?: Regex("\\b(AQVN[A-Za-z0-9_\\-]{20,}|t1\\.[A-Za-z0-9_\\-.]{20,})").find(text)?.groupValues?.get(1)
        val folder = Regex("YANDEX_FOLDER_ID\\s*[=:]\\s*([a-z0-9]+)").find(text)?.groupValues?.get(1)
            ?: Regex("\\b(b1g[a-z0-9]{17})\\b").find(text)?.groupValues?.get(1)
        return Keys(api, folder)
    }
}
