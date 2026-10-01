package io.horizontalsystems.ethereumkit.api.core

import io.horizontalsystems.ethereumkit.api.models.AccountState
import io.horizontalsystems.ethereumkit.core.IApiStorage
import io.horizontalsystems.ethereumkit.core.IBlockchainListener
import io.horizontalsystems.ethereumkit.models.Address
import io.mockk.mockk
import io.mockk.verify
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Before
import org.junit.Test

class RpcBlockchainTest {
    private val storage = GatedApiStorage()
    private val listener = mockk<IBlockchainListener>(relaxed = true)
    private val blockchain = RpcBlockchain(
        Address("0x0000000000000000000000000000000000000001"), storage, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true)
    ).also { it.listener = listener }

    @Before
    fun setUp() {
        // The save resumes on the thread that opens the gate, so the test controls when it completes.
        RxJavaPlugins.setIoSchedulerHandler { Schedulers.trampoline() }
    }

    @After
    fun tearDown() {
        RxJavaPlugins.reset()
    }

    @Test
    fun didUpdateLastBlockHeight_saveCompletesAfterStop_doesNotPublish() {
        blockchain.start()
        blockchain.didUpdateLastBlockHeight(HEIGHT)

        blockchain.stop()
        storage.saveGate.complete(Unit)

        verify(exactly = 0) { listener.onUpdateLastBlockHeight(any()) }
    }

    @Test
    fun didUpdateLastBlockHeight_afterRestart_publishesOnceSaved() {
        blockchain.start()
        blockchain.stop()
        blockchain.start()
        blockchain.didUpdateLastBlockHeight(HEIGHT)

        storage.saveGate.complete(Unit)

        verify(exactly = 1) { listener.onUpdateLastBlockHeight(HEIGHT) }
    }

    private class GatedApiStorage : IApiStorage {
        val saveGate = CompletableDeferred<Unit>()

        override suspend fun getLastBlockHeight(): Long? = null
        override suspend fun saveLastBlockHeight(lastBlockHeight: Long) = saveGate.await()
        override suspend fun getAccountState(): AccountState? = null
        override suspend fun saveAccountState(state: AccountState) = Unit
    }

    companion object {
        private const val HEIGHT = 1_000L
    }
}
