package ru.ivan.jarvis.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Голосовые ответы через системный TextToSpeech. */
class Speaker(context: Context) : TextToSpeech.OnInitListener {
    private var ready = false
    private val tts = TextToSpeech(context.applicationContext, this)

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            ready = true
            tts.language = Locale("ru", "RU")
        }
    }

    fun say(text: String) {
        if (ready) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
    }

    fun shutdown() = tts.shutdown()
}
