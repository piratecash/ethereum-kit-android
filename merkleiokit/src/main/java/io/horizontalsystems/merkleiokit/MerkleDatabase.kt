package io.horizontalsystems.merkleiokit

import androidx.room.Database
import androidx.room.RoomDatabase
import io.horizontalsystems.ethereumkit.PlatformContext
import io.horizontalsystems.ethereumkit.database.kitDatabaseBuilder

@Database(
    entities = [
        MerkleTransactionHash::class,
    ],
    version = 1,
    exportSchema = true
)
abstract class MerkleDatabase : RoomDatabase() {
    abstract fun merkleTransactionDao(): MerkleTransactionDao

    companion object Companion {
        fun getInstance(context: PlatformContext, databaseName: String): MerkleDatabase {
            return kitDatabaseBuilder<MerkleDatabase>(context, databaseName)
                    .fallbackToDestructiveMigration(dropAllTables = false)
                    .build()
        }
    }
}
