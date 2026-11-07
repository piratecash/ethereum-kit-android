package io.horizontalsystems.ethereumkit.core.storage

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.horizontalsystems.ethereumkit.models.RawTransactionBroadcastRecord

@Dao
interface RawTransactionBroadcastDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(record: RawTransactionBroadcastRecord)

    @Update
    suspend fun update(record: RawTransactionBroadcastRecord)

    @Query("select * from RawTransactionBroadcastRecord where hash = :hash limit 1")
    suspend fun get(hash: ByteArray): RawTransactionBroadcastRecord?

    @Query("select * from RawTransactionBroadcastRecord")
    suspend fun getAll(): List<RawTransactionBroadcastRecord>

    @Delete
    suspend fun delete(record: RawTransactionBroadcastRecord)
}
