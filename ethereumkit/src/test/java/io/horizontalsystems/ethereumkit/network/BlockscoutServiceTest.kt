package io.horizontalsystems.ethereumkit.network

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class BlockscoutServiceTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun getTransactions_pageCrossesStartBlock_keepsBoundaryAndStopsPaging() {
        enqueuePage(
            items = listOf(transactionJson(101), transactionJson(100), transactionJson(99)),
            nextPageParams = """{"block_number":99,"index":3}"""
        )

        val transactions = service().getTransactions(ADDRESS, 100).blockingGet()

        assertEquals(listOf(101L, 100L), transactions.map { it.blockNumber })
        assertEquals(1, server.requestCount)
        assertEquals(
            "/api/v2/addresses/$ADDRESS/transactions",
            server.takeRequest().path
        )
    }

    @Test
    fun getTransactions_cursorAvailable_fetchesNextPageWithFirstNonBlankApiKey() {
        enqueuePage(
            items = listOf(transactionJson(102)),
            nextPageParams = """{"block_number":101,"index":7}"""
        )
        enqueuePage(items = listOf(transactionJson(101)), nextPageParams = "null")

        val transactions = service(listOf("", "test-key")).getTransactions(ADDRESS, 100).blockingGet()

        assertEquals(listOf(102L, 101L), transactions.map { it.blockNumber })
        assertEquals(2, server.requestCount)

        val firstRequest = server.takeRequest()
        assertEquals("test-key", firstRequest.requestUrl?.queryParameter("apikey"))
        assertEquals("Mobile App Agent", firstRequest.getHeader("User-Agent"))

        val secondRequest = server.takeRequest()
        assertEquals("101", secondRequest.requestUrl?.queryParameter("block_number"))
        assertEquals("7", secondRequest.requestUrl?.queryParameter("index"))
        assertEquals("test-key", secondRequest.requestUrl?.queryParameter("apikey"))
    }

    @Test
    fun getTransactions_moreThanTwentyPages_fetchesUntilCursorEnds() {
        (25 downTo 5).forEach { blockNumber ->
            val nextPageParams = if (blockNumber == 5) {
                "null"
            } else {
                """{"block_number":${blockNumber - 1}}"""
            }
            enqueuePage(listOf(transactionJson(blockNumber.toLong())), nextPageParams)
        }

        val transactions = service().getTransactions(ADDRESS, 1).blockingGet()

        assertEquals((25L downTo 5L).toList(), transactions.map { it.blockNumber })
        assertEquals(21, server.requestCount)
    }

    @Test
    fun getTransactions_repeatedCursor_stopsWithoutLooping() {
        val cursor = """{"block_number":100}"""
        enqueuePage(listOf(transactionJson(101)), cursor)
        enqueuePage(listOf(transactionJson(100)), cursor)

        val transactions = service().getTransactions(ADDRESS, 1).blockingGet()

        assertEquals(listOf(101L, 100L), transactions.map { it.blockNumber })
        assertEquals(2, server.requestCount)
    }

    private fun service(apiKeys: List<String> = emptyList()): BlockscoutService {
        val baseUrlWithoutTrailingSlash = server.url("/").toString().trimEnd('/')
        return BlockscoutService(baseUrlWithoutTrailingSlash, apiKeys)
    }

    private fun enqueuePage(items: List<String>, nextPageParams: String) {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "items": [${items.joinToString()}],
                      "next_page_params": $nextPageParams
                    }
                    """.trimIndent()
                )
        )
    }

    private fun transactionJson(blockNumber: Long) =
        """{"block_number":$blockNumber,"hash":"$HASH"}"""

    companion object {
        private const val ADDRESS = "0x0000000000000000000000000000000000000001"
        private const val HASH =
            "0x0000000000000000000000000000000000000000000000000000000000000001"
    }
}
