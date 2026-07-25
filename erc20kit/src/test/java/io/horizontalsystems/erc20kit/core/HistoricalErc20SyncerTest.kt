package io.horizontalsystems.erc20kit.core

import io.horizontalsystems.ethereumkit.core.TokenTransactionProvider
import io.horizontalsystems.ethereumkit.core.TransactionManager
import io.horizontalsystems.ethereumkit.models.Transaction
import io.horizontalsystems.ethereumkit.network.ConnectionManager
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigInteger

class HistoricalErc20SyncerTest {

    @Test
    fun start_tokenTransaction_omitsTokenFieldsFromNativeTransaction() {
        val storage = FakeEip20Storage()
        val transactionManager = mockk<TransactionManager>(relaxed = true)
        val tokenTransactionProvider = mockk<TokenTransactionProvider>()
        val connectionManager = mockk<ConnectionManager>(relaxed = true)
        val transactions = slot<List<Transaction>>()
        coEvery { tokenTransactionProvider.fetchBlockNumber() } returns 10L
        coEvery {
            tokenTransactionProvider.getTokenTransactions(0L, 16L)
        } returns TokenTransactionProvider.TokenTransactionsResult(
            transactions = listOf(createTokenTransaction(10L)),
            lastScannedBlock = 10L
        )
        val syncer = HistoricalErc20Syncer(
            transactionManager = transactionManager,
            tokenTransactionProvider = tokenTransactionProvider,
            storage = storage,
            transactionSaver = TransactionSaver(storage),
            connectionManager = connectionManager
        )
        syncer.isEnabled = true

        try {
            syncer.start()

            verify(timeout = 3_000) {
                transactionManager.handle(capture(transactions), initial = false)
            }
        } finally {
            syncer.stop()
        }

        assertNull(transactions.captured.single().to)
        assertNull(transactions.captured.single().value)
        assertEquals(BigInteger.ONE, storage.savedEvents.single().value)
    }
}
