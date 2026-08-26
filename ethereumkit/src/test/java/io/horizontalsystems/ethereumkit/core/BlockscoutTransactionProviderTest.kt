package io.horizontalsystems.ethereumkit.core

import io.horizontalsystems.ethereumkit.models.Address
import io.horizontalsystems.ethereumkit.network.BlockscoutService
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.math.BigInteger

class BlockscoutTransactionProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: BlockscoutTransactionProvider

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        provider = BlockscoutTransactionProvider(
            BlockscoutService(server.url("/").toString(), emptyList()),
            Address(WALLET_ADDRESS)
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun getTransactions_mixedStatusesAndMalformedScalar_mapsValidItems() {
        enqueueItems(
            transactionJson(1, "ok"),
            transactionJson(2, "error"),
            transactionJson(3, "pending"),
            transactionJson(4, "ok", "\"invalid\"")
        )

        val transactions = provider.getTransactions(0).blockingGet()

        assertEquals(3, transactions.size)
        assertEquals(0, transactions[0].isError)
        assertEquals(1, transactions[0].txReceiptStatus)
        assertEquals(1, transactions[1].isError)
        assertEquals(0, transactions[1].txReceiptStatus)
        assertNull(transactions[2].isError)
        assertNull(transactions[2].txReceiptStatus)
    }

    @Test
    fun getTokenTransactions_validTransfer_mapsErc20WithoutInventingInput() {
        enqueueItems(tokenTransferJson())

        val transactions = provider.getTokenTransactions(0).blockingGet()

        assertEquals(1, transactions.size)
        val transaction = transactions.single()
        assertEquals(BigInteger.valueOf(42), transaction.value)
        assertEquals("Test Token", transaction.tokenName)
        assertEquals("TEST", transaction.tokenSymbol)
        assertEquals(18, transaction.tokenDecimal)
        assertNull(transaction.input)
        assertEquals("ERC-20", server.takeRequest().requestUrl?.queryParameter("type"))
    }

    private fun enqueueItems(vararg items: String) {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "items": [${items.joinToString()}],
                      "next_page_params": null
                    }
                    """.trimIndent()
                )
        )
    }

    private fun transactionJson(
        index: Int,
        status: String,
        blockNumber: String = index.toString()
    ) =
        """
        {
          "hash": "${hash(index)}",
          "block_number": $blockNumber,
          "timestamp": "2026-08-26T00:00:00Z",
          "nonce": $index,
          "position": $index,
          "from": {"hash": "$WALLET_ADDRESS"},
          "to": {"hash": "$RECIPIENT_ADDRESS"},
          "value": "42",
          "gas_limit": "21000",
          "gas_price": "1000000000",
          "gas_used": "20000",
          "status": "$status",
          "raw_input": "0x1234"
        }
        """.trimIndent()

    private fun tokenTransferJson() =
        """
        {
          "transaction_hash": "${hash(5)}",
          "block_number": 5,
          "block_hash": "${hash(6)}",
          "timestamp": "2026-08-26T00:00:00Z",
          "from": {"hash": "$WALLET_ADDRESS"},
          "to": {"hash": "$RECIPIENT_ADDRESS"},
          "token": {
            "address_hash": "$TOKEN_ADDRESS",
            "name": "Test Token",
            "symbol": "TEST",
            "decimals": "18"
          },
          "total": {"value": "42", "token_id": null}
        }
        """.trimIndent()

    private fun hash(value: Int) = "0x${value.toString(16).padStart(64, '0')}"

    companion object {
        private const val WALLET_ADDRESS = "0x0000000000000000000000000000000000000001"
        private const val RECIPIENT_ADDRESS = "0x0000000000000000000000000000000000000002"
        private const val TOKEN_ADDRESS = "0x0000000000000000000000000000000000000003"
    }
}
