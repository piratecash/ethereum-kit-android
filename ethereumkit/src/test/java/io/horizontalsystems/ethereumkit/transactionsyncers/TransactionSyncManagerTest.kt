package io.horizontalsystems.ethereumkit.transactionsyncers

import io.horizontalsystems.ethereumkit.core.EthereumKit
import io.horizontalsystems.ethereumkit.core.IBlockchain
import io.horizontalsystems.ethereumkit.core.ITransactionProvider
import io.horizontalsystems.ethereumkit.core.ITransactionStorage
import io.horizontalsystems.ethereumkit.core.ITransactionSyncer
import io.horizontalsystems.ethereumkit.core.TransactionManager
import io.horizontalsystems.ethereumkit.decorations.DecorationManager
import io.horizontalsystems.ethereumkit.models.Address
import io.horizontalsystems.ethereumkit.models.InternalTransaction
import io.horizontalsystems.ethereumkit.models.Transaction
import io.horizontalsystems.ethereumkit.models.TransactionTag
import io.reactivex.Single
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.schedulers.Schedulers
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.math.BigInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TransactionSyncManagerTest {

    @Before
    fun setUp() {
        RxJavaPlugins.setIoSchedulerHandler { Schedulers.trampoline() }
    }

    @After
    fun tearDown() {
        RxJavaPlugins.reset()
    }

    @Test
    fun sync_nativeAndTokenTransactionsWithSameHash_preservesNativeValue() {
        val hash = ByteArray(32) { it.toByte() }
        val nativeValue = BigInteger("1759231567651249")
        val storage = FakeTransactionStorage()
        val syncManager = TransactionSyncManager(transactionManager(storage))
        syncManager.add(transactionSyncer(Transaction(hash, 1L, false, value = nativeValue)))
        syncManager.add(transactionSyncer(Transaction(hash, 1L, false, value = null)))
        val synced = CountDownLatch(1)
        val disposable = syncManager.syncStateAsync.subscribe {
            if (it is EthereumKit.SyncState.Synced) {
                synced.countDown()
            }
        }

        syncManager.sync()

        try {
            assertTrue(synced.await(3, TimeUnit.SECONDS))
            assertEquals(nativeValue, storage.getTransaction(hash)?.value)
        } finally {
            disposable.dispose()
        }
    }

    @Test
    fun sync_nativeAndTokenSyncersWithSameHash_keepsNativeInput() {
        val hash = ByteArray(32) { it.toByte() }
        val nativeInput = byteArrayOf(1, 2, 3, 4)
        val storage = FakeTransactionStorage()
        val syncManager = TransactionSyncManager(transactionManager(storage))
        syncManager.add(transactionSyncer(Transaction(hash, 1L, false, input = nativeInput)))
        syncManager.add(transactionSyncer(Transaction(hash, 1L, false, input = null)))
        val synced = CountDownLatch(1)
        val disposable = syncManager.syncStateAsync.subscribe {
            if (it is EthereumKit.SyncState.Synced) {
                synced.countDown()
            }
        }

        syncManager.sync()

        try {
            assertTrue(synced.await(3, TimeUnit.SECONDS))
            assertArrayEquals(nativeInput, storage.getTransaction(hash)?.input)
        } finally {
            disposable.dispose()
        }
    }

    @Test
    fun handle_authoritativeNativeTransactionAfterResync_replacesCorruptedValue() {
        val hash = ByteArray(32) { it.toByte() }
        val corruptedValue = BigInteger.ONE
        val nativeValue = BigInteger("1759231567651249")
        val storage = FakeTransactionStorage()
        storage.save(listOf(Transaction(hash, 1L, false, value = corruptedValue)))

        transactionManager(storage).handle(
            listOf(Transaction(hash, 1L, false, value = nativeValue))
        )

        assertEquals(nativeValue, storage.getTransaction(hash)?.value)
    }

    @Test
    fun syncRpcOnly_runsOnlyRpcSyncers_andKeepsSyncState() {
        val hash = ByteArray(32) { it.toByte() }
        val storage = FakeTransactionStorage()
        val syncManager = TransactionSyncManager(transactionManager(storage))
        val explorer = RecordingSyncer(requiresExplorer = true)
        val rpcOnly = RecordingSyncer(
            requiresExplorer = false,
            result = Single.just(Pair(listOf(Transaction(hash, 1L, false)), false))
        )
        syncManager.add(explorer)
        syncManager.add(rpcOnly)
        val states = syncManager.syncStateAsync.test()

        syncManager.syncRpcOnly()

        assertEquals(0, explorer.calls)
        assertEquals(1, rpcOnly.calls)
        assertNotNull(storage.getTransaction(hash))
        states.assertNoValues()
    }

    @Test
    fun syncRpcOnly_whileFullSyncing_isSkipped() {
        val syncManager = TransactionSyncManager(transactionManager(FakeTransactionStorage()))
        val explorer = RecordingSyncer(requiresExplorer = true, result = Single.never())
        val rpcOnly = RecordingSyncer(requiresExplorer = false)
        syncManager.add(explorer)
        syncManager.add(rpcOnly)
        syncManager.sync()

        syncManager.syncRpcOnly()

        assertTrue(syncManager.syncState is EthereumKit.SyncState.Syncing)
        assertEquals(1, rpcOnly.calls) // the in-flight full sync already covers it
    }

    @Test
    fun syncRpcOnly_syncerFails_keepsSyncState() {
        val syncManager = TransactionSyncManager(transactionManager(FakeTransactionStorage()))
        syncManager.add(
            RecordingSyncer(requiresExplorer = false, result = Single.error(IllegalStateException()))
        )
        val states = syncManager.syncStateAsync.test()

        syncManager.syncRpcOnly()

        states.assertNoValues()
        assertTrue(syncManager.syncState is EthereumKit.SyncState.NotSynced)
    }

    private class RecordingSyncer(
        override val requiresExplorer: Boolean,
        private val result: Single<Pair<List<Transaction>, Boolean>> =
            Single.just(Pair(emptyList(), false))
    ) : ITransactionSyncer {
        var calls = 0
            private set

        override fun getTransactionsSingle(): Single<Pair<List<Transaction>, Boolean>> {
            calls++
            return result
        }
    }

    private fun transactionSyncer(transaction: Transaction) = object : ITransactionSyncer {
        override fun getTransactionsSingle(): Single<Pair<List<Transaction>, Boolean>> =
            Single.just(Pair(listOf(transaction), false))
    }

    private fun transactionManager(storage: ITransactionStorage): TransactionManager {
        val address = Address("0x0000000000000000000000000000000000000001")
        return TransactionManager(
            address = address,
            storage = storage,
            decorationManager = DecorationManager(address, storage),
            blockchain = unusedProxy(IBlockchain::class.java),
            provider = unusedProxy(ITransactionProvider::class.java)
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> unusedProxy(type: Class<T>): T =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
            error("Unexpected call to ${method.name}")
        } as T

    private class FakeTransactionStorage : ITransactionStorage {
        private val transactions = mutableListOf<Transaction>()

        override fun getTransactions(hashes: List<ByteArray>): List<Transaction> =
            transactions.filter { transaction ->
                hashes.any { it.contentEquals(transaction.hash) }
            }

        override fun getTransaction(hash: ByteArray): Transaction? =
            transactions.firstOrNull { it.hash.contentEquals(hash) }

        override fun getTransactionsBeforeAsync(
            tags: List<List<String>>,
            hash: ByteArray?,
            limit: Int?
        ): Single<List<Transaction>> = Single.just(emptyList())

        override fun save(transactions: List<Transaction>) {
            transactions.forEach { transaction ->
                this.transactions.removeAll { it.hash.contentEquals(transaction.hash) }
                this.transactions.add(transaction)
            }
        }

        override fun getPendingTransactions(): List<Transaction> = emptyList()
        override fun getPendingTransactions(tags: List<List<String>>): List<Transaction> =
            emptyList()

        override fun getNonPendingTransactionsByNonces(
            from: Address,
            pendingTransactionNonces: List<Long>
        ): List<Transaction> = emptyList()

        override fun getLastInternalTransaction(): InternalTransaction? = null
        override fun getInternalTransactions(): List<InternalTransaction> = emptyList()
        override fun getInternalTransactionsByHashes(
            hashes: List<ByteArray>
        ): List<InternalTransaction> = emptyList()

        override fun saveInternalTransactions(internalTransactions: List<InternalTransaction>) {}
        override fun saveTags(tags: List<TransactionTag>) {}
        override fun getDistinctTokenContractAddresses(): List<String> = emptyList()
        override fun getTransactionsAfterSingle(hash: ByteArray?): Single<List<Transaction>> =
            Single.just(emptyList())
    }
}
