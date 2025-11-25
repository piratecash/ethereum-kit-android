package io.horizontalsystems.erc20kit.core

import io.horizontalsystems.ethereumkit.core.IEip20Storage
import io.horizontalsystems.ethereumkit.core.ITransactionProvider
import io.horizontalsystems.ethereumkit.core.ITransactionSyncer
import io.horizontalsystems.ethereumkit.core.TokenTransactionProvider
import io.horizontalsystems.ethereumkit.models.Eip20Event
import io.horizontalsystems.ethereumkit.models.ProviderTokenTransaction
import io.horizontalsystems.ethereumkit.models.Transaction
import io.reactivex.Single

class Erc20TransactionSyncer(
        private val transactionProvider: ITransactionProvider,
        private val tokenTransactionProvider: TokenTransactionProvider,
        private val fallbackHistoryBlockWindow: Long,
        private val storage: IEip20Storage
) : ITransactionSyncer {

    private fun handle(transactions: List<ProviderTokenTransaction>) {
        if (transactions.isEmpty()) return

        val events = transactions.map { tx ->
            Eip20Event(
                hash = tx.hash,
                blockNumber = tx.blockNumber,
                contractAddress = tx.contractAddress,
                from = tx.from,
                to = tx.to,
                value = tx.value,
                tokenName = tx.tokenName,
                tokenSymbol = tx.tokenSymbol,
                tokenDecimal = tx.tokenDecimal
            )
        }

        // Delete zero-value duplicates before saving new events with correct values
        events.forEach { newEvent ->
            if (newEvent.value > java.math.BigInteger.ZERO) {
                storage.deleteZeroValueDuplicate(
                    hash = newEvent.hash,
                    contractAddress = newEvent.contractAddress,
                    from = newEvent.from,
                    to = newEvent.to
                )
            }
        }

        storage.save(events)
    }

    override fun getTransactionsSingle(): Single<Pair<List<Transaction>, Boolean>> {
        val lastTransactionBlockNumber = storage.getLastEvent()?.blockNumber ?: 0
        val initial: Boolean = lastTransactionBlockNumber == 0L

        // Request with overlap to catch late-indexed events from BSCScan
        val SAFETY_OVERLAP = 3
        val startBlock = if (lastTransactionBlockNumber > SAFETY_OVERLAP) {
            lastTransactionBlockNumber - SAFETY_OVERLAP
        } else {
            0L
        }

        val receivedTransactions = if (startBlock == 0L) {
            requestTokenTransactionsEtherscan(startBlock)
                .onErrorResumeNext {
                    tokenTransactionProvider.getTokenTransactions(-fallbackHistoryBlockWindow)
                }
        } else {
            tokenTransactionProvider.getTokenTransactions(startBlock)
        }

        return receivedTransactions
                .doOnSuccess { providerTokenTransactions -> handle(providerTokenTransactions) }
                .map { providerTokenTransactions ->
                    val array = providerTokenTransactions.map { transaction ->
                        Transaction(
                                hash = transaction.hash,
                                timestamp = transaction.timestamp,
                                isFailed = false,
                                blockNumber = transaction.blockNumber,
                                transactionIndex = transaction.transactionIndex,
                                nonce = transaction.nonce,
                                gasPrice = transaction.gasPrice,
                                gasLimit = transaction.gasLimit,
                                gasUsed = transaction.gasUsed
                        )

                    }
                    Pair(array, initial)
                }
                .onErrorReturnItem(Pair(listOf(), initial))
    }

    private fun requestTokenTransactionsEtherscan(startBlock: Long): Single<List<ProviderTokenTransaction>> {
        return transactionProvider.getTokenTransactions(startBlock)
    }

}
