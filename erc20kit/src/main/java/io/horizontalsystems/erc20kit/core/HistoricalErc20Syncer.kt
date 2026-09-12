package io.horizontalsystems.erc20kit.core

import io.horizontalsystems.ethereumkit.core.EthereumKit
import io.horizontalsystems.ethereumkit.core.IEip20Storage
import io.horizontalsystems.ethereumkit.core.TokenTransactionProvider
import io.horizontalsystems.ethereumkit.core.TransactionManager
import io.horizontalsystems.ethereumkit.models.ProviderTokenTransaction
import io.horizontalsystems.ethereumkit.network.ConnectionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

class HistoricalErc20Syncer(
    private val transactionManager: TransactionManager,
    private val tokenTransactionProvider: TokenTransactionProvider,
    private val storage: IEip20Storage,
    private val transactionSaver: TransactionSaver,
    private val connectionManager: ConnectionManager
) : EthereumKit.HistoricalSyncer, ConnectionManager.Listener {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var syncJob: Job? = null
    private var startBlock: Long = 0

    // Parent job of the current run — isEnabled survives stop() and cannot tell a paused syncer
    // from a runnable one. stop() cancels this, which also cancels any coroutine launched into it.
    private val runJob = AtomicReference<Job?>(null)

    private val _syncState = MutableStateFlow<EthereumKit.HistoricalSyncState>(EthereumKit.HistoricalSyncState.Idle)
    override val syncState: StateFlow<EthereumKit.HistoricalSyncState> = _syncState.asStateFlow()

    // Only start if explicitly enabled by EthereumKit (when Etherscan returns no data).
    // Written on the transaction-sync thread, read by the kit's resume on the lifecycle thread.
    @Volatile
    override var isEnabled: Boolean = false

    companion object {
        private const val HIST_WINDOW = 50_000L
        private const val SAFETY_OVERLAP = 6L
    }

    init {
        connectionManager.addListener(this)
    }

    /**
     * Returns the run every coroutine of this start must be launched into. Replacing a live run
     * would orphan its sync job — stop() would then cancel only the replacement while the real sync
     * kept issuing network requests — so a live run is reused and a new one is published by CAS.
     */
    private fun claimRun(): Job {
        while (true) {
            val current = runJob.get()
            if (current?.isActive == true) return current
            val fresh = SupervisorJob(scope.coroutineContext[Job])
            if (runJob.compareAndSet(current, fresh)) return fresh
            fresh.cancel()
        }
    }

    override fun start() {
        val run = claimRun()

        // stop() unregisters the listener, so a restarted syncer has to register again.
        connectionManager.addListener(this)

        launchSync(run)
    }

    override fun stop() {
        runJob.get()?.cancel()
        _syncState.value = EthereumKit.HistoricalSyncState.Idle
        connectionManager.removeListener(this)
        Timber.i("Stopped historical ERC20 sync")
    }

    private fun launchSync(run: Job) {
        if (!isEnabled) {
            Timber.i("Historical sync not enabled, skipping")
            return
        }

        if (syncJob?.isActive == true) {
            Timber.i("Historical sync already running")
            return
        }

        Timber.i("Starting historical ERC20 sync")

        // Launching into [run] is the fence: if stop() cancelled it — even after the checks above —
        // the coroutine is born cancelled and its body never executes.
        syncJob = scope.launch(run) {
            try {
                syncHistoricalBatches()
            } catch (e: CancellationException) {
                Timber.i("Historical sync cancelled")
            } catch (e: Throwable) {
                Timber.e(e, "Historical sync failed")
                _syncState.value = EthereumKit.HistoricalSyncState.Idle
            }
        }
    }

    private suspend fun syncHistoricalBatches() {
        var repeat = true
        var retryAttemptsRemaining = 3
        var isFirstBatch = true
        while (repeat) {
            val latest = storage.getHistoricalMinScannedBlock()
                ?: storage.getEarliestEip20Event()?.blockNumber
                ?: tokenTransactionProvider.fetchBlockNumber()

            if (isFirstBatch) {
                startBlock = latest
                _syncState.value = EthereumKit.HistoricalSyncState.Syncing(startBlock, latest)
                isFirstBatch = false
            }

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
        // Ensure terminal state when loop exits (retries exhausted or scope cancelled)
        if (_syncState.value is EthereumKit.HistoricalSyncState.Syncing) {
            _syncState.value = EthereumKit.HistoricalSyncState.Idle
        }
    }

    private suspend fun performBatchSync(latest: Long): Boolean {
        if (!coroutineContext.isActive) {
            Timber.i("Historical sync stopped by user")
            return false
        }

        if (latest <= 0) {
            Timber.i("Reached genesis block, historical sync complete")
            _syncState.value = EthereumKit.HistoricalSyncState.Completed
            return false
        }

        val fromBlock = maxOf(0, latest - HIST_WINDOW)
        val toBlock = maxOf(0, latest + SAFETY_OVERLAP)

        Timber.i("Historical sync step: fetching blocks $fromBlock to $toBlock")

        return try {
            val result = tokenTransactionProvider.getTokenTransactions(fromBlock, toBlock)
            handleBatchResult(result.transactions, fromBlock)
            _syncState.value = EthereumKit.HistoricalSyncState.Syncing(startBlock, fromBlock)
            true
        } catch (e: CancellationException) {
            throw e
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

            val transactionObjects = transactions.map {
                it.ethereumTransaction()
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
            // Continues the run this syncer is already on; a stopped syncer stays stopped.
            runJob.get()?.let(::launchSync)
        } else {
            stop()
        }
    }
}
