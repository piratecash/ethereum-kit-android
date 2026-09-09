package io.horizontalsystems.ethereumkit.transactionsyncers

import io.horizontalsystems.ethereumkit.core.ITransactionProvider
import io.horizontalsystems.ethereumkit.core.ITransactionStorage
import io.horizontalsystems.ethereumkit.core.toHexString
import io.horizontalsystems.ethereumkit.models.Address
import io.horizontalsystems.ethereumkit.models.InternalTransaction
import io.horizontalsystems.ethereumkit.models.ProviderInternalTransaction
import io.horizontalsystems.ethereumkit.models.Transaction
import io.horizontalsystems.ethereumkit.models.TransactionTag
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import io.mockk.verify
import io.reactivex.Single
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigInteger

class InternalTransactionSyncerTest {

    private val address = Address("0x0000000000000000000000000000000000000001")

    private fun internalTx(hash: ByteArray, blockNumber: Long, traceId: String) =
        ProviderInternalTransaction(
            hash = hash,
            blockNumber = blockNumber,
            timestamp = 1000L,
            from = address,
            to = address,
            value = BigInteger.ONE,
            traceId = traceId
        )

    @Test
    fun getTransactionsSingle_storedCheckpoint_requestsFromThatBlockInclusive() {
        val storage = mockk<ITransactionStorage>()
        every { storage.getLastInternalTransaction() } returns InternalTransaction(
            hash = ByteArray(32) { 1 }, traceId = "0", blockNumber = 50L, from = address, to = address, value = BigInteger.ONE
        )
        every { storage.getInternalTransactionsByHashes(any()) } returns emptyList()
        every { storage.saveInternalTransactions(any()) } just Runs

        val requestedBlock = slot<Long>()
        val provider = mockk<ITransactionProvider>()
        every { provider.getInternalTransactions(capture(requestedBlock)) } returns Single.just(emptyList())

        InternalTransactionSyncer(provider, storage).getTransactionsSingle().blockingGet()

        assertEquals(50L, requestedBlock.captured)
    }

    @Test
    fun getTransactionsSingle_recordsAlreadyStored_areNeitherSavedNorEmitted() {
        val hash = ByteArray(32) { 1 }
        val stored = InternalTransaction(hash = hash, traceId = "0", blockNumber = 10L, from = address, to = address, value = BigInteger.ONE)
        val storage = mockk<ITransactionStorage>()
        every { storage.getLastInternalTransaction() } returns stored
        every { storage.getInternalTransactionsByHashes(any()) } returns listOf(stored)
        every { storage.saveInternalTransactions(any()) } just Runs

        val provider = mockk<ITransactionProvider>()
        every { provider.getInternalTransactions(10L) } returns Single.just(listOf(internalTx(hash, 10L, "0")))

        val (transactions, _) = InternalTransactionSyncer(provider, storage).getTransactionsSingle().blockingGet()

        assertEquals(emptyList<Transaction>(), transactions)
        verify(exactly = 0) { storage.saveInternalTransactions(any()) }
    }

    @Test
    fun getTransactionsSingle_capSplitsBlock_nextRoundCompletesTheBlock() {
        val cap = 3
        val hashA = ByteArray(32) { 1 }
        val hashX = ByteArray(32) { 2 }
        val hashB = ByteArray(32) { 3 }
        val hashB2 = hashB.copyOf() // separate instance, same content as hashB

        val a = internalTx(hashA, 10L, "0")
        val x = internalTx(hashX, 10L, "0")
        val b1 = internalTx(hashB, 11L, "0")
        val b2 = internalTx(hashB2, 11L, "1")
        val c = internalTx(ByteArray(32) { 4 }, 12L, "0")
        val allTransactions = listOf(a, x, b1, b2, c)

        val provider = mockk<ITransactionProvider>()
        every { provider.getInternalTransactions(any()) } answers {
            val startBlock = firstArg<Long>()
            Single.just(allTransactions.filter { it.blockNumber >= startBlock }.take(cap))
        }

        val storage = spyk(FakeInternalTransactionStorage())
        val syncer = InternalTransactionSyncer(provider, storage)

        syncer.getTransactionsSingle().blockingGet()

        val (round2Transactions, _) = syncer.getTransactionsSingle().blockingGet()
        assertEquals(listOf(hashB2, c.hash).map { it.toHexString() }, round2Transactions.map { it.hash.toHexString() })
        verify(exactly = 1) {
            storage.getInternalTransactionsByHashes(match { it.size == 1 && it[0].contentEquals(hashB) })
        }

        val (round3Transactions, _) = syncer.getTransactionsSingle().blockingGet()
        assertEquals(emptyList<Transaction>(), round3Transactions)

        val storedKeys = storage.getInternalTransactions().map { it.hashString to it.traceId }.toSet()
        assertEquals(
            setOf(
                hashA.toHexString() to "0",
                hashX.toHexString() to "0",
                hashB.toHexString() to "0",
                hashB.toHexString() to "1",
                c.hash.toHexString() to "0"
            ),
            storedKeys
        )
    }

    private class FakeInternalTransactionStorage : ITransactionStorage {
        private val internalTransactions = mutableListOf<InternalTransaction>()

        override fun getLastInternalTransaction(): InternalTransaction? =
            internalTransactions.maxByOrNull { it.blockNumber }

        override fun getInternalTransactions(): List<InternalTransaction> = internalTransactions.toList()

        override fun getInternalTransactionsByHashes(hashes: List<ByteArray>): List<InternalTransaction> =
            internalTransactions.filter { tx -> hashes.any { it.contentEquals(tx.hash) } }

        override fun saveInternalTransactions(internalTransactions: List<InternalTransaction>) {
            internalTransactions.forEach { tx ->
                this.internalTransactions.removeAll { it.hash.contentEquals(tx.hash) && it.traceId == tx.traceId }
                this.internalTransactions.add(tx)
            }
        }

        override fun getTransactions(hashes: List<ByteArray>) = error("unused")
        override fun getTransaction(hash: ByteArray) = error("unused")
        override fun getTransactionsBeforeAsync(tags: List<List<String>>, hash: ByteArray?, limit: Int?) = error("unused")
        override fun save(transactions: List<Transaction>) = error("unused")
        override fun getPendingTransactions() = error("unused")
        override fun getPendingTransactions(tags: List<List<String>>) = error("unused")
        override fun getNonPendingTransactionsByNonces(from: Address, pendingTransactionNonces: List<Long>) = error("unused")
        override fun saveTags(tags: List<TransactionTag>) = error("unused")
        override fun getDistinctTokenContractAddresses() = error("unused")
        override fun getTransactionsAfterSingle(hash: ByteArray?) = error("unused")
    }
}
