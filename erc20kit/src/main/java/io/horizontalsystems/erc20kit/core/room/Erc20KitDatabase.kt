package io.horizontalsystems.erc20kit.core.room

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import io.horizontalsystems.erc20kit.models.TokenBalance
import io.horizontalsystems.ethereumkit.PlatformContext
import io.horizontalsystems.ethereumkit.api.storage.RoomTypeConverters
import io.horizontalsystems.ethereumkit.database.kitDatabaseBuilder

@Database(entities = [TokenBalance::class], version = 5, exportSchema = true)
@TypeConverters(RoomTypeConverters::class)
abstract class Erc20KitDatabase : RoomDatabase() {

    abstract val tokenBalanceDao: TokenBalanceDao

    companion object {

        fun getInstance(context: PlatformContext, databaseName: String): Erc20KitDatabase {
            return kitDatabaseBuilder<Erc20KitDatabase>(context, databaseName)
                    .fallbackToDestructiveMigration(dropAllTables = false)
                    .build()
        }
    }

}
