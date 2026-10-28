package ru.ivan.jarvis.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.ivan.jarvis.parse.SearchHit
import ru.ivan.jarvis.parse.SearchParse
import ru.ivan.jarvis.settings.Prefs
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Поиск в интернете с коротким ответом голосом:
 * Yandex Search API (тот же ключ Yandex Cloud) → запасной DuckDuckGo → YandexGPT Lite пересказывает выдержки.
 * Одинаковые вопросы отвечаются из кэша сутки — без новых токенов.
 */
class WebSearch(context: Context, private val prefs: Prefs, private val gpt: YandexGpt) {
    private val cache = context.applicationContext.getSharedPreferences("jarvis_search", Context.MODE_PRIVATE)

    data class Answer(val text: String, val hits: List<SearchHit>, val fromCache: Boolean = false)

    suspend fun ask(question: String): Answer? = withContext(Dispatchers.IO) {
        val plan = SearchParse.plan(question)
        val query = plan.query.ifBlank { question }
        val key = SearchParse.cacheKey(query)
        cached(key)?.let { return@withContext it }

        val hits = runCatching { yandex(query) }.getOrNull().orEmpty().ifEmpty { runCatching { ddg(query) }.getOrNull().orEmpty() }
        if (hits.isEmpty()) return@withContext null

        val task = when (plan.mode) {
            SearchParse.Mode.FORUM -> "Перескажи, что пишут люди на форумах и Драйв2: частые причины, решения, советы. "
            SearchParse.Mode.STOCK -> "Скажи, где есть в наличии и почём: магазин, цена, срок. Если наличия в выдержках нет — так и скажи. "
            SearchParse.Mode.GENERAL -> "Ответь на вопрос. "
        }
        val system = "Ты Джарвис, помощник руководителя автосервиса. $task" +
            "По выдержкам из поиска: 1-3 коротких предложения, конкретные цифры, по-русски, без markdown. " +
            "В конце в скобках домен источника. Если ответа в выдержках нет, скажи об этом."
        val user = "Вопрос: ${SearchParse.cleanQuery(question)}\nВыдержки:\n${SearchParse.digest(hits)}"
        val ai = if (prefs.aiOn && prefs.yandexReady) gpt.complete(listOf("system" to system, "user" to user), maxTokens = 200) else null
        val text = ai?.text ?: hits.first().let { "${it.snippet.take(220)} (${it.domain})" }
        val answer = Answer(text, hits)
        if (ai?.text != null) store(key, answer)
        answer
    }

    private fun yandex(query: String): List<SearchHit> {
        if (!prefs.yandexReady) return emptyList()
        val body = JSONObject()
            .put("query", JSONObject().put("searchType", "SEARCH_TYPE_RU").put("queryText", query).put("familyMode", "FAMILY_MODE_MODERATE"))
            .put("groupSpec", JSONObject().put("groupMode", "GROUP_MODE_DEEP").put("groupsOnPage", "5").put("docsInGroup", "1"))
            .put("maxPassages", "2")
            .put("l10n", "LOCALIZATION_RU")
            .put("folderId", prefs.folderId)
            .put("responseFormat", "FORMAT_XML")
        val conn = URL("https://searchapi.api.cloud.yandex.net/v2/web/search").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 10000
        conn.readTimeout = 20000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", gpt.authHeader(prefs.apiKey))
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()
        if (code !in 200..299) return emptyList()
        val raw = JSONObject(text).optString("rawData")
        if (raw.isBlank()) return emptyList()
        val xml = String(java.util.Base64.getDecoder().decode(raw), Charsets.UTF_8)
        return SearchParse.parseYandexXml(xml)
    }

    private fun ddg(query: String): List<SearchHit> {
        val conn = URL("https://html.duckduckgo.com/html/?kl=ru-ru&q=" + URLEncoder.encode(query, "UTF-8")).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 15000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
        conn.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9")
        val html = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        return SearchParse.parseDdgHtml(html)
    }

    private fun cached(key: String): Answer? {
        val s = cache.getString(key, null) ?: return null
        return runCatching {
            val o = JSONObject(s)
            if (System.currentTimeMillis() - o.getLong("t") > 24 * 3600_000L) return null
            val arr = o.getJSONArray("h")
            val hits = (0 until arr.length()).map { arr.getJSONObject(it).let { h -> SearchHit(h.getString("t"), h.getString("u"), "") } }
            Answer(o.getString("a"), hits, fromCache = true)
        }.getOrNull()
    }

    private fun store(key: String, a: Answer) {
        val o = JSONObject().put("t", System.currentTimeMillis()).put("a", a.text)
            .put("h", JSONArray().apply { a.hits.take(3).forEach { put(JSONObject().put("t", it.title).put("u", it.url)) } })
        val edit = cache.edit().putString(key, o.toString())
        if (cache.all.size > 200) cache.all.keys.take(50).forEach { edit.remove(it) }
        edit.apply()
    }
}
