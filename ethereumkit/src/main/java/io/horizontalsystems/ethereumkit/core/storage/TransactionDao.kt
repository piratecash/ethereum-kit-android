package io.horizontalsystems.ethereumkit.core.storage

import androidx.room.*
import io.horizontalsystems.ethereumkit.models.InternalTransaction
import io.horizontalsystems.ethereumkit.models.Transaction

@Dao
interface TransactionDao {

    @Query("SELECT * FROM `Transaction` WHERE hash=:hash")
    suspend fun getTransaction(hash: ByteArray): Transaction?

    @Query("SELECT * FROM `InternalTransaction` ORDER BY blockNumber DESC LIMIT 1")
    suspend fun getLastInternalTransaction() : InternalTransaction?

    @Query("SELECT * FROM `Transaction` WHERE hash IN (:hashes)")
    suspend fun getTransactions(hashes: List<ByteArray>): List<Transaction>

    @RawQuery
    suspend fun getTransactionsByRawQuery(query: RoomRawQuery): List<Transaction>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transactions: List<Transaction>)

    @Query("SELECT * FROM `Transaction` WHERE blockNumber IS NULL AND isFailed IS 0")
    suspend fun getPendingTransactions(): List<Transaction>

    @androidx.room.Transaction
    @RawQuery
    suspend fun getPending(query: RoomRawQuery): List<Transaction>

    @Query("SELECT * FROM `Transaction` WHERE blockNumber IS NOT NULL AND nonce IN (:nonces) AND `from`=:from")
    suspend fun getNonPendingByNonces(from: ByteArray, nonces: List<Long>): List<Transaction>

    @Query("SELECT * FROM `InternalTransaction`")
    suspend fun getInternalTransactions(): List<InternalTransaction>

    @Query("SELECT * FROM `InternalTransaction` WHERE hash IN (:hashes)")
    suspend fun getInternalTransactionsByHashes(hashes: List<ByteArray>): List<InternalTransaction>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInternalTransactions(internalTransactions: List<InternalTransaction>)

}
