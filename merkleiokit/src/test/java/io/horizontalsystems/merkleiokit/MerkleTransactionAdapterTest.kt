package io.horizontalsystems.merkleiokit

import io.horizontalsystems.ethereumkit.api.core.IRpcSyncer
import io.horizontalsystems.ethereumkit.core.EthereumKit
import io.horizontalsystems.ethereumkit.core.TransactionBuilder
import io.horizontalsystems.ethereumkit.core.TransactionManager
import io.horizontalsystems.ethereumkit.models.Address
import io.horizontalsystems.ethereumkit.models.GasPrice
import io.horizontalsystems.ethereumkit.models.RawTransaction
import io.horizontalsystems.ethereumkit.models.Signature
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.schedulers.TestScheduler
import io.reactivex.subjects.SingleSubject
import org.junit.After
import org.junit.BeforeClass
import org.junit.Test
import java.math.BigInteger

class MerkleTransactionAdapterTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun beforeClass() {
            EthereumKit.init()
        }
    }

    private val address = Address("0x3535353535353535353535353535353535353535")
    private val hashManager = mockk<MerkleTransactionHashManager>(relaxed = true)
    private val transactionManager = mockk<TransactionManager>()
    private val syncer = mockk<IRpcSyncer>()

    @After
    fun tearDown() {
        RxJavaPlugins.reset()
    }

    @Test
    fun send_disposedRightAfterBroadcastAccepted_stillSavesHashAndHandlesTransaction() {
        val io = TestScheduler()
        RxJavaPlugins.setIoSchedulerHandler { io }
        val accepted = SingleSubject.create<ByteArray>()
        every { syncer.single<ByteArray>(any()) } returns accepted
        coEvery { transactionManager.handle(any(), any()) } returns listOf(mockk())
        val blockchain = MerkleRpcBlockchain(address, hashManager, syncer, TransactionBuilder(address, 1))
        val adapter = MerkleTransactionAdapter(blockchain, mockk(relaxed = true), transactionManager, "test")

        val observer = adapter.send(rawTransaction(), signature()).test()
        accepted.onSuccess(byteArrayOf(1))
        observer.dispose()
        io.triggerActions()

        coVerify(exactly = 1) { hashManager.save(any()) }
        coVerify(exactly = 1) { transactionManager.handle(any(), any()) }
    }

    private fun rawTransaction() = RawTransaction(
        gasPrice = GasPrice.Legacy(1),
        gasLimit = 21_000,
        to = address,
        value = BigInteger.ONE,
        nonce = 0,
    )

    private fun signature() = Signature(v = 27, r = ByteArray(32) { 1 }, s = ByteArray(32) { 2 })
}
