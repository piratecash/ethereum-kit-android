package io.horizontalsystems.erc20kit.core

import android.annotation.SuppressLint
import io.horizontalsystems.ethereumkit.core.IEip20Storage
import io.horizontalsystems.ethereumkit.core.ITransactionProvider
import io.horizontalsystems.ethereumkit.core.ITransactionSyncer
import io.horizontalsystems.ethereumkit.core.TokenTransactionProvider
import io.horizontalsystems.ethereumkit.core.storage.TransactionSyncSourceStorage
import io.horizontalsystems.ethereumkit.models.SyncSource
import io.horizontalsystems.ethereumkit.models.Transaction
import io.reactivex.Single
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.rx2.rxSingle

class Erc20TransactionSyncer(
    private val transactionProvider: ITransactionProvider,
    private val tokenTransactionProvider: TokenTransactionProvider,
    private val fallbackHistoryBlockWindow: Long,
    private val storage: IEip20Storage,
    private val transactionSaver: TransactionSaver,
    private val syncSourceStorage: TransactionSyncSourceStorage? = null
) : ITransactionSyncer {

    @SuppressLint("CheckResult")
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

        // Always try Etherscan first (faster, indexed), fallback to RPC on error
        val receivedTransactions = requestTokenTransactionsEtherscan(startBlock)
            .onErrorResumeNext {
                rxSingle(Dispatchers.IO) {
                    val fromBlock =
                        if (startBlock == 0L) -fallbackHistoryBlockWindow else startBlock
                    tokenTransactionProvider.getTokenTransactions(fromBlock)
                }
            }

        return receivedTransactions
            .doOnSuccess { result ->
                transactionSaver.handle(result.transactions)
                syncSourceStorage?.saveAll(
                    result.transactions.map { it.hash },
                    SyncSource.ERC20_SYNCER
                )
                rxSingle(Dispatchers.IO) {
                    storage.saveSyncBlockInfo(
                        lastScannedBlock = result.lastScannedBlock,
                        historicalMinScannedBlock = null
                    )
                }.map { result } // we need map to wait for saveSyncBlockInfo finish
            }
            .map { providerTokenTransactions ->
                val array = providerTokenTransactions.transactions.map { transaction ->
                    Transaction(
                        hash = transaction.hash,
                        timestamp = transaction.timestamp,
                        isFailed = false,
                        blockNumber = transaction.blockNumber,
                        transactionIndex = transaction.transactionIndex,
                        from = transaction.from,
                        to = null,
                        value = transaction.value,
                        input = transaction.input,
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
