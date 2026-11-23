package io.horizontalsystems.ethereumkit.transactionsyncers

import co.touchlab.kermit.Logger
import io.horizontalsystems.ethereumkit.api.jsonrpc.JsonRpc
import io.horizontalsystems.ethereumkit.api.jsonrpc.models.RpcAuthorization
import io.horizontalsystems.ethereumkit.api.jsonrpc.models.RpcTransaction
import io.horizontalsystems.ethereumkit.api.core.RpcResponse
import io.horizontalsystems.ethereumkit.core.AccountDelegationState
import io.horizontalsystems.ethereumkit.core.CurrentDelegate
import io.horizontalsystems.ethereumkit.core.EthereumKit
import io.horizontalsystems.ethereumkit.core.IBlockchain
import io.horizontalsystems.ethereumkit.core.ITransactionStorage
import io.horizontalsystems.ethereumkit.core.TransactionManager
import io.horizontalsystems.ethereumkit.core.hexStringToByteArray
import io.horizontalsystems.ethereumkit.core.toHexString
import io.horizontalsystems.ethereumkit.models.Address
import io.horizontalsystems.ethereumkit.models.DefaultBlockParameter
import io.horizontalsystems.ethereumkit.models.Transaction
import io.horizontalsystems.ethereumkit.models.rpcTransaction
import io.horizontalsystems.ethereumkit.models.signAuthorization
import io.horizontalsystems.ethereumkit.models.testAccount
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.reactivex.Single
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.schedulers.TestScheduler
import io.reactivex.subjects.SingleSubject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.IOException
import java.math.BigInteger
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DelegationSyncerTest {

    private val contract = Address("0x64fc23203fcc4bcf09fc4b30669131333ba7936b")
    private val otherContract = Address("0x2222222222222222222222222222222222222222")

    private var candidates = listOf<Transaction>()
    private var carriers = listOf<Transaction>()
    private val rpcResults = mutableMapOf<String, Single<RpcTransaction>>()
    private var codeResult: Single<ByteArray> = Single.just(ByteArray(0))
    @Volatile private var failHandle = false
    @Volatile private var failHandleContaining: String? = null
    @Volatile private var getTransactionCalls = 0
    @Volatile private var bodyRuns = 0
    @Volatile private var blockNextHandle: CountDownLatch? = null
    private val handleEntered = CountDownLatch(1)
    private val handled = CopyOnWriteArrayList<List<Transaction>>()

    private val storage = mockk<ITransactionStorage> {
        coEvery { getTransactionsWithUnknownType(testAccount, 20) } answers {
            bodyRuns++
            candidates
        }
        coEvery { getAuthorizationCarriers() } answers { carriers }
    }
    private val blockchain = mockk<IBlockchain> {
        every { getTransaction(any()) } answers {
            getTransactionCalls++
            rpcResults.getValue(firstArg<ByteArray>().toHexString())
        }
        every { getCode(testAccount, DefaultBlockParameter.Latest) } answers { codeResult }
    }
    private val transactionManager = mockk<TransactionManager> {
        coEvery { handle(any(), any()) } answers {
            handled += firstArg<List<Transaction>>()
            blockNextHandle?.let { release ->
                blockNextHandle = null
                handleEntered.countDown()
                assertTrue(release.await(3, TimeUnit.SECONDS))
            }
            if (failHandle || firstArg<List<Transaction>>().any { it.hashHex == failHandleContaining }) throw IOException("handle failed")
            emptyList()
        }
    }
    private val state = AccountDelegationState()
    private val syncer = DelegationSyncer(storage, blockchain, transactionManager, state, testAccount, chainId = 56, Logger.withTag("Test"))

    @Before
    fun setUp() {
        Logger.setLogWriters(emptyList())
    }

    @Test
    fun getTransactionsSingle_candidateWithOwnTuple_handlesType4WithDelegatesOnly() {
        val candidate = candidate(seed = 1)
        candidates = listOf(candidate)
        rpcResults[candidate.hashHex] = Single.just(delegation(candidate, contract))
        codeResult = Single.just(designator(contract))

        assertEquals(Pair(emptyList<Transaction>(), false), run())

        val row = handled.single().single()
        assertEquals(candidate.hashHex, row.hashHex)
        assertEquals(4, row.type)
        assertArrayEquals(contract.raw, row.authorizationDelegates)
        assertEquals(candidate.timestamp, row.timestamp)
        assertEquals(false, row.isFailed)
        assertEquals(listOf(null, null, null, null, null, null, null), listOf(row.blockNumber, row.from, row.to, row.value, row.input, row.nonce, row.gasUsed))
        assertEquals(CurrentDelegate.Known(contract), state.current)
    }

    @Test
    fun getTransactionsSingle_rpcType2_handlesType2WithoutDelegatesAndNoGetCode() {
        val candidate = candidate(seed = 1)
        candidates = listOf(candidate)
        rpcResults[candidate.hashHex] = Single.just(rpcTransaction(candidate.hash, type = 2, authorizations = null))

        run()

        val row = handled.single().single()
        assertEquals(2, row.type)
        assertNull(row.authorizationDelegates)
        verify(exactly = 0) { blockchain.getCode(any(), any()) }
    }

    @Test
    fun getTransactionsSingle_failingCandidates_leftUntouchedOthersHandled() {
        val invalid = candidate(seed = 1)
        val rpcError = candidate(seed = 2)
        val malformed = candidate(seed = 3)
        val good = candidate(seed = 4)
        candidates = listOf(invalid, rpcError, malformed, good)
        rpcResults[invalid.hashHex] = Single.error(JsonRpc.ResponseError.InvalidResult(null))
        rpcResults[rpcError.hashHex] = Single.error(JsonRpc.ResponseError.RpcError(RpcResponse.Error(-32000, "boom")))
        rpcResults[malformed.hashHex] = Single.just(rpcTransaction(malformed.hash, type = 4, authorizations = listOf(tupleWithoutSignature())))
        rpcResults[good.hashHex] = Single.just(rpcTransaction(good.hash, type = 2, authorizations = null))

        run()

        assertEquals(listOf(good.hashHex), handled.single().map { it.hashHex })
    }

    @Test
    fun getTransactionsSingle_noCandidatesNoCarriers_noRpcCall() {
        run()

        assertEquals(0, getTransactionCalls)
        verify(exactly = 0) { blockchain.getCode(any(), any()) }
        assertEquals(emptyList<List<Transaction>>(), handled)
    }

    @Test
    fun getTransactionsSingle_storedCarriers_oneGetCodePerRunUpdatesState() {
        carriers = listOf(carrier(seed = 9))
        codeResult = Single.just(designator(contract))

        run()

        verify(exactly = 1) { blockchain.getCode(testAccount, DefaultBlockParameter.Latest) }
        assertEquals(CurrentDelegate.Known(contract), state.current)
    }

    @Test
    fun getTransactionsSingle_getCodeFails_stateUnchanged() {
        carriers = listOf(carrier(seed = 9))
        state.current = CurrentDelegate.Known(contract)
        codeResult = Single.error(IOException("offline"))

        run()

        assertEquals(CurrentDelegate.Known(contract), state.current)
    }

    @Test
    fun getTransactionsSingle_currentDelegateChanges_rehandlesCarriersOnlyOnChange() {
        val carrier = carrier(seed = 9)
        carriers = listOf(carrier)
        codeResult = Single.just(designator(contract))

        run()
        run()
        codeResult = Single.just(designator(otherContract))
        run()

        assertEquals(listOf(listOf(carrier.hashHex), listOf(carrier.hashHex)), handled.map { batch -> batch.map { it.hashHex } })
    }

    @Test
    fun getTransactionsSingle_newCarrierWithUnchangedState_rehandlesStoredCarriers() {
        val stored = carrier(seed = 9)
        carriers = listOf(stored)
        codeResult = Single.just(designator(contract))
        run()
        val found = candidate(seed = 1)
        candidates = listOf(found)
        rpcResults[found.hashHex] = Single.just(delegation(found, contract))

        run()

        assertEquals(setOf(found.hashHex, stored.hashHex), handled.last().map { it.hashHex }.toSet())
    }

    @Test
    fun getTransactionsSingle_handleFailedAfterStateChange_nextRunRehandles() {
        val carrier = carrier(seed = 9)
        carriers = listOf(carrier)
        codeResult = Single.just(designator(contract))
        failHandle = true

        assertEquals(Pair(emptyList<Transaction>(), false), run())
        failHandle = false
        run()
        run()

        assertEquals(2, handled.size)
        assertEquals(listOf(carrier.hashHex), handled.last().map { it.hashHex })
    }

    @Test
    fun getTransactionsSingle_newCarrierHandleFailed_rehandledNextRunUntilSuccess() {
        val stored = carrier(seed = 9)
        carriers = listOf(stored)
        codeResult = Single.just(designator(contract))
        run()
        val found = candidate(seed = 1)
        candidates = listOf(found)
        rpcResults[found.hashHex] = Single.just(delegation(found, contract))
        failHandle = true
        run()
        // The row was saved before handle() failed, so it is a stored carrier now, not a candidate.
        candidates = emptyList()
        carriers = listOf(stored, carrier(seed = 1))
        failHandle = false

        run()
        run()

        assertEquals(3, handled.size)
        assertEquals(setOf(stored.hashHex, found.hashHex), handled[2].map { it.hashHex }.toSet())
    }

    @Test
    fun getTransactionsSingle_overlappingRun_returnsEmptyWithoutRpcCall() {
        val candidate = candidate(seed = 1)
        candidates = listOf(candidate)
        val pending = SingleSubject.create<RpcTransaction>()
        rpcResults[candidate.hashHex] = pending
        val first = syncer.getTransactionsSingle().toFuture()
        waitUntil { pending.hasObservers() }

        assertEquals(Pair(emptyList<Transaction>(), false), run())
        assertEquals(1, getTransactionCalls)

        pending.onSuccess(rpcTransaction(candidate.hash, type = 2, authorizations = null))
        first.get(3, TimeUnit.SECONDS)
        // The guard is released in doFinally, just after the first run delivers its result.
        waitUntil { run(); getTransactionCalls == 2 }
    }

    @Test
    fun getTransactionsSingle_disposedWhileHandling_nextRunWaitsAndKeepsRetryFlag() {
        val stored = carrier(seed = 9)
        carriers = listOf(stored)
        codeResult = Single.just(designator(contract))
        val release = CountDownLatch(1)
        blockNextHandle = release
        val first = syncer.getTransactionsSingle().subscribe({}, {})
        assertTrue(handleEntered.await(3, TimeUnit.SECONDS))
        first.dispose()

        assertEquals(Pair(emptyList<Transaction>(), false), run())
        assertEquals(1, bodyRuns)

        val found = candidate(seed = 1)
        candidates = listOf(found)
        rpcResults[found.hashHex] = Single.just(delegation(found, contract))
        failHandleContaining = found.hashHex
        release.countDown()
        waitUntil { run(); handled.size == 2 }
        candidates = emptyList()
        carriers = listOf(stored, carrier(seed = 1))
        failHandleContaining = null
        run()

        assertEquals(setOf(stored.hashHex, found.hashHex), handled.last().map { it.hashHex }.toSet())
    }

    @Test
    fun getTransactionsSingle_disposedBeforeBodyStarts_nextRunExecutes() {
        val io = TestScheduler()
        RxJavaPlugins.setIoSchedulerHandler { io }
        try {
            syncer.getTransactionsSingle().subscribe({}, {}).dispose()
            io.triggerActions()
        } finally {
            RxJavaPlugins.reset()
        }

        run()

        assertEquals(1, bodyRuns)
    }

    private fun run() = syncer.getTransactionsSingle().blockingGet()

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 3_000
        while (!condition()) {
            assertTrue("condition not met in time", System.currentTimeMillis() < deadline)
            Thread.sleep(5)
        }
    }

    private fun candidate(seed: Int) = Transaction(
        ByteArray(32) { seed.toByte() }, timestamp = 1_000L + seed, isFailed = false,
        blockNumber = 100, from = otherContract, to = testAccount, value = BigInteger.TEN, input = byteArrayOf(1, 2)
    )

    private fun carrier(seed: Int) = candidate(seed).copy(type = 4, authorizationDelegates = contract.raw)

    private fun delegation(candidate: Transaction, delegate: Address) =
        rpcTransaction(candidate.hash, type = 4, authorizations = listOf(signAuthorization(chainId = 56, delegate = delegate, nonce = BigInteger.ONE)))

    /** What Gson leaves behind for a tuple whose r/s are missing from the node's JSON. */
    private fun tupleWithoutSignature(): RpcAuthorization =
        EthereumKit.gson.fromJson("""{"chainId":"0x38","address":"${contract.hex}","nonce":"0x1","yParity":"0x0"}""", RpcAuthorization::class.java)

    private fun designator(delegate: Address) = "ef0100".hexStringToByteArray() + delegate.raw

    private val Transaction.hashHex: String get() = hash.toHexString()

    companion object {
        @BeforeClass
        @JvmStatic
        fun setUpClass() {
            EthereumKit.init()
        }
    }
}
