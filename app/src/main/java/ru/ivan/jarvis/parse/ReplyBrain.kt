package ru.ivan.jarvis.parse

/**
 * «Умные ответы» (идея Jev-chat, 8 тыс. звёзд на GitHub): пришло сообщение — Джарвис понимает, о чём оно,
 * учитывает заказ клиента в сервисе и готовит 2 варианта ответа. Отправляет только человек — одним нажатием.
 */
object ReplyBrain {
    enum class Intent(val label: String) {
        READY("спрашивает про машину"),
        PRICE("спрашивает цену"),
        BOOK("хочет записаться"),
        WHERE("спрашивает адрес"),
        PARTS("про запчасти"),
        THANKS("благодарит"),
        COMPLAINT("недоволен"),
        OTHER("сообщение"),
    }

    /** Заказ клиента, если нашли его по имени отправителя. */
    data class OrderInfo(val car: String, val work: String, val status: OrderStatus, val price: Long)

    data class Analysis(val intent: Intent, val urgent: Boolean, val drafts: List<String>)

    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)
    private val ready = rx("(готов[аоы]?\\??|когда\\s+(забирать|заберу|можно\\s+забрать|будет\\s+готов)|что\\s+с\\s+(машин|авто)|как\\s+(там\\s+)?(машин|авто)|сделали|долго\\s+ещ[её]|забрать)")
    private val price = rx("(сколько\\s+(стоит|будет|выйдет|по\\s+деньгам)|цен[аы]|стоимост|почём|почем|по\\s+чем|прайс|счёт|счет)")
    private val book = rx("(записат|запись|запиши|можно\\s+(приехать|подъехать|заехать)|когда\\s+(можно|свободно|сможете)|есть\\s+(время|окно|место)|на\\s+завтра)")
    private val where = rx("(адрес|где\\s+вы|как\\s+(проехать|доехать|найти)|куда\\s+(ехать|подъехать)|геолокац)")
    private val parts = rx("(запчаст|деталь|колодк|фильтр|масло|пришл[иао]|заказали)")
    private val thanks = rx("^(спасибо|благодар|спс|ок\\b|окей|хорошо|понял|договорились|отлично)")
    private val complaint = rx("(опять|снова\\s+(стучит|течёт|течет|горит)|плохо\\s+сделал|не\\s+работает|верните|жалоб|возврат|обман|переделат)")
    private val urgentRx = rx("(срочно|авари|дтп|сломал|не\\s+заводит|заглох|эвакуатор|горит\\s+чек|дым|течёт|течет|помогите|asap)")

    fun intent(text: String): Intent {
        val t = text.trim()
        return when {
            complaint.containsMatchIn(t) -> Intent.COMPLAINT
            where.containsMatchIn(t) -> Intent.WHERE
            ready.containsMatchIn(t) -> Intent.READY
            book.containsMatchIn(t) -> Intent.BOOK
            price.containsMatchIn(t) -> Intent.PRICE
            parts.containsMatchIn(t) -> Intent.PARTS
            thanks.containsMatchIn(t) -> Intent.THANKS
            else -> Intent.OTHER
        }
    }

    fun urgent(text: String) = urgentRx.containsMatchIn(text)

    /** Имя для обращения: «Саша (Иван)» → «Иван»; «Петров Иван» → «Иван». */
    fun firstName(sender: String): String {
        val inner = Regex("\\(([^)]+)\\)").find(sender)?.groupValues?.get(1) ?: sender
        val words = inner.split(Regex("\\s+")).filter { it.isNotBlank() && it.first().isLetter() }
        return (if (words.size >= 2 && Regex("(ов|ова|ев|ева|ин|ина|ский|ская|енко|ук)$").containsMatchIn(words[0])) words[1] else words.firstOrNull())
            ?.takeIf { it.length in 2..20 && it.first().isUpperCase() } ?: ""
    }

    /** Варианты ответа без ИИ: по смыслу сообщения и статусу заказа. */
    fun drafts(text: String, sender: String, order: OrderInfo?): Analysis {
        val i = intent(text)
        val name = firstName(sender)
        val hi = if (name.isNotEmpty()) "$name, добрый день!" else "Добрый день!"
        val car = order?.car?.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""
        val list = when (i) {
            Intent.READY -> when (order?.status) {
                OrderStatus.READY -> listOf("$hi Машина$car готова, можно забирать.", "$hi Всё готово$car. Когда вам удобно подъехать?")
                OrderStatus.DONE -> listOf("$hi Машину$car уже выдали. Если что-то не так — напишите, разберёмся.", "$hi Работы по машине$car закрыты. Есть вопросы?")
                OrderStatus.WAIT_PARTS -> listOf("$hi Ждём запчасти для машины$car, как придут — сразу сделаем и сообщу.", "$hi Пока ждём запчасти$car. Сообщу по срокам сегодня.")
                OrderStatus.DIAGNOSTICS -> listOf("$hi Машина$car на диагностике, по результату позвоню.", "$hi Сейчас диагностируем$car, скоро сообщу, что нашли.")
                OrderStatus.IN_WORK, OrderStatus.ACCEPTED -> listOf("$hi Машина$car в работе, сообщу, как будет готова.", "$hi Делаем$car. Напишу, как закончим.")
                null -> listOf("$hi Сейчас уточню и напишу.", "$hi Уточню у мастера и перезвоню.")
            }
            Intent.PRICE -> listOfNotNull(
                order?.price?.takeIf { it > 0 }?.let { "$hi По машине$car выходит $it ₽." },
                "$hi Сейчас посчитаю стоимость и напишу.",
                "$hi Чтобы назвать точную цену, нужна марка, год и что именно делать.",
            ).take(2)
            Intent.BOOK -> listOf("$hi Можно завтра в 10:00 или в 15:00, как удобнее?", "$hi Давайте запишу. Какая машина и что беспокоит?")
            Intent.WHERE -> listOf("$hi Сейчас скину адрес и как проехать.", "$hi Отправляю геолокацию.")
            Intent.PARTS -> listOf("$hi Уточню по запчастям и напишу.", "$hi Запчасти заказаны, как придут — сообщу.")
            Intent.THANKS -> listOf("Пожалуйста! Обращайтесь.", "Рады помочь! Хорошей дороги.")
            Intent.COMPLAINT -> listOf("$hi Извините за неудобство. Приезжайте, посмотрим бесплатно и исправим.", "$hi Понял вас, разберёмся. Когда сможете подъехать?")
            Intent.OTHER -> listOf("$hi Принял, отвечу чуть позже.", "$hi Сейчас занят, перезвоню.")
        }
        return Analysis(i, urgent(text) || i == Intent.COMPLAINT, list)
    }

    /** Промпт для ИИ: 2 коротких варианта от лица руководителя автосервиса. */
    fun aiPrompt(about: String): String = """Ты помогаешь руководителю автосервиса быстро отвечать клиентам в мессенджерах.
${if (about.isNotBlank()) "О нём: ${about.take(300)}\n" else ""}Дай ровно 2 варианта ответа, каждый с новой строки, начиная с «1)» и «2)». До 25 слов каждый, вежливо, по-деловому, без эмодзи.
Не обещай точных цен и сроков, если их нет в данных. Не выдумывай факты."""

    fun aiUser(text: String, sender: String, order: OrderInfo?, intent: Intent): String = buildString {
        append("Сообщение от ").append(firstName(sender).ifBlank { "клиента" }).append(": ").append(text.take(500))
        append("\nСмысл: ").append(intent.label)
        order?.let { append("\nЗаказ: ${it.car}, ${it.work}, статус «${it.status.label}»" + if (it.price > 0) ", сумма ${it.price} ₽" else "") }
    }

    /** «1) …\n2) …» → два варианта. */
    fun parseAi(answer: String): List<String> = answer.lines()
        .map { it.trim().replace(Regex("^[-*•]?\\s*\\d[).:]\\s*"), "").replace(Regex("[*_`#]"), "").trim().trim('"', '«', '»') }
        .filter { it.length in 2..300 }
        .take(2)
}
