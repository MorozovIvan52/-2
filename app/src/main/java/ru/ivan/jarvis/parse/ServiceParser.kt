package ru.ivan.jarvis.parse

/** Статусы заказ-наряда в автосервисе. */
enum class OrderStatus(val label: String) {
    ACCEPTED("Принят"),
    DIAGNOSTICS("Диагностика"),
    WAIT_PARTS("Ждём запчасти"),
    IN_WORK("В работе"),
    READY("Готов"),
    DONE("Выдан"),
}

sealed interface ServiceCommand {
    /** «новый клиент Петров, Солярис, номер а123вс, замена колодок» */
    data class NewOrder(val client: String, val car: String, val plate: String, val work: String, val phone: String) : ServiceCommand
    /** «Солярис Петрова готов». query — слова для поиска заказа. */
    data class SetStatus(val query: String, val status: OrderStatus) : ServiceCommand
    /** «заказал колодки на Солярис» (ordered = true) / «закажи колодки на Солярис». */
    data class Parts(val part: String, val query: String, val ordered: Boolean) : ServiceCommand
    /** «что в работе», «что по сервису». status = null — все незакрытые. */
    data class ListOrders(val status: OrderStatus?) : ServiceCommand
    /** «приход 15 тысяч от Петрова», «расход 3200 на запчасти». */
    data class Money(val amount: Long, val income: Boolean, val note: String) : ServiceCommand
    /** «касса за неделю», «сколько заработал сегодня». */
    data class MoneySummary(val days: Int) : ServiceCommand
}

/** Голосовые команды режима «Автосервис». Без Android — покрыто тестами. */
object ServiceParser {
    private const val L = "(?<![\\p{L}\\d])"
    private const val R = "(?![\\p{L}\\d])"
    private val OPT = setOf(RegexOption.IGNORE_CASE)
    private fun rx(p: String) = Regex(p, OPT)

    private val wake = rx("^\\s*(джарвис|jarvis|жарвис)[\\s,.!]*")

    /** Популярные модели и марки — чтобы отличать машину от фамилии. */
    private val cars = listOf(
        "солярис", "крета", "рио", "соренто", "спортейдж", "сид", "серато", "элантра", "акцент", "туксон", "санта фе", "хендай", "хундай", "киа",
        "логан", "сандеро", "дастер", "аркана", "каптюр", "меган", "рено", "ларгус", "веста", "гранта", "приора", "калина", "нива", "иксрей", "лада", "ваз", "газель", "уаз", "патриот",
        "камри", "королла", "рав4", "рав 4", "прадо", "ленд крузер", "крузак", "тойота", "хайлюкс",
        "поло", "гольф", "пассат", "тигуан", "джетта", "фольксваген", "октавия", "рапид", "кодиак", "суперб", "шкода",
        "фокус", "мондео", "форд", "куга", "астра", "опель", "корса", "мокка",
        "x-trail", "икстрейл", "кашкай", "альмера", "тиида", "ниссан", "жук", "террано",
        "мазда", "сх-5", "cx-5", "мицубиси", "аутлендер", "лансер", "паджеро", "асх",
        "бмв", "мерседес", "мерс", "ауди", "лексус", "вольво", "субару", "форестер", "хонда", "цивик", "сузуки", "витара",
        "черри", "чери", "тигго", "хавал", "haval", "джили", "geely", "кулрей", "чанган", "эксид", "омода",
        "шевроле", "нива шевроле", "лачетти", "авео", "круз", "пежо", "ситроен", "дэу", "нексия", "матиз",
    ).sortedByDescending { it.length }
    private val carRx = rx("$L(${cars.joinToString("|") { Regex.escape(it).replace(" ", "\\s+") }})(\\p{L}{0,3})$R")

    private const val PLATE_L = "[авекмнорстухabekmhopctyx]"
    private val plateRx = rx("(?:(?:гос\\.?\\s*)?номер(?:ом)?\\s+)?$L($PLATE_L)\\s?(\\d{3})\\s?($PLATE_L{2})(?:\\s?(\\d{2,3}))?$R")
    private val phoneRx = Regex("(?<!\\d)(\\+?[78][\\s\\-()]*\\d{3}[\\s\\-()]*\\d{3}[\\s\\-]*\\d{2}[\\s\\-]*\\d{2})(?!\\d)")
    private val workWords = rx("$L(замен\\p{L}*|поменять|ремонт\\p{L}*|диагност\\p{L}*|то|техобслуж\\p{L}*|покраск\\p{L}*|развал\\p{L}*|сход\\p{L}*|шиномонтаж|переобу\\p{L}*|масл\\p{L}*|колодк\\p{L}*|стук\\p{L}*|не\\s+заводится|чек|ошибк\\p{L}*|грм|сцеплени\\p{L}*|подвеск\\p{L}*|тормоз\\p{L}*|электрик\\p{L}*|кондиционер\\p{L}*|заправк\\p{L}*|компьютерн\\p{L}*|свеч\\p{L}*|фильтр\\p{L}*|аккумулятор\\p{L}*|кузов\\p{L}*|рихтовк\\p{L}*)$R")

    private val newOrder = rx("^(новый\\s+(?:клиент|заказ|заказ-наряд|наряд)|новая\\s+машина|принял[аи]?|приняли|прими|запиши\\s+клиента|записал[аи]?\\s+клиента|заехал[аи]?|приехал[аи]?|приехала|заехала)$R[\\s,:]*(.*)$")

    private val statusWords: List<Pair<Regex, OrderStatus>> = listOf(
        rx("$L(ждут?|ждет|жд[её]м|ожида\\p{L}*|нет)\\s+запчаст\\p{L}*$R") to OrderStatus.WAIT_PARTS,
        rx("$L(на\\s+диагностик\\p{L}*|диагностиру\\p{L}*)$R") to OrderStatus.DIAGNOSTICS,
        rx("$L(в\\s+работ[еу]|взял[аи]?\\s+в\\s+работу|начал[аи]?\\s+делать|приступил[аи]?\\s+к)$R") to OrderStatus.IN_WORK,
        rx("$L(выдал[аи]?|выдан[аоы]?|забрал[аи]?|отдал[аи]?|уехал[аи]?)$R") to OrderStatus.DONE,
        rx("$L(готов[аоы]?|сделан[аоы]?|сделали|закончил[аи]?|закончили)$R") to OrderStatus.READY,
    )
    private val orderRef = rx("$L(машин\\p{L}*|авто|автомобил\\p{L}*|тачк\\p{L}*|клиент\\p{L}*)$R")

    private val parts = rx("^(закажи|заказать|нужно\\s+заказать|надо\\s+заказать|заказал[аи]?|заказали)\\s+(.+?)(?:\\s+(?:на|для)\\s+(.+))?$")

    private val list = rx(
        "^(что|какие\\s+машины|какие\\s+заказы|покажи\\s+(?:машины|заказы)|список\\s+(?:машин|заказов)|сколько\\s+машин)" +
            "(?:\\s+(?:у\\s+нас|сейчас))?\\s*(по\\s+сервису|в\\s+сервисе|на\\s+сервисе|в\\s+работе|готов[оыа]?|жд[её]т\\s+запчаст\\p{L}*|ждут\\s+запчаст\\p{L}*|на\\s+диагностике|в\\s+ремонте)?$"
    )

    private val summary = rx("^(касса|выручка|сколько\\s+(?:я\\s+)?(?:заработал\\p{L}*|получил\\p{L}*|потратил\\p{L}*)|итоги?\\s+по\\s+деньгам|деньги|приход\\s+и\\s+расход)(?:\\s+(сегодня|за\\s+сегодня|за\\s+день|за\\s+неделю|на\\s+неделе|за\\s+месяц|в\\s+этом\\s+месяце))?$")
    private val income = rx("^(приход|получил[аи]?|пришло|пришли|оплата|клиент\\s+оплатил|заплатил\\s+клиент|выручка|плюс)$R\\s*(.*)$")
    private val expense = rx("^(расход|потратил[аи]?|заплатил[аи]?|купил[аи]?|оплатил[аи]?|минус|отдал[аи]?\\s+за)$R\\s*(.*)$")
    private val amountRx = rx("$L(\\d+(?:[.,]\\d+)?)\\s*(тысяч\\p{L}*|тыс\\.?|к|т\\.р\\.?)?(?:\\s*(?:руб\\p{L}*|р\\.?|₽))?$R")
    private val thousandWord = rx("$L(полторы\\s+тысячи|тысячу|тысяча)$R")

    fun parse(raw: String): ServiceCommand? {
        val s = NumberWords.replace(raw.replace(wake, "").trim().trimEnd('.', '!', '?').trim()).replace('ё', 'е').replace(Regex("\\s+"), " ")
        if (s.isEmpty()) return null

        summary.find(s)?.let { m ->
            val p = m.groupValues[2].lowercase()
            return ServiceCommand.MoneySummary(if (p.contains("недел")) 7 else if (p.contains("месяц")) 30 else 1)
        }
        list.find(s)?.let { m ->
            val p = m.groupValues[2].lowercase()
            // «что» без уточнения — это не про сервис («что у меня завтра» и т. п.)
            if (p.isEmpty() && m.groupValues[1].lowercase() == "что") return@let
            val st = when {
                p.contains("работе") || p.contains("ремонте") -> OrderStatus.IN_WORK
                p.startsWith("готов") -> OrderStatus.READY
                p.contains("запчаст") -> OrderStatus.WAIT_PARTS
                p.contains("диагност") -> OrderStatus.DIAGNOSTICS
                else -> null
            }
            return ServiceCommand.ListOrders(st)
        }
        newOrder.find(s)?.let { m -> parseNewOrder(m.groupValues[2])?.let { return it } }
        parts.find(s)?.let { m ->
            val ordered = m.groupValues[1].lowercase().startsWith("заказал")
            return ServiceCommand.Parts(m.groupValues[2].trim(), m.groupValues[3].trim(), ordered)
        }
        income.find(s)?.let { m -> money(m.groupValues[2], true)?.let { return it } }
        expense.find(s)?.let { m -> money(m.groupValues[2], false)?.let { return it } }

        // Статус: фраза должна указывать на машину — модель, госномер или слова «машина/клиент»
        for ((r, st) in statusWords) {
            val m = r.find(s) ?: continue
            val rest = s.removeRange(m.range).replace(orderRef, " ")
                .replace(rx("$L(у|уже|можно|забирать|все|всё|полностью|и)$R"), " ").replace(Regex("[,\\s]+"), " ").trim()
            val pointsToCar = carRx.containsMatchIn(s) || plateRx.containsMatchIn(s) || orderRef.containsMatchIn(s)
            if (rest.isNotEmpty() && pointsToCar) return ServiceCommand.SetStatus(rest, st)
        }
        return null
    }

    private fun money(rest: String, income: Boolean): ServiceCommand.Money? {
        var amount: Long? = null
        var text = rest
        thousandWord.find(text)?.let { m ->
            amount = if (m.value.lowercase().startsWith("полторы")) 1500 else 1000
            text = text.removeRange(m.range)
        }
        if (amount == null) amountRx.find(text)?.let { m ->
            val n = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@let
            val mult = if (m.groupValues[2].isNotEmpty()) 1000 else 1
            amount = (n * mult).toLong()
            text = text.removeRange(m.range)
        }
        val a = amount ?: return null
        if (a <= 0) return null
        val note = text.replace(rx("^\\s*(от|за|на|с)\\s+"), "").replace(Regex("\\s+"), " ").trim(' ', ',', '.')
        return ServiceCommand.Money(a, income, note.replaceFirstChar { it.uppercase() })
    }

    private fun parseNewOrder(raw: String): ServiceCommand.NewOrder? {
        var s = raw
        var phone = ""
        phoneRx.find(s)?.let { phone = it.value.filter { c -> c.isDigit() || c == '+' }; s = s.removeRange(it.range) }
        s = s.replace(rx("${L}телефон(ом)?$R"), " ")
        var plate = ""
        plateRx.find(s)?.let { m ->
            plate = (m.groupValues[1] + m.groupValues[2] + m.groupValues[3] + m.groupValues[4]).uppercase()
                .map { latinToCyr[it] ?: it }.joinToString("")
            s = s.removeRange(m.range)
        }
        // Работа: с первого слова-работы до конца
        var work = ""
        workWords.find(s)?.let { m ->
            var start = m.range.first
            // захватываем предлог «на» перед работой: «на замену колодок»
            val before = s.substring(0, start)
            Regex("(?:^|[\\s,])на\\s*$", OPT).find(before)?.let { start = it.range.first }
            work = s.substring(start).replace(rx("^[\\s,]*на\\s+"), "").trim(' ', ',', '.')
            s = s.substring(0, start)
        }
        var car = ""
        carRx.find(s)?.let { m ->
            car = m.value.trim().replaceFirstChar { it.uppercase() }
            // «на Солярисе» → «Солярис»
            car = car.replace(Regex("(е|ом|а|у)$"), "").let { base -> if (cars.any { it.equals(base, true) }) base.replaceFirstChar { it.uppercase() } else car }
            s = s.removeRange(m.range)
        }
        val client = s.replace(rx("${L}(клиент\\p{L}*|на|у|по|от|машин\\p{L}*|авто|номер|госномер)$R"), " ")
            .split(Regex("[\\s,]+")).filter { it.isNotBlank() && it.any(Char::isLetter) }
            .joinToString(" ").replaceFirstChar { it.uppercase() }
        if (client.isEmpty() && car.isEmpty() && plate.isEmpty()) return null
        return ServiceCommand.NewOrder(client, car, plate, work.replaceFirstChar { it.uppercase() }, phone)
    }

    private val latinToCyr = mapOf(
        'A' to 'А', 'B' to 'В', 'E' to 'Е', 'K' to 'К', 'M' to 'М', 'H' to 'Н', 'O' to 'О', 'P' to 'Р', 'C' to 'С', 'T' to 'Т', 'Y' to 'У', 'X' to 'Х',
    )

    /** Слова заказа для поиска: клиент, машина, номер, работа. */
    fun orderText(client: String, car: String, plate: String, work: String) = listOf(client, car, plate, work).joinToString(" ")
}
