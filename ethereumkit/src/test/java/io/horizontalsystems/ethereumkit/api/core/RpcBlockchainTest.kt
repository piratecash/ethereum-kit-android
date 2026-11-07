package io.horizontalsystems.ethereumkit.api.core

import io.horizontalsystems.ethereumkit.api.jsonrpc.GetTransactionByHashJsonRpc
import io.horizontalsystems.ethereumkit.api.jsonrpc.GetTransactionReceiptJsonRpc
import io.horizontalsystems.ethereumkit.api.jsonrpc.JsonRpc
import io.horizontalsystems.ethereumkit.api.jsonrpc.SendRawTransactionJsonRpc
import io.horizontalsystems.ethereumkit.api.models.AccountState
import io.horizontalsystems.ethereumkit.core.IApiStorage
import io.horizontalsystems.ethereumkit.core.IBlockchainListener
import io.horizontalsystems.ethereumkit.core.TransactionBuilder
import io.horizontalsystems.ethereumkit.models.Address
import io.horizontalsystems.ethereumkit.models.Transaction
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.reactivex.Single
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Before
import org.junit.Test

class RpcBlockchainTest {
    private val storage = GatedApiStorage()
    private val listener = mockk<IBlockchainListener>(relaxed = true)
    private val syncer = mockk<IRpcSyncer>(relaxed = true)
    private val transaction = Transaction(hash = byteArrayOf(1, 2, 3), timestamp = 0, isFailed = false)
    private val transactionBuilder = mockk<TransactionBuilder> {
        every { transaction(any(), any()) } returns this@RpcBlockchainTest.transaction
        every { encode(any(), any()) } returns byteArrayOf(9)
    }
    private val blockchain = RpcBlockchain(
        Address("0x0000000000000000000000000000000000000001"), storage, syncer, transactionBuilder, mockk(relaxed = true)
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

    @Test
    fun send_alreadyKnown_returnsTransaction() {
        broadcastFails("already known")

        send().assertValue(transaction)
    }

    @Test
    fun send_nonceTooLowAndTxExists_returnsTransaction() {
        broadcastFails("nonce too low")
        every { syncer.single(ofType<GetTransactionByHashJsonRpc>()) } returns Single.just(mockk())

        send().assertValue(transaction)
    }

    @Test
    fun send_nonceTooLowAndTxMissing_fails() {
        val error = broadcastFails("nonce too low")
        transactionMissing()

        send().assertError(error)
    }

    @Test
    fun send_otherRpcError_fails() {
        val error = broadcastFails("internal error")
        every { syncer.single(ofType<GetTransactionByHashJsonRpc>()) } returns Single.just(mockk())

        send().assertError(error)
    }

    private fun send() = blockchain.send(mockk(), mockk()).test().await()

    private fun broadcastFails(message: String): Throwable {
        val error = JsonRpc.ResponseError.RpcError(RpcResponse.Error(-32000, message))
        every { syncer.single(ofType<SendRawTransactionJsonRpc>()) } returns Single.error(error)
        return error
    }

    private fun transactionMissing() {
        every { syncer.single(ofType<GetTransactionByHashJsonRpc>()) } returns Single.error(JsonRpc.ResponseError.InvalidResult(null))
        every { syncer.single(ofType<GetTransactionReceiptJsonRpc>()) } returns Single.error(JsonRpc.ResponseError.InvalidResult(null))
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
