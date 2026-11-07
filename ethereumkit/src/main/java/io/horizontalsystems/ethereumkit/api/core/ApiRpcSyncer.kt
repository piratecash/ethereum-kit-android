package io.horizontalsystems.ethereumkit.api.core

import io.horizontalsystems.ethereumkit.api.jsonrpc.BlockNumberJsonRpc
import io.horizontalsystems.ethereumkit.api.jsonrpc.JsonRpc
import io.horizontalsystems.ethereumkit.core.EthereumKit
import io.horizontalsystems.ethereumkit.network.ConnectionManager
import io.reactivex.Single
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.SerialDisposable
import io.reactivex.schedulers.Schedulers
import java.util.*
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.schedule

class ApiRpcSyncer(
    private val rpcApiProvider: IRpcApiProvider,
    private val connectionManager: ConnectionManager,
    private val syncInterval: Long,
) : IRpcSyncer, ConnectionManager.Listener {
    // Authority to poll for the current run: stop() disposes it, so a timer task that fired across
    // the stop boundary registers into a disposed container instead of issuing its RPC.
    @Volatile
    private var disposables = CompositeDisposable()

    @Volatile
    private var isStarted = false

    // Atomic so two concurrent connection callbacks cannot leave an unreferenced timer polling.
    private val timer = AtomicReference<Timer?>(null)

    init {
        connectionManager.addListener(this)
    }

    //region IRpcSyncer
    override var listener: IRpcSyncerListener? = null
    override val source = "API ${rpcApiProvider.source}"
    override var state: SyncerState = SyncerState.NotReady(EthereumKit.SyncError.NotStarted())
        private set(value) {
            if (value != field) {
                field = value
                listener?.didUpdateSyncerState(value)
            }
        }

    override fun start() {
        isStarted = true
        if (disposables.isDisposed) {
            disposables = CompositeDisposable()
        }

        // stop() unregisters the listener, so a restarted syncer has to register again.
        connectionManager.addListener(this)

        handleConnectionChange()
    }

    override fun stop() {
        isStarted = false

        connectionManager.removeListener(this)
        state = SyncerState.NotReady(EthereumKit.SyncError.NotStarted())
        disposables.dispose()
        stopTimer()
    }

    override fun <T: Any> single(rpc: JsonRpc<T>): Single<T> =
        rpcApiProvider.single(rpc)
    //endregion

    private fun handleConnectionChange() {
        if (!isStarted) return

        if (connectionManager.isConnected) {
            state = SyncerState.Ready
            startTimer()
        } else {
            state = SyncerState.NotReady(EthereumKit.SyncError.NoNetworkConnection())
            stopTimer()
        }
    }

    private fun startTimer() {
        val newTimer = Timer().apply {
            schedule(0, syncInterval * 1000) {
                onFireTimer()
            }
        }
        timer.getAndSet(newTimer)?.cancel()
        // A stop() between scheduling and publishing would not have seen this timer.
        if (!isStarted) stopTimer()
    }

    private fun stopTimer() {
        timer.getAndSet(null)?.cancel()
    }

    private fun onFireTimer() {
        // Claim ownership before subscribing: add() fails on an already-disposed container, and a
        // stop() landing afterwards still reaches the request through this placeholder.
        val run = SerialDisposable()
        if (!disposables.add(run)) return

        rpcApiProvider.single(BlockNumberJsonRpc())
            .subscribeOn(Schedulers.io())
            .observeOn(Schedulers.io())
            // subscribe() schedules the request before it returns the disposable; onSubscribe hands
            // it over while the caller still owns the chain, so a stop() can never miss it.
            .doOnSubscribe { run.set(it) }
            .subscribe({ lastBlockNumber ->
                if (run.isDisposed) return@subscribe

                listener?.didUpdateLastBlockHeight(lastBlockNumber)
            }, {
                if (run.isDisposed) return@subscribe

                state = SyncerState.NotReady(it)
            })
    }

    override fun onConnectionChange() {
        handleConnectionChange()
    }
}
