package ru.ivan.jarvis.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.ivan.jarvis.JarvisApp
import ru.ivan.jarvis.actions.PhoneController
import ru.ivan.jarvis.ai.Assistant
import ru.ivan.jarvis.ai.YandexGpt
import ru.ivan.jarvis.data.Item
import ru.ivan.jarvis.data.ItemType
import ru.ivan.jarvis.data.occursOn
import ru.ivan.jarvis.data.toLocalDateTime
import ru.ivan.jarvis.data.toMillis
import ru.ivan.jarvis.focus.Focus
import ru.ivan.jarvis.parse.ActionParser
import ru.ivan.jarvis.parse.Command
import ru.ivan.jarvis.parse.CommandParser
import ru.ivan.jarvis.parse.PhoneAction
import ru.ivan.jarvis.parse.TaskEdit
import ru.ivan.jarvis.parse.TaskEditParser
import ru.ivan.jarvis.parse.TaskMatcher
import ru.ivan.jarvis.widget.JarvisWidget
import ru.ivan.jarvis.calendar.CalendarSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.core.app.NotificationManagerCompat
import ru.ivan.jarvis.reminders.ReminderScheduler
import ru.ivan.jarvis.settings.Prefs
import ru.ivan.jarvis.voice.Speaker
import ru.ivan.jarvis.voice.VoiceInput
import java.time.LocalDate

/** Ответ Джарвиса на последнюю команду. itemId — чтобы можно было отменить. */
data class Feedback(
    val text: String,
    val itemId: Long? = null,
    val restore: Item? = null,
    val quick: List<String> = emptyList(),
    /** Источники ответа из интернета: (домен, ссылка). */
    val links: List<Pair<String, String>> = emptyList(),
)

/** Что Джарвис ждёт в следующей фразе. */
private sealed interface Pending {
    data class Confirm(val sms: PhoneController.PendingSms) : Pending
    data class SmsText(val sms: PhoneController.PendingSms) : Pending
    data class When(val itemId: Long, val title: String) : Pending
}

/** Запрос разрешений, после которого команду надо повторить. */
data class PermissionAsk(val permissions: List<String>, val retry: () -> Unit)

data class FocusState(val phase: String = "", val endAt: Long = 0)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val appContext: Application = app
    private val dao = (app as JarvisApp).db.items()
    val prefs = Prefs(app)
    val speaker = Speaker(app, prefs)
    private val phone = PhoneController(app)
    private val gpt = YandexGpt(prefs)
    private val web = ru.ivan.jarvis.ai.WebSearch(app, prefs, gpt)

    val items: StateFlow<List<Item>> =
        dao.observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val voice = VoiceInput(app) { handleVoice(it) }

    var feedback by mutableStateOf<Feedback?>(null)
        private set
    var speakEnabled by mutableStateOf(prefs.speak)
        private set
    var thinking by mutableStateOf(false)
        private set
    var permissionAsk by mutableStateOf<PermissionAsk?>(null)
        private set
    var focus by mutableStateOf(FocusState())
        private set
    /** Растёт, когда после голосового ответа нужно снова слушать (режим диалога). */
    var listenAgain by mutableStateOf(0)
        private set

    private var fromVoice = false
    private var pending: Pending? = null
    private var continueAfterSpeech = false

    /** Последний обмен с ИИ — передаётся только если был недавно (экономия токенов). */
    private var lastExchange: Triple<String, String, Long>? = null

    init {
        refreshFocus()
        speaker.onDone = {
            if (continueAfterSpeech) {
                continueAfterSpeech = false
                listenAgain++
            }
        }
        viewModelScope.launch { items.collect { JarvisWidget.update(appContext) } }
    }

    fun toggleSpeak() {
        speakEnabled = !speakEnabled
        prefs.speak = speakEnabled
        if (!speakEnabled) speaker.stop()
    }

    fun dismissFeedback() {
        feedback = null
    }

    /** followUp — в режиме диалога после ответа снова включить микрофон. */
    private fun answer(
        text: String, itemId: Long? = null, speak: Boolean = true, followUp: Boolean = false,
        restore: Item? = null, quick: List<String> = emptyList(), links: List<Pair<String, String>> = emptyList(),
    ) {
        feedback = Feedback(text, itemId, restore, quick, links)
        val willSpeak = speakEnabled && speak
        continueAfterSpeech = willSpeak && fromVoice && prefs.dialog && (followUp || text.trimEnd().endsWith("?"))
        if (willSpeak) speaker.say(text)
    }

    fun sayWhatNow() {
        fromVoice = false
        answer(whatNow())
    }

    /** Главная точка: распознанная или набранная фраза. */
    fun handle(text: String) {
        fromVoice = false
        handleInternal(text, afterPermission = false)
    }

    private fun handleVoice(text: String) {
        fromVoice = true
        handleInternal(text, afterPermission = false)
    }

    private fun handleInternal(text: String, afterPermission: Boolean) {
        if (text.isBlank()) return
        // 0. Ответ на уточняющий вопрос Джарвиса («Отправить?», «Когда напомнить?»)
        pending?.let { p ->
            pending = null
            if (handlePending(p, text)) return
        }
        if (STOP_DIALOG.matches(text.trim().lowercase())) {
            fromVoice = false
            pending = null
            answer(listOf("Обращайтесь", "Хорошо, я рядом", "Удачи!").random())
            return
        }
        // 1. Команды управления телефоном — бесплатно, на устройстве
        ActionParser.parse(text)?.let { action ->
            runAction(text, action, afterPermission)
            return
        }
        // 1б. Просто название приложения: «телеграм», «ютуб»
        val bare = text.trim().replace(Regex("^(джарвис|jarvis)[\\s,.!]*", RegexOption.IGNORE_CASE), "").trim().trimEnd('.', '!', '?')
        if (bare.isNotEmpty() && bare.split(' ').size <= 2 && phone.hasApp(bare)) {
            runAction(text, PhoneAction.OpenApp(bare), afterPermission)
            return
        }
        // 2. Правка дел: «сделал…», «перенеси…», «удали…», «итоги дня»
        TaskEditParser.parse(text)?.let { edit ->
            viewModelScope.launch { applyEdit(edit) }
            return
        }
        viewModelScope.launch {
            // 3а. Цены, моменты затяжки, коды ошибок, погода — ищем в интернете
            if (ru.ivan.jarvis.parse.SearchParse.needsWeb(text) && (CommandParser.parse(text) as? Command.Save)?.due == null) {
                webAnswer(text)
                return@launch
            }
            // 3. Вопросы и сложные просьбы — ИИ (если подключён)
            if (prefs.aiOn && prefs.yandexReady && Assistant.shouldAsk(text) &&
                (CommandParser.parse(text) as? Command.Save)?.due == null
            ) {
                askAi(text)
                return@launch
            }
            // 4. Заметки, напоминания, «что у меня завтра»
            saveOrQuery(text)
        }
    }

    private fun runAction(text: String, action: PhoneAction, afterPermission: Boolean) {
        when (action) {
            PhoneAction.Help -> answer(HELP, followUp = true)
            PhoneAction.WhatNow -> answer(whatNow())
            is PhoneAction.WebSearch -> viewModelScope.launch { webAnswer(action.query) }
            is PhoneAction.Focus -> {
                Focus.start(appContext, action.minutes)
                refreshFocus()
                answer("Фокус на ${action.minutes} минут. Убираю отвлечения, поехали! Потом перерыв ${prefs.breakMinutes} минут.")
            }
            PhoneAction.FocusStop -> {
                Focus.stop(appContext)
                refreshFocus()
                answer("Фокус остановлен")
            }
            else -> {
                val r = phone.perform(action, canAsk = !afterPermission)
                if (r.missingPermissions.isNotEmpty()) {
                    permissionAsk = PermissionAsk(r.missingPermissions) { handleInternal(text, afterPermission = true) }
                } else if (r.fallThrough) {
                    viewModelScope.launch { saveOrQuery(text) }
                } else if (r.pendingSms != null) {
                    askConfirm(r.pendingSms)
                } else if (r.message.isNotBlank()) {
                    answer(r.message, speak = r.speak)
                }
            }
        }
    }

    /** Поиск в интернете с коротким ответом голосом и ссылками на источники. */
    private suspend fun webAnswer(question: String) {
        thinking = true
        feedback = Feedback("Ищу в интернете…")
        val r = runCatching { web.ask(question) }.getOrNull()
        thinking = false
        if (r == null) {
            // Не получилось — открываем обычный поиск, чтобы вопрос не пропал
            phone.perform(PhoneAction.WebSearch(ru.ivan.jarvis.parse.SearchParse.cleanQuery(question)), canAsk = false)
            answer("Не смог найти ответ сам, открыл поиск", speak = false)
            return
        }
        answer(r.text, followUp = true, links = r.hits.take(3).map { it.domain to it.url })
    }

    private fun askConfirm(sms: PhoneController.PendingSms) {
        val where = when (sms.app) { "whatsapp" -> "В Ватсап"; else -> "SMS" }
        if (sms.text.isBlank()) {
            pending = Pending.SmsText(sms)
            answer("Что написать ${sms.who}?", followUp = true, quick = listOf("Отмена"))
        } else {
            pending = Pending.Confirm(sms)
            answer("$where для ${sms.who}: «${sms.text}». Отправить?", followUp = true, quick = listOf("Да, отправь", "Нет"))
        }
    }

    private fun sendConfirmed(sms: PhoneController.PendingSms, afterPermission: Boolean) {
        val r = phone.send(sms, canAsk = !afterPermission)
        if (r.missingPermissions.isNotEmpty()) {
            permissionAsk = PermissionAsk(r.missingPermissions) { sendConfirmed(sms, afterPermission = true) }
        } else {
            answer(r.message)
        }
    }

    /** true — фраза была ответом на вопрос и обработана. */
    private fun handlePending(p: Pending, text: String): Boolean {
        val t = text.trim().lowercase().replace(Regex("^(джарвис|jarvis)[\\s,.!]*"), "")
        val yes = YES.containsMatchIn(t)
        val no = NO.containsMatchIn(t)
        when (p) {
            is Pending.Confirm -> {
                if (yes) { sendConfirmed(p.sms, afterPermission = false); return true }
                if (no) { answer("Хорошо, не отправляю"); return true }
                return false
            }
            is Pending.SmsText -> {
                if (no) { answer("Отменил"); return true }
                askConfirm(p.sms.copy(text = text.trim()))
                return true
            }
            is Pending.When -> {
                if (no) { answer("Хорошо, оставил в заметках"); return true }
                val c = CommandParser.parse("напомни $text ЗАДАЧА") as? Command.Save ?: return false
                if (c.due == null || !c.title.equals("ЗАДАЧА", ignoreCase = true)) return false
                viewModelScope.launch {
                    val item = dao.get(p.itemId) ?: return@launch
                    val moved = item.copy(type = ItemType.REMINDER, dueAt = c.due.toMillis(), allDay = c.allDay, repeat = c.repeat)
                    dao.update(moved)
                    ReminderScheduler.schedule(appContext, moved)
                    val cal = syncCal(moved)
                    answer("Напомню ${describeDue(moved)}: ${moved.title}" + if (cal) ". Добавил в календарь" else "", moved.id)
                }
                return true
            }
        }
    }

    fun onPermissionsResult() {
        val ask = permissionAsk ?: return
        permissionAsk = null
        ask.retry()
    }

    private suspend fun saveOrQuery(text: String, announce: Boolean = true): String {
        return when (val c = CommandParser.parse(text)) {
            is Command.Save -> {
                // «запиши в календарь …» без даты — событие на сегодня, весь день
                val toCalendar = c.due == null && text.contains("календар", ignoreCase = true)
                val item = Item(
                    type = if (toCalendar) ItemType.REMINDER else c.type,
                    title = c.title,
                    source = text,
                    dueAt = if (toCalendar) LocalDate.now().atTime(9, 0).toMillis() else c.due?.toMillis(),
                    allDay = toCalendar || c.allDay,
                    repeat = c.repeat,
                )
                val saved = item.copy(id = dao.insert(item))
                ReminderScheduler.schedule(appContext, saved)
                val inCalendar = syncCal(saved)
                val msg = if (saved.type == ItemType.NOTE) "Записал в заметки: ${saved.title}"
                else "Напомню ${describeDue(saved)}: ${saved.title}" + if (inCalendar) ". Добавил в календарь" else ""
                // Задача без времени — уточняем, когда напомнить, чтобы она не потерялась
                if (announce && saved.type == ItemType.NOTE && prefs.askWhen && !NOTE_WORDS.containsMatchIn(text)) {
                    pending = Pending.When(saved.id, saved.title)
                    answer(
                        "Записал: ${saved.title}. Когда напомнить?", saved.id, followUp = true,
                        quick = listOf("Через час", "Сегодня вечером", "Завтра утром", "Не надо"),
                    )
                } else if (announce) answer(msg, saved.id)
                msg
            }
            is Command.Query -> {
                val days = (0 until c.days).map { c.from.plusDays(it.toLong()) }
                val found = items.value
                    .filter { it.type == ItemType.REMINDER && !it.done && days.any { d -> it.occursOn(d) } }
                    .map { (if (it.allDay) "" else "в ${timeOf(it)} ") + it.title to it.dueAt!!.toLocalDateTime().toLocalTime() }
                // Плюс события из календаря телефона (встречи, дни рождения из Google)
                val phone = withContext(Dispatchers.IO) { CalendarSync.events(appContext, c.from, c.days) }
                    .map { e ->
                        val t = e.begin.toLocalDateTime().toLocalTime()
                        (if (e.allDay) "" else "в %d:%02d ".format(t.hour, t.minute)) + e.title to (if (e.allDay) java.time.LocalTime.MIN else t)
                    }
                val all = (found + phone).sortedBy { it.second }.map { it.first }.distinct()
                val msg = if (all.isEmpty()) "На ${c.label} ничего не запланировано."
                else "На ${c.label}: " + all.joinToString("; ")
                if (announce) answer(msg)
                msg
            }
        }
    }

    private suspend fun askAi(text: String) {
        thinking = true
        feedback = Feedback("Думаю…")
        val messages = mutableListOf("system" to Assistant.systemPrompt())
        lastExchange?.takeIf { System.currentTimeMillis() - it.third < 3 * 60_000 }?.let {
            messages += "user" to it.first.take(300)
            messages += "assistant" to it.second.take(300)
        }
        messages += "user" to (text + todayContext(text))
        val result = gpt.complete(messages)
        thinking = false
        val raw = result.text
        if (raw == null) {
            // ИИ недоступен — не теряем мысль, сохраняем как заметку
            val saved = saveOrQuery(text, announce = false)
            answer("${result.error}. $saved")
            return
        }
        lastExchange = Triple(text, raw, System.currentTimeMillis())
        val parsed = Assistant.parse(raw)
        for (cmd in parsed.commands) {
            // Команды телефона от ИИ выполняем как голосовые; остальное — заметки и напоминания
            val action = ActionParser.parse(cmd)
            if (action != null) runAction(cmd, action, afterPermission = false) else saveOrQuery(cmd, announce = false)
        }
        val extra = when (parsed.commands.size) {
            0 -> ""
            1 -> " Записал."
            else -> " Записал шагов: ${parsed.commands.size}."
        }
        answer((parsed.speech.ifBlank { "Готово." } + extra).trim(), followUp = parsed.commands.isEmpty())
    }

    /** Для вопросов о планах — коротко передаём ИИ дела на сегодня (до 6 штук). */
    private fun todayContext(text: String): String {
        if (!PLAN_WORDS.containsMatchIn(text)) return ""
        val today = LocalDate.now()
        val list = items.value.filter { it.type == ItemType.REMINDER && !it.done && it.occursOn(today) }
            .sortedBy { it.dueAt }.take(6)
        if (list.isEmpty()) return "\n(На сегодня дел нет)"
        return "\n(Мои дела сегодня: " + list.joinToString("; ") {
            if (it.allDay) it.title else "${timeOf(it)} ${it.title}"
        } + ")"
    }

    private suspend fun applyEdit(e: TaskEdit) {
        val all = dao.all()
        val open = all.filter { !it.done }
        when (e) {
            TaskEdit.Summary -> answer(summary(all), followUp = true)
            TaskEdit.Notes -> {
                val notes = all.filter { it.type == ItemType.NOTE && !it.done }.take(5)
                answer(if (notes.isEmpty()) "Заметок пока нет." else "Последние заметки: " + notes.joinToString("; ") { it.title })
            }
            is TaskEdit.Done -> {
                val item = pick(e.query, open) ?: return notFound(e.query)
                ReminderScheduler.cancelSnooze(appContext, item.id)
                NotificationManagerCompat.from(appContext).cancel(item.id.toInt())
                if (item.repeat == ru.ivan.jarvis.data.Repeat.NONE) {
                    ReminderScheduler.cancel(appContext, item.id)
                    dao.update(item.copy(done = true))
                }
                val n = prefs.markDone()
                val streak = prefs.streak.takeIf { it > 1 }?.let { " Серия $it дн. подряд." } ?: ""
                answer("${PRAISE.random()} «${item.title}» сделано. Сегодня уже $n.$streak")
            }
            is TaskEdit.Delete -> {
                val item = (if (e.query.isBlank()) all.firstOrNull() else pick(e.query, all)) ?: return notFound(e.query)
                ReminderScheduler.cancel(appContext, item.id)
                dao.delete(item)
                unsyncCal(item.id)
                answer("Удалил: ${item.title}", itemId = item.id, restore = item)
            }
            is TaskEdit.Move -> {
                val item = pick(e.query, open) ?: return notFound(e.query)
                val moved = item.copy(type = ItemType.REMINDER, dueAt = e.due.toMillis(), allDay = e.allDay, done = false)
                ReminderScheduler.cancelSnooze(appContext, item.id)
                NotificationManagerCompat.from(appContext).cancel(item.id.toInt())
                dao.update(moved)
                ReminderScheduler.schedule(appContext, moved)
                syncCal(moved)
                answer("Перенёс ${describeDue(moved)}: ${moved.title}")
            }
        }
    }

    /** Дублирует напоминание в календарь телефона; при первом разе просит доступ. */
    private suspend fun syncCal(item: Item): Boolean {
        if (!prefs.calendarSync || item.type != ItemType.REMINDER) return false
        if (!CalendarSync.granted(appContext)) {
            if (!prefs.calendarAsked) {
                prefs.calendarAsked = true
                permissionAsk = PermissionAsk(CalendarSync.PERMISSIONS) { syncAllToCalendar() }
            }
            return false
        }
        return withContext(Dispatchers.IO) { CalendarSync.upsert(appContext, item) }
    }

    private suspend fun unsyncCal(id: Long) = withContext(Dispatchers.IO) { CalendarSync.delete(appContext, id) }

    /** Перенести все будущие напоминания в календарь телефона (после выдачи доступа). */
    fun syncAllToCalendar() {
        viewModelScope.launch(Dispatchers.IO) {
            if (!CalendarSync.granted(appContext)) return@launch
            val now = System.currentTimeMillis()
            val list = dao.all().filter { it.type == ItemType.REMINDER && !it.done && it.dueAt != null && (it.dueAt >= now || it.repeat != ru.ivan.jarvis.data.Repeat.NONE) }
            val n = list.count { CalendarSync.upsert(appContext, it) }
            if (n > 0) withContext(Dispatchers.Main) { feedback = Feedback("В календарь телефона добавлено: $n") }
        }
    }

    /** Запросить доступ к календарю из настроек. */
    fun requestCalendar() {
        permissionAsk = PermissionAsk(CalendarSync.PERMISSIONS) { syncAllToCalendar() }
    }

    private fun notFound(query: String) =
        answer(if (query.isBlank()) "Не понял, о каком деле речь. Назовите его." else "Не нашёл дело «$query». Скажите точнее?", followUp = true)

    /** Пустой запрос — самое актуальное напоминание: последнее сработавшее или ближайшее. */
    private fun pick(query: String, candidates: List<Item>): Item? {
        if (query.isBlank()) {
            val now = System.currentTimeMillis()
            val rem = candidates.filter { it.type == ItemType.REMINDER && it.dueAt != null }
            return rem.filter { it.dueAt!! <= now }.maxByOrNull { it.dueAt!! } ?: rem.minByOrNull { it.dueAt!! }
        }
        val id = TaskMatcher.best(query, candidates.map { it.id to it.title }) ?: return null
        return candidates.first { it.id == id }
    }

    private fun summary(all: List<Item>): String {
        val n = prefs.doneToday
        val today = LocalDate.now()
        val now = System.currentTimeMillis()
        val left = all.filter { it.type == ItemType.REMINDER && !it.done && it.occursOn(today) && (it.allDay || it.dueAt!! >= now) }
        val head = if (n == 0) "Сегодня пока ничего не отмечено как сделанное." else "Сегодня сделано: $n. ${PRAISE.random()}"
        val streak = prefs.streak.takeIf { it > 1 }?.let { " Серия $it дн. подряд." } ?: ""
        val tail = if (left.isEmpty()) " На сегодня больше ничего нет." else " Осталось: " + left.take(3).joinToString("; ") { it.title } + "."
        return head + streak + tail
    }

    /** «Что мне сейчас делать?» — одна главная задача, без перегруза. */
    private fun whatNow(): String {
        val now = System.currentTimeMillis()
        val today = LocalDate.now()
        val active = items.value.filter { it.type == ItemType.REMINDER && !it.done && it.dueAt != null }
        val overdue = active.filter { it.repeat == ru.ivan.jarvis.data.Repeat.NONE && it.dueAt!! < now && !it.allDay }
            .sortedBy { it.dueAt }
        val todayLeft = active.filter { it.occursOn(today) && (it.allDay || it.dueAt!! >= now) }
            .sortedWith(compareBy<Item>({ !it.allDay }, { it.dueAt }))
        val first = overdue.firstOrNull() ?: todayLeft.firstOrNull()
        if (first == null) {
            val note = items.value.firstOrNull { it.type == ItemType.NOTE }
            return if (note != null) "На сегодня дел нет. Можно заняться заметкой: ${note.title}. Скажите «фокус», и я засеку 25 минут."
            else "Дел нет. Отдохните или скажите «фокус», чтобы поработать 25 минут."
        }
        val next = (overdue + todayLeft).distinct().drop(1).firstOrNull()
        val head = if (first in overdue) "Сначала просроченное: ${first.title}." else "Сейчас главное: ${first.title}" +
            (if (first.allDay) "." else ", в ${timeOf(first)}.")
        val tail = next?.let { " Потом: ${it.title}." } ?: ""
        return "$head$tail Начните с первого маленького шага."
    }

    fun refreshFocus() {
        focus = if (prefs.focusPhase.isNotEmpty() && prefs.focusEndAt > System.currentTimeMillis() - 60_000)
            FocusState(prefs.focusPhase, prefs.focusEndAt) else FocusState()
    }

    fun startFocus(minutes: Int) = runAction("", PhoneAction.Focus(minutes), false)
    fun stopFocus() = runAction("", PhoneAction.FocusStop, false)

    fun undo() {
        val fb = feedback ?: return
        fb.restore?.let { item ->
            viewModelScope.launch {
                dao.insert(item)
                ReminderScheduler.schedule(appContext, item)
                syncCal(item)
                feedback = Feedback("Вернул: ${item.title}")
            }
            return
        }
        val id = fb.itemId ?: return
        viewModelScope.launch {
            dao.get(id)?.let { delete(it) }
            feedback = Feedback("Отменено")
        }
    }

    fun save(item: Item) {
        viewModelScope.launch {
            val saved = if (item.id == 0L) item.copy(id = dao.insert(item)) else item.also { dao.update(it) }
            ReminderScheduler.schedule(appContext, saved)
            syncCal(saved)
        }
    }

    fun delete(item: Item) {
        viewModelScope.launch {
            ReminderScheduler.cancel(appContext, item.id)
            dao.delete(item)
            unsyncCal(item.id)
        }
    }

    fun toggleDone(item: Item) {
        if (!item.done) prefs.markDone()
        save(item.copy(done = !item.done))
    }

    override fun onCleared() {
        voice.destroy()
        speaker.shutdown()
    }

    companion object {
        const val HELP = "Я умею: записывать заметки и напоминания (они попадают и в календарь телефона), ставить будильник на любое время и по будням, открывать приложения, звонить, писать SMS, " +
            "ставить таймер, включать фонарик, менять громкость, управлять музыкой, строить маршрут, искать в интернете. " +
            "Ищу в интернете и отвечаю голосом: цены, моменты затяжки, объёмы масла, коды ошибок. " +
            "Для работы: «фокус 25 минут», «что мне сейчас делать», «сделал …», «перенеси … на завтра», «удали …», «итоги дня». " +
            "С ключом Яндекса отвечаю на любые вопросы и разбиваю задачи на шаги. Что сделаем?"
        private val STOP_DIALOG = Regex("^(джарвис[, ]*)?(спасибо|всё|все|хватит|отбой|пока|ничего|не надо)[.!]?$")
        private val PLAN_WORDS = Regex("(план|день|дня|задач|дела|успеть|приоритет|расписани)", RegexOption.IGNORE_CASE)
        private val YES = Regex("^(да|ага|угу|отправь|отправляй|давай|конечно|верно|подтверждаю|ок|окей|точно|yes)(?![\\p{L}])")
        private val NO = Regex("^(нет|не\\s+надо|не\\s+нужно|отмена|отмени|не\\s+отправляй|никогда|не\\s+напоминай|стоп)(?![\\p{L}])")
        private val NOTE_WORDS = Regex("(запиши|заметк|запомни|сохрани|иде[яюи]|список|мысль|в заметки)", RegexOption.IGNORE_CASE)
        private val PRAISE = listOf("Отлично!", "Супер!", "Молодец!", "Так держать!", "Есть!")
    }
}
