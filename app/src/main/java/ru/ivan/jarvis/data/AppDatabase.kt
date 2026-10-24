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

@Database(entities = [Item::class], version = 2, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun items(): ItemDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        /** v8: приоритет задач. Старые записи сохраняются. */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN priority INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "jarvis.db")
                .addMigrations(MIGRATION_1_2)
                .build().also { instance = it }
        }
    }
}
