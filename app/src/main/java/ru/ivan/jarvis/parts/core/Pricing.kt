package ru.ivan.jarvis.parts.core

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/** Итог проценки: таблица, статусы сайтов, время данных. */
data class PriceReport(
    val query: PartQuery,
    val offers: List<Offer>,
    /** Сайт → «ок 12», «пусто», «недоступен: капча…». */
    val status: List<Pair<String, String>>,
    val at: Long,
    val fromCache: Boolean = false,
) {
    val table: OfferSort.Table get() = OfferSort.table(offers)
}

/**
 * Порядок по ТЗ: сначала ЗетЗап; если пусто или недоступен — запасные сайты
 * (параллельно, чтобы не ждать каждый по очереди; в таблице — по цене).
 * Каждый сайт ограничен по времени: один зависший не держит остальных.
 */
object Pricing {
    const val SITE_TIMEOUT_MS = 25_000L // 10 с на загрузку × до 2 попыток + разбор

    suspend fun run(
        q: PartQuery,
        providers: List<PartsProvider>,
        now: Long = System.currentTimeMillis(),
        onDebug: (ProviderResult.Empty) -> Unit = {},
    ): PriceReport = coroutineScope {
        val status = mutableListOf<Pair<String, String>>()
        val offers = mutableListOf<Offer>()

        suspend fun one(p: PartsProvider): ProviderResult =
            withTimeoutOrNull(SITE_TIMEOUT_MS) { runCatching { p.search(q.article, q.brand) }.getOrElse { ProviderResult.Unavailable(p, "ошибка: ${it.message?.take(60)}") } }
                ?: ProviderResult.Unavailable(p, "не ответил вовремя")

        fun take(r: ProviderResult) {
            when (r) {
                is ProviderResult.Ok -> { offers += r.offers; status += r.provider.title to "ок, ${r.offers.size}" }
                is ProviderResult.Empty -> { onDebug(r); status += r.provider.title to "ничего не нашёл" }
                is ProviderResult.Unavailable -> status += r.provider.title to "сейчас недоступен (${r.reason})"
            }
        }

        val main = providers.first()
        take(one(main))
        if (offers.isEmpty()) {
            providers.drop(1).map { async { one(it) } }.forEach { take(it.await()) }
        }
        PriceReport(q, offers.distinctBy { listOf(it.site, it.article, it.price, it.supplier, it.days) }, status, now)
    }

    /** Коротко вслух: дешевле всего, срок, аналог дешевле. */
    fun speech(r: PriceReport): String {
        val t = r.table
        val best = t.originals.firstOrNull()
        val head = when {
            best != null -> "${r.query.brand ?: ""} ${r.query.article}: дешевле всего ${best.price} ₽ на сайте ${best.site}" +
                (best.days?.let { if (it == 0) ", в наличии" else ", срок $it дн." } ?: "") + ". Предложений: ${t.originals.size}."
            r.offers.isNotEmpty() -> "Оригинал ${r.query.article} не нашёл, есть аналоги."
            else -> "По артикулу ${r.query.article} ничего не нашёл." +
                (if (r.status.any { it.second.startsWith("сейчас недоступен") }) " Часть сайтов сейчас недоступна." else "")
        }
        val analog = t.analogs.firstOrNull()?.let { " Аналог ${it.brand} ${it.article} дешевле: ${it.price} ₽." } ?: ""
        return (head + analog).trim()
    }
}
