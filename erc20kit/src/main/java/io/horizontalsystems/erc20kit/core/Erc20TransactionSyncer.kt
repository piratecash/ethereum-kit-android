package io.horizontalsystems.erc20kit.core

import co.touchlab.kermit.Logger
import io.horizontalsystems.ethereumkit.core.ChainHeadProvider
import io.horizontalsystems.ethereumkit.core.IEip20Storage
import io.horizontalsystems.ethereumkit.core.ITransactionProvider
import io.horizontalsystems.ethereumkit.core.ITransactionSyncer
import io.horizontalsystems.ethereumkit.core.TokenTransactionProvider
import io.horizontalsystems.ethereumkit.core.storage.TransactionSyncSourceStorage
import io.horizontalsystems.ethereumkit.models.ProviderTokenTransaction
import io.horizontalsystems.ethereumkit.models.SyncSource
import io.horizontalsystems.ethereumkit.models.Transaction
import io.reactivex.Single
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.rx2.rxSingle

class Erc20TransactionSyncer(
    private val transactionProvider: ITransactionProvider,
    private val tokenTransactionProvider: TokenTransactionProvider?,
    private val fallbackHistoryBlockWindow: Long,
    private val storage: IEip20Storage,
    private val transactionSaver: TransactionSaver,
    private val syncSourceStorage: TransactionSyncSourceStorage,
    private val chainHeadProvider: ChainHeadProvider,
    private val log: Logger
) : ITransactionSyncer {

    private class SyncResult(
        val transactions: List<ProviderTokenTransaction>,
        val lastScannedBlock: Long,
        val persistCursor: Boolean
    )

    override fun getTransactionsSingle(): Single<Pair<List<Transaction>, Boolean>> =
        // The heal has to run before this sync reads the cursor, or the run would save a value
        // derived from the foreign one and make it permanent.
        rxSingle(Dispatchers.IO) {
            healForeignCursor()
            storage.getLastScannedBlock() ?: 0L
        }.flatMap(::syncFrom)

    private suspend fun healForeignCursor() {
        val chainHead = chainHeadProvider.liveBlockHeight ?: return
        val cursor = storage.getLastScannedBlock()

        if (storage.clearForeignSyncState(chainHead, CURSOR_SANITY_MARGIN)) {
            log.w { "cleared foreign ERC-20 cursor $cursor, own chain head $chainHead" }
        }
    }

    private fun syncFrom(lastScannedBlock: Long): Single<Pair<List<Transaction>, Boolean>> {
        val initial = lastScannedBlock == 0L

        // Overlap to survive small reorgs/late indexing
        val startBlock = if (lastScannedBlock > SAFETY_OVERLAP) {
            lastScannedBlock - SAFETY_OVERLAP
        } else {
            0L
        }

        // Always try the explorer first (faster, indexed), fall back to own-chain RPC logs on error
        return requestTokenTransactionsEtherscan(startBlock)
            .onErrorResumeNext { error -> requestTokenTransactionsRpcLogs(startBlock, error) }
            .flatMap { result ->
                transactionSaver.handle(result.transactions)
                syncSourceStorage.saveAll(
                    result.transactions.map { it.hash },
                    SyncSource.ERC20_SYNCER
                )
                rxSingle(Dispatchers.IO) {
                    if (result.persistCursor) {
                        storage.saveSyncBlockInfo(
                            lastScannedBlock = result.lastScannedBlock,
                            historicalMinScannedBlock = null
                        )
                    }
                }.map { result } // we need map to wait for saveSyncBlockInfo finish
            }
            .map { result ->
                Pair(result.transactions.map { it.ethereumTransaction() }, initial)
            }
            .onErrorReturnItem(Pair(listOf(), initial))
    }

    private fun requestTokenTransactionsEtherscan(startBlock: Long): Single<SyncResult> =
        transactionProvider.getTokenTransactions(startBlock)
            .map { list ->
                SyncResult(
                    transactions = list,
                    lastScannedBlock = list.maxOfOrNull { it.blockNumber } ?: startBlock,
                    persistCursor = true
                )
            }

    private fun requestTokenTransactionsRpcLogs(
        startBlock: Long,
        explorerError: Throwable
    ): Single<SyncResult> {
        val provider = tokenTransactionProvider ?: return Single.error(explorerError)
        val initial = startBlock == 0L
        val fromBlock = if (initial) -fallbackHistoryBlockWindow else startBlock

        log.w { "explorer failed, using own-chain RPC logs fallback from block $fromBlock" }
        if (initial) {
            // The window scan sees only recent blocks; saving its head would pass the unscanned
            // history off as synced and the explorer would never fetch it.
            log.w { "initial fallback: cursor not persisted" }
        }

        return rxSingle(Dispatchers.IO) {
            val result = provider.getTokenTransactions(fromBlock)
            SyncResult(result.transactions, result.lastScannedBlock, persistCursor = !initial)
        }
    }

    companion object {
        private const val SAFETY_OVERLAP = 6L

        // A live RPC never lags this far behind its own chain, so a cursor above the head by this
        // much (~11 days of blocks on a 1 s chain) can only have come from a foreign chain.
        private const val CURSOR_SANITY_MARGIN = 1_000_000L
    }
}

internal fun ProviderTokenTransaction.ethereumTransaction() = Transaction(
    hash = hash,
    timestamp = timestamp,
    isFailed = false,
    blockNumber = blockNumber,
    transactionIndex = transactionIndex,
    // Never `from`: that is the token transfer's sender, and passing it off as the
    // transaction's sender makes swap decorators treat the output as sent to a third party.
    from = transactionSender,
    to = null,
    value = null,
    input = input,
    nonce = nonce,
    gasPrice = gasPrice,
    gasLimit = gasLimit,
    gasUsed = gasUsed
)
