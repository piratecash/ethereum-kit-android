package io.horizontalsystems.erc20kit.core

import io.horizontalsystems.ethereumkit.core.IEip20Storage
import io.horizontalsystems.ethereumkit.core.ITransactionProvider
import io.horizontalsystems.ethereumkit.core.TokenTransactionProvider
import io.horizontalsystems.ethereumkit.core.storage.TransactionSyncSourceStorage
import io.horizontalsystems.ethereumkit.models.Address
import io.horizontalsystems.ethereumkit.models.Eip20Event
import io.horizontalsystems.ethereumkit.models.ProviderEip1155Transaction
import io.horizontalsystems.ethereumkit.models.ProviderEip721Transaction
import io.horizontalsystems.ethereumkit.models.ProviderInternalTransaction
import io.horizontalsystems.ethereumkit.models.ProviderTokenTransaction
import io.horizontalsystems.ethereumkit.models.ProviderTransaction
import io.mockk.coEvery
import io.mockk.mockk
import io.reactivex.Single
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigInteger

// Four distinct addresses: reusing one would hide any mix-up between these roles.
internal val transferSenderAddress = Address("0x0000000000000000000000000000000000000001")
internal val transferRecipientAddress = Address("0x0000000000000000000000000000000000000002")
internal val tokenContractAddress = Address("0x0000000000000000000000000000000000000003")
internal val transactionSenderAddress = Address("0x0000000000000000000000000000000000000004")
internal val testHash = ByteArray(32) { it.toByte() }

internal fun createTokenTransaction(
    blockNumber: Long,
    transactionSender: Address? = null
) = ProviderTokenTransaction(
    blockNumber = blockNumber,
    timestamp = 1000L,
    hash = testHash,
    nonce = 1L,
    blockHash = ByteArray(32),
    from = transferSenderAddress,
    contractAddress = tokenContractAddress,
    to = transferRecipientAddress,
    value = BigInteger.ONE,
    tokenName = "Test",
    tokenSymbol = "TST",
    tokenDecimal = 18,
    transactionIndex = 0,
    gasLimit = 21000L,
    gasPrice = 1000L,
    gasUsed = 21000L,
    cumulativeGasUsed = 21000L,
    input = null,
    transactionSender = transactionSender
)

class Erc20TransactionSyncerTest {
    private fun createSyncer(
        storage: IEip20Storage,
        etherscanResult: Single<List<ProviderTokenTransaction>> =
            Single.just(listOf(createTokenTransaction(2000L))),
        rpcResult: TokenTransactionProvider.TokenTransactionsResult? = null
    ): Erc20TransactionSyncer {
        val transactionProvider = object : ITransactionProvider {
            override fun getTransactions(startBlock: Long) =
                Single.just(emptyList<ProviderTransaction>())
            override fun getInternalTransactions(startBlock: Long) =
                Single.just(emptyList<ProviderInternalTransaction>())
            override fun getInternalTransactionsAsync(hash: ByteArray) =
                Single.just(emptyList<ProviderInternalTransaction>())
            override fun getTokenTransactions(startBlock: Long): Single<List<ProviderTokenTransaction>> =
                etherscanResult
            override fun getEip721Transactions(startBlock: Long) =
                Single.just(emptyList<ProviderEip721Transaction>())
            override fun getEip1155Transactions(startBlock: Long) =
                Single.just(emptyList<ProviderEip1155Transaction>())
        }

        val tokenTransactionProvider = mockk<TokenTransactionProvider>()
        if (rpcResult != null) {
            coEvery { tokenTransactionProvider.getTokenTransactions(any<Long>()) } returns rpcResult
        }
        val transactionSaver = TransactionSaver(storage)
        val syncSourceStorage = mockk<TransactionSyncSourceStorage>(relaxed = true)

        return Erc20TransactionSyncer(
            transactionProvider = transactionProvider,
            tokenTransactionProvider = tokenTransactionProvider,
            fallbackHistoryBlockWindow = 1000L,
            storage = storage,
            transactionSaver = transactionSaver,
            syncSourceStorage = syncSourceStorage
        )
    }

    @Test
    fun getTransactionsSingle_successfulSync_savesSyncBlockInfo() = runTest {
        val storage = FakeEip20Storage(lastScannedBlock = 1000L)
        val syncer = createSyncer(storage)

        syncer.getTransactionsSingle().blockingGet()

        assertEquals(
            "saveSyncBlockInfo must be called with the max block from synced transactions",
            2000L,
            storage.savedLastScannedBlock
        )
    }

    @Test
    fun getTransactionsSingle_subsequentSync_usesUpdatedLastScannedBlock() = runTest {
        val storage = FakeEip20Storage(lastScannedBlock = 1000L)
        val syncer = createSyncer(storage)

        syncer.getTransactionsSingle().blockingGet()

        assertEquals(
            "After sync, getLastScannedBlock should return the updated value",
            2000L,
            storage.getLastScannedBlock()
        )
    }

    @Test
    fun getTransactionsSingle_tokenTransaction_omitsTokenFieldsFromNativeTransaction() = runTest {
        val storage = FakeEip20Storage(lastScannedBlock = 1000L)
        val syncer = createSyncer(storage)

        val transaction = syncer.getTransactionsSingle().blockingGet().first.single()

        assertNull(transaction.to)
        assertNull(transaction.value)
        assertEquals(BigInteger.ONE, storage.savedEvents.single().value)
    }

    @Test
    fun getTransactionsSingle_noTransactionSender_claimsNoSender() = runTest {
        val storage = FakeEip20Storage(lastScannedBlock = 1000L)
        val syncer = createSyncer(storage)

        val transaction = syncer.getTransactionsSingle().blockingGet().first.single()

        assertNull("Transfer sender must never be passed off as the transaction sender", transaction.from)
        assertEquals(transferSenderAddress, storage.savedEvents.single().from)
    }

    @Test
    fun getTransactionsSingle_withTransactionSender_usesItInsteadOfTransferSender() = runTest {
        val storage = FakeEip20Storage(lastScannedBlock = 1000L)
        val syncer = createSyncer(
            storage = storage,
            etherscanResult = Single.just(
                listOf(createTokenTransaction(2000L, transactionSender = transactionSenderAddress))
            )
        )

        val transaction = syncer.getTransactionsSingle().blockingGet().first.single()

        assertEquals(transactionSenderAddress, transaction.from)
        assertEquals(transferSenderAddress, storage.savedEvents.single().from)
    }

    @Test
    fun getTransactionsSingle_etherscanFails_rpcFallback_savesSyncBlockInfo() = runTest {
        val storage = FakeEip20Storage(lastScannedBlock = 1000L)
        val rpcResult = TokenTransactionProvider.TokenTransactionsResult(
            transactions = listOf(createTokenTransaction(3000L)),
            lastScannedBlock = 3000L
        )
        val syncer = createSyncer(
            storage = storage,
            etherscanResult = Single.error(RuntimeException("Etherscan unavailable")),
            rpcResult = rpcResult
        )

        syncer.getTransactionsSingle().blockingGet()

        assertEquals(
            "saveSyncBlockInfo must be called even when falling back to RPC",
            3000L,
            storage.savedLastScannedBlock
        )
    }

    @Test
    fun getTransactionsSingle_bothFail_doesNotSaveSyncBlockInfo() = runTest {
        val storage = FakeEip20Storage(lastScannedBlock = 1000L)
        val syncer = createSyncer(
            storage = storage,
            etherscanResult = Single.error(RuntimeException("Etherscan unavailable")),
            rpcResult = null
        )

        // onErrorReturnItem catches this, so blockingGet succeeds with empty list
        val result = syncer.getTransactionsSingle().blockingGet()

        assertEquals(emptyList<Any>(), result.first)
        assertNull(
            "saveSyncBlockInfo must NOT be called when both providers fail",
            storage.savedLastScannedBlock
        )
    }

}

internal class FakeEip20Storage(
    private var lastScannedBlock: Long? = null,
    private var historicalMinScannedBlock: Long? = null
) : IEip20Storage {
    var savedEvents: List<Eip20Event> = emptyList()
        private set
    var savedLastScannedBlock: Long? = null
        private set
    var saveSyncBlockInfoCallCount = 0
        private set

    override fun getLastEvent(): Eip20Event? = null
    override fun getEarliestEip20Event(): Eip20Event? = null
    override fun save(events: List<Eip20Event>) {
        savedEvents = events
    }
    override fun getEvents(): List<Eip20Event> = savedEvents
    override fun getEventsByHashes(hashes: List<ByteArray>): List<Eip20Event> = emptyList()
    override fun deleteZeroValueDuplicate(
        hash: ByteArray, contractAddress: Address, from: Address, to: Address
    ) {}

    override fun getLastScannedBlock(): Long? = lastScannedBlock
    override fun getHistoricalMinScannedBlock(): Long? = historicalMinScannedBlock

    override suspend fun saveSyncBlockInfo(
        lastScannedBlock: Long?,
        historicalMinScannedBlock: Long?
    ) {
        saveSyncBlockInfoCallCount++
        savedLastScannedBlock = lastScannedBlock
        lastScannedBlock?.let {
            this.lastScannedBlock = it
        }
        historicalMinScannedBlock?.let {
            this.historicalMinScannedBlock = it
        }
    }
}
