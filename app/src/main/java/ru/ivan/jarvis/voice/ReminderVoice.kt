package ru.ivan.jarvis.voice

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ru.ivan.jarvis.settings.Prefs
import java.util.Locale
import kotlin.coroutines.resume

/** Проговаривает напоминание вслух в момент срабатывания (из BroadcastReceiver). */
object ReminderVoice {
    suspend fun say(context: Context, text: String) {
        val prefs = Prefs(context)
        withTimeoutOrNull(9_000) {
            var tts: TextToSpeech? = null
            try {
                val ok = withContext(Dispatchers.Main) {
                    suspendCancellableCoroutine { cont ->
                        tts = TextToSpeech(context.applicationContext) { status ->
                            if (cont.isActive) cont.resume(status == TextToSpeech.SUCCESS)
                        }
                    }
                }
                val engine = tts ?: return@withTimeoutOrNull
                if (!ok) return@withTimeoutOrNull
                engine.setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                )
                engine.voices?.firstOrNull { it.name == prefs.systemVoice }?.let { engine.voice = it }
                    ?: run { engine.language = Locale("ru", "RU") }
                engine.setSpeechRate(prefs.speechRate)
                suspendCancellableCoroutine { cont ->
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) {}
                        override fun onDone(id: String?) { if (cont.isActive) cont.resume(Unit) }
                        @Deprecated("Deprecated in Java")
                        override fun onError(id: String?) { if (cont.isActive) cont.resume(Unit) }
                    })
                    engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reminder")
                }
            } finally {
                tts?.shutdown()
            }
        }
    }
}
