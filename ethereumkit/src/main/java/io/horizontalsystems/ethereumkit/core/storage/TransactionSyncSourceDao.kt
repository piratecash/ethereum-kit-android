package io.horizontalsystems.ethereumkit.core.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.horizontalsystems.ethereumkit.models.TransactionSyncSource

@Dao
interface TransactionSyncSourceDao {
    @Query("SELECT * FROM TransactionSyncSource WHERE transactionHash = :hash")
    suspend fun getSource(hash: ByteArray): TransactionSyncSource?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(source: TransactionSyncSource)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(sources: List<TransactionSyncSource>)
}
