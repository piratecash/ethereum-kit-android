package io.horizontalsystems.ethereumkit.core.storage

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import io.horizontalsystems.ethereumkit.PlatformContext
import io.horizontalsystems.ethereumkit.api.storage.RoomTypeConverters
import io.horizontalsystems.ethereumkit.database.kitDatabaseBuilder
import io.horizontalsystems.ethereumkit.models.InternalTransaction
import io.horizontalsystems.ethereumkit.models.RawTransactionBroadcastRecord
import io.horizontalsystems.ethereumkit.models.Transaction
import io.horizontalsystems.ethereumkit.models.TransactionSyncerState
import io.horizontalsystems.ethereumkit.models.TransactionSyncSource
import io.horizontalsystems.ethereumkit.models.TransactionTag

@Database(
        entities = [
            Transaction::class,
            InternalTransaction::class,
            TransactionTag::class,
            TransactionSyncerState::class,
            TransactionSyncSource::class,
            RawTransactionBroadcastRecord::class
        ],
        version = 17,
        exportSchema = true
)
@TypeConverters(RoomTypeConverters::class, TransactionDatabase.TypeConverters::class)
abstract class TransactionDatabase : RoomDatabase() {

    abstract fun transactionDao(): TransactionDao
    abstract fun rawTransactionBroadcastDao(): RawTransactionBroadcastDao
    abstract fun transactionTagDao(): TransactionTagDao
    abstract fun transactionSyncerStateDao(): TransactionSyncerStateDao
    abstract fun transactionSyncSourceDao(): TransactionSyncSourceDao

    companion object {

        fun getInstance(context: PlatformContext, databaseName: String, databaseKey: ByteArray): TransactionDatabase =
            build(kitDatabaseBuilder(context, databaseName, databaseKey))

        internal fun build(builder: RoomDatabase.Builder<TransactionDatabase>): TransactionDatabase =
            builder.addMigrations(migration13_14, migration14_15, migration15_16, migration16_17)
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
