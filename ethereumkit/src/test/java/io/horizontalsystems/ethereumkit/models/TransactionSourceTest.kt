package io.horizontalsystems.ethereumkit.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionSourceTest {

    @Test
    fun robinhood_primaryBlockscout_fallbackEtherscanV2() {
        val source = TransactionSource.robinhood(listOf(BLOCKSCOUT_KEY), listOf(ETHERSCAN_KEY))

        val primary = source.type as TransactionSource.SourceType.Etherscan
        assertEquals("https://api.blockscout.com/v2/", primary.apiBaseUrl)
        assertEquals(listOf(BLOCKSCOUT_KEY), primary.apiKeys)

        val fallback = source.fallbacks.single()
        assertEquals("https://api.etherscan.io/v2/", fallback.apiBaseUrl)
        assertEquals("https://robin.etherscan.io", fallback.txBaseUrl)
        assertEquals(listOf(ETHERSCAN_KEY), fallback.apiKeys)
    }

    @Test
    fun zkSync_primaryOfficialApi_fallbackBlockscout() {
        val source = TransactionSource.zkSync(listOf(BLOCKSCOUT_KEY))

        val primary = source.type as TransactionSource.SourceType.Etherscan
        assertEquals("https://block-explorer-api.mainnet.zksync.io/", primary.apiBaseUrl)
        assertTrue(primary.apiKeys.isEmpty())

        val fallback = source.fallbacks.single()
        assertEquals("https://api.blockscout.com/v2/", fallback.apiBaseUrl)
        assertEquals(listOf(BLOCKSCOUT_KEY), fallback.apiKeys)

        assertEquals("https://explorer.zksync.io/tx/$HASH", source.transactionUrl(HASH))
    }

    // The official zkSync API answers `page * offset > 1000` with status 0 "Result window is too
    // large", which would fail every history call and silently drop the chain onto its fallback.
    @Test
    fun zkSync_primaryPageSize_staysWithinOfficialApiWindow() {
        val source = TransactionSource.zkSync(listOf(BLOCKSCOUT_KEY))

        val primary = source.type as TransactionSource.SourceType.Etherscan
        assertEquals(1_000, primary.listPageSize)
        assertEquals(TransactionSource.DEFAULT_LIST_PAGE_SIZE, source.fallbacks.single().listPageSize)
    }

    @Test
    fun robinhood_pageSize_usesDefaultForBothSources() {
        val source = TransactionSource.robinhood(listOf(BLOCKSCOUT_KEY), listOf(ETHERSCAN_KEY))

        val primary = source.type as TransactionSource.SourceType.Etherscan
        assertEquals(TransactionSource.DEFAULT_LIST_PAGE_SIZE, primary.listPageSize)
        assertEquals(TransactionSource.DEFAULT_LIST_PAGE_SIZE, source.fallbacks.single().listPageSize)
    }

    companion object {
        private const val BLOCKSCOUT_KEY = "blockscout-key"
        private const val ETHERSCAN_KEY = "etherscan-key"
        private const val HASH = "0x01"
    }
}
