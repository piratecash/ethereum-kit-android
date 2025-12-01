package io.horizontalsystems.erc20kit.core

import io.horizontalsystems.ethereumkit.core.EthereumKit
import io.horizontalsystems.ethereumkit.core.IEip20Storage
import io.horizontalsystems.ethereumkit.core.TokenTransactionProvider
import io.horizontalsystems.ethereumkit.core.TransactionManager
import io.horizontalsystems.ethereumkit.models.ProviderTokenTransaction
import io.horizontalsystems.ethereumkit.models.Transaction
import io.horizontalsystems.ethereumkit.network.ConnectionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

class HistoricalErc20Syncer(
    private val transactionManager: TransactionManager,
    private val tokenTransactionProvider: TokenTransactionProvider,
    private val storage: IEip20Storage,
    private val transactionSaver: TransactionSaver,
    private val connectionManager: ConnectionManager
) : EthereumKit.HistoricalSyncer, ConnectionManager.Listener {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var syncJob: Job? = null

    companion object {
        private const val HIST_WINDOW = 50_000L
        private const val SAFETY_OVERLAP = 6L
    }

    init {
        connectionManager.addListener(this)
    }

    override fun start() {
        if (syncJob?.isActive == true) {
            Timber.i("Historical sync already running")
            return
        }

        Timber.i("Starting historical ERC20 sync")

        syncJob = scope.launch {
            try {
                syncHistoricalBatches()
            } catch (e: CancellationException) {
                Timber.i("Historical sync cancelled")
            } catch (e: Throwable) {
                Timber.e(e, "Historical sync failed")
            }
        }
    }

    override fun stop() {
        syncJob?.cancel()
        Timber.i("Stopped historical ERC20 sync")
    }

    private suspend fun syncHistoricalBatches() {
        var repeat = true
        var retryAttemptsRemaining = 3
        while (repeat) {
            val latest = storage.getHistoricalMinScannedBlock()
                ?: storage.getEarliestEip20Event()?.blockNumber
                ?: tokenTransactionProvider.fetchBlockNumber()

            repeat = performBatchSync(latest)
            if (repeat) {
                retryAttemptsRemaining = 3
            }
            if (!repeat && latest > 0 && retryAttemptsRemaining > 0) {
                // Wait random time to avoid limits
                Timber.i("Waiting before next historical sync attempt")
                delay((1_000L..10_000L).random())
                --retryAttemptsRemaining
                repeat = true
            }
        }
    }

    private suspend fun performBatchSync(latest: Long): Boolean {
        if (!scope.isActive) {
            Timber.i("Historical sync stopped by user")
            return false
        }

        if (latest <= 0) {
            Timber.i("Reached genesis block, historical sync complete")
            return false
        }

        val fromBlock = maxOf(0, latest - HIST_WINDOW)
        val toBlock = maxOf(0, latest + SAFETY_OVERLAP)

        Timber.i("Historical sync step: fetching blocks $fromBlock to $toBlock")

        return try {
            val result = tokenTransactionProvider.getTokenTransactions(fromBlock, toBlock)
            handleBatchResult(result.transactions, fromBlock)
            true
        } catch (e: Throwable) {
            Timber.e(e, "Historical sync batch failed for blocks $fromBlock-$toBlock")
            false
        }
    }

    private suspend fun handleBatchResult(
        transactions: List<ProviderTokenTransaction>,
        fromBlock: Long
    ) {
        withContext(Dispatchers.IO + CoroutineExceptionHandler {
            _, exception ->
            Timber.e(exception, "Error handling historical sync batch result")
        }) {
            transactionSaver.handle(transactions)

            // Create Transaction objects and process them through TransactionManager
            val transactionObjects = transactions.map { tx ->
                Transaction(
                    hash = tx.hash,
                    timestamp = tx.timestamp,
                    isFailed = false,
                    blockNumber = tx.blockNumber,
                    transactionIndex = tx.transactionIndex,
                    from = tx.from,
                    to = tx.contractAddress,
                    value = tx.value,
                    input = tx.input,
                    nonce = tx.nonce,
                    gasPrice = tx.gasPrice,
                    gasLimit = tx.gasLimit,
                    gasUsed = tx.gasUsed
                )
            }

            if (transactionObjects.isNotEmpty()) {
                transactionManager.handle(transactions = transactionObjects, initial = false)
                Timber.i("Historical sync: saved ${transactionObjects.size} events and transactions from batch $fromBlock-${fromBlock + HIST_WINDOW - 1}")
            }

            storage.saveSyncBlockInfo(
                lastScannedBlock = null,
                historicalMinScannedBlock = fromBlock
            )
            Timber.i("Historical sync: updated cursor to $fromBlock")
        }
    }

    override fun onConnectionChange() {
        if (connectionManager.isConnected) {
            start()
        } else {
            stop()
        }
    }
}
