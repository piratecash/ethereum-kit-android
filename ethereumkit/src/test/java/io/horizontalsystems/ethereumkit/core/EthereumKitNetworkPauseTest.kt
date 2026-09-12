package io.horizontalsystems.ethereumkit.core

import io.horizontalsystems.ethereumkit.api.models.AccountState
import io.horizontalsystems.ethereumkit.api.models.EthereumKitState
import io.horizontalsystems.ethereumkit.models.Address
import io.horizontalsystems.ethereumkit.models.Chain
import io.horizontalsystems.ethereumkit.models.FullTransaction
import io.horizontalsystems.ethereumkit.transactionsyncers.TransactionSyncManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.reactivex.Single
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.processors.PublishProcessor
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigInteger

class EthereumKitNetworkPauseTest {

    private val storedBlockHeight = 1_000L
    private val storedAccountState = AccountState(BigInteger.TEN, 3L)

    private val blockchain = mockk<IBlockchain>(relaxed = true)
    private val transactionManager = mockk<TransactionManager>(relaxed = true)
    private val transactionSyncManager = mockk<TransactionSyncManager>(relaxed = true)
    private val rawTransactionBroadcaster = mockk<RawTransactionBroadcaster>()
    private val fullTransactions = PublishProcessor.create<Pair<List<FullTransaction>, Boolean>>()
    private val syncStates = PublishProcessor.create<EthereumKit.SyncState>()

    @Before
    fun setUp() {
        RxJavaPlugins.setIoSchedulerHandler { Schedulers.trampoline() }
    }

    @After
    fun tearDown() {
        RxJavaPlugins.reset()
    }

    @Test
    fun pauseNetwork_keepsCachedState_andStopsNetwork() {
        val kit = ethereumKit()
        kit.start()

        kit.pauseNetwork()

        assertFalse(kit.isStarted)
        assertEquals(storedBlockHeight, kit.lastBlockHeight)
        assertEquals(storedAccountState, kit.accountState)
        verify(exactly = 1) { blockchain.stop() }
        verify(exactly = 1) { transactionSyncManager.pause() }
    }

    @Test
    fun stop_clearsCachedState() {
        val kit = ethereumKit()
        kit.start()

        kit.stop()

        assertNull(kit.lastBlockHeight)
        assertNull(kit.accountState)
    }

    @Test
    fun attachLocalState_afterStop_restoresFromStorageWithoutNetworkCalls() {
        val kit = ethereumKit()
        kit.start()
        kit.stop()
        val emitted = kit.accountStateFlowable.test()

        kit.attachLocalState()

        assertEquals(storedBlockHeight, kit.lastBlockHeight)
        assertEquals(storedAccountState, kit.accountState)
        emitted.assertValue(storedAccountState)
        verify(exactly = 1) { blockchain.start() }
        verify(exactly = 0) { blockchain.refresh() }
        verify(exactly = 0) { blockchain.syncAccountState() }
    }

    @Test
    fun fullTransactions_emittedAfterPause_doesNotSyncAccountState() {
        val kit = ethereumKit()
        kit.start()
        kit.pauseNetwork()

        fullTransactions.onNext(Pair(emptyList(), false))

        verify(exactly = 0) { blockchain.syncAccountState() }
    }

    @Test
    fun start_afterPause_restartsTransactionSyncAndEnabledHistoricalSyncer() {
        val historicalSyncer = FakeHistoricalSyncer(isEnabled = true)
        val kit = ethereumKit()
        kit.setHistoricalSyncer(historicalSyncer)
        kit.start()
        kit.pauseNetwork()

        kit.start()

        assertTrue(kit.isStarted)
        assertEquals(2, historicalSyncer.startCount)
        verify(exactly = 2) { blockchain.start() }
        verify(exactly = 2) { transactionSyncManager.sync() }
    }

    private fun ethereumKit(): EthereumKit {
        every { blockchain.lastBlockHeight } returns storedBlockHeight
        every { blockchain.accountState } returns storedAccountState
        every { transactionManager.fullTransactionsAsync } returns fullTransactions
        every { transactionSyncManager.syncStateAsync } returns syncStates
        every { rawTransactionBroadcaster.retryQueued() } returns Single.just(Unit)

        return EthereumKit(
            blockchain = blockchain,
            nonceProvider = mockk(relaxed = true),
            transactionManager = transactionManager,
            transactionSyncManager = transactionSyncManager,
            connectionManager = mockk(relaxed = true),
            address = Address("0x3535353535353535353535353535353535353535"),
            chain = Chain.Ethereum,
            walletId = "wallet",
            transactionProvider = mockk(relaxed = true),
            tokenTransactionProvider = mockk(relaxed = true),
            fallbackHistoryBlockWindow = 0L,
            eip20Storage = mockk(relaxed = true),
            decorationManager = mockk(relaxed = true),
            scanHistoricalEip20 = false,
            transactionSyncSourceStorage = mockk(relaxed = true),
            rawTransactionBroadcaster = rawTransactionBroadcaster,
            state = EthereumKitState(),
        )
    }

    private class FakeHistoricalSyncer(
        override var isEnabled: Boolean
    ) : EthereumKit.HistoricalSyncer {
        var startCount = 0

        override val syncState = MutableStateFlow<EthereumKit.HistoricalSyncState>(
            EthereumKit.HistoricalSyncState.Idle
        )

        override fun start() {
            startCount++
        }

        override fun stop() = Unit
    }
}
