package io.horizontalsystems.ethereumkit.transactionsyncers

import io.horizontalsystems.ethereumkit.core.ITransactionProvider
import io.horizontalsystems.ethereumkit.core.ITransactionStorage
import io.horizontalsystems.ethereumkit.core.ITransactionSyncer
import io.horizontalsystems.ethereumkit.core.toHexString
import io.horizontalsystems.ethereumkit.models.InternalTransaction
import io.horizontalsystems.ethereumkit.models.ProviderInternalTransaction
import io.horizontalsystems.ethereumkit.models.Transaction
import io.reactivex.Single

class InternalTransactionSyncer(
        private val transactionProvider: ITransactionProvider,
        private val storage: ITransactionStorage
) : ITransactionSyncer {

    private fun handle(transactions: List<ProviderInternalTransaction>) {
        if (transactions.isEmpty()) return

        val internalTransactions = transactions.map { tx ->
            InternalTransaction(
                hash = tx.hash,
                traceId = tx.traceId,
                blockNumber = tx.blockNumber,
                from = tx.from,
                to = tx.to,
                value = tx.value
            )
        }

        storage.saveInternalTransactions(internalTransactions)
    }

    // Only the checkpoint block can overlap with storage: the request starts at it (inclusive),
    // so everything above it is new by construction.
    private fun unseen(
        transactions: List<ProviderInternalTransaction>,
        checkpointBlockNumber: Long
    ): List<ProviderInternalTransaction> {
        val checkpointHashes = transactions
            .filter { it.blockNumber == checkpointBlockNumber }
            .map { it.hash }
            .distinctBy { it.toHexString() } // ByteArray equality is by reference

        val stored = storage.getInternalTransactionsByHashes(checkpointHashes)
            .map { it.hashString to it.traceId }
            .toSet()

        return transactions.filterNot { (it.hash.toHexString() to it.traceId) in stored }
    }

    override fun getTransactionsSingle(): Single<Pair<List<Transaction>, Boolean>> {
        val lastTransactionBlockNumber = storage.getLastInternalTransaction()?.blockNumber ?: 0
        val initial = lastTransactionBlockNumber == 0L

        return transactionProvider.getInternalTransactions(lastTransactionBlockNumber)
                .map { providerInternalTransactions -> unseen(providerInternalTransactions, lastTransactionBlockNumber) }
                .doOnSuccess { unseenTransactions -> handle(unseenTransactions) }
                .map { unseenTransactions ->
                    val array = unseenTransactions.map { transaction ->
                        Transaction(
                                hash = transaction.hash,
                                timestamp = transaction.timestamp,
                                isFailed = false,
                                blockNumber = transaction.blockNumber,
                        )
                    }
                    Pair(array, initial)
                }
                .onErrorReturnItem(Pair(listOf(), initial))
    }

}
