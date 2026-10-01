package io.horizontalsystems.ethereumkit.core

import io.horizontalsystems.ethereumkit.decorations.DecorationManager
import io.horizontalsystems.ethereumkit.models.Address
import io.horizontalsystems.ethereumkit.models.InternalTransaction
import io.horizontalsystems.ethereumkit.models.Transaction
import io.horizontalsystems.ethereumkit.models.TransactionTag
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionManagerTest {

    private val address = Address("0x9d6888ba1ea048dccac5c5f116e14d4713bc3673")
    private val swapHash = ByteArray(32) { 1 }
    private val storage = InMemoryTransactionStorage()
    private val manager = TransactionManager(address, storage, DecorationManager(address, storage), mockk(), mockk())

    private fun pending(hash: ByteArray) = Transaction(hash, 1L, false, from = address, nonce = 998)
    private fun mined(hash: ByteArray) = Transaction(hash, 1L, false, blockNumber = 100, from = address, nonce = 998)

    @Test
    fun handle_sameTransactionConfirmedBetweenPendingReads_staysSuccessful() = runTest {
        // A concurrent handle() confirms the swap after this one listed it as pending.
        storage.beforeNextNonPendingRead = { manager.handle(listOf(mined(swapHash))) }

        manager.handle(listOf(pending(swapHash)))

        val stored = requireNotNull(storage.getTransaction(swapHash))
        assertFalse(stored.isFailed)
        assertNull(stored.replacedWith)
    }

    @Test
    fun handle_otherTransactionMinedWithSameNonce_marksPendingReplaced() = runTest {
        val replacementHash = ByteArray(32) { 2 }
        manager.handle(listOf(pending(swapHash)))

        manager.handle(listOf(mined(replacementHash)))

        val stored = requireNotNull(storage.getTransaction(swapHash))
        assertTrue(stored.isFailed)
        assertArrayEquals(replacementHash, stored.replacedWith)
    }

    /** Mirrors the Room queries TransactionManager relies on; hashes compare by content like SQLite blobs. */
    private class InMemoryTransactionStorage : ITransactionStorage {
        private val rows = linkedMapOf<String, Transaction>()
        var beforeNextNonPendingRead: (suspend () -> Unit)? = null

        override suspend fun getTransactions(hashes: List<ByteArray>) = hashes.mapNotNull { rows[it.toHexString()]?.detached() }
        override suspend fun getTransaction(hash: ByteArray) = rows[hash.toHexString()]?.detached()
        override suspend fun getTransactionsBefore(tags: List<List<String>>, hash: ByteArray?, limit: Int?) =
            emptyList<Transaction>()

        /** Room materializes a fresh blob per read, so no two rows may share a hash array. */
        private fun Transaction.detached() = copy(hash = hash.copyOf())

        override suspend fun save(transactions: List<Transaction>) {
            transactions.forEach { rows[it.hashString] = it.detached() }
        }

        override suspend fun getPendingTransactions() =
            rows.values.filter { it.blockNumber == null && !it.isFailed }.map { it.detached() }

        override suspend fun getPendingTransactions(tags: List<List<String>>) = getPendingTransactions()

        override suspend fun getNonPendingTransactionsByNonces(from: Address, pendingTransactionNonces: List<Long>): List<Transaction> {
            beforeNextNonPendingRead?.also { beforeNextNonPendingRead = null }?.invoke()
            return rows.values
                .filter { it.blockNumber != null && it.nonce in pendingTransactionNonces && it.from == from }
                .map { it.detached() }
        }

        override suspend fun getLastInternalTransaction(): InternalTransaction? = null
        override suspend fun getInternalTransactions() = emptyList<InternalTransaction>()
        override suspend fun getInternalTransactionsByHashes(hashes: List<ByteArray>) = emptyList<InternalTransaction>()
        override suspend fun saveInternalTransactions(internalTransactions: List<InternalTransaction>) = Unit
        override suspend fun saveTags(tags: List<TransactionTag>) = Unit
        override suspend fun getDistinctTokenContractAddresses() = emptyList<String>()
        override suspend fun getTransactionsAfter(hash: ByteArray?) = emptyList<Transaction>()
    }
}
