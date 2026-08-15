package io.horizontalsystems.erc20kit.core

import io.horizontalsystems.ethereumkit.core.EthereumKit
import io.horizontalsystems.ethereumkit.models.FullTransaction
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.reactivex.Flowable
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.processors.PublishProcessor
import io.reactivex.schedulers.Schedulers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.math.BigInteger

class Erc20KitNetworkPauseTest {

    private val transactions = PublishProcessor.create<List<FullTransaction>>()
    private val storedBalance = BigInteger.valueOf(42)

    private val ethereumKit = mockk<EthereumKit>()
    private val transactionManager = mockk<TransactionManager>()
    private val balanceManager = mockk<IBalanceManager>(relaxed = true)

    @Before
    fun setUp() {
        RxJavaPlugins.setIoSchedulerHandler { Schedulers.trampoline() }
    }

    @After
    fun tearDown() {
        RxJavaPlugins.reset()
    }

    @Test
    fun transactions_emittedWhileEthereumKitPaused_keepsBalanceReadableWithoutSync() {
        val kit = erc20Kit(ethereumKitStarted = false)

        transactions.onNext(emptyList())

        assertEquals(storedBalance, kit.balance)
        verify(exactly = 0) { balanceManager.sync() }
    }

    @Test
    fun transactions_emittedWhileEthereumKitStarted_syncsBalance() {
        erc20Kit(ethereumKitStarted = true)

        transactions.onNext(emptyList())

        verify(exactly = 1) { balanceManager.sync() }
    }

    private fun erc20Kit(ethereumKitStarted: Boolean): Erc20Kit {
        every { ethereumKit.isStarted } returns ethereumKitStarted
        // Synced here would make the constructor itself call balanceManager.sync().
        every { ethereumKit.syncState } returns EthereumKit.SyncState.NotSynced(Throwable())
        every { ethereumKit.syncStateFlowable } returns Flowable.empty()
        every { transactionManager.transactionsAsync } returns transactions
        every { balanceManager.balance } returns storedBalance

        return Erc20Kit(
            ethereumKit = ethereumKit,
            transactionManager = transactionManager,
            balanceManager = balanceManager,
            allowanceManager = mockk(relaxed = true)
        )
    }
}
