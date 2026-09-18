package io.horizontalsystems.ethereumkit.transactionsyncers

import io.horizontalsystems.ethereumkit.core.ITransactionProvider
import io.horizontalsystems.ethereumkit.core.storage.TransactionSyncSourceStorage
import io.horizontalsystems.ethereumkit.core.storage.TransactionSyncerStateStorage
import io.horizontalsystems.ethereumkit.models.Transaction
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.reactivex.Single
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class EthereumTransactionSyncerTest {

    // An explorer outage (402/429) must stay invisible to the UI: empty history, green state.
    @Test
    fun getTransactionsSingle_providerFails_returnsEmptyAndDoesNotAdvanceState() {
        val storage = mockk<TransactionSyncerStateStorage>()
        every { storage.get(EthereumTransactionSyncer.SyncerId) } returns null
        val syncSourceStorage = mockk<TransactionSyncSourceStorage>(relaxed = true)
        val provider = mockk<ITransactionProvider>()
        every { provider.getTransactions(any()) } returns Single.error(IOException("HTTP 402"))

        val (transactions, initial) =
            EthereumTransactionSyncer(provider, storage, syncSourceStorage)
                .getTransactionsSingle()
                .blockingGet()

        assertEquals(emptyList<Transaction>(), transactions)
        assertTrue(initial)
        verify(exactly = 0) { storage.save(any()) }
        verify(exactly = 0) { syncSourceStorage.saveAll(any(), any()) }
    }
}
