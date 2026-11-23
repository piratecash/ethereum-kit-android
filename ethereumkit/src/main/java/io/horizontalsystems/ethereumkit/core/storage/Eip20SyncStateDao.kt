package io.horizontalsystems.ethereumkit.core.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.horizontalsystems.ethereumkit.models.Eip20SyncState

@Dao
interface Eip20SyncStateDao {

    @Query("SELECT * FROM Eip20SyncState WHERE contractAddress = :contractAddress LIMIT 1")
    suspend fun get(contractAddress: String): Eip20SyncState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(state: Eip20SyncState)

    @Query("DELETE FROM Eip20SyncState WHERE contractAddress = :contractAddress")
    suspend fun delete(contractAddress: String)
}
