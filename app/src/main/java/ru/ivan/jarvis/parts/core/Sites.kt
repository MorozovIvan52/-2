package ru.ivan.jarvis.parts.core

/*
 * Адаптеры сайтов. Адреса поиска — открытые публичные страницы (как в браузере).
 * Если сайт сменил адрес, его можно поправить в Настройках → «Проценка» без новой сборки
 * ({a} — артикул, {b} — бренд). Селекторы строк чинятся здесь, в адаптере своего сайта.
 */

/** Главный источник: региональный ЗетЗап по Нижнему Новгороду. Выдача рисуется скриптом (DevExpress-таблица). */
class ZzapProvider(f: PageFetcher, o: (String) -> String? = { null }) : BaseProvider(f, o) {
    override val id = "nn.zzap.ru"
    override val title = "ЗетЗап"
    override val needsJs = true
    override fun defaultUrl(a: String, b: String) =
        "https://nn.zzap.ru/public/search.aspx#rawdata=$a" + (if (b.isNotEmpty()) "&class_man=$b&partnumber=$a" else "")
    override val rowSelector = "tr.dxgvDataRow, tr[id*=DXDataRow], tr[class*=DataRow]"

    override fun parseOffers(html: String, article: String): List<Offer> =
        super.parseOffers(html, article).map { it.copy(main = true, site = title) }
}

class AutotradeProvider(f: PageFetcher, o: (String) -> String? = { null }) : BaseProvider(f, o) {
    override val id = "autotrade.su"
    override val title = "Автотрейд"
    override val needsJs = true
    override fun defaultUrl(a: String, b: String) = "https://autotrade.su/nizhnij-novgorod/search?query=$a"
    override val rowSelector = "tr[class*=item], div[class*=search-result] [class*=row], [class*=product-item]"
}

class RosskoProvider(f: PageFetcher, o: (String) -> String? = { null }) : BaseProvider(f, o) {
    override val id = "nn.rossko.ru"
    override val title = "Росско"
    override val needsJs = true
    override fun defaultUrl(a: String, b: String) = "https://nn.rossko.ru/search?text=$a"
    override val rowSelector = "[class*=product-list] [class*=item], [class*=search] tr, [class*=offer]"
}

class PartKomProvider(f: PageFetcher, o: (String) -> String? = { null }) : BaseProvider(f, o) {
    override val id = "part-kom.ru"
    override val title = "Парт-Ком"
    override val needsJs = true
    override fun defaultUrl(a: String, b: String) = "https://part-kom.ru/search/?q=$a"
    override val rowSelector = "[class*=search-result] tr, [class*=offer-row], [class*=product-row]"
}

class IxoraProvider(f: PageFetcher, o: (String) -> String? = { null }) : BaseProvider(f, o) {
    override val id = "ixora-auto.ru"
    override val title = "Иксора"
    override val needsJs = true
    override fun defaultUrl(a: String, b: String) = "https://ixora-auto.ru/search?article=$a"
    override val rowSelector = "[class*=search] tr, [class*=offer], [class*=price-row]"
}

class TopdetalProvider(f: PageFetcher, o: (String) -> String? = { null }) : BaseProvider(f, o) {
    override val id = "topdetal.ru"
    override val title = "Топдеталь"
    override val needsJs = true
    override fun defaultUrl(a: String, b: String) = "https://topdetal.ru/search/?q=$a"
    override val rowSelector = "[class*=search] tr, [class*=product-item], [class*=offer]"
}

object Sites {
    /** Порядок = приоритет: ЗетЗап, затем запасные. */
    fun all(f: PageFetcher, o: (String) -> String? = { null }): List<PartsProvider> = listOf(
        ZzapProvider(f, o), AutotradeProvider(f, o), RosskoProvider(f, o), PartKomProvider(f, o), IxoraProvider(f, o), TopdetalProvider(f, o),
    )
}
