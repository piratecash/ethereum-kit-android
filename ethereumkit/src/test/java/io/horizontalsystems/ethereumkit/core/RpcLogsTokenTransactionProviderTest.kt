package io.horizontalsystems.ethereumkit.core

import co.touchlab.kermit.Logger as KermitLogger
import io.horizontalsystems.ethereumkit.models.Address
import io.horizontalsystems.ethereumkit.models.ProviderTokenTransaction
import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

class RpcLogsTokenTransactionProviderTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        // The module's unit tests have no Android stubs, so a real log writer would hit android.util.Log.
        KermitLogger.setLogWriters(emptyList())
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun getTokenTransactions_sourceWithBasicAuth_sendsAuthorizationHeader() {
        val request = firstRequestOf(auth = "node-secret")

        assertEquals(Credentials.basic("", "node-secret"), request)
    }

    @Test
    fun getTokenTransactions_sourceWithoutAuth_sendsNoAuthorizationHeader() {
        assertNull(firstRequestOf(auth = null))
    }

    @Test
    fun getTokenTransactions_decimalsCallFails_returnsUnresolvedMetaAndCachesIt() {
        val node = TransferNode(failDecimals = true)
        val provider = provider(node)

        val failed = scanSingleTransfer(provider)
        node.failDecimals = false
        val cached = scanSingleTransfer(provider)

        assertFalse(failed.tokenMetaResolved)
        assertEquals(18, failed.tokenDecimal)
        assertFalse(cached.tokenMetaResolved)
        assertEquals(1, node.decimalsCalls.get())
    }

    @Test
    fun getTokenTransactions_decimalsCallSucceeds_returnsResolvedMetaAndCachesIt() {
        val node = TransferNode(failDecimals = false)
        val provider = provider(node)

        val first = scanSingleTransfer(provider)
        scanSingleTransfer(provider)

        assertTrue(first.tokenMetaResolved)
        assertEquals(6, first.tokenDecimal)
        assertEquals(1, node.decimalsCalls.get())
    }

    private fun provider(node: TransferNode): RpcLogsTokenTransactionProvider {
        server.dispatcher = node
        return RpcLogsTokenTransactionProvider(
            uris = listOf(URI(server.url("/").toString())),
            address = ADDRESS,
            chainId = 1
        )
    }

    private fun scanSingleTransfer(provider: RpcLogsTokenTransactionProvider): ProviderTokenTransaction =
        runBlocking { provider.getTokenTransactions(-100L) }.transactions.single()

    /** Answers one incoming Transfer log; the decimals() call fails while [failDecimals] is set. */
    private class TransferNode(@Volatile var failDecimals: Boolean) : Dispatcher() {
        val decimalsCalls = AtomicInteger()

        override fun dispatch(request: RecordedRequest): MockResponse {
            val body = request.body.readUtf8()
            val method = METHOD_REGEX.find(body)?.groupValues?.get(1)
            return when (method) {
                "eth_blockNumber" -> result("\"0x3e8\"")
                "eth_newFilter" -> result("\"0x1\"")
                "eth_getFilterLogs" -> result("[$TRANSFER_LOG]")
                "eth_getBlockByNumber" -> result("""{"number":"0x3e0","timestamp":"0x64"}""")
                "eth_call" -> if (body.contains(DECIMALS_SELECTOR)) decimals() else result("\"0x\"")
                else -> result("null")
            }
        }

        private fun decimals(): MockResponse {
            decimalsCalls.incrementAndGet()
            return if (failDecimals) {
                MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"error":{"code":-32000,"message":"execution reverted"}}""")
            } else {
                result("\"0x${"6".padStart(64, '0')}\"")
            }
        }

        private fun result(json: String) = MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"result":$json}""")
    }

    // The RPC answer is irrelevant here: the header is written before the call can fail.
    private fun firstRequestOf(auth: String?): String? {
        server.enqueue(MockResponse().setResponseCode(500))

        val provider = RpcLogsTokenTransactionProvider(
            uris = listOf(URI(server.url("/").toString())),
            address = ADDRESS,
            chainId = 1,
            auth = auth
        )

        try {
            runBlocking { provider.getTokenTransactions(-100L) }
        } catch (_: Throwable) {
        }

        return server.takeRequest().getHeader("Authorization")
    }

    companion object {
        private val ADDRESS = Address("0x0000000000000000000000000000000000000001")
        private val METHOD_REGEX = Regex("\"method\":\"([a-zA-Z_]+)\"")
        private const val DECIMALS_SELECTOR = "313ce567"
        private val TRANSFER_LOG = """
            {"transactionHash":"0x${"ab".repeat(32)}","transactionIndex":"0x0","logIndex":"0x0",
            "address":"0x${"33".repeat(20)}","blockHash":"0x${"cd".repeat(32)}","blockNumber":"0x3e0",
            "data":"0x${"1".padStart(64, '0')}","removed":false,
            "topics":["0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef",
            "0x${"22".repeat(32)}","0x${"1".padStart(64, '0')}"]}
        """.trimIndent()
    }
}
