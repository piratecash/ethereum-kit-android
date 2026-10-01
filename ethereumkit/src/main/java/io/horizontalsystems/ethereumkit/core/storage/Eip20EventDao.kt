package io.horizontalsystems.ethereumkit.core.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.horizontalsystems.ethereumkit.models.Eip20Event

@Dao
interface Eip20EventDao {

    @Query("SELECT * FROM Eip20Event ORDER BY blockNumber DESC LIMIT 1")
    suspend fun getLastEip20Event(): Eip20Event?

    @Query("SELECT * FROM Eip20Event ORDER BY blockNumber ASC LIMIT 1")
    suspend fun getEarliestEip20Event(): Eip20Event?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEip20Events(events: List<Eip20Event>)

    @Query("SELECT * FROM Eip20Event")
    suspend fun getEip20Events(): List<Eip20Event>

    @Query("SELECT * FROM Eip20Event WHERE hash IN (:hashes)")
    suspend fun getEip20EventsByHashes(hashes: List<ByteArray>): List<Eip20Event>

    @Query("""
        DELETE FROM Eip20Event
        WHERE hash = :hash
          AND contractAddress = :contractAddress
          AND `from` = :from
          AND `to` = :to
          AND value = 0
    """)
    suspend fun deleteZeroValueDuplicate(
        hash: ByteArray,
        contractAddress: ByteArray,
        from: ByteArray,
        to: ByteArray
    )

}
