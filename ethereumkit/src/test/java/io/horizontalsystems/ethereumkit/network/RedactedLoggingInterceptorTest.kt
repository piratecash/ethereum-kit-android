package io.horizontalsystems.ethereumkit.network

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger as KermitLogger
import co.touchlab.kermit.Severity
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RedactedLoggingInterceptorTest {

    private lateinit var server: MockWebServer
    private val logs = mutableListOf<String>()

    private val captureWriter = object : LogWriter() {
        override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
            logs.add(message)
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        KermitLogger.setLogWriters(listOf(captureWriter))
    }

    @After
    fun tearDown() {
        server.shutdown()
        KermitLogger.setLogWriters(emptyList())
    }

    @Test
    fun hostOnly_urlWithApiKeyInPathAndQuery_returnsSchemeAndHostOnly() {
        val url = server.url("/v3/super-secret-key").newBuilder()
            .addQueryParameter("apikey", "another-secret")
            .build()

        assertEquals("http://${url.host}", url.hostOnly())
    }

    @Test
    fun intercept_urlWithApiKeyInPathAndQuery_logsNoPathOrQuery() {
        server.enqueue(MockResponse().setResponseCode(200))

        val client = OkHttpClient.Builder()
            .addInterceptor(RedactedLoggingInterceptor(KermitLogger.withTag("Test")))
            .build()

        val url = server.url("/v3/super-secret-key").newBuilder()
            .addQueryParameter("apikey", "another-secret")
            .build()

        client.newCall(Request.Builder().url(url).build()).execute().close()

        val leaked = logs.filter { it.contains("super-secret-key") || it.contains("another-secret") }
        assertEquals(emptyList<String>(), leaked)
        assertTrue("expected request/response log lines in $logs", logs.any { it.contains("-->") })
        assertFalse("no log line may contain a path", logs.any { it.contains("/v3/") })
    }
}
