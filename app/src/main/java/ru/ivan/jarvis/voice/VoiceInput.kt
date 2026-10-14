package ru.ivan.jarvis.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class VoiceState(
    val listening: Boolean = false,
    val partial: String = "",
    val level: Float = 0f,
    val error: String? = null,
)

/** Обёртка над системным SpeechRecognizer (русский язык, промежуточные результаты). */
class VoiceInput(private val context: Context, private val onResult: (String) -> Unit) {
    private val _state = MutableStateFlow(VoiceState())
    val state: StateFlow<VoiceState> = _state.asStateFlow()
    private var recognizer: SpeechRecognizer? = null

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        if (_state.value.listening) return stop()
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        _state.value = VoiceState(listening = true)
        r.startListening(recognizerIntent())
    }

    fun stop() {
        recognizer?.stopListening()
        _state.value = _state.value.copy(listening = false, level = 0f)
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {
            _state.value = _state.value.copy(level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            _state.value = _state.value.copy(level = 0f)
        }
        override fun onError(error: Int) {
            val msg = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Не расслышал, попробуйте ещё раз"
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Нет сети для распознавания речи"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Нет доступа к микрофону"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Распознавание занято, попробуйте ещё раз"
                else -> "Ошибка распознавания ($error)"
            }
            _state.value = VoiceState(error = msg)
        }
        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            _state.value = VoiceState()
            if (text.isNotBlank()) onResult(text)
        }
        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (text.isNotBlank()) _state.value = _state.value.copy(partial = text)
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    companion object {
        fun recognizerIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Скажите, что записать или напомнить")
        }
    }
}
