package ru.ivan.jarvis.parts.core

/**
 * Фото этикетки или техпаспорта → артикул/VIN. Текст распознаёт ML Kit на телефоне (без интернета),
 * здесь — только разбор текста (покрыт тестами).
 */
object LabelReader {
    sealed interface Result {
        data class Vin(val vin: String, val maker: String?, val year: Int?) : Result
        data class Part(val q: PartQuery) : Result
        data object Nothing : Result
    }

    private val vinRx = Regex("(?<![A-Z0-9])([A-HJ-NPR-Z0-9]{17})(?![A-Z0-9])")

    /** Первые 3 знака VIN → производитель (самые частые в Нижнем). */
    private val wmi = mapOf(
        "XTA" to "LADA", "XTT" to "УАЗ", "X96" to "ГАЗ", "Z94" to "Hyundai/Kia (Россия)", "XWE" to "Kia (Россия)",
        "KMH" to "Hyundai", "KNA" to "Kia", "KNE" to "Kia", "U5Y" to "Kia", "TMA" to "Hyundai",
        "XW8" to "Volkswagen/Skoda (Калуга)", "WVW" to "Volkswagen", "WVG" to "Volkswagen", "TMB" to "Skoda", "WAU" to "Audi",
        "X7L" to "Renault (Россия)", "VF1" to "Renault", "X7M" to "Hyundai (ТагАЗ)", "Z8N" to "Nissan (Россия)", "SJN" to "Nissan",
        "JN1" to "Nissan", "JTD" to "Toyota", "JTE" to "Toyota", "JTN" to "Toyota", "XW7" to "Toyota (Россия)", "NMT" to "Toyota",
        "WBA" to "BMW", "WDD" to "Mercedes-Benz", "WDB" to "Mercedes-Benz", "X4X" to "BMW (Калининград)", "Z6F" to "Ford (Россия)",
        "WF0" to "Ford", "XUF" to "Chevrolet/Opel (Россия)", "W0L" to "Opel", "KL1" to "Chevrolet", "XUU" to "Chevrolet (Россия)",
        "JMB" to "Mitsubishi", "Z8T" to "Mitsubishi/Peugeot (Калуга)", "JMZ" to "Mazda", "JHM" to "Honda", "LVV" to "Chery",
        "LB3" to "Geely", "LGW" to "Haval", "XZG" to "Haval (Россия)", "VF3" to "Peugeot", "VF7" to "Citroen",
    )

    private val yearCodes = "ABCDEFGHJKLMNPRSTVWXY123456789"

    /** Модельный год по 10-му знаку VIN (цикл 30 лет; берём ближайший не в будущем). */
    fun vinYear(vin: String, currentYear: Int = java.time.LocalDate.now().year): Int? {
        val i = yearCodes.indexOf(vin.getOrNull(9) ?: return null)
        if (i < 0) return null
        var y = 1980 + i
        while (y + 30 <= currentYear + 1) y += 30
        return y
    }

    fun read(ocr: String): Result {
        // VIN ищем построчно: в VIN нет O, I, Q — распознаватель их путает с 0, 1, 0
        for (line in ocr.lines()) {
            val clean = line.uppercase().replace(Regex("\\bVIN\\b[:№#\\s]*"), "").replace(Regex("[\\s\\-]"), "")
                .replace('O', '0').replace('I', '1').replace('Q', '0')
            vinRx.find(clean)?.value?.takeIf { v -> v.count(Char::isDigit) >= 5 && v.count(Char::isLetter) >= 2 }?.let { v ->
                return Result.Vin(v, wmi[v.take(3)], vinYear(v))
            }
        }
        // Артикул: строка рядом с брендом, иначе самый «артикульный» токен
        val lines = ocr.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val brand = lines.firstNotNullOfOrNull { Article.brand(it) }
        val tokens = lines.flatMap { l -> Regex("[A-Za-zА-Яа-я0-9][A-Za-zА-Яа-я0-9 ./\\-]{2,20}").findAll(l).map { it.value.trim() } }
        val best = tokens.map { Article.normalize(it) }
            .filter { t -> t.length in 4..18 && t.count(Char::isDigit) >= 2 && !Regex("^(19|20)\\d{2}$").matches(t) && !Regex("^\\d{13}$").matches(t) }
            .maxByOrNull { t -> (if (t.any(Char::isLetter)) 10 else 0) + t.length.coerceAtMost(10) }
            ?: return Result.Nothing
        return Result.Part(PartQuery(best, brand, ""))
    }
}
