package io.horizontalsystems.ethereumkit.network

import co.touchlab.kermit.Logger as KermitLogger
import io.horizontalsystems.ethereumkit.models.Address
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger

class EtherscanServiceTest {

    private lateinit var server: MockWebServer

    private val httpLogs = mutableListOf<String>()
    private val timberLogs = mutableListOf<String>()

    private val logHandler = object : Handler() {
        override fun publish(record: LogRecord) {
            httpLogs.add(record.message)
        }

        override fun flush() = Unit
        override fun close() = Unit
    }

    private val timberTree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            timberLogs.add(message)
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        // The module's unit tests have no Android stubs, so a real log writer would hit android.util.Log.
        KermitLogger.setLogWriters(emptyList())
        Logger.getLogger("EtherscanService").addHandler(logHandler)
        Timber.plant(timberTree)
    }

    @After
    fun tearDown() {
        server.shutdown()
        Logger.getLogger("EtherscanService").removeHandler(logHandler)
        Timber.uproot(timberTree)
    }

    @Test
    fun getTransactionList_rateLimited_retriesWithNextApiKey() {
        server.enqueue(jsonResponse(429, """{"error":"Too Many Requests"}"""))
        enqueueTransactionList()

        val response = service().getTransactionList(ADDRESS, 0).blockingGet()

        assertEquals(listOf("10"), response.result.map { it.getValue("blockNumber") })
        assertEquals(2, server.requestCount)
        assertNotEquals(apiKeyOf(server.takeRequest()), apiKeyOf(server.takeRequest()))
    }

    // Etherscan answers HTTP 200 with a NOTOK envelope and keeps changing the wording
    // ("Max rate limit reached", "Max calls per sec rate limit reached (3/sec)"), so matching the
    // exact old text turned a throttle into a hard failure of the whole source chain.
    @Test
    fun getTransactionList_perSecondRateLimitEnvelope_retriesInsteadOfFailing() {
        server.enqueue(
            jsonResponse(
                200,
                """{"status":"0","message":"NOTOK","result":"Max calls per sec rate limit reached (3/sec)"}"""
            )
        )
        enqueueTransactionList()

        val response = service().getTransactionList(ADDRESS, 0).blockingGet()

        assertEquals(listOf("10"), response.result.map { it.getValue("blockNumber") })
        assertEquals(2, server.requestCount)
    }

    @Test
    fun getTransactionList_unauthorized_retriesWithNextApiKey() {
        server.enqueue(jsonResponse(401, """{"error":"Unauthorized"}"""))
        enqueueTransactionList()

        val response = service().getTransactionList(ADDRESS, 0).blockingGet()

        assertEquals(listOf("10"), response.result.map { it.getValue("blockNumber") })
        assertEquals(2, server.requestCount)
        assertNotEquals(apiKeyOf(server.takeRequest()), apiKeyOf(server.takeRequest()))
    }

    @Test
    fun getTransactionList_unauthorized_singleKey_failsWithoutRetry() {
        server.enqueue(jsonResponse(401, """{"error":"Unauthorized"}"""))

        assertFailsWithInvalidApiKey(service(listOf(FIRST_KEY)))

        assertEquals(1, server.requestCount)
    }

    @Test
    fun getTransactionList_unauthorized_twoKeys_failsAfterEachKeyOnce() {
        repeat(2) { server.enqueue(jsonResponse(401, """{"error":"Unauthorized"}""")) }

        assertFailsWithInvalidApiKey(service())

        assertEquals(2, server.requestCount)
    }

    @Test
    fun getTransactionList_sendsPageAndOffset() {
        enqueueTransactionList()

        service().getTransactionList(ADDRESS, 0).blockingGet()

        val url = server.takeRequest().requestUrl
        assertEquals("1", url?.queryParameter("page"))
        assertEquals("10000", url?.queryParameter("offset"))
    }

    @Test
    fun getTransactionList_blankApiKeys_sendsNoApiKeyAndDoesNotCrash() {
        enqueueTransactionList()

        val response = service(listOf("", " ")).getTransactionList(ADDRESS, 0).blockingGet()

        assertEquals(1, response.result.size)
        assertNull(apiKeyOf(server.takeRequest()))
    }

    @Test
    fun getTokenTransactions_noTokenTransfersFound_returnsEmptyList() {
        server.enqueue(jsonResponse(200, """{"status":"0","message":"No token transfers found","result":[]}"""))

        val response = service().getTokenTransactions(ADDRESS, 0).blockingGet()

        assertEquals("0", response.status)
        assertTrue(response.result.isEmpty())
    }

    @Test
    fun getInternalTransactionList_requestsAscendingOrder() {
        enqueueTransactionList()
        enqueueTransactionList()
        val service = service()

        service.getInternalTransactionList(ADDRESS, 0).blockingGet()
        service.getTransactionList(ADDRESS, 0).blockingGet()

        assertEquals("asc", server.takeRequest().requestUrl?.queryParameter("sort"))
        assertEquals("desc", server.takeRequest().requestUrl?.queryParameter("sort"))
    }

    @Test
    fun getTransactionList_rateLimited_logsMaskedKeyOnly() {
        server.enqueue(jsonResponse(429, """{"error":"Too Many Requests"}"""))
        enqueueTransactionList()

        service().getTransactionList(ADDRESS, 0).blockingGet()

        val leaked = (httpLogs + timberLogs).filter { it.contains(FIRST_KEY) || it.contains(SECOND_KEY) }
        assertEquals(emptyList<String>(), leaked)
        assertTrue("no masked apikey in $httpLogs", httpLogs.any { it.contains("apikey=***") })
    }

    @Test
    fun getTransactionList_configuredPageSize_isSentAsOffset() {
        enqueueTransactionList()

        EtherscanService(server.url("/").toString(), emptyList(), CHAIN_ID, listPageSize = 1_000)
            .getTransactionList(ADDRESS, 0)
            .blockingGet()

        val url = server.takeRequest().requestUrl
        assertEquals("1", url?.queryParameter("page"))
        assertEquals("1000", url?.queryParameter("offset"))
    }

    private fun assertFailsWithInvalidApiKey(service: EtherscanService) {
        val error = try {
            service.getTransactionList(ADDRESS, 0).blockingGet()
            null
        } catch (thrown: Throwable) {
            thrown
        }

        assertTrue(
            "expected InvalidApiKey, got $error",
            generateSequence(error) { it.cause }.any { it is EtherscanService.RequestError.InvalidApiKey }
        )
    }

    private fun service(apiKeys: List<String> = listOf(FIRST_KEY, SECOND_KEY)) =
        EtherscanService(server.url("/").toString(), apiKeys, CHAIN_ID)

    private fun apiKeyOf(request: RecordedRequest) = request.requestUrl?.queryParameter("apikey")

    private fun enqueueTransactionList() = server.enqueue(
        jsonResponse(200, """{"status":"1","message":"OK","result":[{"hash":"$HASH","blockNumber":"10"}]}""")
    )

    private fun jsonResponse(code: Int, body: String) = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    companion object {
        private const val FIRST_KEY = "key-one"
        private const val SECOND_KEY = "key-two"
        private const val CHAIN_ID = 1
        private const val HASH =
            "0x0000000000000000000000000000000000000000000000000000000000000001"
        private val ADDRESS = Address("0x0000000000000000000000000000000000000001")
    }
}
