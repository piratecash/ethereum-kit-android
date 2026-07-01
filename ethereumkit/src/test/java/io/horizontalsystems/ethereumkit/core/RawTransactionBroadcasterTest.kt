package io.horizontalsystems.ethereumkit.core

import io.horizontalsystems.ethereumkit.api.core.RpcResponse
import io.horizontalsystems.ethereumkit.api.jsonrpc.JsonRpc
import io.horizontalsystems.ethereumkit.api.jsonrpc.models.RpcBlock
import io.horizontalsystems.ethereumkit.api.jsonrpc.models.RpcTransaction
import io.horizontalsystems.ethereumkit.api.jsonrpc.models.RpcTransactionReceipt
import io.horizontalsystems.ethereumkit.api.models.AccountState
import io.horizontalsystems.ethereumkit.crypto.CryptoUtils
import io.horizontalsystems.ethereumkit.models.Address
import io.horizontalsystems.ethereumkit.models.DefaultBlockParameter
import io.horizontalsystems.ethereumkit.models.GasPrice
import io.horizontalsystems.ethereumkit.models.RawTransactionBroadcastRecord
import io.horizontalsystems.ethereumkit.models.RawTransactionBroadcastStatus
import io.horizontalsystems.ethereumkit.models.RawTransaction
import io.horizontalsystems.ethereumkit.models.Signature
import io.horizontalsystems.ethereumkit.models.Transaction
import io.horizontalsystems.ethereumkit.models.TransactionLog
import io.reactivex.Single
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.IOException
import java.math.BigInteger
import java.util.concurrent.TimeUnit

class RawTransactionBroadcasterTest {
    private val blockchain = FakeBlockchain()
    private lateinit var storage: InMemoryBroadcastStorage
    private var now = 1_000L
    private lateinit var broadcaster: RawTransactionBroadcaster

    private val rawTransaction = byteArrayOf(1, 2, 3)
    private val hash = CryptoUtils.sha3(rawTransaction)

    companion object {
        @JvmStatic
        @BeforeClass
        fun beforeClass() {
            EthereumKit.init()
        }
    }

    @Before
    fun setup() {
        storage = InMemoryBroadcastStorage()
        broadcaster = RawTransactionBroadcaster(
            blockchain = blockchain,
            storage = storage,
            currentTime = { now },
        )
    }

    @Test
    fun broadcast_success_returnsSubmittedAndDoesNotQueue() {
        blockchain.sendRawTransactionResult = Single.just(hash)

        val result = broadcaster.broadcast(rawTransaction).blockingGet()

        assertEquals(RawTransactionBroadcastStatus.Submitted, result.status)
        assertArrayEquals(hash, result.transactionHash)
        assertTrue(storage.records.isEmpty())
    }

    @Test
    fun broadcast_transientError_queuesFreshRecordWithTtl() {
        blockchain.sendRawTransactionResult = Single.error(IOException("offline"))

        val result = broadcaster.broadcast(rawTransaction).blockingGet()
        val record = storage.getRawTransactionBroadcast(hash)

        assertEquals(RawTransactionBroadcastStatus.Queued, result.status)
        requireNotNull(record)
        assertArrayEquals(rawTransaction, record.rawTransaction)
        assertEquals(now, record.firstSendTime)
        assertEquals(now, record.lastSendTime)
        assertEquals(now + RawTransactionBroadcaster.retryTtl, record.expiresAt)
        assertEquals(0, record.retriesCount)
        assertEquals(1, storage.insertCount)
        assertEquals(0, storage.updateCount)
    }

    @Test
    fun broadcast_withoutSubscription_doesNotQueueDuplicate() {
        broadcaster.broadcast(rawTransaction)
        broadcaster.broadcast(rawTransaction)

        assertTrue(storage.records.isEmpty())
        assertTrue(blockchain.sentRawTransactions.isEmpty())
    }

    @Test
    fun retry_transientError_updatesExistingRecordPreservingFirstSendTimeAndExpiresAt() {
        val firstSendTime = 100L
        val expiresAt = now + RawTransactionBroadcaster.retryTtl
        storage.records[hash.toRawHexString()] = RawTransactionBroadcastRecord(
            hash = hash,
            rawTransaction = rawTransaction,
            firstSendTime = firstSendTime,
            lastSendTime = 0L,
            retriesCount = 0,
            expiresAt = expiresAt,
        )
        now = RawTransactionBroadcaster.retriesPeriod + 1
        stubTransactionAbsent()
        blockchain.sendRawTransactionResult = Single.error(IOException("offline"))

        broadcaster.retryQueued().blockingGet()
        val record = storage.getRawTransactionBroadcast(hash)

        requireNotNull(record)
        assertEquals(firstSendTime, record.firstSendTime)
        assertEquals(expiresAt, record.expiresAt)
        assertEquals(now, record.lastSendTime)
        assertEquals(1, record.retriesCount)
        assertEquals(0, storage.insertCount)
        assertEquals(1, storage.updateCount)
    }

    @Test
    fun retry_withoutSubscription_doesNotHoldRetryGuard() {
        storage.records[hash.toRawHexString()] = queuedRecord(lastSendTime = 0L)
        now = RawTransactionBroadcaster.retriesPeriod + 1
        stubTransactionAbsent()
        blockchain.sendRawTransactionResult = Single.just(hash)

        broadcaster.retryQueued()
        broadcaster.retryQueued().blockingGet()

        assertEquals(1, blockchain.sentRawTransactions.size)
        assertTrue(storage.records.isEmpty())
    }

    @Test
    fun retry_transactionAlreadyInNetwork_deletesRecordWithoutBroadcast() {
        storage.records[hash.toRawHexString()] = queuedRecord(lastSendTime = 0L)
        now = RawTransactionBroadcaster.retriesPeriod + 1
        blockchain.getTransactionResult = Single.just(rpcTransaction())

        broadcaster.retryQueued().blockingGet()

        assertTrue(storage.records.isEmpty())
        assertTrue(blockchain.sentRawTransactions.isEmpty())
    }

    @Test
    fun retry_permanentError_deletesRecord() {
        storage.records[hash.toRawHexString()] = queuedRecord(lastSendTime = 0L)
        now = RawTransactionBroadcaster.retriesPeriod + 1
        stubTransactionAbsent()
        blockchain.sendRawTransactionResult =
            Single.error(JsonRpc.ResponseError.RpcError(RpcResponse.Error(-32000, "nonce too low")))

        broadcaster.retryQueued().blockingGet()

        assertTrue(storage.records.isEmpty())
    }

    @Test
    fun retry_retriesExhausted_deletesWithoutBroadcast() {
        storage.records[hash.toRawHexString()] = queuedRecord(
            lastSendTime = 0L,
            retriesCount = RawTransactionBroadcaster.maxRetriesCount,
        )

        broadcaster.retryQueued().blockingGet()

        assertTrue(storage.records.isEmpty())
        assertTrue(blockchain.sentRawTransactions.isEmpty())
    }

    @Test
    fun retry_concurrentCall_skipsSecondRun() {
        storage.records[hash.toRawHexString()] = queuedRecord(lastSendTime = 0L)
        now = RawTransactionBroadcaster.retriesPeriod + 1
        stubTransactionAbsent()
        blockchain.sendRawTransactionResult = Single.never()

        val firstRetry = broadcaster.retryQueued().test()
        broadcaster.retryQueued().blockingGet()

        assertEquals(1, blockchain.sentRawTransactions.size)
        firstRetry.dispose()
    }

    @Test
    fun retry_hangingBroadcast_timesOutAndAllowsNextRetry() {
        broadcaster = RawTransactionBroadcaster(
            blockchain = blockchain,
            storage = storage,
            currentTime = { now },
            networkTimeoutMs = 1,
        )
        storage.records[hash.toRawHexString()] = queuedRecord(lastSendTime = 0L)
        now = RawTransactionBroadcaster.retriesPeriod + 1
        stubTransactionAbsent()
        blockchain.sendRawTransactionResult = Single.never()

        broadcaster.retryQueued()
            .test()
            .awaitDone(1, TimeUnit.SECONDS)
            .assertComplete()
            .assertNoErrors()
        val timedOutRecord = storage.getRawTransactionBroadcast(hash)
        requireNotNull(timedOutRecord)
        assertEquals(1, timedOutRecord.retriesCount)
        assertEquals(1, blockchain.sentRawTransactions.size)

        now += RawTransactionBroadcaster.retriesPeriod + 1
        blockchain.sendRawTransactionResult = Single.just(hash)

        broadcaster.retryQueued().blockingGet()

        assertEquals(2, blockchain.sentRawTransactions.size)
        assertTrue(storage.records.isEmpty())
    }

    @Test
    fun retry_expiredRecord_deletesWithoutBroadcast() {
        storage.records[hash.toRawHexString()] = queuedRecord(
            lastSendTime = 0L,
            expiresAt = now - 1,
        )

        broadcaster.retryQueued().blockingGet()

        assertTrue(storage.records.isEmpty())
        assertTrue(blockchain.sentRawTransactions.isEmpty())
    }

    @Test
    fun broadcast_knownTransactionError_returnsAlreadyKnownAndDeletesQueue() {
        storage.records[hash.toRawHexString()] = queuedRecord(lastSendTime = 0L)
        blockchain.sendRawTransactionResult =
            Single.error(JsonRpc.ResponseError.RpcError(RpcResponse.Error(-32000, "already known")))

        val result = broadcaster.broadcast(rawTransaction).blockingGet()

        assertEquals(RawTransactionBroadcastStatus.AlreadyKnown, result.status)
        assertTrue(storage.records.isEmpty())
    }

    @Test
    fun broadcast_rpcHashMismatch_throwsAndDoesNotQueue() {
        blockchain.sendRawTransactionResult = Single.just(byteArrayOf(9, 9, 9))

        broadcaster.broadcast(rawTransaction).test().assertError(IllegalStateException::class.java)

        assertTrue(storage.records.isEmpty())
    }

    @Test
    fun broadcast_unsupportedOperation_throwsWithoutQueueOrPresenceCheck() {
        blockchain.sendRawTransactionResult = Single.error(UnsupportedOperationException("unsupported"))

        broadcaster.broadcast(rawTransaction).test().assertError(UnsupportedOperationException::class.java)

        assertTrue(storage.records.isEmpty())
        assertEquals(0, blockchain.getTransactionCallCount)
        assertEquals(0, blockchain.getTransactionReceiptCallCount)
    }

    private fun stubTransactionAbsent() {
        blockchain.getTransactionResult = Single.error(JsonRpc.ResponseError.InvalidResult(null))
        blockchain.getTransactionReceiptResult = Single.error(JsonRpc.ResponseError.InvalidResult(null))
    }

    private fun rpcTransaction() = RpcTransaction(
        hash = hash,
        nonce = 0,
        blockHash = null,
        blockNumber = null,
        transactionIndex = null,
        from = Address("0x0000000000000000000000000000000000000001"),
        to = Address("0x0000000000000000000000000000000000000002"),
        value = BigInteger.ZERO,
        gasPrice = 0,
        maxFeePerGas = null,
        maxPriorityFeePerGas = null,
        gasLimit = 0,
        input = byteArrayOf(),
    )
    private class FakeBlockchain : IBlockchain {
        val sentRawTransactions = mutableListOf<ByteArray>()
        var sendRawTransactionResult: Single<ByteArray> = Single.error(IOException("not stubbed"))
        var getTransactionResult: Single<RpcTransaction> = Single.error(JsonRpc.ResponseError.InvalidResult(null))
        var getTransactionReceiptResult: Single<RpcTransactionReceipt> = Single.error(JsonRpc.ResponseError.InvalidResult(null))
        var getTransactionCallCount = 0
        var getTransactionReceiptCallCount = 0

        override val source = "fake"
        override var listener: IBlockchainListener? = null
        override val syncState: EthereumKit.SyncState = EthereumKit.SyncState.Synced()
        override val lastBlockHeight: Long? = null
        override val accountState: AccountState? = null

        override fun start() = Unit
        override fun refresh() = Unit
        override fun stop() = Unit
        override fun syncAccountState() = Unit
        override fun send(rawTransaction: RawTransaction, signature: Signature): Single<Transaction> = Single.error(IOException("not used"))
        override fun sendRawTransaction(rawTransaction: ByteArray): Single<ByteArray> {
            sentRawTransactions.add(rawTransaction)
            return sendRawTransactionResult
        }
        override fun getNonce(defaultBlockParameter: DefaultBlockParameter): Single<Long> = Single.error(IOException("not used"))
        override fun estimateGas(to: Address?, amount: BigInteger?, gasLimit: Long?, gasPrice: GasPrice?, data: ByteArray?): Single<Long> =
            Single.error(IOException("not used"))
        override fun getTransactionReceipt(transactionHash: ByteArray): Single<RpcTransactionReceipt> = getTransactionReceiptResult
            .also { getTransactionReceiptCallCount++ }
        override fun getTransaction(transactionHash: ByteArray): Single<RpcTransaction> = getTransactionResult
            .also { getTransactionCallCount++ }
        override fun getBlock(blockNumber: Long): Single<RpcBlock> = Single.error(IOException("not used"))
        override fun getLogs(
            address: Address?,
            topics: List<ByteArray?>,
            fromBlock: Long,
            toBlock: Long,
            pullTimestamps: Boolean
        ): Single<List<TransactionLog>> = Single.error(IOException("not used"))
        override fun getStorageAt(contractAddress: Address, position: ByteArray, defaultBlockParameter: DefaultBlockParameter): Single<ByteArray> =
            Single.error(IOException("not used"))
        override fun call(contractAddress: Address, data: ByteArray, defaultBlockParameter: DefaultBlockParameter): Single<ByteArray> =
            Single.error(IOException("not used"))
        override fun <T : Any> rpcSingle(rpc: JsonRpc<T>): Single<T> = Single.error(IOException("not used"))
    }

    private fun queuedRecord(
        lastSendTime: Long,
        expiresAt: Long = now + RawTransactionBroadcaster.retryTtl,
        retriesCount: Int = 0,
    ) = RawTransactionBroadcastRecord(
        hash = hash,
        rawTransaction = rawTransaction,
        firstSendTime = now,
        lastSendTime = lastSendTime,
        retriesCount = retriesCount,
        expiresAt = expiresAt,
    )

    private class InMemoryBroadcastStorage : IRawTransactionBroadcastStorage {
        val records = mutableMapOf<String, RawTransactionBroadcastRecord>()
        var insertCount = 0
        var updateCount = 0

        override fun getRawTransactionBroadcast(hash: ByteArray): RawTransactionBroadcastRecord? {
            return records[hash.toRawHexString()]
        }

        override fun getRawTransactionBroadcasts(): List<RawTransactionBroadcastRecord> {
            return records.values.toList()
        }

        override fun addRawTransactionBroadcast(record: RawTransactionBroadcastRecord) {
            insertCount++
            records.putIfAbsent(record.hash.toRawHexString(), record)
        }

        override fun updateRawTransactionBroadcast(record: RawTransactionBroadcastRecord) {
            updateCount++
            records[record.hash.toRawHexString()] = record
        }

        override fun deleteRawTransactionBroadcast(record: RawTransactionBroadcastRecord) {
            records.remove(record.hash.toRawHexString())
        }
    }
}
