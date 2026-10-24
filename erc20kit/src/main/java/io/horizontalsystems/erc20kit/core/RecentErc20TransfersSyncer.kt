package io.horizontalsystems.erc20kit.core

import co.touchlab.kermit.Logger
import io.horizontalsystems.ethereumkit.core.IEip20Storage
import io.horizontalsystems.ethereumkit.core.ITransactionSyncer
import io.horizontalsystems.ethereumkit.core.TokenTransactionProvider
import io.horizontalsystems.ethereumkit.core.TransactionManager
import io.horizontalsystems.ethereumkit.core.storage.TransactionSyncSourceStorage
import io.horizontalsystems.ethereumkit.models.SyncSource
import io.horizontalsystems.ethereumkit.models.Transaction
import io.reactivex.Single
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.rx2.rxSingle
import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean

/** Reads transfers of the last blocks from the node after a token balance change, ahead of explorer indexing. */
class RecentErc20TransfersSyncer(
    private val tokenTransactionProvider: TokenTransactionProvider,
    private val transactionManager: TransactionManager,
    private val transactionSaver: TransactionSaver,
    private val syncSourceStorage: TransactionSyncSourceStorage,
    private val storage: IEip20Storage,
    private val awaitExplorerHistory: Boolean,
    private val log: Logger,
    private val clock: Clock = Clock.systemUTC()
) : ITransactionSyncer {

    private val requested = AtomicBoolean(false)
    private val inFlight = AtomicBoolean(false)

    // Written only while owning inFlight.
    @Volatile
    private var lastScanStartedAt: Long? = null

    override val requiresExplorer = false

    override fun onTokenBalanceChanged() {
        requested.set(true)
    }

    // Persists and handles its own result, so neither a failing sibling syncer nor a disposed zip can discard it.
    override fun getTransactionsSingle(): Single<Pair<List<Transaction>, Boolean>> =
        rxSingle(Dispatchers.IO) {
            scanIfDue()
            emptyList<Transaction>() to false
        }

    private suspend fun scanIfDue() {
        if (!inFlight.compareAndSet(false, true)) return

        try {
            val now = clock.millis()
            if (!requested.get() || withinFloor(now) || explorerHistoryPending()) return

            requested.set(false)
            lastScanStartedAt = now
            scan()
        } catch (e: CancellationException) {
            requested.set(true)
            throw e
        } catch (e: Throwable) {
            // No re-arm: the explorer still delivers these transfers.
            log.w(e) { "recent ERC-20 transfers scan failed" }
        } finally {
            inFlight.set(false)
        }
    }

    // A wall clock moved backwards counts as elapsed: a jump may cost one extra scan, never a stall.
    private fun withinFloor(now: Long): Boolean {
        val elapsed = now - (lastScanStartedAt ?: return false)
        return elapsed in 0 until SCAN_FLOOR_MILLIS
    }

    // The historical gate reads "no events yet" as "explorer has no history"; an RPC event saved
    // before the explorer's first delivery would close it for good.
    private suspend fun explorerHistoryPending() =
        awaitExplorerHistory && (storage.getLastScannedBlock() ?: 0L) == 0L

    private suspend fun scan() {
        val transfers = tokenTransactionProvider.getTokenTransactions(-RECENT_WINDOW_BLOCKS)
            .transactions
            .filter { it.tokenMetaResolved }
        log.d { "recent ERC-20 transfers found: ${transfers.size}" }
        if (transfers.isEmpty()) return

        transactionSaver.handle(transfers, replaceExisting = false)
        syncSourceStorage.saveAll(transfers.map { it.hash }, SyncSource.RPC)
        // A failed lookup yields 0, never a real gas value; nonce 0 is ambiguous. Null never overwrites a stored value.
        transactionManager.handle(transfers.map {
            it.ethereumTransaction().copy(
                nonce = null,
                gasPrice = it.gasPrice.nullIfPlaceholder(),
                gasLimit = it.gasLimit.nullIfPlaceholder(),
                gasUsed = it.gasUsed.nullIfPlaceholder()
            )
        })
    }

    private fun Long.nullIfPlaceholder() = takeIf { it > 0 }

    companion object {
        private const val RECENT_WINDOW_BLOCKS = 4000L
        private const val SCAN_FLOOR_MILLIS = 30_000L
    }
}
