package ru.ivan.jarvis.parts

import android.content.Context
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import org.json.JSONArray
import org.json.JSONObject
import ru.ivan.jarvis.parts.core.Offer
import ru.ivan.jarvis.parts.core.PartQuery
import ru.ivan.jarvis.parts.core.PriceReport
import ru.ivan.jarvis.parts.core.ProviderResult
import java.io.File

/** Кэш проценки в Room: результаты живут 4 часа («данные от 14:30, обновить?»). */
@Entity(tableName = "parts_cache")
data class PartsEntry(
    @PrimaryKey val key: String,
    val json: String,
    val createdAt: Long,
)

@Dao
interface PartsDao {
    @Query("SELECT * FROM parts_cache WHERE `key` = :key LIMIT 1")
    suspend fun get(key: String): PartsEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(e: PartsEntry)

    @Query("DELETE FROM parts_cache WHERE createdAt < :before")
    suspend fun purge(before: Long)
}

object PartsJson {
    const val TTL_MS = 4 * 3600_000L

    fun encode(r: PriceReport): String = JSONObject()
        .put("a", r.query.article).put("b", r.query.brand ?: "").put("p", r.query.part)
        .put("s", JSONArray().apply { r.status.forEach { put(JSONArray().put(it.first).put(it.second)) } })
        .put("o", JSONArray().apply {
            r.offers.forEach { o ->
                put(JSONObject().put("n", o.name).put("b", o.brand).put("a", o.article).put("p", o.price).put("st", o.stock)
                    .put("d", o.days ?: -1).put("su", o.supplier).put("si", o.site).put("u", o.url).put("an", o.analog).put("m", o.main))
            }
        }).toString()

    fun decode(json: String, at: Long): PriceReport? = runCatching {
        val j = JSONObject(json)
        val q = PartQuery(j.getString("a"), j.optString("b").ifBlank { null }, j.optString("p"))
        val s = j.getJSONArray("s").let { a -> (0 until a.length()).map { a.getJSONArray(it).let { p -> p.getString(0) to p.getString(1) } } }
        val o = j.getJSONArray("o").let { a ->
            (0 until a.length()).map { i ->
                val x = a.getJSONObject(i)
                Offer(x.getString("n"), x.getString("b"), x.getString("a"), x.getInt("p"), x.getString("st"),
                    x.getInt("d").takeIf { it >= 0 }, x.getString("su"), x.getString("si"), x.getString("u"), x.getBoolean("an"), x.getBoolean("m"))
            }
        }
        PriceReport(q, o, s, at, fromCache = true)
    }.getOrNull()
}

/**
 * Отладка вёрстки: если сайт открылся, но предложений не разобрали — сохраняем кусок HTML
 * (последний по каждому сайту). Скопировать: Настройки → «Проценка» → «Скопировать отладку».
 */
object PartsDebug {
    private fun file(ctx: Context) = File(ctx.filesDir, "parts-debug.txt")

    fun save(ctx: Context, e: ProviderResult.Empty) {
        runCatching {
            val f = file(ctx)
            val old = if (f.exists()) f.readText() else ""
            val blocks = old.split(SEP).filter { it.isNotBlank() && !it.startsWith("\n== ${e.provider.id} ") && !it.startsWith("== ${e.provider.id} ") }
            val now = java.time.LocalDateTime.now().withNano(0)
            val block = "== ${e.provider.id} $now\nurl: ${e.url}\n${e.debug}\n"
            f.writeText((blocks + block).takeLast(6).joinToString(SEP))
        }
    }

    fun export(ctx: Context): String = runCatching { file(ctx).readText() }.getOrDefault("").ifBlank { "Отладки нет: все сайты разбирались нормально." }

    private const val SEP = "\n-----\n"
}
