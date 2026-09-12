package io.horizontalsystems.ethereumkit.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RobinhoodChainTest {

    @Test
    fun robinhoodChain_configuration_matchesMainnetContract() {
        val chain = Chain.RobinhoodChain

        assertEquals(4663, chain.id)
        assertEquals(60, chain.coinType)
        assertEquals(10_000_000, chain.gasLimit)
        assertEquals(15, chain.syncInterval)
        assertTrue(chain.isEIP1559Supported)
        assertTrue(chain.isMainNet)
    }

    @Test
    fun transactionSource_trailingSlashInExplorerUrl_buildsCanonicalTransactionUrl() {
        val source = TransactionSource(
            name = "example.com",
            type = TransactionSource.SourceType.Etherscan(
                apiBaseUrl = "https://example.com/",
                txBaseUrl = "https://example.com/",
                apiKeys = emptyList()
            )
        )

        assertEquals("https://example.com/tx/0x1234", source.transactionUrl("0x1234"))
    }

    @Test
    fun robinhoodSource_factoryUsesBlockscoutProEndpoints() {
        val apiKeys = listOf("key1", "key2")
        val source = TransactionSource.robinhood(apiKeys)
        val type = source.type as TransactionSource.SourceType.Etherscan

        assertEquals("robinhoodchain.blockscout.com", source.name)
        assertEquals("https://api.blockscout.com/v2/", type.apiBaseUrl)
        assertEquals("https://robinhoodchain.blockscout.com", type.txBaseUrl)
        assertEquals(apiKeys, type.apiKeys)
        assertEquals(
            "https://robinhoodchain.blockscout.com/tx/0x1234",
            source.transactionUrl("0x1234")
        )
    }

    @Test
    fun zkSyncSource_factoryUsesBlockscoutProEndpoints() {
        val apiKeys = listOf("key1", "key2")
        val source = TransactionSource.zkSync(apiKeys)
        val type = source.type as TransactionSource.SourceType.Etherscan

        assertEquals("zksync.blockscout.com", source.name)
        assertEquals("https://api.blockscout.com/v2/", type.apiBaseUrl)
        assertEquals("https://zksync.blockscout.com", type.txBaseUrl)
        assertEquals(apiKeys, type.apiKeys)
        assertEquals(
            "https://zksync.blockscout.com/tx/0x1234",
            source.transactionUrl("0x1234")
        )
    }
}
