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
    fun robinhoodSource_trailingSlashInExplorerUrl_buildsCanonicalTransactionUrl() {
        val source = TransactionSource(
            name = "Blockscout",
            type = TransactionSource.SourceType.Blockscout(
                apiBaseUrl = "https://example.com/",
                txBaseUrl = "https://example.com/",
                apiKeys = emptyList()
            )
        )

        assertEquals("https://example.com/tx/0x1234", source.transactionUrl("0x1234"))
    }

    @Test
    fun robinhoodSource_factoryUsesOfficialBlockscoutEndpoints() {
        val source = TransactionSource.robinhood(emptyList())
        val type = source.type as TransactionSource.SourceType.Blockscout

        assertEquals("robinhoodchain.blockscout.com", source.name)
        assertEquals("https://robinhoodchain.blockscout.com/", type.apiBaseUrl)
        assertEquals("https://robinhoodchain.blockscout.com", type.txBaseUrl)
        assertEquals(
            "https://robinhoodchain.blockscout.com/tx/0x1234",
            source.transactionUrl("0x1234")
        )
    }
}
