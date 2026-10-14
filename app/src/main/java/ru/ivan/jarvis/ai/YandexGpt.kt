package ru.ivan.jarvis.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.ivan.jarvis.settings.Prefs
import java.net.HttpURLConnection
import java.net.URL

/** Клиент YandexGPT Lite (Yandex Cloud Foundation Models, синхронный completion). */
class YandexGpt(private val prefs: Prefs) {
    data class Answer(val text: String?, val error: String? = null)

    fun authHeader(key: String): String = if (key.startsWith("t1.")) "Bearer $key" else "Api-Key $key"

    suspend fun complete(messages: List<Pair<String, String>>, maxTokens: Int = 250): Answer = withContext(Dispatchers.IO) {
        if (!prefs.yandexReady) return@withContext Answer(null, "Не задан ключ Yandex Cloud")
        if (prefs.tokensToday >= prefs.dailyTokenLimit) return@withContext Answer(null, "Дневной лимит ИИ исчерпан")
        val body = JSONObject()
            .put("modelUri", "gpt://${prefs.folderId}/yandexgpt-lite/latest")
            .put(
                "completionOptions",
                JSONObject().put("stream", false).put("temperature", 0.3).put("maxTokens", maxTokens.toString()),
            )
            .put("messages", JSONArray().apply {
                messages.forEach { (role, text) -> put(JSONObject().put("role", role).put("text", text)) }
            })
        try {
            val conn = URL("https://llm.api.cloud.yandex.net/foundationModels/v1/completion").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 40000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", authHeader(prefs.apiKey))
            conn.setRequestProperty("x-folder-id", prefs.folderId)
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(text).optString("message") }.getOrNull().orEmpty()
                return@withContext Answer(null, when (code) {
                    401, 403 -> "Яндекс не принял ключ: проверьте API-ключ и ID каталога"
                    429 -> "Слишком много запросов к ИИ, попробуйте позже"
                    else -> "Ошибка ИИ ($code) $msg".trim()
                })
            }
            val result = JSONObject(text).getJSONObject("result")
            val answer = result.getJSONArray("alternatives").getJSONObject(0).getJSONObject("message").getString("text")
            val used = result.optJSONObject("usage")?.optString("totalTokens")?.toIntOrNull() ?: 0
            prefs.addTokens(used)
            Answer(answer.trim())
        } catch (e: Exception) {
            Answer(null, "Нет связи с ИИ: проверьте интернет")
        }
    }
}
