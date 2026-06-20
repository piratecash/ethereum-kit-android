package io.horizontalsystems.ethereumkit.core.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import io.horizontalsystems.ethereumkit.api.storage.RoomTypeConverters
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
        version = 16,
        exportSchema = false
)
@TypeConverters(RoomTypeConverters::class, TransactionDatabase.TypeConverters::class)
abstract class TransactionDatabase : RoomDatabase() {

    abstract fun transactionDao(): TransactionDao
    abstract fun rawTransactionBroadcastDao(): RawTransactionBroadcastDao
    abstract fun transactionTagDao(): TransactionTagDao
    abstract fun transactionSyncerStateDao(): TransactionSyncerStateDao
    abstract fun transactionSyncSourceDao(): TransactionSyncSourceDao

    companion object {

        fun getInstance(context: Context, databaseName: String): TransactionDatabase {
            return Room.databaseBuilder(context, TransactionDatabase::class.java, databaseName)
                    .addMigrations(migration13_14, migration14_15, migration15_16)
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
