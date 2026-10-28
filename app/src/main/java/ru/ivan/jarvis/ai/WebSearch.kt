package ru.ivan.jarvis.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.ivan.jarvis.parse.AutoSearch
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
    
    /**
     * offline — ответ из кэша без интернета; note — честная пометка, если источник недоступен.
     */
    data class Answer(
        val text: String, val hits: List<SearchHit>, val fromCache: Boolean = false, val byAi: Boolean = true,
        val offline: Boolean = false, val note: String? = null,
    )

    private val db = ru.ivan.jarvis.data.AppDatabase.get(context).search()
    private val appContext = context.applicationContext
    /** Почему Яндекс-поиск не ответил (код ответа) — показываем пользователю, а не скрываем. */
    @Volatile private var yandexError: String? = null

    suspend fun ask(question: String): Answer? = withContext(Dispatchers.IO) {
        val plan = SearchParse.plan(question)
        // Жаргон механиков → термины форумов: «свистит» → «натяжитель ремня»
        val query = (if (plan.mode == SearchParse.Mode.STOCK) plan.query else AutoSearch.expand(plan.query)).ifBlank { question }
        val key = SearchParse.cacheKey(query)
        val online = ru.ivan.jarvis.actions.Net.online(appContext)
        cached(key, maxAgeMs = if (online) 3 * 24 * 3600_000L else Long.MAX_VALUE)?.let {
            return@withContext if (online) it else it.copy(offline = true)
        }
        if (!online) return@withContext null

        yandexError = null
        // 1) профильные автоисточники (Drive2, Drom, Авто.ру, eTuners, YouTube); 2) весь интернет — если там пусто
        val profile = plan.mode != SearchParse.Mode.STOCK && !query.contains("site:")
        var hits = if (profile) runCatching { yandex(AutoSearch.profileQuery(query)) }.getOrNull().orEmpty() else emptyList()
        if (!AutoSearch.hasProfile(hits)) hits = hits + runCatching { yandex(query) }.getOrNull().orEmpty()
        if (hits.isEmpty()) hits = runCatching { ddg(query) }.getOrNull().orEmpty()
        hits = AutoSearch.rank(hits).take(5)
        val note = yandexError?.let { "Яндекс-поиск недоступен ($it)" + if (hits.isNotEmpty()) ", искал через запасной поисковик" else "" }
        if (hits.isEmpty()) return@withContext note?.let { Answer(it, emptyList(), byAi = false, note = it) }

        val task = when (plan.mode) {
            SearchParse.Mode.STOCK -> "Скажи, где есть в наличии и почём: магазин, цена, срок. Если наличия в выдержках нет — так и скажи. "
            else -> "Ответь на вопрос. Для Drive2 и форумов выдели самое ценное: марка и модель, год, пробег, " +
                "что за неисправность и как решили. Ники и имена авторов не называй. "
        }
        val system = "Ты Джарвис, помощник руководителя автосервиса. $task" +
            "По выдержкам из поиска: 2-4 коротких предложения, конкретные цифры, по-русски, без markdown. " +
            "В конце в скобках источник. Если ответа в выдержках нет, скажи об этом."
        val user = "Вопрос: ${SearchParse.cleanQuery(question)}\nВыдержки:\n${SearchParse.digest(hits)}"
        val ai = if (prefs.aiOn && prefs.yandexReady) gpt.complete(listOf("system" to system, "user" to user), maxTokens = 300) else null
        val text = ai?.text ?: hits.first().let { "${it.snippet.take(220)} (${AutoSearch.sourceName(it.domain)})" }
        val answer = Answer(text, hits, byAi = ai?.text != null, note = note)
        if (ai?.text != null) store(key, query, answer)
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
        if (code !in 200..299) {
            yandexError = when (code) { 401, 403 -> "нет доступа: проверьте роль search-api.executor"; 429 -> "лимит запросов"; else -> "ошибка $code" }
            return emptyList()
        }
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

    private suspend fun cached(key: String, maxAgeMs: Long): Answer? = runCatching {
        val e = db.get(key) ?: return null
        if (System.currentTimeMillis() - e.createdAt > maxAgeMs) return null
        val arr = JSONArray(e.links)
        val hits = (0 until arr.length()).map { arr.getJSONObject(it).let { h -> SearchHit(h.getString("t"), h.getString("u"), "") } }
        Answer(e.answer, hits, fromCache = true)
    }.getOrNull()

    private suspend fun store(key: String, query: String, a: Answer) {
        val links = JSONArray().apply { a.hits.take(5).forEach { put(JSONObject().put("t", it.title).put("u", it.url)) } }
        runCatching {
            db.put(ru.ivan.jarvis.data.SearchEntry(key, query, a.text, links.toString(), System.currentTimeMillis()))
            db.trim()
        }
    }
}
