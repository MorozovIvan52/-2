package ru.ivan.jarvis.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import ru.ivan.jarvis.JarvisApp
import ru.ivan.jarvis.R
import ru.ivan.jarvis.ui.MainActivity

/**
 * Режим «Слушать всегда»: постоянно слушает и реагирует на слово «Джарвис».
 * Пока открыт экран Джарвиса, молчит — там работает свой микрофон.
 */
class HandsFreeService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var awaitingCommand = false
    private var listening = false
    private var stopped = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ru.ivan.jarvis.settings.Prefs(this).handsFree = false
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        running = true
        scheduleListen(500)
        return START_STICKY
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Слушаю «Джарвис»", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 9001, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 9002, Intent(this, HandsFreeService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Джарвис слушает")
            .setContentText("Скажите «Джарвис, …» — например, «Джарвис, что мне сейчас делать»")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Выключить", stop)
            .build()
        if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(NOTIF_ID, n)
    }

    private fun scheduleListen(delay: Long) {
        main.removeCallbacksAndMessages(null)
        main.postDelayed({ listen() }, delay)
    }

    private fun listen() {
        if (stopped || listening) return
        // Экран Джарвиса открыт — уступаем микрофон
        if (JarvisApp.currentActivity is MainActivity) { scheduleListen(2000); return }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { scheduleListen(30_000); return }
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(listener); recognizer = it }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        listening = true
        runCatching { r.startListening(intent) }.onFailure { listening = false; scheduleListen(3000) }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            listening = false
            val wait = when (error) {
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 1500L
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> 10_000L
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { stopSelf(); return }
                else -> 300L
            }
            if (awaitingCommand && error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) awaitingCommand = false
            scheduleListen(wait)
        }

        override fun onResults(results: Bundle?) {
            listening = false
            val variants = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            val command = if (awaitingCommand) {
                awaitingCommand = false
                variants.firstOrNull()
            } else {
                variants.firstNotNullOfOrNull { WakeWord.command(it) }
            }
            when {
                command == null -> scheduleListen(200)
                command.isBlank() -> {
                    // Сказали только «Джарвис» — пикаем и ждём команду
                    awaitingCommand = true
                    runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 80).startTone(ToneGenerator.TONE_PROP_BEEP, 150) }
                    scheduleListen(250)
                }
                else -> {
                    dispatch(command)
                    scheduleListen(4000)
                }
            }
        }
    }

    /** Передаём команду в приложение (оно ответит голосом и выполнит). */
    private fun dispatch(command: String) {
        val i = Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_COMMAND)
            .putExtra(MainActivity.EXTRA_TEXT, command)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        runCatching { startActivity(i) }
    }

    override fun onDestroy() {
        stopped = true
        running = false
        main.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "handsfree"
        private const val NOTIF_ID = 4242
        const val ACTION_STOP = "ru.ivan.jarvis.HANDSFREE_STOP"
        @Volatile var running = false
            private set

        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, HandsFreeService::class.java))
        fun stop(context: Context) = context.stopService(Intent(context, HandsFreeService::class.java))
    }
}
