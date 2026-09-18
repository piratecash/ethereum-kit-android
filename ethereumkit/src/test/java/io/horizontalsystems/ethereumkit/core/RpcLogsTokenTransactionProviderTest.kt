package io.horizontalsystems.ethereumkit.core

import io.horizontalsystems.ethereumkit.models.Address
import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.net.URI

class RpcLogsTokenTransactionProviderTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
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
    }
}
