package io.horizontalsystems.ethereumkit.core.storage

import androidx.room.RoomRawQuery
import io.horizontalsystems.ethereumkit.core.IRawTransactionBroadcastStorage
import io.horizontalsystems.ethereumkit.core.ITransactionStorage
import io.horizontalsystems.ethereumkit.core.toRawHexString
import io.horizontalsystems.ethereumkit.models.Address
import io.horizontalsystems.ethereumkit.models.InternalTransaction
import io.horizontalsystems.ethereumkit.models.RawTransactionBroadcastRecord
import io.horizontalsystems.ethereumkit.models.Transaction
import io.horizontalsystems.ethereumkit.models.TransactionTag

class TransactionStorage(database: TransactionDatabase) : ITransactionStorage, IRawTransactionBroadcastStorage {
    private val transactionDao = database.transactionDao()
    private val tagsDao = database.transactionTagDao()
    private val rawTransactionBroadcastDao = database.rawTransactionBroadcastDao()

    override suspend fun getTransactions(hashes: List<ByteArray>): List<Transaction> =
        transactionDao.getTransactions(hashes)

    override suspend fun getTransaction(hash: ByteArray): Transaction? =
        transactionDao.getTransaction(hash)

    override suspend fun getTransactionsBefore(tags: List<List<String>>, hash: ByteArray?, limit: Int?): List<Transaction> {
        val whereConditions = mutableListOf<String>()

        if (tags.isNotEmpty()) {
            val tagConditions = tags
                .mapIndexed { index, andTags ->
                    val tagsString = andTags.joinToString(", ") { "'$it'" }
                    "transaction_tags_$index.name IN ($tagsString)"
                }
                .joinToString(" AND ")

            whereConditions.add(tagConditions)
        }

        hash?.let { transactionDao.getTransaction(hash) }?.let { fromTransaction ->
            val transactionIndex = fromTransaction.transactionIndex ?: 0
            val fromCondition = """
                           (
                                tx.timestamp < ${fromTransaction.timestamp} OR 
                                (
                                    tx.timestamp = ${fromTransaction.timestamp} AND 
                                    tx.transactionIndex < $transactionIndex
                                ) OR
                                (
                                    tx.timestamp = ${fromTransaction.timestamp} AND
                                    tx.transactionIndex = $transactionIndex AND
                                    HEX(tx.hash) < "${fromTransaction.hash.toRawHexString().uppercase()}"
                                )
                           )
                           """

            whereConditions.add(fromCondition)
        }

        val transactionTagJoinStatements = tags
            .mapIndexed { index, _ ->
                "INNER JOIN TransactionTag AS transaction_tags_$index ON tx.hash = transaction_tags_$index.hash"
            }
            .joinToString("\n")

        val whereClause = if (whereConditions.isNotEmpty()) "WHERE ${whereConditions.joinToString(" AND ")}" else ""
        val orderClause = "ORDER BY tx.timestamp DESC, tx.transactionIndex DESC, HEX(tx.hash) DESC"
        val limitClause = limit?.let { "LIMIT $limit" } ?: ""

        val sqlQuery = """
                      SELECT tx.*
                      FROM `Transaction` as tx
                      $transactionTagJoinStatements
                      $whereClause
                      $orderClause
                      $limitClause
                      """

        return transactionDao.getTransactionsByRawQuery(RoomRawQuery(sqlQuery))
    }

    override suspend fun save(transactions: List<Transaction>) {
        transactionDao.insert(transactions)
    }

    override suspend fun getPendingTransactions(): List<Transaction> =
        transactionDao.getPendingTransactions()

    override suspend fun getPendingTransactions(tags: List<List<String>>): List<Transaction> {
        val whereConditions = mutableListOf<String>()
        var transactionTagJoinStatements = ""

        if (tags.isNotEmpty()) {
            val tagCondition = tags
                .mapIndexed { index, andTags ->
                    val tagsString = andTags.joinToString(", ") { "'$it'" }
                    "transaction_tags_$index.name IN ($tagsString)"
                }
                .joinToString(" AND ")

            whereConditions.add(tagCondition)

            transactionTagJoinStatements += tags
                .mapIndexed { index, _ ->
                    "INNER JOIN TransactionTag AS transaction_tags_$index ON tx.hash = transaction_tags_$index.hash"
                }
                .joinToString("\n")
        }

        whereConditions.add(
            "tx.blockNumber IS NULL"
        )

        val whereClause =
            if (whereConditions.isNotEmpty()) "WHERE ${whereConditions.joinToString(" AND ")}" else ""

        val sqlQuery = """
                      SELECT tx.*
                      FROM `Transaction` as tx
                      $transactionTagJoinStatements
                      $whereClause
                      """

        return transactionDao.getPending(RoomRawQuery(sqlQuery))
    }

    override suspend fun getNonPendingTransactionsByNonces(from: Address, pendingTransactionNonces: List<Long>): List<Transaction> =
        transactionDao.getNonPendingByNonces(from.raw, pendingTransactionNonces)

    override suspend fun getLastInternalTransaction(): InternalTransaction? =
        transactionDao.getLastInternalTransaction()

    override suspend fun getInternalTransactions(): List<InternalTransaction> =
        transactionDao.getInternalTransactions()

    override suspend fun getInternalTransactionsByHashes(hashes: List<ByteArray>): List<InternalTransaction> =
        transactionDao.getInternalTransactionsByHashes(hashes)

    override suspend fun saveInternalTransactions(internalTransactions: List<InternalTransaction>) {
        transactionDao.insertInternalTransactions(internalTransactions)
    }

    override suspend fun saveTags(tags: List<TransactionTag>) {
        tagsDao.insert(tags)
    }

    override suspend fun getDistinctTokenContractAddresses(): List<String> {
        return tagsDao.getDistinctTokenContractAddresses()
    }

    override suspend fun getTransactionsAfter(hash: ByteArray?): List<Transaction> {
        val whereConditions = mutableListOf<String>()
        hash?.let { transactionDao.getTransaction(hash) }?.let { fromTransaction ->
            val transactionIndex = fromTransaction.transactionIndex ?: 0
            val fromCondition = """
                           (
                                tx.timestamp > ${fromTransaction.timestamp} OR 
                                (
                                    tx.timestamp = ${fromTransaction.timestamp} AND 
                                    tx.transactionIndex > $transactionIndex
                                ) OR
                                (
                                    tx.timestamp = ${fromTransaction.timestamp} AND
                                    tx.transactionIndex = $transactionIndex AND
                                    HEX(tx.hash) > "${fromTransaction.hash.toRawHexString().uppercase()}"
                                )
                           )
                           """

            whereConditions.add(fromCondition)
        }

        val whereClause = if (whereConditions.isNotEmpty()) "WHERE ${whereConditions.joinToString(" AND ")}" else ""
        val orderClause = "ORDER BY tx.timestamp, tx.transactionIndex, HEX(tx.hash)"

        val sqlQuery = """
                      SELECT tx.*
                      FROM `Transaction` as tx
                      $whereClause
                      $orderClause
                      """

        return transactionDao.getTransactionsByRawQuery(RoomRawQuery(sqlQuery))
    }

    override suspend fun getRawTransactionBroadcast(hash: ByteArray): RawTransactionBroadcastRecord? =
        rawTransactionBroadcastDao.get(hash)

    override suspend fun getRawTransactionBroadcasts(): List<RawTransactionBroadcastRecord> =
        rawTransactionBroadcastDao.getAll()

    override suspend fun addRawTransactionBroadcast(record: RawTransactionBroadcastRecord) {
        rawTransactionBroadcastDao.insert(record)
    }

    override suspend fun updateRawTransactionBroadcast(record: RawTransactionBroadcastRecord) {
        rawTransactionBroadcastDao.update(record)
    }

    override suspend fun deleteRawTransactionBroadcast(record: RawTransactionBroadcastRecord) {
        rawTransactionBroadcastDao.delete(record)
    }
}
