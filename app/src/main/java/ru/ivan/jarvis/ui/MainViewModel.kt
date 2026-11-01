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
import ru.ivan.jarvis.parse.CoachCommand
import ru.ivan.jarvis.parse.CoachParser
import ru.ivan.jarvis.parse.StepPlan
import ru.ivan.jarvis.parse.minutesLabel
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
    /** Кто ответил: «ИИ …», «Интернет» или пусто — встроенные команды. */
    val source: String = "",
)

/** Что Джарвис ждёт в следующей фразе. */
private sealed interface Pending {
    data class Confirm(val sms: PhoneController.PendingSms) : Pending
    data class SmsText(val sms: PhoneController.PendingSms) : Pending
    data class When(val itemId: Long, val title: String) : Pending
    /** Разбор «Входящих»: очередь заметок и сколько уже разобрали. */
    data class Inbox(val ids: List<Long>, val handled: Int) : Pending
    data class ReplyText(val msg: ru.ivan.jarvis.messages.InMessage) : Pending
    data class ReplyConfirm(val msg: ru.ivan.jarvis.messages.InMessage, val text: String) : Pending
    /** Ждём поток мыслей для «выгрузки из головы». */
    data object Dump : Pending
    /** ИИ предложил действия — выполняем только после «да». */
    data class AiActions(val cmds: List<String>) : Pending
    /** Агент ждёт «да» перед необратимым нажатием (отправить, оплатить…). */
    data class AgentConfirm(val run: AgentRun, val label: String, val pkg: String?) : Pending
    /** Агент спросил что-то, без чего не может продолжить. */
    data class AgentAnswer(val run: AgentRun, val pkg: String?) : Pending
}

/** Выполнение дела агентом: цель и что уже сделано. */
data class AgentRun(val goal: String, val history: List<String> = emptyList(), val step: Int = 0) {
    fun add(h: String) = copy(history = history + h, step = step + 1)
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
    private val serviceDao = ru.ivan.jarvis.service.ServiceDb.get(app).dao()
    private val service = ru.ivan.jarvis.service.ServiceAssistant(serviceDao)
    val orders: StateFlow<List<ru.ivan.jarvis.service.Order>> =
        serviceDao.observeOrders().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val money: StateFlow<List<ru.ivan.jarvis.service.MoneyEntry>> =
        serviceDao.observeMoney().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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
    /** Последняя проценка запчастей — сводная таблица на главном. */
    var parts by mutableStateOf<ru.ivan.jarvis.parts.core.PriceReport?>(null)
        private set
    private val partsDao = (app as JarvisApp).db.parts()
    private val partsFetcher by lazy { ru.ivan.jarvis.parts.AndroidFetcher(app) }
    private var partsJob: kotlinx.coroutines.Job? = null
    /** Растёт — главный экран открывает камеру (голосом: «сфоткай артикул»). */
    var cameraTick by mutableStateOf(0)
        private set
    /** Задача, которую проходим по одному шагу (null — нет). */
    var steps by mutableStateOf<StepPlan?>(null)
        private set
    /** Растёт, когда после голосового ответа нужно снова слушать (режим диалога). */
    var listenAgain by mutableStateOf(0)
        private set

    private var fromVoice = false
    val journal = ru.ivan.jarvis.data.Journal(app)
    val memory = ru.ivan.jarvis.data.Memory(app)
    /** Фраза, на которую ещё не записан ответ в журнал. */
    private var lastSaid: String? = null
    private var pending: Pending? = null
    private var continueAfterSpeech = false

    /** Последний обмен с ИИ — передаётся только если был недавно (экономия токенов). */
    private var lastExchange: Triple<String, String, Long>? = null

    init {
        refreshFocus()
        steps = StepPlan.decode(prefs.stepPlan)?.takeUnless { it.finished }
        phone.serviceLookup = { name -> service.phoneFor(orders.value, name) }
        speaker.onDone = {
            // Диалог продолжается, только пока экран Джарвиса на виду (не ушли в часы, Ватсап, карты)
            if (continueAfterSpeech && ru.ivan.jarvis.JarvisApp.currentActivity is MainActivity) {
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
        close: Boolean = false, source: String = "",
    ) {
        val text = withHint(text, close)
        feedback = Feedback(text, itemId, restore, quick, links, source)
        lastSaid?.let { journal.add(it, text); lastSaid = null }
        val willSpeak = speakEnabled && speak
        // В режиме диалога после любого голосового ответа слушаем снова; тишина или «спасибо» — конец
        continueAfterSpeech = willSpeak && fromVoice && prefs.dialog && !close
        if (willSpeak) speaker.say(text)
    }

    private var lastHintAt = 0L

    /**
     * Подсказка к ответу: просроченное дело или дело в ближайший час. Не чаще раза в 20 минут,
     * не к вопросам (там ждём ответ) и не к прощанию.
     */
    private fun withHint(text: String, close: Boolean): String {
        val now = System.currentTimeMillis()
        if (close || pending != null || text.trimEnd().endsWith("?") || now - lastHintAt < 20 * 60_000) return text
        val active = items.value.filter { it.type == ItemType.REMINDER && !it.done && it.dueAt != null && !it.allDay }
        val overdue = active.filter { it.repeat == ru.ivan.jarvis.data.Repeat.NONE && it.dueAt!! < now - 10 * 60_000 }
            .sortedWith(compareByDescending<Item> { it.priority }.thenBy { it.dueAt })
        val soon = active.filter { it.dueAt!! in now..(now + 60 * 60_000) }.minByOrNull { it.dueAt!! }
        val hint = when {
            overdue.isNotEmpty() -> "Кстати, не сделано: ${overdue.first().title}" + (if (overdue.size > 1) " и ещё ${overdue.size - 1}." else ".")
            soon != null -> "Кстати, в ${timeOf(soon)}: ${soon.title}."
            else -> return text
        }
        if (text.contains(hint.substringAfter(": ").trimEnd('.'))) return text
        lastHintAt = now
        return "${text.trimEnd()} $hint"
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

    /** Команда из режима «Слушать всегда» (уже без слова «Джарвис»). */
    fun handleExternalVoice(text: String) = handleVoice(text)

    /** «Не так» — пометить последнюю команду как понятую неправильно. */
    fun markWrong() {
        val e = journal.markLastWrong()
        fromVoice = false
        feedback = Feedback(
            if (e != null) "Запомнил, что «${e.said}» я понял неправильно. Скажите иначе. Журнал можно скопировать в настройках и прислать разработчику."
            else "Журнал пуст"
        )
    }

    private fun handleInternal(text: String, afterPermission: Boolean) {
        if (text.isBlank()) return
        if (!afterPermission) lastSaid = text
        if (text.trim().equals(SAVE_NOTE, ignoreCase = true) || SAVE_NOTE_RX.matches(text.trim())) {
            viewModelScope.launch { saveWebNote() }
            return
        }
        // 0. Ответ на уточняющий вопрос Джарвиса («Отправить?», «Когда напомнить?»)
        pending?.let { p ->
            pending = null
            if (handlePending(p, text)) return
        }
        if (agentJob?.isActive == true && AGENT_STOP.containsMatchIn(text.trim())) {
            agentJob?.cancel()
            answer("Остановил выполнение.")
            return
        }
        // 0. Проценка запчастей: «проценка Манн W753», «где купить колодки TRW GDB1330», «найди болячку Солярис»
        ru.ivan.jarvis.parts.core.PartsParser.parse(text)?.let { cmd ->
            when (cmd) {
                is ru.ivan.jarvis.parts.core.PartsCommand.Price -> priceParts(cmd.q, cmd.refresh)
                ru.ivan.jarvis.parts.core.PartsCommand.RefreshLast ->
                    if (parts != null) refreshParts() else answer("Нечего обновлять. Скажите: «проценка Манн W 75/3».")
                ru.ivan.jarvis.parts.core.PartsCommand.AskArticle ->
                    answer("Назовите артикул, например: «проценка Манн W 75/3».", followUp = true)
                is ru.ivan.jarvis.parts.core.PartsCommand.Sores ->
                    viewModelScope.launch { webAnswer("${cmd.model} болячки слабые места типичные неисправности drive2 drom форум") }
            }
            return
        }
        if (PHOTO.containsMatchIn(text.trim())) {
            cameraTick++
            answer("Наведите камеру на этикетку или VIN и сделайте фото.", speak = fromVoice)
            return
        }
        if (SUMMARY.matches(text.trim())) {
            viewModelScope.launch { answer(summary(dao.all()) + " " + whatNow(), followUp = true) }
            return
        }
        // 0. Супер-Джарвис: многошаговое дело в приложениях — «открой Озон и найди фильтр Mann»
        ru.ivan.jarvis.parse.AgentProtocol.goal(text)?.let { g ->
            val app = g.app
            val known = app == null || listOf(app, app.dropLast(1), app.dropLast(2)).filter { it.length >= 2 }.any { phone.hasApp(it) }
            if (known) { startAgent(g.text); return }
        }
        if (STOP_DIALOG.matches(text.trim().lowercase())) {
            fromVoice = false
            pending = null
            answer(listOf("Обращайтесь, шеф", "Хорошо, шеф, я рядом", "Удачи, шеф!").random(), close = true)
            return
        }
        // 0. СДВГ-коуч: шаги по одному, выгрузка из головы, «не могу начать», перенос несделанного
        CoachParser.parse(text, stepsActive = steps != null)?.let { cmd ->
            handleCoach(cmd)
            return
        }
        // 0а. Разбор входящих
        if (INBOX.containsMatchIn(text.trim())) {
            viewModelScope.launch { startInbox() }
            return
        }
        // 0в. Сообщения мессенджеров: «прочитай сообщения», «ответь Саше: ок»
        ru.ivan.jarvis.parse.MessageParser.parse(text)?.let { cmd ->
            handleMessages(cmd, text, afterPermission)
            return
        }
        // 0б. Поручение человеку: «поручи Саше поменять масло»
        ru.ivan.jarvis.parse.DelegateParser.delegation(text)?.let { d ->
            runDelegate(d, afterPermission)
            return
        }
        // 0г. Экран: «назад», «нажми Принять», «прокрути вниз» — через службу спецвозможностей
        ru.ivan.jarvis.parse.ScreenParser.parse(text)?.let { cmd ->
            runScreen(cmd)
            return
        }
        // 0д. Долгая память: «запомни, что…», «что ты обо мне знаешь», «забудь про…»
        ru.ivan.jarvis.parse.MemoryParser.parse(text)?.let { cmd ->
            when (cmd) {
                is ru.ivan.jarvis.parse.MemoryCommand.Remember -> { memory.add(cmd.fact); answer("Запомнил: ${cmd.fact}") }
                is ru.ivan.jarvis.parse.MemoryCommand.Forget -> answer(memory.forget(cmd.query)?.let { "Забыл: $it" } ?: "Такого не помню")
                ru.ivan.jarvis.parse.MemoryCommand.Recall -> {
                    val all = memory.all()
                    answer(if (all.isEmpty()) "Пока ничего. Скажите «запомни, что…», и я буду это учитывать."
                    else "Я помню: " + all.take(8).joinToString("; ") + if (all.size > 8) " и ещё ${all.size - 8}." else ".")
                }
            }
            return
        }
        // 1. Команды управления телефоном — бесплатно, на устройстве
        ActionParser.parse(text)?.let { action ->
            runAction(text, action, afterPermission)
            return
        }
        // 1а. Автосервис: клиенты, статусы машин, запчасти, касса
        ru.ivan.jarvis.parse.ServiceParser.parse(text)?.let { cmd ->
            viewModelScope.launch {
                val reply = service.handle(cmd)
                if (reply == null) handleRest(text, afterPermission) else applyService(reply)
            }
            return
        }
        handleRest(text, afterPermission)
    }

    private fun applyService(r: ru.ivan.jarvis.service.ServiceAssistant.Reply) {
        viewModelScope.launch {
            if (r.task != null) saveOrQuery(r.task, announce = r.text.isBlank())
            when {
                r.sms != null -> askConfirm(r.sms, prefix = r.text + " Написать ${r.sms.who}: ")
                r.text.isNotBlank() -> answer(r.text, quick = r.quick)
            }
        }
    }

    private fun handleRest(text: String, afterPermission: Boolean) {
        // «Жду ответа от поставщика» — сам спрошу через 2 часа
        ru.ivan.jarvis.parse.DelegateParser.waiting(text)?.let { w ->
            viewModelScope.launch {
                val c = CommandParser.parse("напомни ${w.what}") as? Command.Save
                val due = c?.due ?: java.time.LocalDateTime.now().plusHours(2)
                val what = (c?.title ?: w.what).replaceFirstChar { it.lowercase() }
                val item = insertReminder("Ждём: $what", due, text)
                answer("Хорошо, ${describeDue(item)} спрошу: пришёл ли $what?", item.id)
            }
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
            // Недавно говорили с ИИ (3 мин) — продолжение разговора тоже идёт ему: «а если завтра?»
            val talking = lastExchange?.let { System.currentTimeMillis() - it.third < 3 * 60_000 } == true &&
                !NOTE_WORDS.containsMatchIn(text)
            // «ИИ на всё»: фраза без времени, не «запиши/напомни» и не вопрос о делах — пусть ИИ разберётся
            val parsed = CommandParser.parse(text)
            val unclear = prefs.aiFirst && parsed is Command.Save && parsed.due == null &&
                !NOTE_WORDS.containsMatchIn(text) && !LOCAL_LEAD.containsMatchIn(text.trim())
            if (prefs.aiOn && prefs.yandexReady && (Assistant.shouldAsk(text) || talking || unclear) &&
                (parsed as? Command.Save)?.due == null
            ) {
                if (ru.ivan.jarvis.actions.Net.online(getApplication<Application>())) { askAi(text); return@launch }
                // Без интернета ИИ недоступен — сохраняем фразу как обычно, ничего не теряем
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
    private fun runScreen(cmd: ru.ivan.jarvis.parse.ScreenCommand) {
        val a11y = ru.ivan.jarvis.actions.JarvisAccessibility
        if (!a11y.running()) {
            answer("Для этого включите Джарвиса в спецвозможностях: Настройки → раздел «Управление экраном».", quick = listOf("Что ты умеешь"))
            return
        }
        if (cmd is ru.ivan.jarvis.parse.ScreenCommand.Read) {
            // Телефоны и номера машин маскируем до отправки в облако
            val screen = a11y.screenText()?.let { ru.ivan.jarvis.parse.Privacy.mask(it) }
                ?: return answer("Не вижу текста на экране")
            if (prefs.aiOn && prefs.yandexReady && ru.ivan.jarvis.actions.Net.online(getApplication<Application>())) {
                val q = cmd.question.ifBlank { "Кратко: что здесь главное и что мне с этим сделать?" }
                viewModelScope.launch { askAi("Текст с экрана телефона:\n$screen\n\nВопрос: $q") }
            } else {
                answer("На экране: " + screen.replace('\n', ' ').take(400))
            }
            return
        }
        val ok = when (cmd) {
            is ru.ivan.jarvis.parse.ScreenCommand.Global -> a11y.global(cmd.action)
            is ru.ivan.jarvis.parse.ScreenCommand.Click -> a11y.click(cmd.text)
            is ru.ivan.jarvis.parse.ScreenCommand.Scroll -> a11y.scroll(cmd.forward)
            is ru.ivan.jarvis.parse.ScreenCommand.Read -> false
        }
        val fail = when (cmd) {
            is ru.ivan.jarvis.parse.ScreenCommand.Click -> "Не нашёл на экране «${cmd.text}»"
            is ru.ivan.jarvis.parse.ScreenCommand.Scroll -> "Здесь нечего прокручивать"
            else -> "Телефон не дал это сделать"
        }
        answer(if (ok) "Готово" else fail, speak = !ok)
    }

    /** Последний ответ из интернета — для кнопки «Сохранить в заметку». */
    private var lastWeb: Pair<String, ru.ivan.jarvis.ai.WebSearch.Answer>? = null

    private suspend fun saveWebNote() {
        val (q, r) = lastWeb ?: return answer("Нечего сохранять — сначала спросите что-нибудь")
        val date = LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"))
        val links = r.hits.take(5).joinToString("\n") { "${ru.ivan.jarvis.parse.AutoSearch.sourceName(it.domain)}: ${it.url}" }
        val title = "🔎 " + ru.ivan.jarvis.parse.SearchParse.cleanQuery(q).replaceFirstChar { it.uppercase() }
        // Заметка целиком в заголовке — его видно и можно править в карточке заметки
        val saved = Item(type = ItemType.NOTE, title = "$title\n${r.text}\n$links\nНайдено $date", source = q)
        dao.insert(saved)
        lastWeb = null
        answer("Сохранил в заметки: ${title.removePrefix("🔎 ")}")
    }

    private suspend fun webAnswer(question: String) {
        if (!ru.ivan.jarvis.actions.Net.online(getApplication<Application>())) {
            // Без сети — сначала смотрим в кэш поиска (Room): повторный вопрос отвечается и офлайн
            runCatching { web.ask(question) }.getOrNull()?.let { r ->
                lastWeb = question to r
                answer(r.text, links = webLinks(r), quick = listOf(SAVE_NOTE), source = "💾 Из памяти поиска (нет интернета)")
                return
            }
            dao.insert(Item(type = ItemType.NOTE, title = "Найти: " + question.replaceFirstChar { it.lowercase() }, source = question))
            answer("Нет интернета. Записал вопрос в заметки, спросите, когда появится связь.")
            return
        }
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
        if (r.hits.isNotEmpty()) lastWeb = question to r
        val src = when {
            r.fromCache -> "💾 Из памяти поиска"
            r.byAi -> "🌐 Интернет + ИИ"
            else -> "🌐 Интернет (без ИИ — вставьте ключ, будет пересказ)"
        } + (r.note?.let { " · ⚠️ $it" } ?: "")
        answer(r.text, followUp = true, links = webLinks(r), quick = if (r.hits.isNotEmpty()) listOf(SAVE_NOTE) else emptyList(), source = src)
    }

    /** Кнопки-ссылки: «Drive2 · заголовок». */
    private fun webLinks(r: ru.ivan.jarvis.ai.WebSearch.Answer) = r.hits.take(5).map {
        "${ru.ivan.jarvis.parse.AutoSearch.sourceName(it.domain)} · ${it.title.take(28)}" to it.url
    }

    private fun askConfirm(sms: PhoneController.PendingSms, prefix: String? = null) {
        val where = when (sms.app) { "whatsapp" -> "В Ватсап"; else -> "SMS" }
        if (sms.text.isBlank()) {
            pending = Pending.SmsText(sms)
            answer("Что написать ${sms.who}?", followUp = true, quick = listOf("Отмена"))
        } else {
            pending = Pending.Confirm(sms)
            val q = if (prefix != null) "$prefix«${sms.text}»?" else "$where для ${sms.who}: «${sms.text}». Отправить?"
            answer(q, followUp = true, quick = listOf("Да, отправь", "Нет"))
        }
    }

    private fun handleMessages(cmd: ru.ivan.jarvis.parse.MessageCommand, text: String, afterPermission: Boolean) {
        val ml = ru.ivan.jarvis.messages.MessageListener
        if (!ml.enabled(appContext)) {
            runCatching { (ru.ivan.jarvis.JarvisApp.currentActivity ?: return@runCatching).startActivity(ml.settingsIntent()) }
            answer("Чтобы читать сообщения, разрешите Джарвису доступ к уведомлениям — я открыл настройки. Включите «Джарвис» и вернитесь.")
            return
        }
        when (cmd) {
            is ru.ivan.jarvis.parse.MessageCommand.Read -> {
                val list = ml.recent(cmd.from, 5)
                if (list.isEmpty()) {
                    answer(if (cmd.from.isBlank()) "Новых сообщений нет." else "От ${cmd.from} сообщений нет.")
                    return
                }
                val spoken = list.joinToString(" ") { m -> "${m.sender} в ${m.app}: ${m.text.take(200)}." }
                val first = list.first()
                answer(
                    (if (list.size > 1) "Сообщений: ${list.size}. " else "") + spoken, followUp = true,
                    quick = listOf("Ответь ${first.sender.substringBefore(' ')}", "Хватит"),
                )
            }
            is ru.ivan.jarvis.parse.MessageCommand.Reply -> {
                val target = ml.replyTarget(cmd.who)
                if (target == null) {
                    if (cmd.who.isNotBlank()) {
                        // В уведомлениях нет — пишем обычное SMS по контакту
                        runAction(text, PhoneAction.Sms(cmd.who, cmd.text), afterPermission)
                    } else answer("Не нашёл, кому ответить: в уведомлениях нет свежих сообщений.")
                    return
                }
                if (cmd.text.isBlank()) {
                    pending = Pending.ReplyText(target)
                    answer("Что ответить ${target.sender}?", followUp = true, quick = listOf("Отмена"))
                } else askReply(target, cmd.text)
            }
        }
    }

    private fun askReply(msg: ru.ivan.jarvis.messages.InMessage, text: String) {
        pending = Pending.ReplyConfirm(msg, text)
        answer("Ответить ${msg.sender} в ${msg.app}: «$text»?", followUp = true, quick = listOf("Да, отправь", "Нет"))
    }

    private suspend fun insertReminder(title: String, due: java.time.LocalDateTime, source: String, priority: Int = 0): Item {
        val item = Item(type = ItemType.REMINDER, title = title.replaceFirstChar { it.uppercase() }, source = source, dueAt = due.toMillis(), priority = priority)
        val saved = item.copy(id = dao.insert(item))
        ReminderScheduler.schedule(appContext, saved)
        syncCal(saved)
        return saved
    }

    /** Поручение: SMS человеку с подтверждением + напоминание проверить через 2 часа. */
    private fun runDelegate(d: ru.ivan.jarvis.parse.Delegation, afterPermission: Boolean) {
        val msg = ru.ivan.jarvis.parse.DelegateParser.message(d)
        val r = phone.perform(PhoneAction.Sms(d.who, msg), canAsk = !afterPermission)
        if (r.missingPermissions.isNotEmpty()) {
            permissionAsk = PermissionAsk(r.missingPermissions) { runDelegate(d, afterPermission = true) }
            return
        }
        viewModelScope.launch {
            val check = insertReminder("Проверить: ${d.name} — ${d.task}", java.time.LocalDateTime.now().plusHours(2), "поручение")
            val sms = r.pendingSms
            if (sms != null) {
                askConfirm(sms, prefix = "Поручение для ${d.name}, проверю ${describeDue(check)}. Отправить: ")
            } else {
                answer("Записал поручение для ${d.name}, проверю ${describeDue(check)}. Номера нет в контактах, передайте сами.", check.id)
            }
        }
    }

    /** «Разбери входящие»: по одной заметке — когда напомнить, сделано, удалить или пропустить. */
    private suspend fun startInbox() {
        val ids = dao.all().filter { it.type == ItemType.NOTE && !it.done }.sortedWith(compareByDescending<Item> { it.priority }.thenBy { it.createdAt }).map { it.id }.take(15)
        if (ids.isEmpty()) { answer("Входящие пусты. Отлично!"); return }
        nextInbox(Pending.Inbox(ids, 0), "Во входящих ${ids.size}. ")
    }

    private suspend fun nextInbox(p: Pending.Inbox, prefix: String = "") {
        val id = p.ids.firstOrNull()
        val item = id?.let { dao.get(it) }
        if (item == null) {
            if (p.ids.size > 1) return nextInbox(p.copy(ids = p.ids.drop(1)), prefix)
            pending = null
            answer("${prefix}Разобрали ${p.handled}. Входящие чистые, молодец!")
            return
        }
        pending = p
        answer(
            "$prefix«${item.title}». Когда напомнить?", item.id, followUp = true,
            quick = listOf("Сегодня вечером", "Завтра утром", "Сделано", "Удалить", "Пропустить", "Хватит"),
        )
    }

    private fun handleInbox(p: Pending.Inbox, t: String, text: String): Boolean {
        val id = p.ids.first()
        val rest = p.copy(ids = p.ids.drop(1), handled = p.handled + 1)
        when {
            Regex("^(хватит|стоп|все|всё|закончим|потом)").containsMatchIn(t) -> {
                pending = null
                answer("Хорошо, разобрали ${p.handled}. Остальное потом.")
            }
            Regex("^(сделано|сделал|готово|выполнено)").containsMatchIn(t) -> viewModelScope.launch {
                dao.get(id)?.let { dao.update(it.copy(done = true)); prefs.markDone() }
                nextInbox(rest, "${PRAISE.random()} ")
            }
            Regex("^(удали|удалить|не нужно|не надо|убери)").containsMatchIn(t) -> viewModelScope.launch {
                dao.get(id)?.let { dao.delete(it) }
                nextInbox(rest, "Удалил. ")
            }
            Regex("^(пропусти|пропустить|оставь|дальше|следующ)").containsMatchIn(t) -> viewModelScope.launch {
                nextInbox(rest.copy(handled = p.handled))
            }
            else -> {
                val c = CommandParser.parse("напомни $text ЗАДАЧА") as? Command.Save ?: return false
                if (c.due == null || !c.title.equals("ЗАДАЧА", ignoreCase = true)) return false
                viewModelScope.launch {
                    val item = dao.get(id) ?: return@launch nextInbox(rest)
                    val moved = item.copy(type = ItemType.REMINDER, dueAt = c.due.toMillis(), allDay = c.allDay, repeat = c.repeat)
                    dao.update(moved)
                    ReminderScheduler.schedule(appContext, moved)
                    syncCal(moved)
                    nextInbox(rest, "Напомню ${describeDue(moved)}. ")
                }
            }
        }
        return true
    }

    // Действия из вкладки «Сервис»
    fun saveOrder(o: ru.ivan.jarvis.service.Order) {
        viewModelScope.launch {
            if (o.id == 0L) serviceDao.insert(o) else serviceDao.update(o.copy(updatedAt = System.currentTimeMillis()))
        }
    }

    fun deleteOrder(o: ru.ivan.jarvis.service.Order) {
        viewModelScope.launch { serviceDao.deleteOrder(o.id) }
    }

    fun setOrderStatus(o: ru.ivan.jarvis.service.Order, st: ru.ivan.jarvis.parse.OrderStatus) {
        fromVoice = false
        viewModelScope.launch {
            val q = listOf(o.car, o.client, o.plate).filter { it.isNotBlank() }.joinToString(" ").ifBlank { o.work }
            service.handle(ru.ivan.jarvis.parse.ServiceCommand.SetStatus(q, st))?.let { applyService(it) }
                ?: serviceDao.update(o.copy(status = st, updatedAt = System.currentTimeMillis()))
        }
    }

    suspend fun moneyTotals(days: Int) = service.totals(days)

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
            is Pending.Inbox -> return handleInbox(p, t, text)
            is Pending.AiActions -> {
                if (yes) { viewModelScope.launch { runAiActions(p.cmds) }; return true }
                if (no) { answer("Понял, шеф, ничего не делаю."); return true }
                return false
            }
            is Pending.AgentConfirm -> {
                if (yes) { viewModelScope.launch { resumeAgent(p.run, p.pkg, confirmLabel = p.label) }; return true }
                if (no) { answer("Хорошо, не нажимаю. Остановился."); return true }
                return false
            }
            is Pending.AgentAnswer -> {
                if (no) { answer("Хорошо, остановился."); return true }
                viewModelScope.launch { resumeAgent(p.run.add("пользователь ответил: ${text.trim()}"), p.pkg) }
                return true
            }
            Pending.Dump -> {
                if (no) { answer("Хорошо, в другой раз"); return true }
                viewModelScope.launch { brainDump(text) }
                return true
            }
            is Pending.ReplyText -> {
                if (no) { answer("Отменил"); return true }
                askReply(p.msg, text.trim())
                return true
            }
            is Pending.ReplyConfirm -> {
                if (yes) {
                    val ok = ru.ivan.jarvis.messages.MessageListener.reply(appContext, p.msg, p.text)
                    answer(if (ok) "Ответил ${p.msg.sender}" else "Не получилось ответить из уведомления. Откройте ${p.msg.app} и ответьте сами.")
                    return true
                }
                if (no) { answer("Хорошо, не отвечаю"); return true }
                return false
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

    private suspend fun saveOrQuery(rawText: String, announce: Boolean = true): String {
        // «срочно», «важно» — пометка приоритета, в название не попадает
        val (urgent, text) = ru.ivan.jarvis.parse.DelegateParser.priority(rawText).let { (u, t) -> u to t.ifBlank { rawText } }
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
                    priority = if (urgent) 1 else 0,
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
        val messages = mutableListOf("system" to Assistant.systemPrompt(facts = memory.forPrompt(), about = prefs.about))
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
        // Правило Ивана: «помогать только правильно, никакой самодеятельности».
        // Сам ИИ только ищет в интернете (это ничего не меняет). Записать, напомнить, запомнить, позвонить,
        // открыть, управлять телефоном — только после «да».
        val found = mutableListOf<String>()
        val links = mutableListOf<Pair<String, String>>()
        val proposals = mutableListOf<String>()
        val askedSteps = STEPS_ASK.containsMatchIn(text)
        for (cmd in parsed.commands) {
            val lower = cmd.lowercase()
            when {
                lower.startsWith("найди") || lower.startsWith("поищи") || lower.startsWith("узнай") -> {
                    feedback = Feedback("Ищу в интернете…")
                    runCatching { web.ask(cmd) }.getOrNull()?.let { r ->
                        found += r.text
                        links += r.hits.take(3).map { it.domain to it.url }
                    }
                }
                (lower.startsWith("разбей") || lower.startsWith("шаги")) && askedSteps -> {
                    // Человек сам попросил разбить задачу — показываем шаги по одному
                    val task = (CoachParser.parse(cmd) as? CoachCommand.Breakdown)?.task ?: cmd.substringAfter(' ', "").trim()
                    startBreakdown(task, intro = parsed.speech)
                    return
                }
                else -> proposals += cmd.trim()
            }
        }
        val speech = (parsed.speech.ifBlank { if (found.isEmpty() && proposals.isEmpty()) "Не уверен, что понял. Скажите иначе?" else "" } +
            if (found.isNotEmpty()) " " + found.joinToString(" ") else "").trim()
        val src = "🧠 ${gpt.modelLabel}" + if (found.isNotEmpty()) " + 🌐 интернет" else ""
        if (proposals.isEmpty()) {
            answer(speech, followUp = true, links = links, source = src)
            return
        }
        val list = proposals.take(5)
        pending = Pending.AiActions(list)
        val ask = if (list.size == 1) "Сделать: ${list[0]}?" else "Сделать: " + list.mapIndexed { n, c -> "${n + 1}) $c" }.joinToString("; ") + "?"
        answer("$speech $ask Скажите «да» или «нет».".trim(), followUp = true, links = links, source = src, quick = listOf("Да", "Нет"))
    }

    /** Выполнить действия, которые предложил ИИ, после «да». */
    private suspend fun runAiActions(cmds: List<String>) {
        val done = mutableListOf<String>()
        for (cmd in cmds) {
            val lower = cmd.lowercase()
            when {
                lower.startsWith("сделай на телефоне") || lower.startsWith("агент") -> {
                    startAgent(cmd.substringAfter(if (lower.startsWith("агент")) "агент" else "телефоне").trim(' ', ':', ','))
                    return
                }
                lower.startsWith("разбей") || lower.startsWith("шаги") -> {
                    startBreakdown((CoachParser.parse(cmd) as? CoachCommand.Breakdown)?.task ?: cmd.substringAfter(' ', "").trim())
                    return
                }
                lower.startsWith("запомни") -> {
                    memory.add(cmd.substringAfter(' ').removePrefix("что").trim(' ', ',', ':'))
                    done += "запомнил"
                }
                else -> {
                    val action = ActionParser.parse(cmd)
                    if (action != null) { runAction(cmd, action, afterPermission = false); return }
                    done += saveOrQuery(cmd, announce = false)
                }
            }
        }
        answer(done.joinToString(". ").ifBlank { "Готово" })
    }

    /** Для вопросов о планах — коротко передаём ИИ дела на сегодня (до 6 штук). */
    /**
     * Короткая сводка для ИИ: дела на сегодня и машины в работе — чтобы советовал по делу.
     * Имена и телефоны клиентов в облако не отправляем: только машина и статус.
     */
    private fun todayContext(text: String): String {
        val today = LocalDate.now()
        val list = items.value.filter { it.type == ItemType.REMINDER && !it.done && it.occursOn(today) }
            .sortedBy { it.dueAt }.take(if (PLAN_WORDS.containsMatchIn(text)) 6 else 3)
        val cars = orders.value.filter { it.status != ru.ivan.jarvis.parse.OrderStatus.DONE }.take(4)
            .joinToString("; ") { "${it.car.ifBlank { "машина" }} — ${it.status.label}" }
        val tasks = if (list.isEmpty()) "дел нет" else list.joinToString("; ") {
            if (it.allDay) it.title else "${timeOf(it)} ${it.title}"
        }
        val now = System.currentTimeMillis()
        val overdue = items.value.count {
            it.type == ItemType.REMINDER && !it.done && it.repeat == ru.ivan.jarvis.data.Repeat.NONE && !it.allDay && (it.dueAt ?: now) < now
        }
        val step = steps?.let { ". Идёт по шагам: «${it.task}», ${it.progress}: ${it.current}" } ?: ""
        val late = if (overdue > 0) ". Просрочено дел: $overdue" else ""
        return "\n(Сегодня: $tasks" + (if (cars.isNotEmpty()) ". В сервисе: $cars" else "") + late + step + ")"
    }

    val aiLabel: String get() = gpt.modelLabel

    /** Почему ИИ сейчас не ответит (null — всё в порядке). */
    fun aiProblem(): String? = when {
        !prefs.aiOn -> "выключен в настройках"
        !prefs.yandexReady -> "не вставлен ключ Яндекса"
        prefs.tokensToday >= prefs.dailyTokenLimit -> "исчерпан дневной лимит"
        else -> null
    }

    /** Кнопка «Проверить ИИ» в настройках. */
    fun testAi(onResult: (String) -> Unit) {
        viewModelScope.launch {
            if (!prefs.yandexReady) { onResult("❌ Не вставлены API-ключ и ID каталога"); return@launch }
            val r = gpt.complete(listOf("user" to "Ответь одним словом: работаешь?"), maxTokens = 10)
            onResult(if (r.text != null) "✅ ИИ работает (${gpt.modelLabel}): «${r.text}»" else "❌ ${r.error}")
        }
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
        steps?.let { p -> return "Вы на задаче «${p.task}», ${p.progress}: ${p.current}. Скажите «дальше», когда сделаете." }
        val now = System.currentTimeMillis()
        val today = LocalDate.now()
        val active = items.value.filter { it.type == ItemType.REMINDER && !it.done && it.dueAt != null }
        val overdue = active.filter { it.repeat == ru.ivan.jarvis.data.Repeat.NONE && it.dueAt!! < now && !it.allDay }
            .sortedWith(compareByDescending<Item> { it.priority }.thenBy { it.dueAt })
        val todayLeft = active.filter { it.occursOn(today) && (it.allDay || it.dueAt!! >= now) }
            .sortedWith(compareByDescending<Item> { it.priority }.thenBy { !it.allDay }.thenBy { it.dueAt })
        val first = overdue.firstOrNull() ?: todayLeft.firstOrNull()
        if (first == null) {
            val note = items.value.filter { it.type == ItemType.NOTE && !it.done }.maxByOrNull { it.priority }
            return if (note != null) "На сегодня дел нет. Можно заняться заметкой: ${note.title}. Скажите «фокус», и я засеку 25 минут."
            else "Дел нет. Отдохните или скажите «фокус», чтобы поработать 25 минут."
        }
        val next = (overdue + todayLeft).distinct().drop(1).firstOrNull()
        val left = (first.dueAt!! - now) / 60_000
        val head = if (first in overdue) "Сначала просроченное: ${first.title}." else "Сейчас главное: ${first.title}" +
            (if (first.allDay) "." else ", в ${timeOf(first)} (через ${minutesLabel(left)}).")
        val tail = next?.let { " Потом: ${it.title}." } ?: ""
        return "$head$tail Начните с первого маленького шага или скажите «разбей на шаги»."
    }

    // ---------- Проценка запчастей ----------

    private fun partsProviders() = ru.ivan.jarvis.parts.core.Sites.all(partsFetcher) { prefs.partsUrl(it) }

    /** Кнопка «Обновить проценку». */
    fun refreshParts() { parts?.let { priceParts(it.query, refresh = true) } }
    fun closeParts() { parts = null }

    private fun priceParts(q: ru.ivan.jarvis.parts.core.PartQuery, refresh: Boolean) {
        partsJob?.cancel()
        partsJob = viewModelScope.launch {
            val now = System.currentTimeMillis()
            if (!refresh) {
                val cached = partsDao.get(q.key)?.takeIf { now - it.createdAt < ru.ivan.jarvis.parts.PartsJson.TTL_MS }
                    ?.let { ru.ivan.jarvis.parts.PartsJson.decode(it.json, it.createdAt) }
                if (cached != null) {
                    parts = cached
                    val t = java.time.Instant.ofEpochMilli(cached.at).atZone(java.time.ZoneId.systemDefault()).toLocalTime()
                    answer(ru.ivan.jarvis.parts.core.Pricing.speech(cached) + " Данные от %d:%02d, обновить?".format(t.hour, t.minute),
                        quick = listOf("Обновить проценку"), source = "💾 Проценка из памяти")
                    return@launch
                }
            }
            if (!ru.ivan.jarvis.actions.Net.online(appContext)) { answer("Нет интернета, проценить не получится."); return@launch }
            thinking = true
            feedback = Feedback("🔎 Проценка ${q.spoken}: смотрю ЗетЗап…")
            val report = ru.ivan.jarvis.parts.core.Pricing.run(q, partsProviders(),
                onDebug = { ru.ivan.jarvis.parts.PartsDebug.save(appContext, it) })
            thinking = false
            parts = report
            if (report.offers.isNotEmpty()) {
                partsDao.put(ru.ivan.jarvis.parts.PartsEntry(q.key, ru.ivan.jarvis.parts.PartsJson.encode(report), report.at))
                partsDao.purge(now - 24 * 3600_000L)
            }
            answer(ru.ivan.jarvis.parts.core.Pricing.speech(report), source = "🔎 Проценка: " + report.status.joinToString(", ") { it.first })
        }
    }

    /** Фото этикетки: ML Kit на телефоне → артикул (сразу проценка) или VIN (расшифровка, в заметки). */
    fun onLabelPhoto(uri: android.net.Uri) {
        viewModelScope.launch {
            fromVoice = false
            feedback = Feedback("📷 Читаю фото…")
            val text = runCatching {
                val img = com.google.mlkit.vision.common.InputImage.fromFilePath(appContext, uri)
                val rec = com.google.mlkit.vision.text.TextRecognition.getClient(com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS)
                kotlinx.coroutines.suspendCancellableCoroutine<String> { c ->
                    rec.process(img)
                        .addOnSuccessListener { if (c.isActive) c.resumeWith(Result.success(it.text)) }
                        .addOnFailureListener { if (c.isActive) c.resumeWith(Result.success("")) }
                }
            }.getOrDefault("")
            when (val r = ru.ivan.jarvis.parts.core.LabelReader.read(text)) {
                is ru.ivan.jarvis.parts.core.LabelReader.Result.Vin -> {
                    val what = listOfNotNull(r.maker, r.year?.let { "$it г." }).joinToString(", ").ifBlank { "производитель не определён" }
                    dao.insert(Item(type = ItemType.NOTE, title = "🚗 VIN ${r.vin} — $what", source = text.take(500)))
                    answer("VIN ${r.vin}: $what. Записал в заметки.", source = "📷 Фото")
                }
                is ru.ivan.jarvis.parts.core.LabelReader.Result.Part -> {
                    answer("На фото артикул ${r.q.article}" + (r.q.brand?.let { ", $it" } ?: "") + ". Делаю проценку.", source = "📷 Фото")
                    priceParts(r.q, refresh = false)
                }
                ru.ivan.jarvis.parts.core.LabelReader.Result.Nothing ->
                    answer(if (text.isBlank()) "Не разобрал текст. Снимите ближе и при хорошем свете." else "Не нашёл на фото ни артикула, ни VIN. Попробуйте ещё раз ближе.")
            }
        }
    }

    /** Настройки → «Проверить источники»: каждый сайт по тестовому артикулу. */
    fun testPartsSources(article: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val q = ru.ivan.jarvis.parts.core.Article.parse(article) ?: ru.ivan.jarvis.parts.core.PartQuery(article, null, "")
            val lines = mutableListOf<String>()
            onResult("Проверяю ${q.article}…")
            for (p in partsProviders()) {
                val r = kotlinx.coroutines.withTimeoutOrNull(ru.ivan.jarvis.parts.core.Pricing.SITE_TIMEOUT_MS) { p.search(q.article, q.brand) }
                lines += p.title + ": " + when (r) {
                    is ru.ivan.jarvis.parts.core.ProviderResult.Ok -> "✅ ${r.offers.size} предл., от ${r.offers.minOf { it.price }} ₽"
                    is ru.ivan.jarvis.parts.core.ProviderResult.Empty -> { ru.ivan.jarvis.parts.PartsDebug.save(appContext, r); "⚠️ открылся, но не разобрал (отладка сохранена)" }
                    is ru.ivan.jarvis.parts.core.ProviderResult.Unavailable -> "❌ недоступен: ${r.reason}"
                    null -> "❌ не ответил вовремя"
                }
                onResult(lines.joinToString("\n"))
            }
        }
    }

    // ---------- Супер-Джарвис: агент на экране ----------

    private var agentJob: kotlinx.coroutines.Job? = null

    private fun startAgent(goal: String) {
        val a11y = ru.ivan.jarvis.actions.JarvisAccessibility
        if (!a11y.running()) {
            answer(
                "Чтобы я сам нажимал в приложениях, включите Джарвиса в спецвозможностях: Настройки → «Управление экраном». Это один раз.",
                quick = listOf("Что ты умеешь"),
            )
            return
        }
        if (!(prefs.aiOn && prefs.yandexReady)) { answer("Для этого нужен ИИ: вставьте ключ Яндекса в Настройках."); return }
        if (!ru.ivan.jarvis.actions.Net.online(appContext)) { answer("Нет интернета, а без него ИИ не видит экран."); return }
        agentJob?.cancel()
        agentJob = viewModelScope.launch { resumeAgent(AgentRun(goal), null) }
    }

    /**
     * Цикл агента: снимок экрана → ИИ выбирает одно действие → выполняем → ждём → снова.
     * pkg — приложение, куда вернуться после вопроса пользователю. confirmLabel — пользователь
     * сказал «да» на нажатие этого элемента.
     */
    private suspend fun resumeAgent(start: AgentRun, pkg: String?, confirmLabel: String? = null) {
        val a11y = ru.ivan.jarvis.actions.JarvisAccessibility
        val proto = ru.ivan.jarvis.parse.AgentProtocol
        var st = start
        val myPkg = appContext.packageName
        // Возвращаемся в приложение, где остановились
        if (pkg != null && pkg != myPkg) {
            appContext.packageManager.getLaunchIntentForPackage(pkg)?.let {
                runCatching { appContext.startActivity(it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            kotlinx.coroutines.delay(1800)
        }
        if (confirmLabel != null) {
            val els = a11y.snapshot().orEmpty()
            val target = els.firstOrNull { it.label == confirmLabel } ?: els.firstOrNull { it.label.contains(confirmLabel.take(20), true) }
            val ok = target != null && a11y.tapIndex(target.index)
            st = st.add(if (ok) "нажал «$confirmLabel» (подтверждено)" else "не нашёл «$confirmLabel»")
            kotlinx.coroutines.delay(1500)
        }
        var lastSig = ""
        var repeats = 0
        while (st.step < proto.MAX_STEPS) {
            var els = a11y.snapshot() ?: return answer("Служба спецвозможностей не отвечает. Выключите и включите её снова.")
            // Свой экран ИИ не показываем: первым делом он откроет нужное приложение
            val cur = a11y.currentPackage().orEmpty()
            if (cur == myPkg) els = emptyList()
            val app = if (cur == myPkg) "Джарвис (своё приложение, нужно открыть другое)" else appLabel(cur)
            val screen = proto.maskPhones(proto.render(els))
            feedback = Feedback("🤖 Шаг ${st.step + 1}: смотрю на экран…", quick = listOf("Стоп"))
            val r = gpt.complete(
                listOf("system" to proto.systemPrompt(), "user" to proto.stepPrompt(st.goal, app, screen, st.history)),
                maxTokens = 80,
            )
            val raw = r.text ?: return finishAgent("Остановился: ${r.error}.", bring = true)
            val action = proto.parse(raw)
            if (action == null) { st = st.add("ответ ИИ не понят: ${raw.take(40)}"); continue }
            val what = proto.describe(action, els)
            val sig = raw.trim().lowercase()
            repeats = if (sig == lastSig) repeats + 1 else 0
            lastSig = sig
            if (repeats >= 2) return finishAgent("Похоже, я застрял на одном месте. Доделайте, пожалуйста, сами или скажите иначе.", bring = true)
            feedback = Feedback("🤖 Шаг ${st.step + 1}: $what", quick = listOf("Стоп"))
            when (action) {
                is ru.ivan.jarvis.parse.AgentAction.Done ->
                    return finishAgent(action.summary.ifBlank { "Готово." }, bring = false)
                is ru.ivan.jarvis.parse.AgentAction.Ask -> {
                    a11y.bringJarvis()
                    pending = Pending.AgentAnswer(st, cur.takeIf { it != myPkg })
                    answer(action.question, followUp = true)
                    return
                }
                is ru.ivan.jarvis.parse.AgentAction.Tap -> {
                    val label = els.firstOrNull { it.index == action.index }?.label
                    if (label == null) { st = st.add("номера ${action.index} нет на экране"); continue }
                    if (proto.risky(label)) {
                        a11y.bringJarvis()
                        pending = Pending.AgentConfirm(st, label, cur.takeIf { it != myPkg })
                        answer("Дошёл до кнопки «${label.take(40)}». Нажать? Скажите «да» или «нет».", followUp = true)
                        return
                    }
                    val ok = a11y.tapIndex(action.index)
                    st = st.add(if (ok) "нажал «${label.take(40)}»" else "не нажалось «${label.take(40)}»")
                }
                is ru.ivan.jarvis.parse.AgentAction.Type -> {
                    val ok = a11y.typeIndex(action.index, action.text)
                    st = st.add(if (ok) "ввёл «${action.text.take(40)}»" else "не удалось ввести в №${action.index}")
                }
                is ru.ivan.jarvis.parse.AgentAction.Scroll -> {
                    val ok = a11y.scroll(action.down)
                    st = st.add(if (ok) (if (action.down) "пролистал вниз" else "пролистал вверх") else "листать некуда")
                }
                is ru.ivan.jarvis.parse.AgentAction.Open -> {
                    val res = phone.perform(PhoneAction.OpenApp(action.app), canAsk = false)
                    st = st.add(if (res.message.startsWith("Открываю")) "открыл ${action.app}" else "не открылось ${action.app}: ${res.message.take(60)}")
                    kotlinx.coroutines.delay(1500)
                }
                ru.ivan.jarvis.parse.AgentAction.Back -> { a11y.global(ru.ivan.jarvis.parse.ScreenGlobal.BACK); st = st.add("назад") }
                ru.ivan.jarvis.parse.AgentAction.Home -> { a11y.global(ru.ivan.jarvis.parse.ScreenGlobal.HOME); st = st.add("домой") }
                ru.ivan.jarvis.parse.AgentAction.Wait -> st = st.add("подождал")
            }
            kotlinx.coroutines.delay(1600)
        }
        finishAgent("Сделал ${proto.MAX_STEPS} шагов, но до конца не дошёл. Посмотрите экран и скажите, что дальше.", bring = false)
    }

    private fun finishAgent(text: String, bring: Boolean) {
        if (bring) ru.ivan.jarvis.actions.JarvisAccessibility.bringJarvis()
        answer("🤖 $text", source = "🤖 Агент · ${gpt.modelLabel}")
    }

    private fun appLabel(pkg: String): String = runCatching {
        val pm = appContext.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    // ---------- СДВГ-коуч ----------

    private fun updateSteps(p: StepPlan?) {
        steps = p
        prefs.stepPlan = p?.encode() ?: ""
    }

    /** Главное дело сейчас: просроченное важное или ближайшее на сегодня. */
    private fun mainTask(): Item? {
        val now = System.currentTimeMillis()
        val today = LocalDate.now()
        val active = items.value.filter { it.type == ItemType.REMINDER && !it.done && it.dueAt != null }
        return active.filter { it.repeat == ru.ivan.jarvis.data.Repeat.NONE && it.dueAt!! < now && !it.allDay }
            .sortedWith(compareByDescending<Item> { it.priority }.thenBy { it.dueAt }).firstOrNull()
            ?: active.filter { it.occursOn(today) && (it.allDay || it.dueAt!! >= now) }
                .sortedWith(compareByDescending<Item> { it.priority }.thenBy { it.dueAt }).firstOrNull()
    }

    private fun handleCoach(cmd: CoachCommand) {
        when (cmd) {
            is CoachCommand.Breakdown -> {
                val task = cmd.task.ifBlank { steps?.current ?: mainTask()?.title.orEmpty() }
                if (task.isBlank()) answer("Какую задачу разбить? Скажите, например: «разбей уборку гаража на шаги».", followUp = true)
                else viewModelScope.launch { startBreakdown(task) }
            }
            CoachCommand.StepDone -> stepDone()
            CoachCommand.StepWhere -> steps?.let { answer("Задача «${it.task}», ${it.progress}: ${it.current}.", quick = STEP_QUICK) }
                ?: answer(NO_STEPS)
            CoachCommand.StepsAll -> steps?.let { p ->
                answer(p.steps.mapIndexed { i, st -> (if (i == p.index) "▶ " else if (i < p.index) "✓ " else "") + "${i + 1}. $st" }
                    .joinToString("\n"), speak = false, quick = STEP_QUICK)
            } ?: answer(NO_STEPS)
            CoachCommand.StepsStop -> {
                val p = steps
                updateSteps(null)
                answer(if (p == null) NO_STEPS else "Хорошо, шаги убрал. Задача «${p.task}» осталась в заметках, вернёмся, когда будут силы.")
            }
            is CoachCommand.Dump -> if (cmd.text.isBlank()) {
                pending = Pending.Dump
                answer("Говорите всё подряд, что крутится в голове. Я разложу по делам и напоминаниям.", followUp = true)
            } else viewModelScope.launch { brainDump(cmd.text) }
            is CoachCommand.Unload -> viewModelScope.launch { unload(cmd.all) }
            is CoachCommand.Stuck -> stuck(cmd.task)
        }
    }

    /** Разбить задачу на крошечные шаги (ИИ или запасной шаблон) и показать первый. */
    private suspend fun startBreakdown(task: String, intro: String = "") {
        thinking = true
        feedback = Feedback("Разбиваю на маленькие шаги…")
        var list = emptyList<String>()
        if (prefs.aiOn && prefs.yandexReady && ru.ivan.jarvis.actions.Net.online(appContext)) {
            val r = gpt.complete(
                listOf("system" to Assistant.breakdownPrompt(memory.forPrompt(300), prefs.about), "user" to task),
                maxTokens = 350,
            )
            list = r.text?.let { CoachParser.parseSteps(it) }.orEmpty()
        }
        thinking = false
        val fromAi = list.size >= 2
        if (!fromAi) list = CoachParser.fallbackSteps(task)
        val note = Item(
            type = ItemType.NOTE, title = "📋 $task",
            source = list.mapIndexed { i, st -> "${i + 1}. $st" }.joinToString("\n"),
        )
        val id = dao.insert(note)
        updateSteps(StepPlan(task, list, 0, id))
        val head = intro.takeIf { it.isNotBlank() }?.let { "$it " } ?: ""
        answer(
            "${head}Разбил на ${list.size} ${stepsWord(list.size)}. Шаг 1: ${list[0]}. Скажите «дальше», когда сделаете.",
            quick = STEP_QUICK, source = if (fromAi) "🧠 ${gpt.modelLabel}" else "",
        )
    }

    private fun stepDone() {
        val p = steps ?: return answer(NO_STEPS)
        val n = p.next()
        if (!n.finished) {
            updateSteps(n)
            answer("${PRAISE.random()} ${n.progress.replaceFirstChar { it.uppercase() }}: ${n.current}.", quick = STEP_QUICK)
            return
        }
        updateSteps(null)
        val count = prefs.markDone()
        viewModelScope.launch { dao.get(p.noteId)?.let { dao.update(it.copy(done = true)) } }
        answer("${PRAISE.random()} Все шаги пройдены, «${p.task}» сделано. Сегодня уже $count. Отдохните пару минут.")
    }

    /** Правило 5 минут: одно крошечное действие и таймер, бросить можно в любой момент. */
    private fun stuck(task: String) {
        val what = steps?.current ?: task.ifBlank { mainTask()?.title.orEmpty() }
        Focus.start(appContext, 5)
        refreshFocus()
        answer(
            if (what.isNotBlank()) "Это нормально, так бывает. Давайте только 5 минут: «$what». Таймер пошёл, потом можно бросить."
            else "Это нормально, так бывает. Выберите самое лёгкое дело на 5 минут, таймер пошёл. Потом можно бросить.",
            quick = listOf("Разбей на шаги", "Что делать?"),
        )
    }

    /** Выгрузка из головы: поток мыслей → отдельные напоминания и заметки. */
    private suspend fun brainDump(text: String) {
        thinking = true
        feedback = Feedback("Раскладываю по полочкам…")
        var lines = emptyList<String>()
        var fromAi = false
        if (prefs.aiOn && prefs.yandexReady && ru.ivan.jarvis.actions.Net.online(appContext)) {
            val r = gpt.complete(listOf("system" to Assistant.dumpPrompt(), "user" to text.take(2000)), maxTokens = 500)
            lines = r.text.orEmpty().lines()
                .map { it.trim().replace(Regex("^([-*•\\d.)\\s]+|CMD\\s*:\\s*)", RegexOption.IGNORE_CASE), "").trim() }
                .filter { Regex("^(напомни|запиши)\\s", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            fromAi = lines.isNotEmpty()
        }
        thinking = false
        if (!fromAi) lines = CoachParser.splitDump(text)
        var timed = 0
        val titles = mutableListOf<String>()
        for (line in lines) {
            val c = CommandParser.parse(line) as? Command.Save ?: continue
            saveOrQuery(line, announce = false)
            if (c.due != null) timed++
            titles += c.title
        }
        if (titles.isEmpty()) {
            answer("Не разобрал отдельных дел. Попробуйте ещё раз: «выгрузка: купить масло, позвонить Пете завтра в 10…»")
            return
        }
        val notes = titles.size - timed
        val list = titles.take(5).joinToString("; ") + if (titles.size > 5) " и ещё ${titles.size - 5}" else ""
        answer(
            "Голова свободна! Записал ${titles.size}: $list. С временем — $timed" +
                (if (notes > 0) ", во входящих — $notes. Разобрать их можно потом за 2 минуты." else "."),
            quick = if (notes > 0) listOf("Разбери входящие", "Что делать?") else listOf("Что делать?"),
            source = if (fromAi) "🧠 ${gpt.modelLabel}" else "",
        )
    }

    /** Перенос несделанного на завтра без чувства вины. all — заодно разгрузить остаток сегодняшнего (кроме срочного). */
    private suspend fun unload(all: Boolean) {
        val now = System.currentTimeMillis()
        val today = LocalDate.now()
        val tomorrow = today.plusDays(1)
        val open = dao.all().filter {
            it.type == ItemType.REMINDER && !it.done && it.dueAt != null && it.repeat == ru.ivan.jarvis.data.Repeat.NONE
        }
        val overdue = open.filter { it.dueAt!! < now && (!it.allDay || it.dueAt.toLocalDateTime().toLocalDate() < today) }
        val rest = if (all) open.filter { it.dueAt!! >= now && it.occursOn(today) && it.priority == 0 } else emptyList()
        val move = (overdue + rest).distinctBy { it.id }
        if (move.isEmpty()) {
            answer("Переносить нечего, просроченных дел нет. " + whatNow())
            return
        }
        for (item in move) {
            val t = item.dueAt!!.toLocalDateTime().toLocalTime()
            val at = if (item.allDay || t.hour < 9) java.time.LocalTime.of(9, 0) else t
            val moved = item.copy(dueAt = tomorrow.atTime(at).toMillis())
            ReminderScheduler.cancelSnooze(appContext, item.id)
            NotificationManagerCompat.from(appContext).cancel(item.id.toInt())
            dao.update(moved)
            ReminderScheduler.schedule(appContext, moved)
            syncCal(moved)
        }
        val left = dao.all().filter { it.type == ItemType.REMINDER && !it.done && it.dueAt != null && it.occursOn(today) && it.dueAt >= now }
        val tail = left.maxByOrNull { it.priority }?.let { " Сегодня осталось главное: ${it.title}." } ?: " На сегодня всё, можно выдохнуть."
        answer("Без проблем, так бывает. Перенёс на завтра: ${move.size} — ${move.take(3).joinToString("; ") { it.title }}." + tail)
    }

    private fun stepsWord(n: Int) = when {
        n % 10 == 1 && n % 100 != 11 -> "шаг"
        n % 10 in 2..4 && n % 100 !in 12..14 -> "шага"
        else -> "шагов"
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
            "Без рук: включите в настройках «Слушать всегда» и говорите «Джарвис, …». " +
            "Сообщения: «прочитай сообщения», «ответь Саше: буду через час». " +
            "Экран (после включения в спецвозможностях): «назад», «на главный экран», «нажми Принять», «прокрути вниз», «сделай скриншот», «что на экране». " +
            "Память: «запомни, что мой поставщик — Автодок», «что ты обо мне знаешь», «забудь про Автодок». " +
            "Поручения: «поручи Саше поменять масло на Ларгусе», «жду ответа от поставщика», «разбери входящие», «срочно позвонить в банк». " +
            "Автосервис: «новый клиент Петров, Солярис, замена колодок», «Солярис Петрова готов», «заказал колодки на Солярис», " +
            "«что в работе», «приход 15 тысяч от Петрова», «касса за неделю». " +
            "Ищу в интернете и отвечаю голосом: цены, моменты затяжки, объёмы масла, коды ошибок, «есть ли в наличии колодки на Солярис», «что пишут на Драйве про стук в подвеске Тигуана», «найди на форумах почему троит Логан». " +
            "Фото: «сфоткай артикул» или кнопка 📷 — прочитаю артикул (и сразу проценка) или VIN (марка и год). " +
            "Умные ответы: на сообщение клиента готовлю 2 варианта ответа с учётом его заказа — отправка одной кнопкой из уведомления. " +
            "Проценка запчастей: «проценка Манн W 75/3», «где купить колодки TRW GDB1330» — сначала ЗетЗап по Нижнему, потом Автотрейд, Росско, Парт-Ком, Иксора, Топдеталь; аналоги, если дешевле. «Найди болячку Солярис 2015». «Сводка» — итоги и что дальше. " +
            "Сам делаю в приложениях (нужны спецвозможности и ИИ): «открой Озон и найди масляный фильтр Mann», «в ютубе включи Высоцкого», «сделай сам: напиши Саше в телеграм, что задерживаюсь». Перед отправкой и оплатой спрошу «да». «Стоп» — остановлюсь. " +
            "СДВГ-помощь: «разбей ремонт гаража на шаги» (покажу по одному, говорите «дальше»), «с чего начать отчёт», " +
            "«выгрузка: …» (всё из головы разложу по делам), «не могу начать» (правило 5 минут), «перенеси всё несделанное на завтра», «разгрузи день». " +
            "Для работы: «фокус 25 минут», «что мне сейчас делать», «сделал …», «перенеси … на завтра», «удали …», «итоги дня». " +
            "С ключом Яндекса отвечаю на любые вопросы и разбиваю задачи на шаги. Что сделаем, шеф?"
        private val STOP_DIALOG = Regex("^(джарвис[, ]*)?(спасибо|всё|все|хватит|отбой|пока|ничего|не надо)[.!]?$")
        const val SAVE_NOTE = "Сохранить в заметку"
        private val SAVE_NOTE_RX = Regex("^(джарвис[, ]*)?(сохрани|запиши)\\s+(это|ответ|найденное)(\\s+в\\s+заметк\\p{L}*)?[.!]?$", RegexOption.IGNORE_CASE)
        private val LOCAL_LEAD = Regex("^(джарвис[, ]*)?(напомни|напоминай|запиши|запомни|добавь|сохрани)", RegexOption.IGNORE_CASE)
        private val PLAN_WORDS = Regex("(план|день|дня|задач|дела|успеть|приоритет|расписани)", RegexOption.IGNORE_CASE)
        private val YES = Regex("^(да|ага|угу|отправь|отправляй|давай|конечно|верно|подтверждаю|ок|окей|точно|yes)(?![\\p{L}])")
        private val NO = Regex("^(нет|не\\s+надо|не\\s+нужно|отмена|отмени|не\\s+отправляй|никогда|не\\s+напоминай|стоп)(?![\\p{L}])")
        private val NOTE_WORDS = Regex("(запиши|заметк|запомни|сохрани|иде[яюи]|список|мысль|в заметки)", RegexOption.IGNORE_CASE)
        private val INBOX = Regex("^(джарвис[, ]*)?(разбери|разберем|разберём|разобрать|давай\\s+разберем|разбор)\\s+(входящ\\p{L}*|заметк\\p{L}*)|^(что\\s+во\\s+входящих|входящие)$", RegexOption.IGNORE_CASE)
        private val PHOTO = Regex("^(джарвис[, ]*)?(сфоткай|сфотографируй|фото|сними|распознай|прочитай|считай)\\s+(артикул|этикетк\\p{L}*|vin|вин|вин-код|номер\\s+детали|запчаст\\p{L}*)", RegexOption.IGNORE_CASE)
        private val STEPS_ASK = Regex("(разб|раздел|по\\s+шагам|шаг|с\\s+чего\\s+начать|как\\s+начать|план)", RegexOption.IGNORE_CASE)
        private val SUMMARY = Regex("^(джарвис[, ]*)?(сводка|сводку|дай\\s+сводку|сводка\\s+дня)[.!]?$", RegexOption.IGNORE_CASE)
        private val AGENT_STOP = Regex("^(джарвис[, ]*)?(стоп|хватит|отмена|остановись|прекрати|не\\s+надо)", RegexOption.IGNORE_CASE)
        private val STEP_QUICK = listOf("Дальше", "Фокус 5 минут", "Все шаги", "Хватит шагов")
        private const val NO_STEPS = "Сейчас нет задачи по шагам. Скажите: «разбей <задача> на шаги»."
        private val PRAISE = listOf("Отлично, шеф!", "Есть, шеф!", "Готово, шеф!", "Так держать, шеф!", "Сделано!")
    }
}
