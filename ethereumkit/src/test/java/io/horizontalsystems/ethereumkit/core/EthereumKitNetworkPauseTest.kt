package io.horizontalsystems.ethereumkit.core

import co.touchlab.kermit.Logger
import io.horizontalsystems.ethereumkit.api.models.AccountState
import io.horizontalsystems.ethereumkit.api.models.EthereumKitState
import io.horizontalsystems.ethereumkit.models.Address
import io.horizontalsystems.ethereumkit.models.Chain
import io.horizontalsystems.ethereumkit.models.FullTransaction
import io.horizontalsystems.ethereumkit.transactionsyncers.ExplorerSyncScheduler
import io.horizontalsystems.ethereumkit.transactionsyncers.MutableTestClock
import io.horizontalsystems.ethereumkit.transactionsyncers.TransactionSyncManager
import io.mockk.clearMocks
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
import io.horizontalsystems.ethereumkit.models.RpcSource
import java.net.URI
import java.time.Duration

private val OWN_CHAIN_RPC = RpcSource.Http(listOf(URI("https://rpc.example.org")), auth = null)

class EthereumKitNetworkPauseTest {

    private val storedBlockHeight = 1_000L
    private val storedAccountState = AccountState(BigInteger.TEN, 3L)

    private val blockchain = mockk<IBlockchain>(relaxed = true)
    private val transactionManager = mockk<TransactionManager>(relaxed = true)
    private val transactionSyncManager = mockk<TransactionSyncManager>(relaxed = true)
    private val rawTransactionBroadcaster = mockk<RawTransactionBroadcaster>()
    private val eip20Storage = mockk<IEip20Storage>(relaxed = true)
    private val fullTransactions = PublishProcessor.create<Pair<List<FullTransaction>, Boolean>>()
    private val syncStates = PublishProcessor.create<EthereumKit.SyncState>()
    private val clock = MutableTestClock()

    @Before
    fun setUp() {
        RxJavaPlugins.setIoSchedulerHandler { Schedulers.trampoline() }
        // Android's Log is not available in unit tests, and Kermit writes to it by default.
        Logger.setLogWriters(emptyList())
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

    // Offline mode pauses the kit but leaves it installed in the app, so the UI keeps calling
    // syncTransactions() when a screen opens. No explorer request may leave while paused.
    @Test
    fun syncTransactions_whilePaused_issuesNoExplorerRequest() {
        val kit = ethereumKit()
        kit.start()
        kit.pauseNetwork()
        clearMocks(transactionSyncManager, answers = false)

        kit.syncTransactions()
        kit.onTokenBalanceChanged()
        kit.refresh()

        verify(exactly = 0) { transactionSyncManager.sync() }
        verify(exactly = 0) { blockchain.refresh() }
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

    @Test
    fun onUpdateLastBlockHeight_syncsAccountStateOncePerSyncInterval() {
        val kit = startedKit()

        kit.onUpdateLastBlockHeight(storedBlockHeight + 1)
        kit.onUpdateLastBlockHeight(storedBlockHeight + 2)

        verify(exactly = 1) { blockchain.syncAccountState() }
        verify(exactly = 1) { transactionSyncManager.syncRpcOnly() }

        clock.advance(Duration.ofSeconds(Chain.Ethereum.syncInterval))
        kit.onUpdateLastBlockHeight(storedBlockHeight + 3)

        verify(exactly = 2) { blockchain.syncAccountState() }
    }

    @Test
    fun onUpdateLastBlockHeight_withinPeriod_doesNotRunExplorerSync() {
        val kit = startedKit()

        kit.onUpdateLastBlockHeight(storedBlockHeight + 1)

        verify(exactly = 1) { transactionSyncManager.sync() }
    }

    @Test
    fun onUpdateAccountState_balanceChanged_runsExplorerSync() {
        val kit = startedKit()
        clock.advance(Duration.ofSeconds(31))

        kit.onUpdateAccountState(AccountState(BigInteger.ONE, 9L))

        verify(exactly = 2) { transactionSyncManager.sync() }
    }

    @Test
    fun refresh_twiceWithin10s_runsExplorerSyncOnce() {
        val kit = startedKit()
        clock.advance(Duration.ofSeconds(11))

        kit.refresh()
        finishSync()
        kit.refresh()

        verify(exactly = 2) { transactionSyncManager.sync() }
    }

    @Test
    fun scanHistoricalEip20_withoutOwnChainRpcUris_isDisabled() {
        val kit = ethereumKit(ownChainRpcSource = null, scanHistoricalEip20Requested = true)

        assertFalse(
            "A WebSocket source has no HTTP endpoint to scan logs on",
            kit.scanHistoricalEip20
        )
    }

    @Test
    fun scanHistoricalEip20_withOwnChainRpcUris_staysEnabled() {
        val kit = ethereumKit(ownChainRpcSource = OWN_CHAIN_RPC, scanHistoricalEip20Requested = true)

        assertTrue(kit.scanHistoricalEip20)
    }

    @Test
    fun historicalGate_syncedWithoutCursor_defersDecision() {
        every { eip20Storage.getLastScannedBlock() } returns null
        val historicalSyncer = FakeHistoricalSyncer(isEnabled = false)
        historicalKit(historicalSyncer)

        finishSync()

        assertFalse("A swallowed explorer error also publishes Synced", historicalSyncer.isEnabled)
        assertEquals(0, historicalSyncer.startCount)

        every { eip20Storage.getLastScannedBlock() } returns storedBlockHeight
        every { eip20Storage.getHistoricalMinScannedBlock() } returns null
        every { eip20Storage.getLastEvent() } returns null
        finishSync()

        assertEquals(1, historicalSyncer.startCount)
    }

    @Test
    fun historicalGate_syncedWithCursorAndNoEvents_startsHistorical() {
        every { eip20Storage.getLastScannedBlock() } returns storedBlockHeight
        every { eip20Storage.getHistoricalMinScannedBlock() } returns null
        every { eip20Storage.getLastEvent() } returns null
        val historicalSyncer = FakeHistoricalSyncer(isEnabled = false)
        historicalKit(historicalSyncer)

        finishSync()
        finishSync()

        assertTrue(historicalSyncer.isEnabled)
        assertEquals("The gate closes after it decided once", 1, historicalSyncer.startCount)
    }

    private fun startedKit(): EthereumKit {
        val kit = ethereumKit()
        kit.start()
        finishSync()
        return kit
    }

    private fun finishSync() {
        syncStates.onNext(EthereumKit.SyncState.Synced())
    }

    private fun ethereumKit(
        ownChainRpcSource: RpcSource.Http? = null,
        scanHistoricalEip20Requested: Boolean = false
    ): EthereumKit {
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
            scheduler = ExplorerSyncScheduler(Chain.Ethereum, clock),
            connectionManager = mockk(relaxed = true),
            address = Address("0x3535353535353535353535353535353535353535"),
            chain = Chain.Ethereum,
            walletId = "wallet",
            transactionProvider = mockk(relaxed = true),
            ownChainRpcSource = ownChainRpcSource,
            fallbackHistoryBlockWindow = 0L,
            eip20Storage = eip20Storage,
            decorationManager = mockk(relaxed = true),
            scanHistoricalEip20Requested = scanHistoricalEip20Requested,
            transactionSyncSourceStorage = mockk(relaxed = true),
            rawTransactionBroadcaster = rawTransactionBroadcaster,
            state = EthereumKitState(),
        )
    }

    private fun historicalKit(historicalSyncer: FakeHistoricalSyncer): EthereumKit {
        val kit = ethereumKit(ownChainRpcSource = OWN_CHAIN_RPC, scanHistoricalEip20Requested = true)
        kit.setHistoricalSyncer(historicalSyncer)
        kit.start()
        return kit
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
