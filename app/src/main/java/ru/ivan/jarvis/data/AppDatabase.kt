package ru.ivan.jarvis.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Converters {
    @TypeConverter fun typeToString(t: ItemType): String = t.name
    @TypeConverter fun stringToType(s: String): ItemType = ItemType.valueOf(s)
    @TypeConverter fun repeatToString(r: Repeat): String = r.name
    @TypeConverter fun stringToRepeat(s: String): Repeat = Repeat.valueOf(s)
}

@Database(entities = [Item::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun items(): ItemDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "jarvis.db")
                .build().also { instance = it }
        }
    }
}
