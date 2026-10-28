package ru.ivan.jarvis.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/** Последние ответы поиска: повторный вопрос отвечается мгновенно, в том числе без интернета. */
@Entity(tableName = "search_cache")
data class SearchEntry(
    @PrimaryKey val key: String,
    val query: String,
    val answer: String,
    /** JSON: [{"t": заголовок, "u": ссылка}] — только ссылки, без данных авторов. */
    val links: String,
    val createdAt: Long,
)

@Dao
interface SearchDao {
    @Query("SELECT * FROM search_cache WHERE `key` = :key LIMIT 1")
    suspend fun get(key: String): SearchEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(e: SearchEntry)

    /** Храним последние 300 ответов. */
    @Query("DELETE FROM search_cache WHERE `key` NOT IN (SELECT `key` FROM search_cache ORDER BY createdAt DESC LIMIT 300)")
    suspend fun trim()
}
