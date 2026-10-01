package io.horizontalsystems.ethereumkit.api.storage

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import io.horizontalsystems.ethereumkit.PlatformContext
import io.horizontalsystems.ethereumkit.api.models.AccountState
import io.horizontalsystems.ethereumkit.api.models.LastBlockHeight
import io.horizontalsystems.ethereumkit.database.kitDatabaseBuilder


@Database(entities = [AccountState::class, LastBlockHeight::class], version = 3, exportSchema = true)
@TypeConverters(RoomTypeConverters::class)
abstract class ApiDatabase : RoomDatabase() {

    abstract fun balanceDao(): AccountStateDao
    abstract fun lastBlockHeightDao(): LastBlockHeightDao

    companion object {

        fun getInstance(context: PlatformContext, databaseName: String): ApiDatabase {
            return kitDatabaseBuilder<ApiDatabase>(context, databaseName)
                    .fallbackToDestructiveMigration(dropAllTables = false)
                    .build()
        }

    }

}
