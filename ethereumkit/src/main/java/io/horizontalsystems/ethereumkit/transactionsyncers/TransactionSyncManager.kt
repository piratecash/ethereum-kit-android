package io.horizontalsystems.ethereumkit.transactionsyncers

import io.horizontalsystems.ethereumkit.core.EthereumKit
import io.horizontalsystems.ethereumkit.core.ITransactionSyncer
import io.horizontalsystems.ethereumkit.core.TransactionManager
import io.horizontalsystems.ethereumkit.models.Transaction
import io.reactivex.BackpressureStrategy
import io.reactivex.Flowable
import io.reactivex.Single
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.SerialDisposable
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.PublishSubject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Logger

class TransactionSyncManager(
    private val transactionManager: TransactionManager
) {
    private val logger = Logger.getLogger(this.javaClass.simpleName)

    // The container IS the authority to run: pause() disposes it and only resume() opens a new one,
    // so a sync that captured it before a pause registers into the very container pause disposes.
    @Volatile
    private var disposables = CompositeDisposable()

    private val stateSubject = PublishSubject.create<EthereumKit.SyncState>()
    private val syncers = CopyOnWriteArrayList<ITransactionSyncer>()

    var syncState: EthereumKit.SyncState =
        EthereumKit.SyncState.NotSynced(EthereumKit.SyncError.NotStarted())
        private set(value) {
            field = value
            stateSubject.onNext(value)
        }
    val syncStateAsync: Flowable<EthereumKit.SyncState> =
        stateSubject.toFlowable(BackpressureStrategy.BUFFER)

    fun add(syncer: ITransactionSyncer) {
        syncers.add(syncer)
    }

    fun sync() {
        if (syncState is EthereumKit.SyncState.Syncing) return

        // Claim ownership before subscribing: add() fails on an already-disposed container, and a
        // pause landing afterwards still reaches the subscription through this placeholder.
        val generation = disposables
        val run = SerialDisposable()
        if (!generation.add(run)) return

        syncState = EthereumKit.SyncState.Syncing()

        Single.zip(syncers.map {
            it.getTransactionsSingle()
        }) { array ->
            array.map { it as Pair<List<Transaction>, Boolean> }
                .reduce { acc, list ->
                    Pair(acc.first + list.first, acc.second && list.second)
                }
        }
            .subscribeOn(Schedulers.io())
            // subscribe() schedules the sources before it returns the disposable; onSubscribe hands
            // it over while the caller still owns the chain, so a pause can never miss it.
            .doOnSubscribe { run.set(it) }
            .subscribe({ transactions ->
                if (run.isDisposed) return@subscribe

                handle(transactions)
                publishTerminal(generation, run, EthereumKit.SyncState.Synced())
            }, {
                if (run.isDisposed) return@subscribe

                publishTerminal(generation, run, EthereumKit.SyncState.NotSynced(it))
                logger.warning("sync ERROR = ${it.message}")
            })
    }

    /**
     * Publishes the outcome of [run] and repairs it if a concurrent [pause] disposed it.
     *
     * Only the generation that authorized this run may speak for the manager: after a resume
     * installed a new container, neither the outcome nor its paused repair belongs to the live
     * run. Within the generation, pause() disposes before it publishes, so re-reading the
     * disposal after the write restores that order.
     */
    private fun publishTerminal(
        generation: CompositeDisposable,
        run: SerialDisposable,
        state: EthereumKit.SyncState
    ) {
        if (generation !== disposables) return

        syncState = state
        if (run.isDisposed) syncState = pausedState()
    }

    private fun pausedState() = EthereumKit.SyncState.NotSynced(EthereumKit.SyncError.NotStarted())

    /** Drops in-flight requests and revokes the right to start new ones until [resume]. */
    fun pause() {
        disposables.dispose()
        syncState = pausedState()
    }

    /** Opens a new run after [pause]; called from the kit's serialized lifecycle only. */
    fun resume() {
        if (disposables.isDisposed) {
            disposables = CompositeDisposable()
        }
        // A sync of the previous run may still write its Syncing state after pause; without this
        // reset that stale value would make every later sync() return early.
        syncState = pausedState()
    }

    private fun merge(tx1: Transaction, tx2: Transaction) =
        Transaction(
            tx1.hash,
            tx1.timestamp,
            tx1.isFailed,
            tx1.blockNumber ?: tx2.blockNumber,
            tx1.transactionIndex ?: tx2.transactionIndex,
            tx1.from ?: tx2.from,
            tx1.to ?: tx2.to,
            tx1.value ?: tx2.value,
            tx1.input ?: tx2.input,
            tx1.nonce ?: tx2.nonce,
            tx1.gasPrice ?: tx2.gasPrice,
            tx1.maxFeePerGas ?: tx2.maxFeePerGas,
            tx1.maxPriorityFeePerGas ?: tx2.maxPriorityFeePerGas,
            tx1.gasLimit ?: tx2.gasLimit,
            tx1.gasUsed ?: tx2.gasUsed
        )

    private fun handle(result: Pair<List<Transaction>, Boolean>) {
        val transactions = result.first
        val initial = result.second

        val map: MutableMap<String, Transaction> = mutableMapOf()

        for (transaction in transactions) {
            val tx = map[transaction.hashString]

            if (tx == null) {
                map[transaction.hashString] = transaction
            } else {
                map[transaction.hashString] = merge(transaction, tx)
            }
        }

        transactionManager.handle(map.values.toList(), initial)
    }

}
