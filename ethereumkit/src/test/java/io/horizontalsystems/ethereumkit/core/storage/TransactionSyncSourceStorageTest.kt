package io.horizontalsystems.ethereumkit.core.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.horizontalsystems.ethereumkit.core.ITransactionProvider
import io.horizontalsystems.ethereumkit.core.TransactionManager
import io.horizontalsystems.ethereumkit.decorations.DecorationManager
import io.horizontalsystems.ethereumkit.fixture.EthereumKitFixture.hash
import io.horizontalsystems.ethereumkit.models.Address
import io.horizontalsystems.ethereumkit.models.ProviderTransaction
import io.horizontalsystems.ethereumkit.models.SyncSource
import io.horizontalsystems.ethereumkit.transactionsyncers.EthereumTransactionSyncer
import io.mockk.every
import io.mockk.mockk
import io.reactivex.Single
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.math.BigInteger

@RunWith(RobolectricTestRunner::class)
class TransactionSyncSourceStorageTest {
    private val address = Address("0x0000000000000000000000000000000000000001")
    private val database = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(), TransactionDatabase::class.java
    ).build()
    private val storage = TransactionStorage(database)
    private val syncSourceStorage = TransactionSyncSourceStorage(database.transactionSyncSourceDao())

    @After
    fun tearDown() = database.close()

    @Test
    fun syncSource_transactionSavedByEtherscanSyncer_readFromExtra() = runTest {
        val provider = mockk<ITransactionProvider>()
        every { provider.getTransactions(any()) } returns Single.just(listOf(providerTransaction()))
        val syncer = EthereumTransactionSyncer(provider, TransactionSyncerStateStorage(database), syncSourceStorage)
        val decorationManager = DecorationManager(address, storage).apply { addExtraDecorator(syncSourceStorage) }
        val transactionManager = TransactionManager(address, storage, decorationManager, mockk(), mockk())

        val (transactions, _) = syncer.getTransactionsSingle().blockingGet()
        val fullTransaction = transactionManager.handle(transactions).single()

        assertEquals(SyncSource.ETHERSCAN, TransactionSyncSourceStorage.syncSource(fullTransaction))
    }

    private fun providerTransaction() = ProviderTransaction(
        blockNumber = 100, timestamp = 1_000, hash = hash(0x42), nonce = 0, transactionIndex = 0,
        from = address, to = address, value = BigInteger.ONE, gasLimit = 21_000, gasPrice = 1, input = byteArrayOf()
    )
}
