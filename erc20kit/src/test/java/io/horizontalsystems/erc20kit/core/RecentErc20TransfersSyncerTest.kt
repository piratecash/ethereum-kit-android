package io.horizontalsystems.erc20kit.core

import co.touchlab.kermit.Logger
import io.horizontalsystems.ethereumkit.core.IEip20Storage
import io.horizontalsystems.ethereumkit.core.TokenTransactionProvider
import io.horizontalsystems.ethereumkit.core.TransactionManager
import io.horizontalsystems.ethereumkit.core.storage.TransactionSyncSourceStorage
import io.horizontalsystems.ethereumkit.models.ProviderTokenTransaction
import io.horizontalsystems.ethereumkit.models.SyncSource
import io.horizontalsystems.ethereumkit.models.Transaction
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class RecentErc20TransfersSyncerTest {

    private val provider = mockk<TokenTransactionProvider>()
    private val transactionManager = mockk<TransactionManager>(relaxed = true)
    private val syncSourceStorage = mockk<TransactionSyncSourceStorage>(relaxed = true)
    private val storage = FakeEip20Storage()
    private val clock = MutableClock()

    @Before
    fun setUp() {
        // The module's unit tests have no Android stubs, so a real log writer would hit android.util.Log.
        Logger.setLogWriters(emptyList())
        coEvery { provider.getTokenTransactions(any<Long>()) } returns result(createTokenTransaction(100L))
    }

    @Test
    fun getTransactionsSingle_notRequested_doesNotCallProvider() {
        val syncer = syncer()

        val result = syncer.getTransactionsSingle().blockingGet()

        assertTrue(result.first.isEmpty())
        coVerify(exactly = 0) { provider.getTokenTransactions(any<Long>()) }
    }

    @Test
    fun getTransactionsSingle_requested_scansRecentWindowSavesAndHandles() {
        val syncer = syncer()
        val handled = slot<List<Transaction>>()
        syncer.onTokenBalanceChanged()

        val result = syncer.getTransactionsSingle().blockingGet()

        assertTrue(result.first.isEmpty())
        assertFalse(result.second)
        coVerify(exactly = 1) { provider.getTokenTransactions(-4000L) }
        assertEquals(1, storage.savedIfAbsentEvents.size)
        assertTrue(storage.savedEvents.isEmpty())
        coVerify(exactly = 1) {
            syncSourceStorage.saveAll(match { it.single().contentEquals(testHash) }, SyncSource.RPC)
        }
        coVerify(exactly = 1) { transactionManager.handle(capture(handled), any()) }
        val transaction = handled.captured.single()
        assertArrayEquals(testHash, transaction.hash)
        assertNull(transaction.nonce)
    }

    @Test
    fun getTransactionsSingle_realGasValues_keepsThemAndNullsPlaceholders() {
        val placeholderHash = ByteArray(32) { 9 }
        coEvery { provider.getTokenTransactions(any<Long>()) } returns result(
            createTokenTransaction(100L).copy(gasUsed = 50_000L, gasPrice = 1_000_000_000L, gasLimit = 60_000L),
            createTokenTransaction(100L).copy(hash = placeholderHash, gasUsed = 0L, gasPrice = 0L, gasLimit = 0L)
        )
        val syncer = syncer()
        val handled = slot<List<Transaction>>()
        syncer.onTokenBalanceChanged()

        syncer.getTransactionsSingle().blockingGet()

        coVerify { transactionManager.handle(capture(handled), any()) }
        val real = handled.captured.single { it.hash.contentEquals(testHash) }
        assertEquals(50_000L, real.gasUsed)
        assertEquals(1_000_000_000L, real.gasPrice)
        assertEquals(60_000L, real.gasLimit)
        val placeholder = handled.captured.single { it.hash.contentEquals(placeholderHash) }
        assertNull(placeholder.gasUsed)
        assertNull(placeholder.gasPrice)
        assertNull(placeholder.gasLimit)
    }

    @Test
    fun getTransactionsSingle_requested_doesNotTouchExplorerCursor() {
        val cursorStorage = mockk<IEip20Storage>(relaxed = true)
        val syncer = syncer(TransactionSaver(cursorStorage))
        syncer.onTokenBalanceChanged()

        syncer.getTransactionsSingle().blockingGet()

        coVerify(exactly = 1) { cursorStorage.saveIfAbsent(any()) }
        coVerify(exactly = 0) {
            cursorStorage.saveSyncBlockInfo(any(), any())
            cursorStorage.getLastScannedBlock()
            cursorStorage.getHistoricalMinScannedBlock()
        }
    }

    @Test
    fun getTransactionsSingle_requestedWithinFloor_skipsUntilFloorElapses() {
        val syncer = syncer()
        syncer.onTokenBalanceChanged()
        syncer.getTransactionsSingle().blockingGet()

        syncer.onTokenBalanceChanged()
        clock.advance(Duration.ofSeconds(29))
        syncer.getTransactionsSingle().blockingGet()
        coVerify(exactly = 1) { provider.getTokenTransactions(any<Long>()) }

        clock.advance(Duration.ofSeconds(1))
        syncer.getTransactionsSingle().blockingGet()
        coVerify(exactly = 2) { provider.getTokenTransactions(any<Long>()) }
    }

    @Test
    fun getTransactionsSingle_requestedWhileScanInFlight_skipsAndKeepsRequest() {
        val scanStarted = CountDownLatch(1)
        val releaseScan = CountDownLatch(1)
        coEvery { provider.getTokenTransactions(any<Long>()) } answers {
            scanStarted.countDown()
            releaseScan.await(5, TimeUnit.SECONDS)
            result(createTokenTransaction(100L))
        } andThen result(createTokenTransaction(100L))
        val syncer = syncer()
        syncer.onTokenBalanceChanged()
        val firstRun = syncer.getTransactionsSingle().test()
        assertTrue(scanStarted.await(5, TimeUnit.SECONDS))

        // Past the floor, so only the in-flight claim can hold the second scan back.
        clock.advance(Duration.ofSeconds(30))
        syncer.onTokenBalanceChanged()
        syncer.getTransactionsSingle().blockingGet()
        coVerify(exactly = 1) { provider.getTokenTransactions(any<Long>()) }

        releaseScan.countDown()
        firstRun.awaitTerminalEvent(5, TimeUnit.SECONDS)
        syncer.getTransactionsSingle().blockingGet()
        coVerify(exactly = 2) { provider.getTokenTransactions(any<Long>()) }
    }

    @Test
    fun getTransactionsSingle_clockMovedBackwards_scansAgain() {
        val syncer = syncer()
        syncer.onTokenBalanceChanged()
        syncer.getTransactionsSingle().blockingGet()

        syncer.onTokenBalanceChanged()
        clock.advance(Duration.ofMinutes(-1))
        syncer.getTransactionsSingle().blockingGet()

        coVerify(exactly = 2) { provider.getTokenTransactions(any<Long>()) }
    }

    @Test
    fun getTransactionsSingle_awaitingExplorerHistoryCursorZero_skipsAndKeepsRequest() = runTest {
        val syncer = syncer(awaitExplorerHistory = true)
        syncer.onTokenBalanceChanged()

        syncer.getTransactionsSingle().blockingGet()
        coVerify(exactly = 0) { provider.getTokenTransactions(any<Long>()) }

        storage.saveSyncBlockInfo(lastScannedBlock = 100L, historicalMinScannedBlock = null)
        syncer.getTransactionsSingle().blockingGet()
        coVerify(exactly = 1) { provider.getTokenTransactions(any<Long>()) }
    }

    @Test
    fun getTransactionsSingle_notAwaitingExplorerHistory_scansWithZeroCursor() {
        val syncer = syncer(awaitExplorerHistory = false)
        syncer.onTokenBalanceChanged()

        syncer.getTransactionsSingle().blockingGet()

        coVerify(exactly = 1) { provider.getTokenTransactions(any<Long>()) }
    }

    @Test
    fun getTransactionsSingle_providerFails_doesNotRearm() {
        coEvery { provider.getTokenTransactions(any<Long>()) } throws IOException("node down")
        val syncer = syncer()
        syncer.onTokenBalanceChanged()

        val result = syncer.getTransactionsSingle().blockingGet()
        clock.advance(Duration.ofSeconds(31))
        syncer.getTransactionsSingle().blockingGet()

        assertTrue(result.first.isEmpty())
        coVerify(exactly = 1) { provider.getTokenTransactions(any<Long>()) }
    }

    @Test
    fun getTransactionsSingle_built_doesNotClaimUntilSubscribed() {
        val syncer = syncer()
        syncer.onTokenBalanceChanged()

        syncer.getTransactionsSingle()
        syncer.getTransactionsSingle().blockingGet()

        coVerify(exactly = 1) { provider.getTokenTransactions(any<Long>()) }
    }

    @Test
    fun getTransactionsSingle_disposedMidScan_releasesClaimAndKeepsRequest() {
        val scanStarted = CountDownLatch(1)
        val calls = AtomicInteger()
        coEvery { provider.getTokenTransactions(any<Long>()) } coAnswers {
            if (calls.incrementAndGet() == 1) {
                scanStarted.countDown()
                awaitCancellation()
            }
            result(createTokenTransaction(100L))
        }
        val syncer = syncer()
        syncer.onTokenBalanceChanged()
        val run = syncer.getTransactionsSingle().subscribe({}, {})
        assertTrue(scanStarted.await(5, TimeUnit.SECONDS))

        run.dispose()
        clock.advance(Duration.ofSeconds(30))
        // The cancelled scan unwinds on its own thread; poll until it has released the claim.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (calls.get() < 2 && System.nanoTime() < deadline) {
            syncer.getTransactionsSingle().blockingGet()
            Thread.sleep(10)
        }

        assertEquals(2, calls.get())
        coVerify(exactly = 1) { transactionManager.handle(any(), any()) }
    }

    @Test
    fun getTransactionsSingle_concurrentCallers_scanOnceWithinFloor() {
        val syncer = syncer()
        syncer.onTokenBalanceChanged()
        val start = CountDownLatch(1)

        val callers = List(8) {
            thread {
                start.await()
                syncer.getTransactionsSingle().blockingGet()
            }
        }
        start.countDown()
        callers.forEach { it.join(5_000) }

        coVerify(exactly = 1) { provider.getTokenTransactions(any<Long>()) }
        coVerify(exactly = 1) { transactionManager.handle(any(), any()) }
    }

    @Test
    fun getTransactionsSingle_unresolvedTokenMeta_skipsTransfer() {
        coEvery { provider.getTokenTransactions(any<Long>()) } returns
            result(createTokenTransaction(100L).copy(tokenMetaResolved = false))
        val syncer = syncer()
        syncer.onTokenBalanceChanged()

        syncer.getTransactionsSingle().blockingGet()

        assertTrue(storage.savedIfAbsentEvents.isEmpty())
        coVerify(exactly = 0) { syncSourceStorage.saveAll(any(), any()) }
        coVerify(exactly = 0) { transactionManager.handle(any(), any()) }
    }

    private fun syncer(
        transactionSaver: TransactionSaver = TransactionSaver(storage),
        awaitExplorerHistory: Boolean = false
    ) = RecentErc20TransfersSyncer(
        tokenTransactionProvider = provider,
        transactionManager = transactionManager,
        transactionSaver = transactionSaver,
        syncSourceStorage = syncSourceStorage,
        storage = storage,
        awaitExplorerHistory = awaitExplorerHistory,
        log = Logger,
        clock = clock
    )

    private fun result(vararg transactions: ProviderTokenTransaction) =
        TokenTransactionProvider.TokenTransactionsResult(transactions.toList(), lastScannedBlock = 100L)

    private class MutableClock(@Volatile private var now: Instant = Instant.parse("2026-10-14T00:00:00Z")) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now

        fun advance(duration: Duration) {
            now = now.plus(duration)
        }
    }
}
