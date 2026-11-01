package ru.ivan.jarvis.messages

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ru.ivan.jarvis.JarvisApp
import ru.ivan.jarvis.R
import ru.ivan.jarvis.ai.YandexGpt
import ru.ivan.jarvis.parse.AgentProtocol
import ru.ivan.jarvis.parse.ReplyBrain
import ru.ivan.jarvis.parse.TaskMatcher
import ru.ivan.jarvis.settings.Prefs
import ru.ivan.jarvis.ui.MainActivity

/**
 * Умные ответы: на новое сообщение клиента Джарвис сам готовит 2 варианта ответа
 * (с учётом заказа в «Автосервисе») и показывает уведомление с кнопками «Отправить 1/2».
 * Ничего не отправляется без нажатия — нажатие и есть подтверждение.
 */
object SmartReplies {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val seen = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
    private val aiTimes = ArrayDeque<Long>()
    const val ACTION_SEND = "ru.ivan.jarvis.SMART_SEND"

    fun onNew(context: Context, msg: InMessage) {
        val prefs = Prefs(context)
        if (!prefs.smartReplies) return
        val sig = msg.key + "|" + msg.text
        if (!seen.add(sig)) return
        if (seen.size > 200) synchronized(seen) { seen.iterator().let { it.next(); it.remove() } }
        // Только если в уведомлении есть кнопка «Ответить» — иначе отправить одной кнопкой не выйдет
        if (MessageListener.replyTarget(msg.sender)?.key != msg.key) return
        val ctx = context.applicationContext
        scope.launch {
            val order = findOrder(ctx, msg.sender)
            var a = ReplyBrain.drafts(msg.text, msg.sender, order)
            if (prefs.aiOn && prefs.yandexReady && ru.ivan.jarvis.actions.Net.online(ctx) && aiAllowed()) {
                val r = YandexGpt(prefs).complete(
                    listOf(
                        "system" to ReplyBrain.aiPrompt(prefs.about),
                        "user" to AgentProtocol.maskPhones(ReplyBrain.aiUser(msg.text, msg.sender, order, a.intent)),
                    ),
                    maxTokens = 150,
                )
                val ai = r.text?.let { ReplyBrain.parseAi(it) }.orEmpty()
                if (ai.size == 2) a = a.copy(drafts = ai)
            }
            show(ctx, msg, a, order)
        }
    }

    /** Не больше 20 обращений к ИИ в час на ответы — бережём лимит. */
    private fun aiAllowed(): Boolean = synchronized(aiTimes) {
        val now = System.currentTimeMillis()
        while (aiTimes.isNotEmpty() && now - aiTimes.first() > 3600_000) aiTimes.removeFirst()
        if (aiTimes.size >= 20) false else { aiTimes.addLast(now); true }
    }

    private suspend fun findOrder(ctx: Context, sender: String): ReplyBrain.OrderInfo? {
        val orders = runCatching { ru.ivan.jarvis.service.ServiceDb.get(ctx).dao().orders() }.getOrDefault(emptyList())
            .filter { it.status != ru.ivan.jarvis.parse.OrderStatus.DONE || System.currentTimeMillis() - it.updatedAt < 7 * 86_400_000L }
        val withClient = orders.filter { it.client.isNotBlank() }
        val name = ReplyBrain.firstName(sender).ifBlank { sender }
        val id = TaskMatcher.best(sender, withClient.map { it.id to it.client }) ?: TaskMatcher.best(name, withClient.map { it.id to it.client }) ?: return null
        val o = withClient.first { it.id == id }
        return ReplyBrain.OrderInfo(o.car, o.work, o.status, o.price)
    }

    private fun show(ctx: Context, msg: InMessage, a: ReplyBrain.Analysis, order: ReplyBrain.OrderInfo?) {
        val id = 900_000 + (msg.key.hashCode() and 0xFFFF)
        fun send(n: Int, text: String) = PendingIntent.getBroadcast(
            ctx, id * 4 + n,
            Intent(ctx, SmartReplyReceiver::class.java).setAction(ACTION_SEND)
                .putExtra("key", msg.key).putExtra("text", text).putExtra("id", id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            ctx, id, Intent(ctx, MainActivity::class.java).setAction(MainActivity.ACTION_COMMAND)
                .putExtra(MainActivity.EXTRA_TEXT, "ответь ${msg.sender.substringBefore(" (")}"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val head = (if (a.urgent) "⚠️ " else "💬 ") + "${msg.sender}: ${a.intent.label}"
        val body = "«${msg.text.take(200)}»" + (order?.let { "\n🚗 ${it.car} — ${it.status.label}" } ?: "") +
            a.drafts.mapIndexed { i, d -> "\n${i + 1}) $d" }.joinToString("")
        val b = NotificationCompat.Builder(ctx, JarvisApp.CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(head)
            .setContentText(a.drafts.firstOrNull() ?: msg.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(if (a.urgent) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(open)
        a.drafts.take(2).forEachIndexed { i, d -> b.addAction(0, "Отправить ${i + 1}", send(i, d)) }
        b.addAction(0, "Свой ответ", open)
        runCatching { NotificationManagerCompat.from(ctx).notify(id, b.build()) }
    }
}

/** Нажали «Отправить 1/2» в уведомлении умного ответа. */
class SmartReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra("key") ?: return
        val text = intent.getStringExtra("text") ?: return
        val id = intent.getIntExtra("id", 0)
        val msg = MessageListener.recent(limit = 50).firstOrNull { it.key == key }
        val ok = msg != null && MessageListener.reply(context, msg, text)
        val n = NotificationCompat.Builder(context, JarvisApp.CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (ok) "✅ Отправлено ${msg?.sender.orEmpty()}" else "Не получилось отправить")
            .setContentText(if (ok) text else "Откройте мессенджер и ответьте сами: $text")
            .setTimeoutAfter(if (ok) 8_000 else 60_000)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, n) }
    }
}
