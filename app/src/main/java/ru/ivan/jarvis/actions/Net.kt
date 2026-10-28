package ru.ivan.jarvis.actions

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Есть ли сейчас интернет (нужно разрешение ACCESS_NETWORK_STATE в манифесте). */
object Net {
    fun online(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(true)
}
