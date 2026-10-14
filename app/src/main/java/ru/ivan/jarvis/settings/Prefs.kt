package ru.ivan.jarvis.settings

import android.content.Context
import ru.ivan.jarvis.BuildConfig
import java.time.LocalDate

/** Все настройки приложения в одном месте (SharedPreferences). */
class Prefs(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    private fun bool(key: String, def: Boolean) = p.getBoolean(key, def)
    private fun setBool(key: String, v: Boolean) = p.edit().putBoolean(key, v).apply()
    private fun str(key: String, def: String) = p.getString(key, def) ?: def
    private fun setStr(key: String, v: String) = p.edit().putString(key, v).apply()

    // Голос
    var speak: Boolean get() = bool("speak", true); set(v) = setBool("speak", v)
    var systemVoice: String get() = str("system_voice", ""); set(v) = setStr("system_voice", v)
    var speechRate: Float get() = p.getFloat("speech_rate", 1.0f); set(v) = p.edit().putFloat("speech_rate", v).apply()
    var pitch: Float get() = p.getFloat("pitch", 0.95f); set(v) = p.edit().putFloat("pitch", v).apply()
    var yandexVoiceOn: Boolean get() = bool("yandex_voice_on", false); set(v) = setBool("yandex_voice_on", v)
    var yandexVoice: String get() = str("yandex_voice", "ermil"); set(v) = setStr("yandex_voice", v)

    // ИИ (YandexGPT)
    var apiKey: String get() = str("api_key", BuildConfig.YANDEX_API_KEY); set(v) = setStr("api_key", v.trim())
    var folderId: String get() = str("folder_id", BuildConfig.YANDEX_FOLDER_ID); set(v) = setStr("folder_id", v.trim())
    var aiOn: Boolean get() = bool("ai_on", true); set(v) = setBool("ai_on", v)
    var dailyTokenLimit: Int get() = p.getInt("daily_limit", 20000); set(v) = p.edit().putInt("daily_limit", v).apply()
    val yandexReady: Boolean get() = apiKey.isNotBlank() && folderId.isNotBlank()

    val tokensToday: Int
        get() = if (str("tokens_day", "") == LocalDate.now().toString()) p.getInt("tokens_today", 0) else 0
    val tokensTotal: Long get() = p.getLong("tokens_total", 0)
    val ttsCharsTotal: Long get() = p.getLong("tts_chars_total", 0)

    fun addTokens(n: Int) {
        val today = LocalDate.now().toString()
        val current = tokensToday
        p.edit().putString("tokens_day", today).putInt("tokens_today", current + n)
            .putLong("tokens_total", tokensTotal + n).apply()
    }

    fun addTtsChars(n: Int) = p.edit().putLong("tts_chars_total", ttsCharsTotal + n).apply()

    // Напоминания и фокус
    var nag: Boolean get() = bool("nag", true); set(v) = setBool("nag", v)
    var breakMinutes: Int get() = p.getInt("break_min", 5); set(v) = p.edit().putInt("break_min", v).apply()
    var focusPhase: String get() = str("focus_phase", ""); set(v) = setStr("focus_phase", v)
    var focusEndAt: Long get() = p.getLong("focus_end", 0); set(v) = p.edit().putLong("focus_end", v).apply()
}
