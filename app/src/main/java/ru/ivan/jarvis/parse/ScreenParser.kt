package ru.ivan.jarvis.parse

/** Системные действия, которые умеет только служба спецвозможностей. */
enum class ScreenGlobal { BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS, LOCK, SCREENSHOT }

/** «Назад», «нажми Отправить», «прокрути вниз» — управление экраном любого приложения. */
sealed interface ScreenCommand {
    data class Global(val action: ScreenGlobal) : ScreenCommand
    data class Click(val text: String) : ScreenCommand
    data class Scroll(val forward: Boolean) : ScreenCommand
}

object ScreenParser {
    private val OPT = setOf(RegexOption.IGNORE_CASE)
    private fun rx(p: String) = Regex(p, OPT)

    private val wake = rx("^\\s*(джарвис|jarvis|жарвис)[\\s,.!]*")
    private val globals = listOf(
        rx("^(назад|вернись(\\s+назад)?|шаг\\s+назад)$") to ScreenGlobal.BACK,
        rx("^(на\\s+)?(главный\\s+экран|рабочий\\s+стол)$|^(выйди|сверни)(\\s+все)?(\\s+на\\s+главный(\\s+экран)?)?$") to ScreenGlobal.HOME,
        rx("^(покажи\\s+|открой\\s+)?(недавние|последние|открытые)\\s+приложения$|^переключи\\s+приложение$") to ScreenGlobal.RECENTS,
        rx("^(открой|опусти|покажи)\\s+(шторку|уведомления)$|^шторка$") to ScreenGlobal.NOTIFICATIONS,
        rx("^(открой\\s+|покажи\\s+)?быстрые\\s+настройки$") to ScreenGlobal.QUICK_SETTINGS,
        rx("^(заблокируй|блокируй|выключи)\\s+(экран|телефон)$") to ScreenGlobal.LOCK,
        rx("^(сделай\\s+)?(скриншот|снимок\\s+экрана)$") to ScreenGlobal.SCREENSHOT,
    )
    private val click = rx("^(нажми|тыкни|кликни|жми)(\\s+на)?(\\s+кнопку)?\\s+(.+)$")
    private val scroll = rx("^(прокрути|листай|пролистай|промотай)(\\s+(вниз|дальше|вверх|наверх|назад))?$")

    fun parse(raw: String): ScreenCommand? {
        val s = raw.replace(wake, "").trim().trimEnd('.', '!', '?').replace('ё', 'е').trim()
        if (s.isEmpty()) return null
        globals.firstOrNull { it.first.matches(s) }?.let { return ScreenCommand.Global(it.second) }
        click.matchEntire(s)?.let { m ->
            val t = m.groupValues[4].trim().trim('«', '»', '"')
            return if (t.isBlank()) null else ScreenCommand.Click(t)
        }
        scroll.matchEntire(s)?.let { m ->
            val dir = m.groupValues[3].lowercase()
            return ScreenCommand.Scroll(dir !in setOf("вверх", "наверх", "назад"))
        }
        return null
    }
}
