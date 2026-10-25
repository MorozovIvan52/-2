package ru.ivan.jarvis.messages

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import ru.ivan.jarvis.parse.TaskMatcher

/** Сообщение из уведомления мессенджера. */
data class InMessage(val app: String, val sender: String, val text: String, val time: Long, val key: String)

/**
 * Читает уведомления WhatsApp, Telegram, SMS и др. (только после разрешения «Доступ к уведомлениям»),
 * чтобы Джарвис мог зачитать их и ответить через кнопку «Ответить» самого мессенджера.
 */
class MessageListener : NotificationListenerService() {

    override fun onListenerConnected() {
        instance = this
        runCatching { activeNotifications?.forEach { capture(it) } }
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = capture(sbn)

    private fun capture(sbn: StatusBarNotification) {
        val app = appName(sbn.packageName) ?: return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?: ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        // Переписка: берём последнее сообщение из MessagingStyle, иначе обычный текст
        val msgs = ex.getParcelableArray(Notification.EXTRA_MESSAGES)
        val last = msgs?.lastOrNull() as? Bundle
        val text = last?.getCharSequence("text")?.toString() ?: ex.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return
        val sender = last?.getCharSequence("sender")?.toString()?.takeIf { it.isNotBlank() && !title.contains(it) }
            ?.let { "$title ($it)" } ?: title
        if (text.isBlank() || Regex("^\\d+ (новых|новое|new)", RegexOption.IGNORE_CASE).containsMatchIn(text)) return
        synchronized(store) {
            store.removeAll { it.key == sbn.key && it.text == text }
            store.add(0, InMessage(app, sender, text, sbn.postTime, sbn.key))
            while (store.size > 50) store.removeAt(store.size - 1)
        }
        notifications[sbn.key] = sbn
    }

    private fun appName(pkg: String): String? = when {
        pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b" -> "WhatsApp"
        pkg == "org.telegram.messenger" || pkg == "org.thunderdog.challegram" || pkg == "org.telegram.plus" -> "Telegram"
        pkg == "com.viber.voip" -> "Viber"
        pkg == "com.vkontakte.android" -> "ВК"
        pkg == "ru.oneme.app" -> "Max"
        pkg == Telephony.Sms.getDefaultSmsPackage(this) || pkg == "com.google.android.apps.messaging" || pkg == "com.android.mms" -> "SMS"
        else -> null
    }

    companion object {
        @Volatile private var instance: MessageListener? = null
        private val store = mutableListOf<InMessage>()
        private val notifications = java.util.concurrent.ConcurrentHashMap<String, StatusBarNotification>()

        fun enabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
            return flat.contains(ComponentName(context, MessageListener::class.java).flattenToString())
        }

        fun settingsIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

        /** Последние сообщения, новые первыми; from — имя отправителя (с учётом падежей). */
        fun recent(from: String = "", limit: Int = 5): List<InMessage> {
            val all = synchronized(store) { store.toList() }
            if (from.isBlank()) return all.take(limit)
            val id = TaskMatcher.best(from, all.mapIndexed { i, m -> i.toLong() to m.sender }) ?: return emptyList()
            val sender = all[id.toInt()].sender
            return all.filter { it.sender == sender }.take(limit)
        }

        /** Найти сообщение, на которое можно ответить: от who или последнее. */
        fun replyTarget(who: String): InMessage? {
            val list = recent(who, 20)
            return list.firstOrNull { replyAction(it.key) != null }
        }

        private fun replyAction(key: String): Notification.Action? {
            val sbn = notifications[key] ?: return null
            return sbn.notification.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }
        }

        /** Ответить через кнопку «Ответить» в уведомлении мессенджера. */
        fun reply(context: Context, msg: InMessage, text: String): Boolean {
            val action = replyAction(msg.key) ?: return false
            val inputs = action.remoteInputs ?: return false
            val intent = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            val results = Bundle()
            inputs.forEach { results.putCharSequence(it.resultKey, text) }
            RemoteInput.addResultsToIntent(inputs, intent, results)
            return try {
                action.actionIntent.send(context, 0, intent)
                true
            } catch (e: PendingIntent.CanceledException) {
                false
            }
        }
    }
}
