package io.horizontalsystems.erc20kit.core

import io.horizontalsystems.ethereumkit.core.IEip20Storage
import io.horizontalsystems.ethereumkit.core.ITransactionProvider
import io.horizontalsystems.ethereumkit.core.ITransactionSyncer
import io.horizontalsystems.ethereumkit.core.TokenTransactionProvider
import io.horizontalsystems.ethereumkit.models.Eip20Event
import io.horizontalsystems.ethereumkit.models.ProviderTokenTransaction
import io.horizontalsystems.ethereumkit.models.Transaction
import io.reactivex.Single
import io.horizontalsystems.ethereumkit.models.Address

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
        val lastScannedBlock = storage.getLastScannedBlock() ?: 0
        val initial: Boolean = lastScannedBlock == 0L

        // Overlap to survive small reorgs/late indexing
        val SAFETY_OVERLAP = 6
        val startBlock = if (lastScannedBlock > SAFETY_OVERLAP) {
            lastScannedBlock - SAFETY_OVERLAP
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
                .doOnSuccess { result ->
                    handle(result.transactions)
                    storage.saveLastScannedBlock(result.lastScannedBlock)
                }
                .map { providerTokenTransactions ->
                    val array = providerTokenTransactions.transactions.map { transaction ->
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

    private fun requestTokenTransactionsEtherscan(startBlock: Long): Single<TokenTransactionProvider.TokenTransactionsResult> {
        return transactionProvider.getTokenTransactions(startBlock)
            .map { list ->
                val maxBlock = list.maxOfOrNull { it.blockNumber } ?: startBlock
                TokenTransactionProvider.TokenTransactionsResult(list, maxBlock)
            }
    }

}
