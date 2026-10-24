package ru.ivan.jarvis.service

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import ru.ivan.jarvis.parse.OrderStatus

/** Заказ-наряд: клиент, машина, работа, статус. */
@Entity(tableName = "orders")
data class Order(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val client: String = "",
    val phone: String = "",
    val car: String = "",
    val plate: String = "",
    val work: String = "",
    val parts: String = "",
    val status: OrderStatus = OrderStatus.ACCEPTED,
    val price: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val title: String get() = listOf(car, client, plate).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Заказ №$id" }
    val searchText: String get() = "$client $car $plate $work $parts $phone"
}

/** Движение денег: приход или расход. */
@Entity(tableName = "money")
data class MoneyEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amount: Long,
    val income: Boolean,
    val note: String = "",
    val orderId: Long? = null,
    val at: Long = System.currentTimeMillis(),
)

@Dao
interface ServiceDao {
    @Query("SELECT * FROM orders ORDER BY updatedAt DESC")
    fun observeOrders(): Flow<List<Order>>

    @Query("SELECT * FROM orders ORDER BY updatedAt DESC")
    suspend fun orders(): List<Order>

    @Query("SELECT * FROM orders WHERE id = :id")
    suspend fun order(id: Long): Order?

    @Insert suspend fun insert(o: Order): Long
    @Update suspend fun update(o: Order)
    @Query("DELETE FROM orders WHERE id = :id") suspend fun deleteOrder(id: Long)

    @Insert suspend fun insert(m: MoneyEntry): Long
    @Query("SELECT * FROM money WHERE at >= :from ORDER BY at DESC")
    suspend fun moneySince(from: Long): List<MoneyEntry>
    @Query("SELECT * FROM money ORDER BY at DESC LIMIT 200")
    fun observeMoney(): Flow<List<MoneyEntry>>
    @Query("DELETE FROM money WHERE id = :id") suspend fun deleteMoney(id: Long)
}

class ServiceConverters {
    @TypeConverter fun statusToString(s: OrderStatus): String = s.name
    @TypeConverter fun stringToStatus(s: String): OrderStatus = runCatching { OrderStatus.valueOf(s) }.getOrDefault(OrderStatus.ACCEPTED)
}

/** Отдельная база, чтобы не трогать базу заметок (никаких миграций старых данных). */
@Database(entities = [Order::class, MoneyEntry::class], version = 1, exportSchema = false)
@TypeConverters(ServiceConverters::class)
abstract class ServiceDb : RoomDatabase() {
    abstract fun dao(): ServiceDao

    companion object {
        @Volatile private var instance: ServiceDb? = null
        fun get(context: Context): ServiceDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, ServiceDb::class.java, "service.db").build().also { instance = it }
        }
    }
}
