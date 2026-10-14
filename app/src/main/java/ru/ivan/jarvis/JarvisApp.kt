package ru.ivan.jarvis

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import ru.ivan.jarvis.data.AppDatabase

class JarvisApp : Application() {
    val db: AppDatabase by lazy { AppDatabase.get(this) }

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_REMINDERS,
            getString(R.string.channel_reminders),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = getString(R.string.channel_reminders_desc)
            enableVibration(true)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_REMINDERS = "reminders"
    }
}
