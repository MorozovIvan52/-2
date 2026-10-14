package ru.ivan.jarvis.ui

import android.app.Application
import android.content.Context
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
import ru.ivan.jarvis.data.Item
import ru.ivan.jarvis.data.ItemType
import ru.ivan.jarvis.data.occursOn
import ru.ivan.jarvis.data.toLocalDateTime
import ru.ivan.jarvis.data.toMillis
import ru.ivan.jarvis.parse.Command
import ru.ivan.jarvis.parse.CommandParser
import ru.ivan.jarvis.reminders.ReminderScheduler
import ru.ivan.jarvis.voice.Speaker
import ru.ivan.jarvis.voice.VoiceInput

/** Ответ Джарвиса на последнюю команду. itemId — чтобы можно было отменить. */
data class Feedback(val text: String, val itemId: Long? = null)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val appContext: Application = app
    private val dao = (app as JarvisApp).db.items()
    private val prefs = app.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
    private val speaker = Speaker(app)

    val items: StateFlow<List<Item>> =
        dao.observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val voice = VoiceInput(app) { handle(it) }

    var feedback by mutableStateOf<Feedback?>(null)
        private set
    var speakEnabled by mutableStateOf(prefs.getBoolean("speak", true))
        private set

    fun toggleSpeak() {
        speakEnabled = !speakEnabled
        prefs.edit().putBoolean("speak", speakEnabled).apply()
    }

    fun dismissFeedback() {
        feedback = null
    }

    private fun answer(text: String, itemId: Long? = null) {
        feedback = Feedback(text, itemId)
        if (speakEnabled) speaker.say(text)
    }

    /** Главная точка: распознанная или набранная фраза. */
    fun handle(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            when (val c = CommandParser.parse(text)) {
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
                    answer(msg, saved.id)
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
                    answer(msg)
                }
            }
        }
    }

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
}
