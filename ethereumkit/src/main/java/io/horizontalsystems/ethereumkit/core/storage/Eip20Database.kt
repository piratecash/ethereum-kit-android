package io.horizontalsystems.ethereumkit.core.storage

import android.content.Context
import androidx.room.*
import io.horizontalsystems.ethereumkit.api.storage.RoomTypeConverters
import io.horizontalsystems.ethereumkit.models.Eip20Event
import io.horizontalsystems.ethereumkit.models.Eip20SyncState

@Database(
    entities = [
        Eip20Event::class,
        Eip20SyncState::class
    ],
    version = 4,
    exportSchema = false
)
@TypeConverters(RoomTypeConverters::class, Eip20Database.TypeConverters::class)
abstract class Eip20Database : RoomDatabase() {

    abstract fun eip20EventDao(): Eip20EventDao
    abstract fun eip20SyncStateDao(): Eip20SyncStateDao

    companion object {

        fun getInstance(context: Context, databaseName: String): Eip20Database {
            return Room.databaseBuilder(context, Eip20Database::class.java, databaseName)
                .addMigrations(migration2_3, migration3_4)
                .fallbackToDestructiveMigration()
                .allowMainThreadQueries()
                .build()
        }
    }

    class TypeConverters {
        @TypeConverter
        fun toString(list: List<String>): String {
            return list.joinToString(separator = ",")
        }

        @TypeConverter
        fun fromString(string: String): List<String> {
            return string.split(",")
        }
    }

}
