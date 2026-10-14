package ru.ivan.jarvis.voice

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.ivan.jarvis.settings.Prefs
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/** Синтез речи Yandex SpeechKit (v1). Готовые фразы кэшируются, чтобы не платить повторно. */
class YandexTts(context: Context, private val prefs: Prefs) {
    private val dir = File(context.cacheDir, "tts").apply { mkdirs() }

    companion object {
        val VOICES = listOf(
            "ermil" to "Ермил",
            "filipp" to "Филипп",
            "zahar" to "Захар",
            "madirus" to "Мадирус",
            "alena" to "Алёна",
            "jane" to "Джейн",
            "omazh" to "Омаж",
        )
    }

    private fun key(text: String): String {
        val raw = "${prefs.yandexVoice}|${prefs.speechRate}|$text"
        return MessageDigest.getInstance("SHA-1").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    suspend fun synthesize(text: String): File? = withContext(Dispatchers.IO) {
        val file = File(dir, key(text) + ".mp3")
        if (file.exists() && file.length() > 0) return@withContext file.also { it.setLastModified(System.currentTimeMillis()) }
        try {
            fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
            val form = listOf(
                "text" to text.take(4500),
                "lang" to "ru-RU",
                "voice" to prefs.yandexVoice,
                "speed" to "%.1f".format(java.util.Locale.US, prefs.speechRate),
                "format" to "mp3",
                "folderId" to prefs.folderId,
            ).joinToString("&") { "${it.first}=${enc(it.second)}" }
            val conn = URL("https://tts.api.cloud.yandex.net/speech/v1/tts:synthesize").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 10000
            conn.readTimeout = 20000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.setRequestProperty(
                "Authorization",
                if (prefs.apiKey.startsWith("t1.")) "Bearer ${prefs.apiKey}" else "Api-Key ${prefs.apiKey}",
            )
            conn.outputStream.use { it.write(form.toByteArray()) }
            if (conn.responseCode !in 200..299) {
                conn.disconnect()
                return@withContext null
            }
            conn.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
            conn.disconnect()
            prefs.addTtsChars(text.length)
            prune()
            file
        } catch (e: Exception) {
            file.delete()
            null
        }
    }

    private fun prune() {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(300).forEach { it.delete() }
    }
}
