package io.horizontalsystems.ethereumkit.core

import io.horizontalsystems.ethereumkit.api.models.EtherscanResponse
import io.horizontalsystems.ethereumkit.models.Address
import io.horizontalsystems.ethereumkit.network.EtherscanService
import io.mockk.every
import io.mockk.mockk
import io.reactivex.Single
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EtherscanTransactionProviderTest {

    private val address = Address("0x0000000000000000000000000000000000000001")
    private val etherscanHash = ByteArray(32) { 1 }
    private val blockscoutHash = ByteArray(32) { 2 }

    @Test
    fun getInternalTransactions_etherscanAndBlockscoutShapes_mapHashAndTraceId() {
        val etherscanShaped = mapOf(
            "hash" to etherscanHash.toHexString(),
            "traceId" to "0",
            "blockNumber" to "10",
            "timeStamp" to "1000",
            "from" to address.hex,
            "to" to address.hex,
            "value" to "1"
        )
        val blockscoutShaped = mapOf(
            "transactionHash" to blockscoutHash.toHexString(),
            "index" to "2",
            "blockNumber" to "11",
            "timeStamp" to "1001",
            "from" to address.hex,
            "to" to address.hex,
            "value" to "2"
        )
        val service = mockk<EtherscanService>()
        every { service.getInternalTransactionList(address, 0) } returns
            Single.just(EtherscanResponse("1", "OK", listOf(etherscanShaped, blockscoutShaped)))

        val transactions = EtherscanTransactionProvider(service, address).getInternalTransactions(0).blockingGet()

        assertEquals(2, transactions.size)
        assertEquals(etherscanHash.toHexString(), transactions[0].hash.toHexString())
        assertEquals("0", transactions[0].traceId)
        assertEquals(blockscoutHash.toHexString(), transactions[1].hash.toHexString())
        assertEquals("2", transactions[1].traceId)
    }

    @Test
    fun getTokenTransactions_validTransfer_doesNotInventInput() {
        val tokenTransfer = mapOf(
            "blockNumber" to "10",
            "timeStamp" to "1000",
            "hash" to etherscanHash.toHexString(),
            "nonce" to "1",
            "blockHash" to etherscanHash.toHexString(),
            "from" to address.hex,
            "contractAddress" to address.hex,
            "to" to address.hex,
            "value" to "1",
            "tokenName" to "Test",
            "tokenSymbol" to "TST",
            "tokenDecimal" to "18",
            "transactionIndex" to "0",
            "gas" to "21000",
            "gasPrice" to "1000",
            "gasUsed" to "21000",
            "cumulativeGasUsed" to "21000"
        )
        val service = mockk<EtherscanService>()
        every { service.getTokenTransactions(address, 0) } returns
            Single.just(EtherscanResponse("1", "OK", listOf(tokenTransfer)))

        val transactions = EtherscanTransactionProvider(service, address).getTokenTransactions(0).blockingGet()

        assertEquals(1, transactions.size)
        assertNull(transactions.single().input)
    }
}
