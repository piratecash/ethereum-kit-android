package io.horizontalsystems.ethereumkit.core.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.horizontalsystems.ethereumkit.models.TransactionSyncSource

@Dao
interface TransactionSyncSourceDao {
    @Query("SELECT * FROM TransactionSyncSource WHERE transactionHash = :hash")
    fun getSource(hash: ByteArray): TransactionSyncSource?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(source: TransactionSyncSource)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAll(sources: List<TransactionSyncSource>)
}
