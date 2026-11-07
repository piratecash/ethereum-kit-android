package io.horizontalsystems.nftkit.core.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import io.horizontalsystems.ethereumkit.PlatformContext
import io.horizontalsystems.ethereumkit.api.storage.RoomTypeConverters
import io.horizontalsystems.ethereumkit.database.kitDatabaseBuilder
import io.horizontalsystems.nftkit.models.Eip1155Event
import io.horizontalsystems.nftkit.models.Eip721Event
import io.horizontalsystems.nftkit.models.NftBalanceRecord

@Database(entities = [Eip721Event::class, Eip1155Event::class, NftBalanceRecord::class], version = 1, exportSchema = true)
@TypeConverters(NftTypeConverters::class, RoomTypeConverters::class)
abstract class NftKitDatabase : RoomDatabase() {

    abstract fun nftBalanceDao(): NftBalanceDao
    abstract fun eip721EventDao(): Eip721EventDao
    abstract fun eip1155EventDao(): Eip1155EventDao

    companion object {
        fun getInstance(context: PlatformContext, databaseName: String, databaseKey: ByteArray): NftKitDatabase =
            build(kitDatabaseBuilder(context, databaseName, databaseKey))

        internal fun build(builder: RoomDatabase.Builder<NftKitDatabase>): NftKitDatabase =
            builder.fallbackToDestructiveMigration(dropAllTables = false).build()
    }
}