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
import ru.ivan.jarvis.reminders.ReminderScheduler
import ru.ivan.jarvis.settings.Prefs
import ru.ivan.jarvis.voice.Speaker
import ru.ivan.jarvis.voice.VoiceInput
import java.time.LocalDate

/** Ответ Джарвиса на последнюю команду. itemId — чтобы можно было отменить. */
data class Feedback(val text: String, val itemId: Long? = null)

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

    val items: StateFlow<List<Item>> =
        dao.observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val voice = VoiceInput(app) { handle(it) }

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

    /** Последний обмен с ИИ — передаётся только если был недавно (экономия токенов). */
    private var lastExchange: Triple<String, String, Long>? = null

    init {
        refreshFocus()
    }

    fun toggleSpeak() {
        speakEnabled = !speakEnabled
        prefs.speak = speakEnabled
        if (!speakEnabled) speaker.stop()
    }

    fun dismissFeedback() {
        feedback = null
    }

    private fun answer(text: String, itemId: Long? = null, speak: Boolean = true) {
        feedback = Feedback(text, itemId)
        if (speakEnabled && speak) speaker.say(text)
    }

    /** Главная точка: распознанная или набранная фраза. */
    fun handle(text: String) = handleInternal(text, afterPermission = false)

    private fun handleInternal(text: String, afterPermission: Boolean) {
        if (text.isBlank()) return
        // 1. Команды управления телефоном — бесплатно, на устройстве
        ActionParser.parse(text)?.let { action ->
            runAction(text, action, afterPermission)
            return
        }
        viewModelScope.launch {
            // 2. Вопросы и сложные просьбы — ИИ (если подключён)
            if (prefs.aiOn && prefs.yandexReady && Assistant.shouldAsk(text) &&
                (CommandParser.parse(text) as? Command.Save)?.due == null
            ) {
                askAi(text)
                return@launch
            }
            // 3. Заметки, напоминания, «что у меня завтра»
            saveOrQuery(text)
        }
    }

    private fun runAction(text: String, action: PhoneAction, afterPermission: Boolean) {
        when (action) {
            PhoneAction.Help -> answer(HELP)
            PhoneAction.WhatNow -> answer(whatNow())
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
                } else if (r.message.isNotBlank()) {
                    answer(r.message, speak = r.speak)
                }
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
                val item = Item(
                    type = c.type,
                    title = c.title,
                    source = text,
                    dueAt = c.due?.toMillis(),
                    allDay = c.allDay,
                    repeat = c.repeat,
                )
                val saved = item.copy(id = dao.insert(item))
                ReminderScheduler.schedule(appContext, saved)
                val msg = if (saved.type == ItemType.NOTE) "Записал в заметки: ${saved.title}"
                else "Напомню ${describeDue(saved)}: ${saved.title}"
                if (announce) answer(msg, saved.id)
                msg
            }
            is Command.Query -> {
                val days = (0 until c.days).map { c.from.plusDays(it.toLong()) }
                val found = items.value
                    .filter { it.type == ItemType.REMINDER && !it.done && days.any { d -> it.occursOn(d) } }
                    .sortedBy { it.dueAt!!.toLocalDateTime().toLocalTime() }
                val msg = if (found.isEmpty()) "На ${c.label} ничего не запланировано."
                else "На ${c.label}: " + found.joinToString("; ") {
                    if (it.allDay) it.title else "в ${timeOf(it)} ${it.title}"
                }
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
        messages += "user" to text
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
        for (cmd in parsed.commands) saveOrQuery(cmd, announce = false)
        val extra = when (parsed.commands.size) {
            0 -> ""
            1 -> " Записал."
            else -> " Записал шагов: ${parsed.commands.size}."
        }
        answer((parsed.speech.ifBlank { "Готово." } + extra).trim())
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
        val id = feedback?.itemId ?: return
        viewModelScope.launch {
            dao.get(id)?.let { delete(it) }
            feedback = Feedback("Отменено")
        }
    }

    fun save(item: Item) {
        viewModelScope.launch {
            val saved = if (item.id == 0L) item.copy(id = dao.insert(item)) else item.also { dao.update(it) }
            ReminderScheduler.schedule(appContext, saved)
        }
    }

    fun delete(item: Item) {
        viewModelScope.launch {
            ReminderScheduler.cancel(appContext, item.id)
            dao.delete(item)
        }
    }

    fun toggleDone(item: Item) = save(item.copy(done = !item.done))

    override fun onCleared() {
        voice.destroy()
        speaker.shutdown()
    }

    companion object {
        const val HELP = "Я умею: записывать заметки и напоминания, открывать приложения, звонить, писать SMS, " +
            "ставить будильник и таймер, включать фонарик, менять громкость, управлять музыкой, строить маршрут, искать в интернете. " +
            "Для работы: «фокус 25 минут» и «что мне сейчас делать». С ключом Яндекса отвечаю на любые вопросы и разбиваю задачи на шаги."
    }
}
