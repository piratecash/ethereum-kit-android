package io.horizontalsystems.ethereumkit.core.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.horizontalsystems.ethereumkit.fixture.EthereumKitFixture.hash
import io.horizontalsystems.ethereumkit.fixture.EthereumKitFixture.snapshot
import io.horizontalsystems.ethereumkit.models.RawTransactionBroadcastRecord
import io.horizontalsystems.ethereumkit.models.Transaction
import io.horizontalsystems.ethereumkit.models.TransactionTag
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Pins the observable behavior of the raw-SQL storage queries on real Room, independent of how they are executed. */
@RunWith(RobolectricTestRunner::class)
class TransactionStoragePinningTest {
    private val database = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(), TransactionDatabase::class.java
    ).allowMainThreadQueries().build()
    private val storage = TransactionStorage(database)

    @After
    fun tearDown() = database.close()

    // Descending order is E, C, D, B, A: C and D share timestamp and index, so the hex of the hash breaks the tie.
    private val a = confirmed(seed = 0xa0, timestamp = 100, index = 1)
    private val b = confirmed(seed = 0xb0, timestamp = 200, index = 1)
    private val c = confirmed(seed = 0xc0, timestamp = 200, index = 2)
    private val d = confirmed(seed = 0x10, timestamp = 200, index = 2)
    private val e = confirmed(seed = 0xe0, timestamp = 300, index = 0)

    private fun confirmed(seed: Int, timestamp: Long, index: Int) =
        Transaction(hash(seed), timestamp, isFailed = false, blockNumber = 1_000L + timestamp, transactionIndex = index)

    private fun pending(seed: Int, timestamp: Long, isFailed: Boolean = false) =
        Transaction(hash(seed), timestamp, isFailed = isFailed)

    private fun givenHistory() {
        storage.save(listOf(a, b, c, d, e))
        tag(a, TransactionTag.INCOMING, TransactionTag.EVM_COIN_INCOMING)
        tag(b, TransactionTag.OUTGOING, TransactionTag.tokenOutgoing("0xabc"))
        tag(c, TransactionTag.OUTGOING, TransactionTag.SWAP)
        tag(d, TransactionTag.INCOMING)
        tag(e, TransactionTag.OUTGOING, TransactionTag.EIP20_TRANSFER)
    }

    private fun tag(transaction: Transaction, vararg names: String) =
        storage.saveTags(names.map { TransactionTag(it, transaction.hash) })

    private fun before(tags: List<List<String>> = emptyList(), from: Transaction? = null, limit: Int? = null) =
        storage.getTransactionsBeforeAsync(tags, from?.hash, limit).blockingGet().hashes()

    private fun after(from: Transaction? = null) = storage.getTransactionsAfterSingle(from?.hash).blockingGet().hashes()

    private fun List<Transaction>.hashes() = map { it.snapshot()[0] }

    private fun expected(vararg transactions: Transaction) = transactions.map { it.snapshot()[0] }

    @Test
    fun getTransactionsBefore_noFilters_ordersByTimestampIndexThenHashDescending() {
        givenHistory()

        assertEquals(expected(e, c, d, b, a), before())
    }

    @Test
    fun getTransactionsBefore_fromHash_returnsOnlyStrictlyOlder() {
        givenHistory()

        assertEquals(expected(e, c, d, b, a), before(from = null))
        assertEquals(expected(d, b, a), before(from = c))
        assertEquals(expected(b, a), before(from = d))
        assertEquals(expected(c, d, b, a), before(from = e))
        assertEquals(emptyList<String>(), before(from = a))
    }

    @Test
    fun getTransactionsBefore_unknownFromHash_ignoresFromFilter() {
        givenHistory()

        assertEquals(expected(e, c, d, b, a), storage.getTransactionsBeforeAsync(emptyList(), hash(0x99), null).blockingGet().hashes())
    }

    @Test
    fun getTransactionsBefore_limit_keepsNewestRows() {
        givenHistory()

        assertEquals(expected(e, c), before(limit = 2))
        assertEquals(expected(b, a), before(from = d, limit = 5))
        assertEquals(expected(d, b), before(from = c, limit = 2))
    }

    @Test
    fun getTransactionsBefore_singleTagGroup_matchesAnyTagInGroup() {
        givenHistory()

        assertEquals(expected(e, c, b), before(tags = listOf(listOf(TransactionTag.OUTGOING))))
        assertEquals(expected(c, d, a), before(tags = listOf(listOf(TransactionTag.INCOMING, TransactionTag.SWAP))))
    }

    @Test
    fun getTransactionsBefore_severalTagGroups_requiresEveryGroup() {
        givenHistory()

        assertEquals(expected(c), before(tags = listOf(listOf(TransactionTag.OUTGOING), listOf(TransactionTag.SWAP))))
        assertEquals(expected(e), before(tags = listOf(listOf(TransactionTag.INCOMING, TransactionTag.OUTGOING), listOf(TransactionTag.EIP20_TRANSFER))))
        assertEquals(emptyList<String>(), before(tags = listOf(listOf(TransactionTag.OUTGOING), listOf(TransactionTag.INCOMING))))
    }

    @Test
    fun getTransactionsBefore_tagsFromHashAndLimit_combine() {
        givenHistory()

        assertEquals(expected(b), before(tags = listOf(listOf(TransactionTag.OUTGOING)), from = c, limit = 1))
        assertEquals(expected(c), before(tags = listOf(listOf(TransactionTag.OUTGOING)), from = e, limit = 1))
    }

    @Test
    fun getTransactionsAfter_noHash_ordersAscending() {
        givenHistory()

        assertEquals(expected(a, b, d, c, e), after())
    }

    @Test
    fun getTransactionsAfter_fromHash_returnsOnlyStrictlyNewer() {
        givenHistory()

        assertEquals(expected(d, c, e), after(from = b))
        assertEquals(expected(c, e), after(from = d))
        assertEquals(emptyList<String>(), after(from = e))
    }

    @Test
    fun getTransactionsAfter_unknownHash_ignoresFilter() {
        givenHistory()

        assertEquals(expected(a, b, d, c, e), storage.getTransactionsAfterSingle(hash(0x99)).blockingGet().hashes())
    }

    @Test
    fun getPendingTransactions_noTags_returnsUnconfirmedNonFailed() {
        val pendingA = pending(seed = 0x01, timestamp = 400)
        val failed = pending(seed = 0x02, timestamp = 410, isFailed = true)
        storage.save(listOf(a, pendingA, failed))

        assertEquals(expected(pendingA), storage.getPendingTransactions().hashes())
    }

    @Test
    fun getPendingTransactions_tags_filtersUnconfirmedByTagGroups() {
        val outgoing = pending(seed = 0x01, timestamp = 400)
        val incoming = pending(seed = 0x02, timestamp = 410)
        val swap = pending(seed = 0x03, timestamp = 420)
        val failed = pending(seed = 0x04, timestamp = 430, isFailed = true)
        storage.save(listOf(a, outgoing, incoming, swap, failed))
        tag(a, TransactionTag.OUTGOING)
        tag(outgoing, TransactionTag.OUTGOING)
        tag(incoming, TransactionTag.INCOMING)
        tag(swap, TransactionTag.SWAP)
        tag(failed, TransactionTag.OUTGOING)
        tag(swap, TransactionTag.EIP20_TRANSFER)

        // Unlike getPendingTransactions() without tags, failed unconfirmed rows are included.
        assertEquals(expected(outgoing, incoming, swap, failed).toSet(), pendingHashes(emptyList()))
        assertEquals(expected(outgoing, failed).toSet(), pendingHashes(listOf(listOf(TransactionTag.OUTGOING))))
        assertEquals(expected(incoming, swap).toSet(), pendingHashes(listOf(listOf(TransactionTag.INCOMING, TransactionTag.SWAP))))
        assertEquals(expected(swap).toSet(), pendingHashes(listOf(listOf(TransactionTag.SWAP), listOf(TransactionTag.EIP20_TRANSFER))))
        assertEquals(emptySet<String>(), pendingHashes(listOf(listOf(TransactionTag.OUTGOING), listOf(TransactionTag.INCOMING))))
    }

    private fun pendingHashes(tags: List<List<String>>) = storage.getPendingTransactions(tags).hashes().toSet()

    @Test
    fun getDistinctTokenContractAddresses_returnsDistinctDirectionalTagNames() {
        storage.save(listOf(a, b))
        tag(a, "0xabc_incoming", "0xabc_outgoing", "ETH_incoming", TransactionTag.SWAP, TransactionTag.INCOMING, "from_0x1")
        tag(b, "0xabc_outgoing", TransactionTag.OUTGOING, TransactionTag.EIP20_TRANSFER)

        val names = storage.getDistinctTokenContractAddresses()

        assertEquals(setOf("0xabc_incoming", "0xabc_outgoing", "ETH_incoming"), names.toSet())
        assertEquals(3, names.size)
    }

    @Test
    fun rawTransactionBroadcast_crud_roundTrips() {
        val first = record(seed = 0x01, retries = 0)
        val second = record(seed = 0x02, retries = 3)

        storage.addRawTransactionBroadcast(first)
        storage.addRawTransactionBroadcast(second)

        assertEquals(first.snapshot(), storage.getRawTransactionBroadcast(first.hash)?.snapshot())
        assertEquals(
            listOf(first, second).map { it.snapshot() }.sortedBy { it.toString() },
            storage.getRawTransactionBroadcasts().map { it.snapshot() }.sortedBy { it.toString() }
        )

        val updated = first.copy(retriesCount = 7, lastSendTime = 999)
        storage.updateRawTransactionBroadcast(updated)
        assertEquals(updated.snapshot(), storage.getRawTransactionBroadcast(first.hash)?.snapshot())

        storage.deleteRawTransactionBroadcast(first)
        assertNull(storage.getRawTransactionBroadcast(first.hash))
        assertEquals(listOf(second.snapshot()), storage.getRawTransactionBroadcasts().map { it.snapshot() })
    }

    @Test
    fun rawTransactionBroadcast_addExistingHash_keepsOriginalRecord() {
        val original = record(seed = 0x01, retries = 0)
        storage.addRawTransactionBroadcast(original)

        storage.addRawTransactionBroadcast(original.copy(retriesCount = 9))

        assertEquals(original.snapshot(), storage.getRawTransactionBroadcast(original.hash)?.snapshot())
    }

    private fun record(seed: Int, retries: Int) = RawTransactionBroadcastRecord(
        hash = hash(seed), rawTransaction = byteArrayOf(0x02, seed.toByte()),
        firstSendTime = 1_000, lastSendTime = 1_000, retriesCount = retries, expiresAt = 5_000
    )
}
