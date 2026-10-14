package ru.ivan.jarvis.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ru.ivan.jarvis.settings.Prefs
import java.util.Locale

/**
 * Голосовые ответы. Если включён голос Яндекса и задан ключ — SpeechKit,
 * иначе лучший установленный русский голос системного синтезатора.
 */
class Speaker(context: Context, private val prefs: Prefs) : TextToSpeech.OnInitListener {
    private var ready = false
    private val tts = TextToSpeech(context.applicationContext, this)
    private val yandex = YandexTts(context, prefs)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var player: MediaPlayer? = null

    private val _voices = MutableStateFlow<List<Voice>>(emptyList())
    /** Русские голоса системного синтезатора, лучшие первыми. */
    val voices: StateFlow<List<Voice>> = _voices

    val engineName: String get() = runCatching { tts.defaultEngine }.getOrNull().orEmpty()

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        ready = true
        _voices.value = runCatching { tts.voices?.toList() }.getOrNull().orEmpty()
            .filter { it.locale.language == "ru" && !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            .sortedWith(compareByDescending<Voice> { it.quality }.thenBy { it.isNetworkConnectionRequired }.thenBy { it.name })
        applySettings()
    }

    fun applySettings() {
        if (!ready) return
        tts.setSpeechRate(prefs.speechRate)
        tts.setPitch(prefs.pitch)
        val chosen = _voices.value.firstOrNull { it.name == prefs.systemVoice } ?: _voices.value.firstOrNull()
        if (chosen != null) runCatching { tts.voice = chosen } else tts.language = Locale("ru", "RU")
    }

    fun say(text: String) {
        if (text.isBlank()) return
        stop()
        if (prefs.yandexVoiceOn && prefs.yandexReady) {
            scope.launch {
                val file = yandex.synthesize(text)
                if (file == null || runCatching { play(file.absolutePath) }.isFailure) sayLocal(text)
            }
        } else {
            sayLocal(text)
        }
    }

    private fun sayLocal(text: String) {
        if (ready) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
    }

    private fun play(path: String) {
        player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            setDataSource(path)
            setOnCompletionListener { it.release(); if (player === it) player = null }
            prepare()
            start()
        }
    }

    fun stop() {
        tts.stop()
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
    }

    fun shutdown() {
        stop()
        scope.cancel()
        tts.shutdown()
    }
}
