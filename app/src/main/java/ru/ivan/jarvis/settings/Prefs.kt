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
    /** YandexGPT Pro вместо Lite: умнее, но дороже. */
    var aiPro: Boolean get() = bool("ai_pro", true); set(v) = setBool("ai_pro", v)
    /** ИИ отвечает на всё, что не поняли встроенные команды (а не только на вопросы). */
    /** «О себе» — короткий текст, который ИИ учитывает в каждом ответе. */
    var about: String get() = str("about", ""); set(v) = p.edit().putString("about", v.take(400)).apply()
    var aiFirst: Boolean get() = bool("ai_first", true); set(v) = setBool("ai_first", v)
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

    // Диалог, планы дня, прогресс
    var dialog: Boolean get() = bool("dialog", true); set(v) = setBool("dialog", v)
    var morningOn: Boolean get() = bool("morning_on", true); set(v) = setBool("morning_on", v)
    var morningHour: Int get() = p.getInt("morning_hour", 8); set(v) = p.edit().putInt("morning_hour", v).apply()
    var eveningOn: Boolean get() = bool("evening_on", true); set(v) = setBool("evening_on", v)
    var eveningHour: Int get() = p.getInt("evening_hour", 21); set(v) = p.edit().putInt("evening_hour", v).apply()

    val doneToday: Int
        get() = if (str("done_day", "") == LocalDate.now().toString()) p.getInt("done_today", 0) else 0
    val doneTotal: Long get() = p.getLong("done_total", 0)
    /** Дней подряд, когда было сделано хотя бы одно дело. */
    val streak: Int
        get() {
            val last = str("done_day", "")
            val today = LocalDate.now()
            return if (last == today.toString() || last == today.minusDays(1).toString()) p.getInt("streak", 0) else 0
        }

    /** Отметить выполненное дело; возвращает, сколько сделано сегодня. */
    fun markDone(): Int {
        val today = LocalDate.now()
        val last = str("done_day", "")
        val newStreak = when (last) {
            today.toString() -> p.getInt("streak", 1)
            today.minusDays(1).toString() -> p.getInt("streak", 0) + 1
            else -> 1
        }
        val n = doneToday + 1
        p.edit().putString("done_day", today.toString()).putInt("done_today", n)
            .putLong("done_total", doneTotal + 1).putInt("streak", newStreak).apply()
        return n
    }

    // Будильник и календарь телефона
    /** true — свой будильник Джарвиса вместо приложения «Часы». */
    var alarmOwn: Boolean get() = bool("alarm_own", false); set(v) = setBool("alarm_own", v)
    var calendarSync: Boolean get() = bool("calendar_sync", true); set(v) = setBool("calendar_sync", v)
    /** Задача без времени — спросить «Когда напомнить?». */
    /** Режим «Слушать всегда» по слову «Джарвис». */
    var handsFree: Boolean get() = bool("hands_free", false); set(v) = setBool("hands_free", v)
    var askWhen: Boolean get() = bool("ask_when", true); set(v) = setBool("ask_when", v)
    /** Проговаривать напоминание вслух, когда оно срабатывает. */
    var speakReminders: Boolean get() = bool("speak_reminders", true); set(v) = setBool("speak_reminders", v)
    var calendarAsked: Boolean get() = bool("calendar_asked", false); set(v) = setBool("calendar_asked", v)
}
