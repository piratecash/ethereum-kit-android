package io.horizontalsystems.erc20kit.core

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import co.touchlab.kermit.Logger
import io.horizontalsystems.erc20kit.contract.Eip20ContractMethodFactories
import io.horizontalsystems.ethereumkit.core.ChainHeadProvider
import io.horizontalsystems.ethereumkit.core.EthereumKit
import io.horizontalsystems.ethereumkit.core.ITransactionProvider
import io.horizontalsystems.ethereumkit.core.TokenTransactionProvider
import io.horizontalsystems.ethereumkit.core.TransactionManager
import io.horizontalsystems.ethereumkit.core.storage.Eip20Database
import io.horizontalsystems.ethereumkit.core.storage.Eip20Storage
import io.horizontalsystems.ethereumkit.core.storage.TransactionDatabase
import io.horizontalsystems.ethereumkit.core.storage.TransactionStorage
import io.horizontalsystems.ethereumkit.core.storage.TransactionSyncSourceStorage
import io.horizontalsystems.ethereumkit.decorations.DecorationManager
import io.horizontalsystems.ethereumkit.models.Address
import io.horizontalsystems.ethereumkit.models.Eip20Event
import io.horizontalsystems.ethereumkit.models.ProviderTokenTransaction
import io.horizontalsystems.ethereumkit.models.SyncSource
import io.horizontalsystems.ethereumkit.models.Transaction
import io.horizontalsystems.ethereumkit.models.TransactionTag
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.reactivex.Single
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.math.BigInteger

@RunWith(RobolectricTestRunner::class)
class RecentErc20TransfersIntegrationTest {

    private val userAddress = transferRecipientAddress
    private val routerAddress = Address("0x0000000000000000000000000000000000000005")

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val transactionDatabase = Room.inMemoryDatabaseBuilder(context, TransactionDatabase::class.java).build()
    private val eip20Database = Room.inMemoryDatabaseBuilder(context, Eip20Database::class.java).build()
    private val transactionStorage = TransactionStorage(transactionDatabase)
    private val eip20Storage = Eip20Storage(eip20Database)
    private val syncSourceStorage = TransactionSyncSourceStorage(transactionDatabase.transactionSyncSourceDao())
    private val transactionSaver = TransactionSaver(eip20Storage)
    private val transactionManager = TransactionManager(
        userAddress,
        transactionStorage,
        DecorationManager(userAddress, transactionStorage).apply {
            addMethodDecorator(Eip20MethodDecorator(Eip20ContractMethodFactories))
            addEventDecorator(Eip20EventDecorator(userAddress, eip20Storage, Logger))
            addTransactionDecorator(Eip20TransactionDecorator(userAddress))
        },
        mockk(),
        mockk()
    )
    private val tokenTransactionProvider = mockk<TokenTransactionProvider>()

    @Before
    fun setUp() {
        Logger.setLogWriters(emptyList())
    }

    @After
    fun tearDown() {
        transactionDatabase.close()
        eip20Database.close()
    }

    @Test
    fun recentTransfer_existingExplorerEvent_keepsExplorerMetadata() = runTest {
        eip20Storage.save(listOf(explorerEvent()))

        scanRecent(rpcTransfer())

        val event = eip20Storage.getEvents().single()
        assertEquals(6, event.tokenDecimal)
        assertEquals("Explorer", event.tokenName)
        assertTaggedAsIncomingToken()
    }

    @Test
    fun recentTransfer_knownTransactionWithFee_keepsStoredFee() = runTest {
        transactionManager.handle(listOf(txlistRow()))

        scanRecent(rpcTransfer())

        val stored = requireNotNull(transactionStorage.getTransaction(testHash))
        assertEquals(7L, stored.nonce)
        assertEquals(5_000_000_000L, stored.gasPrice)
        assertEquals(200_000L, stored.gasLimit)
        assertEquals(150_000L, stored.gasUsed)
        assertEquals(routerAddress, stored.to)
        assertEquals(BigInteger.ZERO, stored.value)
        assertTaggedAsIncomingToken()
    }

    @Test
    fun recentTransfer_pendingAppTransfer_getsBlockAndRealFee() = runTest {
        transactionManager.handle(listOf(txlistRow().copy(blockNumber = null, transactionIndex = null, gasUsed = null)))

        scanRecent(rpcTransfer().copy(nonce = 0L, gasPrice = 5_000_000_000L, gasLimit = 200_000L, gasUsed = 50_000L))

        val stored = requireNotNull(transactionStorage.getTransaction(testHash))
        assertEquals(100L, stored.blockNumber)
        assertEquals(50_000L, stored.gasUsed)
        assertEquals(7L, stored.nonce)
    }

    @Test
    fun recentTransfer_txlistRowWithoutEvent_addsEventAndTokenTags() = runTest {
        transactionManager.handle(listOf(txlistRow()))

        scanRecent(rpcTransfer())

        assertEquals(BigInteger.ONE, eip20Storage.getEvents().single().value)
        assertTaggedAsIncomingToken()
    }

    @Test
    fun recentTransfer_thenExplorerRedelivers_keepsOneEventAndTokenTags() = runTest {
        scanRecent(rpcTransfer())

        val (explorerTransactions, _) = explorerSyncer(explorerTransfer()).getTransactionsSingle().blockingGet()
        transactionManager.handle(explorerTransactions)

        assertEquals(6, eip20Storage.getEvents().single().tokenDecimal)
        assertTaggedAsIncomingToken()
        assertEquals(SyncSource.RPC, syncSourceStorage.getSource(testHash))
    }

    private fun scanRecent(vararg transfers: ProviderTokenTransaction) {
        coEvery { tokenTransactionProvider.getTokenTransactions(any<Long>()) } returns
            TokenTransactionProvider.TokenTransactionsResult(transfers.toList(), lastScannedBlock = 100L)
        val syncer = RecentErc20TransfersSyncer(
            tokenTransactionProvider = tokenTransactionProvider,
            transactionManager = transactionManager,
            transactionSaver = transactionSaver,
            syncSourceStorage = syncSourceStorage,
            storage = eip20Storage,
            awaitExplorerHistory = false,
            log = Logger
        )
        syncer.onTokenBalanceChanged()
        syncer.getTransactionsSingle().blockingGet()
    }

    private fun explorerSyncer(vararg transfers: ProviderTokenTransaction): Erc20TransactionSyncer {
        val transactionProvider = mockk<ITransactionProvider> {
            every { getTokenTransactions(any()) } returns Single.just(transfers.toList())
        }
        return Erc20TransactionSyncer(
            transactionProvider = transactionProvider,
            tokenTransactionProvider = null,
            fallbackHistoryBlockWindow = 1000L,
            storage = eip20Storage,
            transactionSaver = transactionSaver,
            syncSourceStorage = syncSourceStorage,
            chainHeadProvider = mockk<ChainHeadProvider> { every { liveBlockHeight } returns null },
            log = Logger
        )
    }

    private suspend fun assertTaggedAsIncomingToken() {
        val tags = listOf(listOf(TransactionTag.tokenIncoming(tokenContractAddress.hex)))
        assertArrayEquals(testHash, transactionStorage.getTransactionsBefore(tags, null, null).single().hash)
    }

    /** The user's own swap as the explorer's txlist delivers it, before its token transfer is indexed. */
    private fun txlistRow() = Transaction(
        hash = testHash, timestamp = 1000L, isFailed = false, blockNumber = 100L, transactionIndex = 0,
        from = userAddress, to = routerAddress, value = BigInteger.ZERO, input = byteArrayOf(1, 2, 3, 4),
        nonce = 7L, gasPrice = 5_000_000_000L, gasLimit = 200_000L, gasUsed = 150_000L
    )

    /** Fee fields are the 0 placeholders the RPC provider writes when its lookups fail. */
    private fun rpcTransfer() = createTokenTransaction(100L, transactionSender = userAddress).copy(
        nonce = 0L, gasPrice = 0L, gasLimit = 0L, gasUsed = 0L, tokenName = "Rpc", tokenDecimal = 18
    )

    private fun explorerTransfer() =
        createTokenTransaction(100L, transactionSender = userAddress).copy(tokenName = "Explorer", tokenDecimal = 6)

    private fun explorerEvent() = Eip20Event(
        hash = testHash, blockNumber = 100L, contractAddress = tokenContractAddress,
        from = transferSenderAddress, to = userAddress, value = BigInteger.ONE,
        tokenName = "Explorer", tokenSymbol = "TST", tokenDecimal = 6
    )

    companion object {
        init {
            // Registers the Keccak digest that event signatures and method ids need, as the app does.
            EthereumKit.init()
        }
    }
}
