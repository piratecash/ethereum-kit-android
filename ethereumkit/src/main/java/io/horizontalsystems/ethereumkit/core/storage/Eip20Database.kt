package io.horizontalsystems.ethereumkit.core.storage

import androidx.room.*
import io.horizontalsystems.ethereumkit.PlatformContext
import io.horizontalsystems.ethereumkit.api.storage.RoomTypeConverters
import io.horizontalsystems.ethereumkit.database.kitDatabaseBuilder
import io.horizontalsystems.ethereumkit.models.Eip20Event
import io.horizontalsystems.ethereumkit.models.Eip20SyncState

@Database(
    entities = [
        Eip20Event::class,
        Eip20SyncState::class
    ],
    version = 6,
    exportSchema = true
)
@TypeConverters(RoomTypeConverters::class, Eip20Database.TypeConverters::class)
abstract class Eip20Database : RoomDatabase() {

    abstract fun eip20EventDao(): Eip20EventDao
    abstract fun eip20SyncStateDao(): Eip20SyncStateDao

    companion object {

        fun getInstance(context: PlatformContext, databaseName: String, databaseKey: ByteArray): Eip20Database =
            build(kitDatabaseBuilder(context, databaseName, databaseKey))

        internal fun build(builder: RoomDatabase.Builder<Eip20Database>): Eip20Database =
            builder.addMigrations(migration2_3, migration3_4, migration4_5, migration5_6)
                .fallbackToDestructiveMigration(dropAllTables = false)
                .build()
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
