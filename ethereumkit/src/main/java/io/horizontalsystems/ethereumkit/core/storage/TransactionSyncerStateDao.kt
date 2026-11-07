package io.horizontalsystems.ethereumkit.core.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.horizontalsystems.ethereumkit.models.TransactionSyncerState

@Dao
interface TransactionSyncerStateDao {

    @Query("SELECT * FROM `TransactionSyncerState` WHERE syncerId = :syncerId LIMIT 1")
    suspend fun get(syncerId: String) : TransactionSyncerState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(transactionSyncerState: TransactionSyncerState)

}
