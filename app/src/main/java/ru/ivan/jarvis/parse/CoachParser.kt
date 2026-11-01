package ru.ivan.jarvis.parse

/**
 * СДВГ-коуч: команды, которые помогают начать и не утонуть.
 * Идеи из лучших СДВГ-помощников (Goblin Tools «Magic ToDo», Tiimo, Saner.ai):
 * разбивка на крошечные шаги и показ по одному, «выгрузка из головы», правило 5 минут,
 * перенос несделанного без чувства вины.
 */
sealed interface CoachCommand {
    /** «разбей задачу X на шаги», «с чего начать X», «помоги начать X». */
    data class Breakdown(val task: String) : CoachCommand
    /** «дальше», «сделал шаг», «следующий шаг». */
    data object StepDone : CoachCommand
    /** «какой шаг», «где я», «напомни шаг». */
    data object StepWhere : CoachCommand
    /** «все шаги», «покажи шаги». */
    data object StepsAll : CoachCommand
    /** «хватит шагов», «брось шаги». */
    data object StepsStop : CoachCommand
    /** «выгрузка: …», «выгрузи из головы …». Пустой text — Джарвис попросит говорить. */
    data class Dump(val text: String) : CoachCommand
    /** «перенеси всё несделанное на завтра», «разгрузи день». all — и оставшееся на сегодня тоже. */
    data class Unload(val all: Boolean) : CoachCommand
    /** «не могу начать», «залип», «прокрастинирую». */
    data class Stuck(val task: String) : CoachCommand
}

object CoachParser {
    private const val R = "(?![\\p{L}\\d])"
    private val OPT = setOf(RegexOption.IGNORE_CASE)
    private fun rx(p: String) = Regex(p, OPT)

    private val wake = rx("^\\s*(джарвис|jarvis|жарвис)[\\s,.!]*")

    private val breakdown = rx(
        "^(разбей|разбить|раздели|разложи|распиши)(\\s+мне)?(\\s+(задачу|дело|работу))?\\s+(.+?)(\\s+на\\s+(маленькие\\s+|мелкие\\s+|простые\\s+)?(шаги|этапы|части|пункты|кусочки))?$",
    )
    private val breakdownNoTask = rx("^(разбей|раздели|разложи)(\\s+(её|ее|это|задачу))?\\s+на\\s+(маленькие\\s+|мелкие\\s+)?(шаги|этапы|части)$")
    private val howStart = rx("^(с\\s+чего\\s+(мне\\s+)?начать|помоги\\s+(мне\\s+)?(начать|сделать)|как\\s+(мне\\s+)?начать)$R\\s*(.*)$")

    private val stepDoneAlways = rx("^((я\\s+)?(сделал[аи]?|выполнил[аи]?|готов[оа]?)\\s+(этот\\s+)?шаг|шаг\\s+(сделан|готов|выполнен)|следующий\\s+шаг|дальше\\s+по\\s+шагам)$R")
    private val stepDoneShort = rx("^(дальше|далее|готово|сделал[аи]?|сделано|есть|следующий|следующее|сделал[аи]?\\s*,?\\s*дальше|готово\\s*,?\\s*дальше)$")
    private val stepWhere = rx("^(какой\\s+(сейчас\\s+)?шаг|где\\s+я|на\\s+каком\\s+я\\s+шаге|напомни\\s+шаг|повтори\\s+шаг|текущий\\s+шаг)$R")
    private val stepsAll = rx("^(все\\s+шаги|покажи\\s+шаги|прочитай\\s+шаги|какие\\s+шаги)$R")
    private val stepsStop = rx("^(хватит\\s+шагов|брось\\s+шаги|сбрось\\s+шаги|отмени\\s+шаги|закончим\\s+с\\s+шагами|стоп\\s+шаги)$R")

    private val dump = rx(
        "^(выгрузка(\\s+мыслей)?|выгрузи(\\s+мысли)?(\\s+из\\s+головы)?|мозговой\\s+штурм|в\\s+голове|разгрузи\\s+голову|" +
            "запиши\\s+(всё|все)\\s+(подряд|что\\s+скажу)|brain\\s*dump|брейн\\s*дамп)$R[\\s:,.-]*(.*)$",
    )
    private val unload = rx(
        "^(перенеси|перенести|отложи|сдвинь)\\s+(всё|все)\\s+(несделанное|невыполненное|просроченное|пропущенное|оставшееся|что\\s+не\\s+успел\\p{L}*)(\\s+на\\s+завтра)?$R" +
            "|^(разгрузи|облегчи)\\s+(мой\\s+)?(день|сегодня)$R|^(начни|начать|начнём|начнем)\\s+день\\s+заново$R",
    )
    private val unloadAll = rx("(оставшееся|разгрузи|облегчи|заново)")
    private val stuck = rx(
        "^(я\\s+)?(не\\s+могу\\s+(начать|собраться|сосредоточиться|заставить\\s+себя)|залип\\p{L}*|прокрастинирую|туплю|" +
            "нет\\s+сил\\s+начать|никак\\s+не\\s+начну|не\\s+хочу\\s+начинать)$R\\s*(.*)$",
    )

    /** stepsActive — идёт разбор задачи по шагам: тогда короткое «дальше/готово» — про шаг. */
    fun parse(raw: String, stepsActive: Boolean = false): CoachCommand? {
        val s = raw.replace(wake, "").trim().trimEnd('.', '!', '?').trim()
        if (s.isEmpty()) return null
        dump.find(s)?.let { return CoachCommand.Dump(it.groupValues.last().trim()) }
        if (unload.containsMatchIn(s)) return CoachCommand.Unload(unloadAll.containsMatchIn(s))
        if (stepsStop.containsMatchIn(s)) return CoachCommand.StepsStop
        if (stepsAll.containsMatchIn(s)) return CoachCommand.StepsAll
        if (stepWhere.containsMatchIn(s)) return CoachCommand.StepWhere
        if (stepDoneAlways.containsMatchIn(s)) return CoachCommand.StepDone
        if (stepsActive && stepDoneShort.matches(s)) return CoachCommand.StepDone
        stuck.find(s)?.let { return CoachCommand.Stuck(cleanTask(it.groupValues.last())) }
        if (breakdownNoTask.matches(s)) return CoachCommand.Breakdown("")
        howStart.find(s)?.let { return CoachCommand.Breakdown(cleanTask(it.groupValues.last())) }
        breakdown.find(s)?.let { m ->
            val task = cleanTask(m.groupValues[5])
            // «разбей» без слова «шаги» и без задачи — не наше
            if (task.isNotEmpty()) return CoachCommand.Breakdown(task)
        }
        return null
    }

    private fun cleanTask(t: String) = t.trim()
        .replace(rx("^(с|со|за|над|про|в|на)\\s+"), "")
        .replace(rx("^(задач\\p{L}*|дел\\p{L}*|работ\\p{L}*)\\s+"), "")
        .trim(' ', ',', ':', '«', '»', '"')

    /**
     * Шаги из ответа ИИ: строки «1. …», «- …», «Шаг 2: …» → чистый список (3–8 шагов).
     * Пустой список, если ИИ ответил не списком.
     */
    fun parseSteps(answer: String): List<String> = answer.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.endsWith(":") }
        .map {
            it.replace(Regex("[*#_`]"), "")
                .replace(Regex("^[-•–—]+\\s*"), "")
                .replace(Regex("^(шаг\\s*)?\\d+[.):]?\\s*[-—:]?\\s*", OPT), "")
                .trim()
        }
        .filter { it.length in 3..160 && !it.startsWith("CMD", ignoreCase = true) }
        .take(8)

    /** Запасная разбивка без интернета: любая задача → крошечный старт + фокус + проверка. */
    fun fallbackSteps(task: String): List<String> {
        val t = task.ifBlank { "задача" }
        return listOf(
            "Открой или приготовь всё, что нужно для «$t» (2 минуты)",
            "Сделай самую первую, самую лёгкую часть (5 минут)",
            "Поработай над «$t» 25 минут в фокусе, не отвлекаясь",
            "Проверь результат и реши, что осталось (5 минут)",
            "Отметь «$t» сделанным и похвали себя",
        )
    }

    private val dumpSplit = Regex(
        "\\s*(?:[;\\n]|[.!?](?=\\s|$)|,\\s*(?:и\\s+)?(?:ещё|еще|потом|также|а\\s+также|плюс)\\s+|\\s(?:и\\s+ещё|и\\s+еще|а\\s+ещё|а\\s+еще|а\\s+потом|и\\s+потом|а\\s+также|плюс\\s+к\\s+этому)\\s|,\\s+(?=[\\p{L}]))\\s*",
        OPT,
    )

    /**
     * Выгрузка из головы без ИИ: делим поток на отдельные дела по точкам, «;», запятым,
     * «и ещё», «а потом». Слишком короткие обрывки отбрасываем.
     */
    fun splitDump(text: String): List<String> = text.split(dumpSplit)
        .map { it.trim().trim(',', '.', ' ').replace(Regex("^(ещё|еще|потом|также|и|а)\\s+", OPT), "").trim() }
        .filter { it.length >= 3 && it.split(' ').any { w -> w.length >= 3 } }
        .distinct()
        .take(15)
}

/** План «по шагам»: задача, шаги и номер текущего. Хранится в настройках строкой. */
data class StepPlan(val task: String, val steps: List<String>, val index: Int = 0, val noteId: Long = 0) {
    val current: String? get() = steps.getOrNull(index)
    val finished: Boolean get() = index >= steps.size
    val progress: String get() = "шаг ${index + 1} из ${steps.size}"
    fun next() = copy(index = index + 1)

    fun encode(): String = (listOf(task, index.toString(), noteId.toString()) + steps)
        .joinToString(SEP) { it.replace(SEP, " ") }

    companion object {
        private const val SEP = "\u001F"
        fun decode(s: String): StepPlan? {
            val p = s.split(SEP)
            if (p.size < 4) return null
            val idx = p[1].toIntOrNull() ?: return null
            return StepPlan(p[0], p.drop(3), idx, p[2].toLongOrNull() ?: 0)
        }
    }
}

/** «через 5 мин», «через 1 ч 20 мин» — чтобы время было видно (СДВГ: слепота ко времени). */
fun minutesLabel(minutes: Long): String = when {
    minutes < 1 -> "меньше минуты"
    minutes < 60 -> "$minutes мин"
    minutes % 60 == 0L -> "${minutes / 60} ч"
    else -> "${minutes / 60} ч ${minutes % 60} мин"
}
