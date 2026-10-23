package ru.ivan.jarvis.parse

import java.time.LocalDateTime

enum class VolumeChange { UP, DOWN, MUTE, UNMUTE, MAX }
enum class MediaKey { PLAY, PAUSE, NEXT, PREVIOUS }
enum class SettingsPanel { WIFI, BLUETOOTH, INTERNET, DISPLAY, SOUND, BATTERY, GENERAL }

/** Команда управления телефоном (в отличие от заметки/напоминания). */
sealed interface PhoneAction {
    data class OpenApp(val name: String) : PhoneAction
    data class Call(val target: String) : PhoneAction
    /** app: "" — SMS, "whatsapp", "telegram". strict = false — угадано по «напиши маме …», нужен контакт. */
    data class Sms(val target: String, val text: String, val app: String = "", val strict: Boolean = true) : PhoneAction
    /** hour == null — просто открыть будильники. */
    /** date — конкретный день, если это не ближайшие сутки; days — повтор по дням недели. */
    data class Alarm(
        val hour: Int?,
        val minute: Int,
        val label: String,
        val date: java.time.LocalDate? = null,
        val days: List<java.time.DayOfWeek> = emptyList(),
    ) : PhoneAction
    data class Timer(val seconds: Int) : PhoneAction
    data class Flashlight(val on: Boolean) : PhoneAction
    data class Volume(val change: VolumeChange) : PhoneAction
    data class Media(val key: MediaKey) : PhoneAction
    data class PlayMusic(val query: String) : PhoneAction
    data class Navigate(val destination: String) : PhoneAction
    data class WebSearch(val query: String) : PhoneAction
    data class Settings(val panel: SettingsPanel) : PhoneAction
    data object Camera : PhoneAction
    data object Help : PhoneAction
    /** Фокус-сессия: работа [minutes], потом перерыв. */
    data class Focus(val minutes: Int) : PhoneAction
    data object FocusStop : PhoneAction
    /** «Что мне сейчас делать?» */
    data object WhatNow : PhoneAction
}

/**
 * Распознаёт команды управления: «открой телеграм», «позвони маме», «разбуди меня в 7»,
 * «таймер на 10 минут», «включи фонарик», «громче», «маршрут до работы», «найди рецепт борща».
 * Возвращает null, если фраза — не команда (тогда это заметка или напоминание).
 */
object ActionParser {
    private val OPT = setOf(RegexOption.IGNORE_CASE)
    private fun rx(p: String) = Regex(p, OPT)
    private const val R = "(?![\\p{L}\\d])"

    private val wake = rx("^\\s*(джарвис|jarvis|жарвис)[\\s,.!]*")

    private val help = rx("^(что ты умеешь|что умеешь|помощь|какие (у тебя )?(есть )?команды|что ты можешь)")
    private val focusStop = rx("^(стоп|закончи|останови|выключи|отмени)\\s+(фокус|помодоро|таймер\\s+фокуса)$R")
    private val focus = rx("^(фокус|режим\\s+фокуса|помодоро|начни\\s+фокус|включи\\s+фокус|сосредоточ\\p{L}*)$R\\s*(.*)$")
    private val whatNow = rx("^(что\\s+(мне\\s+)?(сейчас|теперь|дальше)\\s+делать|что\\s+дальше|чем\\s+(мне\\s+)?заняться|что\\s+(мне\\s+)?делать)$R")
    private val flashlight = rx("^(включи|зажги|выключи|погаси|отключи)\\s+(фонарик|фонарь|вспышку)$R")
    private val volUp = rx("^(сделай\\s+)?(погромче|громче|прибавь(\\s+(звук|громкость))?|увеличь\\s+громкость)$R")
    private val volDown = rx("^(сделай\\s+)?(потише|тише|убавь(\\s+(звук|громкость))?|уменьши\\s+громкость)$R")
    private val volMute = rx("^(выключи\\s+звук|отключи\\s+звук|без\\s+звука|беззвучный(\\s+режим)?|включи\\s+беззвучный(\\s+режим)?)$R")
    private val volUnmute = rx("^включи\\s+звук$R")
    private val volMax = rx("(звук|громкость)\\s+на\\s+(максимум|полную|максимальн)")
    private val pause = rx("^(пауза|поставь\\s+на\\s+паузу|останови\\s+музыку|стоп\\s+музыка|выключи\\s+музыку|стоп)$R")
    private val next = rx("^(следующ\\p{L}*\\s+(трек|песн\\p{L}*)|переключи\\s+(трек|песню)|дальше)$R")
    private val prev = rx("^(предыдущ\\p{L}*\\s+(трек|песн\\p{L}*)|верни\\s+(трек|песню))$R")
    private val resume = rx("^(продолжи|продолжай|возобнови)(\\s+(музыку|воспроизведение))?$R")
    private val music = rx("^(включи|поставь|сыграй|играй|воспроизведи)\\s+(музыку|песню|трек|альбом|плейлист)$R\\s*(.*)$")
    private val musicVerb = rx("^(сыграй|воспроизведи)\\s+(.+)$")
    private val timer = rx("^(поставь\\s+|заведи\\s+|установи\\s+|запусти\\s+)?(таймер|засеки)$R\\s*(.*)$")
    private val alarm = rx("^(разбуди(\\s+меня)?|подними\\s+меня|(поставь|заведи|установи|включи|создай|сделай)\\s+(мне\\s+)?будильник|будильник|(мне\\s+)?(надо|нужно)\\s+(встать|проснуться))$R\\s*(.*)$")
    private val please = rx("[\\s,]*(пожалуйста|плиз)[\\s,]*")
    private val weekdaysRx = rx("(по\\s+будням|в\\s+будни|по\\s+рабочим(\\s+дням)?|в\\s+рабочие\\s+дни)")
    private val weekendRx = rx("(по\\s+выходным|в\\s+выходные)")
    private val everyDayRx = rx("(каждый\\s+день|ежедневно|всегда|каждое\\s+утро)")
    private val call = rx("^(позвони|набери|вызови|звонок)$R\\s+(.+)$")
    private val sms = rx("^(напиши|отправь|скинь|пошли|черкни)\\s+(смс|sms|эсэмэс|эсэмэску|смску|сообщение|сообщу|смс-ку)$R\\s*(.+)$")
    private val messenger = rx("^(напиши|отправь|скинь|пошли)\\s+(?:сообщение\\s+)?(?:в|во|по)\\s+(ватсап\\p{L}*|вотсап\\p{L}*|вацап\\p{L}*|whatsapp|телеграм\\p{L}*|телегу|телеге|telegram)$R\\s*(.+)$")
    private val softSms = rx("^(напиши|отправь|скинь|передай)\\s+(?!(заметк|список|иде|текст|письм|пост|ответ|в\\s|себе|мне\\s|план|отчет|отчёт|статью|код|сообщение))(\\p{L}+(?:\\s+\\p{Lu}\\p{L}+)?)\\s*(?:[:,]|\\s+(?:что|чтобы))\\s*(.+)$")
    private val politeLead = rx("^(можешь|можете|сможешь|мог\\s+бы\\s+ты|не\\s+мог\\s+бы\\s+ты|не\\s+могли\\s+бы\\s+вы|давай|будь\\s+добр)\\s+")
    private val infinitives = mapOf(
        "открыть" to "открой", "запустить" to "запусти", "позвонить" to "позвони", "набрать" to "набери",
        "написать" to "напиши", "отправить" to "отправь", "поставить" to "поставь", "разбудить" to "разбуди",
        "включить" to "включи", "выключить" to "выключи", "найти" to "найди", "засечь" to "засеки",
        "проложить" to "проложи", "сделать" to "сделай", "скинуть" to "скинь",
    )
    private val navigate = rx("^(проложи\\s+маршрут|построй\\s+маршрут|маршрут|как\\s+(доехать|добраться|пройти)|навигатор|отвези\\s+меня|поехали)$R\\s*(до|в|к|на)?\\s*(.+)$")
    private val camera = rx("^(открой\\s+камеру|включи\\s+камеру|запусти\\s+камеру|сфотографируй|сделай\\s+(фото|снимок|фотку|селфи)|камера)$R")
    private val settingsVerb = rx("^(включи|выключи|отключи|открой|настрой|настройки)$R")
    private val search = rx("^(найди|поищи|загугли|погугли|поиск|найти|ищи)(\\s+(в\\s+интернете|в\\s+гугле|в\\s+яндексе))?\\s+(.+)$")
    private val openApp = rx("^(открой|открою|откройте|запусти|запустите|включи|зайди\\s+в|зайди\\s+во|перейди\\s+в|иди\\s+в|хочу\\s+в)\\s+(?:мне\\s+)?(приложение\\s+|прогу\\s+|программу\\s+)?(.+)$")

    fun parse(raw: String, now: LocalDateTime = LocalDateTime.now()): PhoneAction? {
        var s = wake.replace(raw.trim().replace('ё', 'е').replace('Ё', 'Е'), "").replace(please, " ")
            .trim().trimEnd('.', '!', '?', ' ').replace(Regex("\\s+"), " ")
        // «можешь открыть телеграм» → «открой телеграм»
        politeLead.find(s)?.let { m ->
            val rest = s.substring(m.range.last + 1).replace(rx("^(мне|пожалуйста)\\s+"), "")
            val first = rest.substringBefore(' ').lowercase()
            infinitives[first]?.let { s = it + rest.substring(first.length) }
        }
        if (s.isEmpty()) return null
        val l = s.lowercase()

        if (help.containsMatchIn(l)) return PhoneAction.Help
        if (focusStop.containsMatchIn(l)) return PhoneAction.FocusStop
        focus.find(s)?.let { m ->
            val minutes = parseDuration(m.groupValues[2])?.let { (it + 59) / 60 } ?: 25
            return PhoneAction.Focus(minutes)
        }
        if (whatNow.containsMatchIn(l)) return PhoneAction.WhatNow
        flashlight.find(l)?.let { return PhoneAction.Flashlight(it.groupValues[1] in setOf("включи", "зажги")) }
        when {
            volMax.containsMatchIn(l) -> return PhoneAction.Volume(VolumeChange.MAX)
            volUp.containsMatchIn(l) -> return PhoneAction.Volume(VolumeChange.UP)
            volDown.containsMatchIn(l) -> return PhoneAction.Volume(VolumeChange.DOWN)
            volMute.containsMatchIn(l) -> return PhoneAction.Volume(VolumeChange.MUTE)
            volUnmute.containsMatchIn(l) -> return PhoneAction.Volume(VolumeChange.UNMUTE)
            pause.containsMatchIn(l) -> return PhoneAction.Media(MediaKey.PAUSE)
            next.containsMatchIn(l) -> return PhoneAction.Media(MediaKey.NEXT)
            prev.containsMatchIn(l) -> return PhoneAction.Media(MediaKey.PREVIOUS)
            resume.containsMatchIn(l) -> return PhoneAction.Media(MediaKey.PLAY)
        }
        music.find(s)?.let { return PhoneAction.PlayMusic(it.groupValues[3].trim()) }
        musicVerb.find(s)?.let { return PhoneAction.PlayMusic(it.groupValues[2].trim()) }

        timer.find(s)?.let { m -> parseDuration(m.groupValues[3])?.let { return PhoneAction.Timer(it) } }
        alarm.find(s)?.let { m -> return parseAlarm(m.groupValues.last(), now) }

        call.find(s)?.let { m ->
            // «позвони маме завтра в 9» — это напоминание, а не звонок сейчас
            if ((CommandParser.parse(s, now) as? Command.Save)?.due != null) return null
            val target = m.groupValues[2].replace(rx("^(мне\\s+|на\\s+номер\\s+|по\\s+номеру\\s+)"), "").trim()
            return PhoneAction.Call(target)
        }
        messenger.find(s)?.let { m ->
            val app = if (m.groupValues[2].lowercase().let { it.startsWith("т") || it.startsWith("tel") }) "telegram" else "whatsapp"
            return parseSms(m.groupValues[3]).copy(app = app)
        }
        sms.find(s)?.let { m -> return parseSms(m.groupValues[3]) }
        softSms.find(s)?.let { m ->
            if ((CommandParser.parse(s, now) as? Command.Save)?.due != null) return null
            return PhoneAction.Sms(m.groupValues[3].trim(), m.groupValues[4].trim(), strict = false)
        }
        navigate.find(s)?.let { m -> return PhoneAction.Navigate(m.groupValues[4].trim()) }
        if (camera.containsMatchIn(l)) return PhoneAction.Camera
        if (settingsVerb.containsMatchIn(l)) settingsPanel(l)?.let { return PhoneAction.Settings(it) }
        search.find(s)?.let { m -> return PhoneAction.WebSearch(m.groupValues[4].trim()) }
        openApp.find(s)?.let { m ->
            val name = m.groupValues[3].trim()
            if (name.lowercase() == "настройки") return PhoneAction.Settings(SettingsPanel.GENERAL)
            return PhoneAction.OpenApp(name)
        }
        return null
    }

    private fun settingsPanel(l: String): SettingsPanel? = when {
        rx("(wi-?fi|вай-?\\s?фай|вайфай)").containsMatchIn(l) -> SettingsPanel.WIFI
        rx("(блютуз|bluetooth|блютус)").containsMatchIn(l) -> SettingsPanel.BLUETOOTH
        rx("(мобильн\\p{L}* интернет|интернет|передач\\p{L}* данных)").containsMatchIn(l) -> SettingsPanel.INTERNET
        rx("(яркост|экран)").containsMatchIn(l) -> SettingsPanel.DISPLAY
        rx("(настройки звука|громкост)").containsMatchIn(l) -> SettingsPanel.SOUND
        rx("(батаре|аккумулятор|энергосбережен)").containsMatchIn(l) -> SettingsPanel.BATTERY
        rx("^(открой\\s+)?настройки$").containsMatchIn(l) -> SettingsPanel.GENERAL
        else -> null
    }

    /** «на 5 минут», «полчаса», «1 час 20 минут», «30 секунд» → секунды. */
    fun parseDuration(text: String): Int? {
        val s = NumberWords.replace(text).lowercase()
        var total = 0
        if (rx("полтора\\s+часа").containsMatchIn(s)) total += 5400
        else if (rx("полчаса").containsMatchIn(s)) total += 1800
        if (rx("полминуты").containsMatchIn(s)) total += 30
        rx("(\\d+)\\s*(час|ч$R)").find(s)?.let { total += it.groupValues[1].toInt() * 3600 }
        rx("(\\d+)\\s*(минут|мин)").find(s)?.let { total += it.groupValues[1].toInt() * 60 }
        rx("(\\d+)\\s*(секунд|сек)").find(s)?.let { total += it.groupValues[1].toInt() }
        if (total == 0) {
            when {
                rx("(^|\\s)(на\\s+)?минуту").containsMatchIn(s) -> total = 60
                rx("(^|\\s)(на\\s+)?час$R").containsMatchIn(s) -> total = 3600
            }
        }
        return total.takeIf { it > 0 }
    }

    private fun parseAlarm(rest: String, now: LocalDateTime): PhoneAction.Alarm {
        var text = rest.trim()
        val days = mutableListOf<java.time.DayOfWeek>()
        fun cut(r: Regex, d: List<java.time.DayOfWeek>) {
            if (r.containsMatchIn(text)) { days += d; text = text.replace(r, " ").trim() }
        }
        val all = java.time.DayOfWeek.entries
        cut(weekdaysRx, all.take(5))
        cut(weekendRx, all.takeLast(2))
        if (days.isEmpty()) cut(everyDayRx, all)
        // «на 7:30» → «в 7:30», «на завтра на 7» → «завтра в 7»
        text = text.replace(rx("(^|\\s)на\\s+(?=(завтра|послезавтра|сегодня))"), " ")
            .replace(rx("(^|\\s)на\\s+(?=\\d)"), " в ").trim()
        if (text.isEmpty()) return PhoneAction.Alarm(null, 0, "", days = days)
        val save = CommandParser.parse(text, now) as? Command.Save
        val due = save?.due ?: return PhoneAction.Alarm(null, 0, "", days = days)
        val label = save.title.takeIf { it != "Напоминание" && it != "Заметка" } ?: ""
        // «в 6» без уточнения для будильника — скорее утро
        val hour = if (!rx("(вечера|дня|ночи|вечером|днем)").containsMatchIn(text) && due.hour in 13..18) due.hour - 12 else due.hour
        val time = java.time.LocalTime.of(hour, due.minute)
        // Часы сами ставят на ближайшее такое время; дату передаём, только если это другой день
        val nearest = if (time.isAfter(now.toLocalTime())) now.toLocalDate() else now.toLocalDate().plusDays(1)
        val explicitDay = rx("(сегодня|завтра|послезавтра|понедельник|вторник|сред|четверг|пятниц|суббот|воскресень|числа|\\d+\\.\\d+|январ|феврал|март|апрел|ма[яй]|июн|июл|август|сентябр|октябр|ноябр|декабр)").containsMatchIn(text)
        val date = if (days.isEmpty() && explicitDay && due.toLocalDate() != nearest) due.toLocalDate() else null
        return PhoneAction.Alarm(hour, due.minute, label, date, days)
    }

    private fun parseSms(rest: String): PhoneAction.Sms {
        val r = rest.trim()
        val split = rx("^(.+?)\\s*(?:[:,]|\\s+(?:что|текст)\\s+)\\s*(.+)$").find(r)
        if (split != null) return PhoneAction.Sms(split.groupValues[1].trim(), split.groupValues[2].trim())
        val words = r.split(Regex("\\s+"), limit = 2)
        return PhoneAction.Sms(words[0], words.getOrElse(1) { "" })
    }
}
