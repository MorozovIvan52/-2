package ru.ivan.jarvis.parts.core

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/** Одно предложение: цена в рублях, срок в днях (0 — в наличии сегодня, null — не указан). */
data class Offer(
    val name: String,
    val brand: String,
    val article: String,
    val price: Int,
    val stock: String,
    val days: Int?,
    val supplier: String,
    val site: String,
    val url: String,
    /** Кросс-номер (аналог), а не запрошенный артикул. */
    val analog: Boolean = false,
    /** Главный источник — ЗетЗап, в таблице помечается «зет зап». */
    val main: Boolean = false,
)

/** Ответ страницы. code 0 — сети нет или таймаут. */
data class Page(val code: Int, val html: String, val error: String? = null)

/** Как получить страницу: обычный GET или через браузерный движок (для сайтов, где выдача рисуется скриптом). */
interface PageFetcher {
    suspend fun get(url: String, js: Boolean): Page
}

sealed interface ProviderResult {
    val provider: PartsProvider
    data class Ok(override val provider: PartsProvider, val offers: List<Offer>) : ProviderResult
    /** Страница открылась, но предложений не разобрали. debug — кусок HTML для починки селекторов. */
    data class Empty(override val provider: PartsProvider, val url: String, val debug: String) : ProviderResult
    /** Капча, блокировка, таймаут — «источник сейчас недоступен», повторно не ломимся. */
    data class Unavailable(override val provider: PartsProvider, val reason: String) : ProviderResult
}

/**
 * Один сайт — один адаптер. При смене вёрстки чинится только он.
 * search — открыть страницу поиска и разобрать; parseOffers — только разбор HTML (покрыт тестами).
 */
interface PartsProvider {
    val id: String
    val title: String
    /** Выдача рисуется JavaScript — открываем браузерным движком. */
    val needsJs: Boolean
    fun searchUrl(article: String, brand: String?): String
    suspend fun search(article: String, brand: String?): ProviderResult
    fun parseOffers(html: String, article: String): List<Offer>
}

/**
 * Общая часть адаптеров: не больше 2 попыток (вторая — только при сетевой ошибке или 5xx),
 * капча/блокировка — сразу «недоступен», пустой разбор — отладочный кусок HTML.
 * urlOverride — адрес поиска из настроек ({a} — артикул, {b} — бренд), если сайт сменил адрес.
 */
abstract class BaseProvider(
    private val fetcher: PageFetcher,
    private val urlOverride: (String) -> String? = { null },
) : PartsProvider {
    protected abstract fun defaultUrl(a: String, b: String): String

    final override fun searchUrl(article: String, brand: String?): String {
        val a = enc(article)
        val b = enc(brand.orEmpty())
        return urlOverride(id)?.takeIf { it.contains("{a}") }?.replace("{a}", a)?.replace("{b}", b) ?: defaultUrl(a, b)
    }

    override suspend fun search(article: String, brand: String?): ProviderResult {
        val url = searchUrl(article, brand)
        var page = fetcher.get(url, needsJs)
        if (page.code == 0 || page.code >= 500) page = fetcher.get(url, needsJs)
        Blocked.reason(page)?.let { return ProviderResult.Unavailable(this, it) }
        val offers = runCatching { parseOffers(page.html, article) }.getOrDefault(emptyList())
        return if (offers.isEmpty()) ProviderResult.Empty(this, url, Html.debugSnippet(page.html))
        else ProviderResult.Ok(this, offers)
    }

    /** Сначала селектор сайта, если ничего — универсальный разбор. */
    override fun parseOffers(html: String, article: String): List<Offer> {
        val doc = Jsoup.parse(html, baseUrl())
        // Ячейки без пробелов между собой («W753</span><b>587 ₽») склеились бы в одно число
        doc.body()?.select("*")?.forEach { it.appendText(" ") }
        val rows = rowSelector?.let { doc.select(it).toList() }.orEmpty()
        val fromRows = rows.mapNotNull { Html.offerFromRow(it, article, this) }
        return (fromRows.ifEmpty { Html.generic(doc, article, this) }).distinct()
    }

    /** CSS-селектор строки с предложением (null — только универсальный разбор). */
    protected open val rowSelector: String? = null
    protected open fun baseUrl(): String = "https://$id/"

    protected fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}

/** Капча, блокировка, защита от роботов — по коду и по тексту. Обходить не пытаемся. */
object Blocked {
    private val words = Regex(
        "(captcha|капча|smartcaptcha|cf-challenge|challenge-platform|подтвердите,?\\s+что\\s+вы\\s+не\\s+робот|" +
            "доступ\\s+(ограничен|запрещ[её]н)|access\\s+denied|too\\s+many\\s+requests|ddos-guard|вы\\s+робот|" +
            "необычный\\s+трафик|проверка\\s+браузера)",
        RegexOption.IGNORE_CASE,
    )

    fun reason(p: Page): String? = when {
        p.code == 0 -> p.error ?: "нет ответа за 10 секунд"
        p.code == 403 || p.code == 429 -> "сайт закрыл доступ (${p.code})"
        p.code >= 400 -> "ошибка сайта ${p.code}"
        words.containsMatchIn(p.html.take(30_000)) && p.html.length < 200_000 && !Html.hasPrices(p.html) -> "капча или защита от роботов"
        else -> null
    }
}

/** Универсальный разбор выдачи интернет-магазинов запчастей. */
object Html {
    private val price = Regex("(?<![\\p{L}\\d])(\\d{1,3}(?:[\\s\\u00A0\\u202F]\\d{3})+|\\d{2,7})(?:[.,]\\d{1,2})?\\s*(₽|руб\\.?|р\\.|р(?![\\p{L}])|RUB)", RegexOption.IGNORE_CASE)
    private val daysRx = Regex("(\\d{1,3})\\s*(?:[-–]\\s*(\\d{1,3})\\s*)?(?:раб\\.?\\s*)?(дн|день|дня|дней|сут)", RegexOption.IGNORE_CASE)
    private val todayRx = Regex("(сегодня|в\\s+наличии|на\\s+складе|в\\s+магазине|самовывоз\\s+сейчас|сейчас)", RegexOption.IGNORE_CASE)
    private val tomorrowRx = Regex("завтра", RegexOption.IGNORE_CASE)
    private val qtyRx = Regex("(?:(>|более|больше|от)\\s*)?(\\d{1,4})\\s*шт", RegexOption.IGNORE_CASE)
    private val articleToken = Regex("(?<![\\p{L}\\d])([A-Za-z0-9][A-Za-z0-9\\-./ ]{1,18}[A-Za-z0-9])(?![\\p{L}\\d])")

    fun hasPrices(html: String) = price.findAll(Jsoup.parse(html).text()).take(3).count() >= 3

    fun parsePrice(text: String): Int? = price.find(text)?.groupValues?.get(1)?.filter { it.isDigit() }?.toIntOrNull()?.takeIf { it in 10..5_000_000 }

    fun parseDays(text: String): Int? {
        daysRx.find(text)?.let { m -> return (m.groupValues[2].ifEmpty { m.groupValues[1] }).toIntOrNull() }
        if (todayRx.containsMatchIn(text)) return 0
        if (tomorrowRx.containsMatchIn(text)) return 1
        return null
    }

    fun parseStock(text: String): String {
        qtyRx.find(text)?.let { m -> return (if (m.groupValues[1].isNotEmpty()) ">" else "") + m.groupValues[2] + " шт" }
        return when {
            Regex("в\\s+наличии|на\\s+складе|в\\s+магазине", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "есть"
            Regex("под\\s+заказ", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "под заказ"
            else -> ""
        }
    }

    /** Строка таблицы/карточка → предложение. null — нет цены. */
    fun offerFromRow(row: Element, article: String, p: PartsProvider): Offer? {
        val text = row.text().replace(' ', ' ')
        if (text.length > 600) return null
        val rub = parsePrice(text) ?: return null
        val brand = Article.knownBrands.firstOrNull { b -> b.split('/').any { text.contains(it, ignoreCase = true) } }
            ?: Regex("(?<![\\p{L}])([A-Z][A-Z\\-]{2,15})(?![\\p{L}])").find(text)?.value ?: ""
        val norm = Article.normalize(text.replace(" ", ""))
        val own = norm.contains(article)
        val noBrand = if (brand.isNotEmpty()) text.replace(brand, " ", ignoreCase = true) else text
        val found = if (own) article else articleToken.findAll(noBrand).map { it.value.replace(" ", "") }
            .firstOrNull { t ->
                val digits = t.count(Char::isDigit)
                digits > 0 && t.count(Char::isLetterOrDigit) >= 4 && (t.any(Char::isLetter) || digits >= 6)
            }
            ?.let { Article.normalize(it) } ?: ""
        val name = text.substringBefore(price.find(text)!!.value).replace(brand, "", ignoreCase = true)
            .replace(Regex("\\s+"), " ").trim(' ', '|', ',', '-').take(70)
        val link = row.selectFirst("a[href]")?.absUrl("href").orEmpty()
        val supplier = row.selectFirst("[class*=supplier], [class*=provider], [class*=seller], [class*=store], [class*=warehouse], [class*=sklad]")
            ?.text()?.take(40).orEmpty()
        return Offer(
            name = name, brand = brand, article = found, price = rub,
            stock = parseStock(text), days = parseDays(text), supplier = supplier.ifBlank { p.title },
            site = p.title, url = link.ifBlank { p.searchUrl(article, null) },
            analog = !own && found.isNotEmpty(),
        )
    }

    /**
     * Без знания вёрстки: самые «узкие» элементы, где есть и цена, и артикул/бренд/срок.
     * Так находятся строки таблиц и карточки товаров почти на любом сайте.
     */
    fun generic(doc: Document, article: String, p: PartsProvider): List<Offer> {
        doc.select("script, style, noscript, svg, header, footer, nav").remove()
        val cand = doc.body()?.select("tr, li, article, div, section")?.filter { el ->
            val t = el.text()
            t.length in 8..500 && parsePrice(t) != null &&
                (Article.normalize(t.replace(" ", "")).contains(article) || parseDays(t) != null ||
                    Article.knownBrands.any { b -> t.contains(b.substringBefore('/'), true) })
        }.orEmpty()
        val set = cand.toHashSet()
        // Оставляем самые глубокие: у которых нет подходящего потомка
        val leaves = cand.filter { el -> el.select("*").none { it !== el && it in set } }
        return leaves.mapNotNull { offerFromRow(it, article, p) }.take(60)
    }

    /** Кусок страницы для отладки: заголовок и видимый текст, без скриптов. */
    fun debugSnippet(html: String, max: Int = 3000): String {
        val d = Jsoup.parse(html)
        val title = d.title()
        d.select("script, style, noscript, svg").remove()
        val body = d.body()?.html().orEmpty().replace(Regex("\\s+"), " ")
        return ("title: $title\n" + body).take(max)
    }
}

/** Сортировка: цена, при равной — срок. Аналоги показываем, только если они дешевле самого дешёвого оригинала. */
object OfferSort {
    fun sorted(list: List<Offer>): List<Offer> = list.sortedWith(compareBy<Offer> { it.price }.thenBy { it.days ?: 999 })

    data class Table(val originals: List<Offer>, val analogs: List<Offer>)

    fun table(all: List<Offer>, maxRows: Int = 15): Table {
        val orig = sorted(all.filter { !it.analog })
        val minOrig = orig.firstOrNull()?.price ?: Int.MAX_VALUE
        val analogs = sorted(all.filter { it.analog && it.price < minOrig })
        return Table(orig.take(maxRows), analogs.take(8))
    }
}
