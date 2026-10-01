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

        fun getInstance(context: PlatformContext, databaseName: String, databaseKey: ByteArray): Erc20KitDatabase =
            build(kitDatabaseBuilder(context, databaseName, databaseKey))

        internal fun build(builder: RoomDatabase.Builder<Erc20KitDatabase>): Erc20KitDatabase =
            builder.fallbackToDestructiveMigration(dropAllTables = false).build()
    }

}
