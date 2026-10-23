package ru.ivan.jarvis

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import ru.ivan.jarvis.data.AppDatabase

class JarvisApp : Application() {
    val db: AppDatabase by lazy { AppDatabase.get(this) }

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: android.app.Activity) { current = java.lang.ref.WeakReference(activity) }
            override fun onActivityPaused(activity: android.app.Activity) { if (current?.get() === activity) current = null }
            override fun onActivityCreated(activity: android.app.Activity, state: android.os.Bundle?) {}
            override fun onActivityStarted(activity: android.app.Activity) {}
            override fun onActivityStopped(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, state: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })
        val channel = NotificationChannel(
            CHANNEL_REMINDERS,
            getString(R.string.channel_reminders),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = getString(R.string.channel_reminders_desc)
            enableVibration(true)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        runCatching { ru.ivan.jarvis.briefing.Briefing.scheduleAll(this) }
        ru.ivan.jarvis.alarm.JarvisAlarm.ensureChannel(this)
    }

    companion object {
        const val CHANNEL_REMINDERS = "reminders"
        @Volatile private var current: java.lang.ref.WeakReference<android.app.Activity>? = null
        /** Открытый сейчас экран Джарвиса (для надёжного запуска других приложений). */
        val currentActivity: android.app.Activity? get() = current?.get()
    }
}
