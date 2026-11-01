package ru.ivan.jarvis.parse

/**
 * «Супер-Джарвис»: ИИ сам выполняет дело в любом приложении — смотрит на экран, нажимает, вводит текст,
 * листает и повторяет, пока не сделает (как DroidRun, AutoGLM, OpenDroid).
 * Экран передаётся ИИ списком пронумерованных элементов (из службы спецвозможностей), ИИ отвечает одним действием.
 * Перед отправкой, оплатой, удалением — обязательно «да» от пользователя.
 */
sealed interface AgentAction {
    data class Tap(val index: Int) : AgentAction
    data class Type(val index: Int, val text: String) : AgentAction
    data class Scroll(val down: Boolean) : AgentAction
    data class Open(val app: String) : AgentAction
    data object Back : AgentAction
    data object Home : AgentAction
    data object Wait : AgentAction
    data class Done(val summary: String) : AgentAction
    /** ИИ не может продолжить без человека: нужен выбор, пароль, данные. */
    data class Ask(val question: String) : AgentAction
}

data class AgentGoal(val text: String, val app: String?)

/** Элемент экрана, как его видит ИИ. */
data class UiElement(val index: Int, val kind: String, val label: String)

object AgentProtocol {
    const val MAX_STEPS = 15
    private val OPT = setOf(RegexOption.IGNORE_CASE)
    private fun rx(p: String) = Regex(p, OPT)

    private val wake = rx("^\\s*(джарвис|jarvis|жарвис)[\\s,.!]*")

    /** Явные просьбы «сделай сам». */
    private val explicit = rx("^(сделай\\s+сам|выполни\\s+сам|сделай\\s+за\\s+меня|сам\\s+сделай|агент|сделай\\s+на\\s+телефоне)[\\s:,.-]+(.{3,})$")
    /** «открой ютуб и включи музыку», «зайди в озон и найди фильтр». */
    private val openAnd = rx("^(открой|зайди\\s+в|запусти|перейди\\s+в)\\s+(.+?)\\s*,?\\s+(и|а\\s+потом|потом|затем|там)\\s+(.{3,})$")
    /** «в ютубе найди…», «на авито посмотри…». */
    private val inApp = rx("^(в|во|на)\\s+(\\S+(\\s+\\S+)?)\\s+(найди|включи|поставь|закажи|напиши|отправь|посмотри|открой|добавь|поищи|оформи|запусти|забронируй|вызови)$R.{3,}$")
    /** «закажи такси до дома», «вызови такси», «оформи заказ». */
    private val order = rx("^(закажи|вызови\\s+такси|оформи\\s+заказ|забронируй|купи\\s+в\\s+\\S+)$R.*$")
    private const val R = "(?![\\p{L}\\d])"

    /**
     * Цель для агента или null, если фраза — не многошаговое дело в приложениях.
     * app — какое приложение названо («ютубе», «озон»): его надо проверить по установленным,
     * чтобы «в понедельник напиши отчёт» не ушло агенту.
     */
    fun goal(raw: String): AgentGoal? {
        val s = raw.replace(wake, "").trim().trimEnd('.', '!').trim()
        if (s.isEmpty()) return null
        explicit.find(s)?.let { return AgentGoal(it.groupValues[2].trim(), null) }
        openAnd.matchEntire(s)?.let { return AgentGoal(s, it.groupValues[2].trim()) }
        inApp.matchEntire(s)?.let { return AgentGoal(s, it.groupValues[2].trim()) }
        if (order.matches(s)) return AgentGoal(s, null)
        return null
    }

    private val riskyWords = rx(
        "(отправ|оплат|оплач|купить|куплю|заказать|оформить|подтверд|перевест|перевод|удалить|удали|стереть|" +
            "списать|pay|send|buy|order|confirm|delete|checkout|позвонить|вызвать|забронир|разместить|опубликов)",
    )

    /** Нажатие, после которого не отменишь: спрашиваем «да». */
    fun risky(label: String): Boolean = riskyWords.containsMatchIn(label)

    fun render(elements: List<UiElement>): String =
        elements.joinToString("\n") { "[${it.index}] ${it.kind}: ${it.label}" }

    fun systemPrompt(): String = """Ты управляешь Android-телефоном пользователя, чтобы выполнить его задачу. На каждом шаге тебе дают цель, что уже сделано и список элементов экрана [номер] тип: подпись.
Ответь ровно одной строкой с одним действием:
нажми N — нажать элемент N
введи N: текст — ввести текст в поле N
листай вниз | листай вверх
открой <приложение> — запустить приложение по названию
назад | домой | жди
готово: <коротко, что сделано или что найдено — это прочитают вслух>
спроси: <вопрос> — если нужен выбор или данные, которых нет (пароль, адрес, какой вариант)
Правила: не выдумывай номера, бери только из списка. Если нужного нет — листай или вернись назад. Не повторяй одно и то же действие больше двух раз.
Если цель уже достигнута на экране — сразу «готово». Ничего не пиши кроме действия."""

    fun stepPrompt(goal: String, app: String, screen: String, history: List<String>): String = buildString {
        append("Цель: ").append(goal).append('\n')
        append("Сделано: ").append(if (history.isEmpty()) "ничего" else history.takeLast(6).joinToString("; ")).append('\n')
        append("Открыто: ").append(app.ifBlank { "неизвестно" }).append('\n')
        append("Экран:\n").append(screen.ifBlank { "(элементов не видно)" })
    }

    private val tap = rx("^(нажми|нажать|тапни|tap|click)\\s*\\[?(\\d+)\\]?")
    private val type = rx("^(введи|ввести|напиши|type)\\s*\\[?(\\d+)\\]?\\s*[:,-]?\\s*(.+)$")
    private val scroll = rx("^(листай|прокрути|scroll|swipe)\\s*(вниз|вверх|down|up)?")
    private val open = rx("^(открой|запусти|open|launch)\\s+(.+)$")
    private val back = rx("^(назад|back)$R")
    private val home = rx("^(домой|на\\s+главный|home)$R")
    private val wait = rx("^(жди|подожди|wait)$R")
    private val done = rx("^(готово|выполнено|done|finish)$R\\s*[:,.-]?\\s*(.*)$")
    private val ask = rx("^(спроси|вопрос|ask)\\s*[:,-]?\\s*(.+)$")

    /** Первое понятное действие из ответа ИИ. */
    fun parse(answer: String): AgentAction? {
        for (rawLine in answer.lines()) {
            val l = rawLine.trim().replace(Regex("[*`#]"), "")
                .replace(rx("^(действие|action|ответ)\\s*:\\s*"), "")
                .trim().trimEnd('.')
            if (l.isEmpty()) continue
            done.find(l)?.let { return AgentAction.Done(it.groupValues[2].trim()) }
            ask.find(l)?.let { return AgentAction.Ask(it.groupValues[2].trim()) }
            type.find(l)?.let { m -> m.groupValues[2].toIntOrNull()?.let { return AgentAction.Type(it, m.groupValues[3].trim().trim('"', '«', '»')) } }
            tap.find(l)?.let { m -> m.groupValues[2].toIntOrNull()?.let { return AgentAction.Tap(it) } }
            scroll.find(l)?.let { m -> return AgentAction.Scroll(m.groupValues[2].lowercase() !in setOf("вверх", "up")) }
            if (back.containsMatchIn(l)) return AgentAction.Back
            if (home.containsMatchIn(l)) return AgentAction.Home
            if (wait.containsMatchIn(l)) return AgentAction.Wait
            open.find(l)?.let { return AgentAction.Open(it.groupValues[2].trim().trim('"', '«', '»')) }
        }
        return null
    }

    /** Как действие звучит для пользователя в строке прогресса. */
    fun describe(a: AgentAction, elements: List<UiElement>): String {
        fun label(i: Int) = elements.firstOrNull { it.index == i }?.label?.take(40) ?: "№$i"
        return when (a) {
            is AgentAction.Tap -> "нажимаю «${label(a.index)}»"
            is AgentAction.Type -> "ввожу «${a.text.take(40)}»"
            is AgentAction.Scroll -> if (a.down) "листаю вниз" else "листаю вверх"
            is AgentAction.Open -> "открываю ${a.app}"
            AgentAction.Back -> "назад"
            AgentAction.Home -> "на главный экран"
            AgentAction.Wait -> "жду загрузку"
            is AgentAction.Done -> "готово"
            is AgentAction.Ask -> "нужен ваш ответ"
        }
    }

    /** Телефоны на экране не отправляем в облако целиком. */
    private val phone = Regex("(?<![\\p{L}\\d])\\+?\\d[\\d\\s()\\-]{8,}\\d(?![\\p{L}\\d])")
    fun maskPhones(s: String) = phone.replace(s) { m -> "•••" + m.value.filter { it.isDigit() }.takeLast(2) }
}
