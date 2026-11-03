package io.horizontalsystems.ethereumkit.transactionsyncers

import co.touchlab.kermit.Logger
import io.horizontalsystems.ethereumkit.api.jsonrpc.models.RpcTransaction
import io.horizontalsystems.ethereumkit.core.AccountDelegationState
import io.horizontalsystems.ethereumkit.core.CurrentDelegate
import io.horizontalsystems.ethereumkit.core.IBlockchain
import io.horizontalsystems.ethereumkit.core.ITransactionStorage
import io.horizontalsystems.ethereumkit.core.ITransactionSyncer
import io.horizontalsystems.ethereumkit.core.TransactionManager
import io.horizontalsystems.ethereumkit.core.rxIoDispatcher
import io.horizontalsystems.ethereumkit.models.Address
import io.horizontalsystems.ethereumkit.models.DefaultBlockParameter
import io.horizontalsystems.ethereumkit.models.Transaction
import io.horizontalsystems.ethereumkit.models.currentDelegate
import io.horizontalsystems.ethereumkit.models.ownDelegates
import io.horizontalsystems.ethereumkit.models.toAuthorizationDelegates
import io.reactivex.Single
import kotlinx.coroutines.rx2.await
import kotlinx.coroutines.rx2.rxSingle
import kotlinx.coroutines.sync.Mutex
import java.util.Optional

/**
 * Backfills the EIP-2718 type and the account's own EIP-7702 delegates of history rows from the node,
 * and keeps [state] at the account's current delegate while any such row exists.
 */
class DelegationSyncer(
    private val storage: ITransactionStorage,
    private val blockchain: IBlockchain,
    private val transactionManager: TransactionManager,
    private val state: AccountDelegationState,
    private val address: Address,
    private val chainId: Int,
    private val logger: Logger
) : ITransactionSyncer {

    override val requiresExplorer = false

    // Held until the body ends, past a disposal: an overlapping run could apply getCode answers out of
    // order, or reset the retry flag while handle() still runs in the disposed one.
    private val runLock = Mutex()
    private var published: CurrentDelegate = CurrentDelegate.Unknown
    private var republishPending = false

    override fun getTransactionsSingle(): Single<Pair<List<Transaction>, Boolean>> =
        rxSingle(rxIoDispatcher) {
            if (runLock.tryLock()) {
                try {
                    sync()
                } finally {
                    runLock.unlock()
                }
            }
            EMPTY_RESULT
        }.onErrorReturn { error ->
            logger.w(error) { "Delegation sync failed" }
            EMPTY_RESULT
        }

    private suspend fun sync() {
        val updates = typeUpdates(storage.getTransactionsWithUnknownType(address, BATCH))
        val storedCarriers = storage.getAuthorizationCarriers()
        val foundCarrier = updates.any { it.authorizationDelegates != null }
        if (storedCarriers.isNotEmpty() || foundCarrier) refreshCurrentDelegate()

        val current = state.current
        if (foundCarrier || current != published) republishPending = true
        val carriersToRefresh = if (republishPending) storedCarriers.filter { carrier -> updates.none { it.hash.contentEquals(carrier.hash) } } else emptyList()

        val transactions = updates + carriersToRefresh
        if (transactions.isNotEmpty()) transactionManager.handle(transactions)
        published = current
        republishPending = false
    }

    private suspend fun typeUpdates(candidates: List<Transaction>): List<Transaction> {
        if (candidates.isEmpty()) return emptyList()

        val singles = candidates.map { candidate ->
            blockchain.getTransaction(candidate.hash)
                .map { Optional.of(typeUpdate(candidate, it)) }
                .onErrorReturn { error ->
                    logger.w(error) { "Type of ${candidate.hashString} not fetched" }
                    Optional.empty()
                }
        }
        return Single.zip(singles) { results -> results.mapNotNull { (it as Optional<*>).orElse(null) as? Transaction } }.await()
    }

    /** Only the new columns are set; save() keeps every stored field that is null here. */
    private fun typeUpdate(candidate: Transaction, rpcTransaction: RpcTransaction) = Transaction(
        hash = candidate.hash,
        timestamp = candidate.timestamp,
        isFailed = false,
        type = rpcTransaction.type?.toInt() ?: 0,
        authorizationDelegates = rpcTransaction.ownDelegates(address, chainId.toLong()).toAuthorizationDelegates()
    )

    private suspend fun refreshCurrentDelegate() {
        blockchain.getCode(address, DefaultBlockParameter.Latest)
            .doOnSuccess { state.current = CurrentDelegate.Known(currentDelegate(it)) }
            .ignoreElement()
            .doOnError { logger.w(it) { "Current delegate not fetched" } }
            .onErrorComplete()
            .await()
    }

    private companion object {
        const val BATCH = 20
        val EMPTY_RESULT = Pair(emptyList<Transaction>(), false)
    }
}
