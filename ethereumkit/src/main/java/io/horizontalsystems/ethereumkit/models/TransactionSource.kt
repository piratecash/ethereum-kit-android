package io.horizontalsystems.ethereumkit.models

class TransactionSource(
    val name: String,
    val type: SourceType,
    val fallbacks: List<SourceType.Etherscan> = emptyList()
) {

    fun transactionUrl(hash: String) = "${type.txBaseUrl.trimEnd('/')}/tx/$hash"

    sealed class SourceType(open val txBaseUrl: String) {
        class Etherscan(
            val apiBaseUrl: String,
            override val txBaseUrl: String,
            val apiKeys: List<String>,
            val listPageSize: Int = DEFAULT_LIST_PAGE_SIZE
        ) : SourceType(txBaseUrl)
    }

    companion object {
        const val DEFAULT_LIST_PAGE_SIZE = 10_000

        // The official zkSync API rejects `page * offset > 1000` with status 0 "Result window is
        // too large", which would fail every history call.
        private const val ZKSYNC_LIST_PAGE_SIZE = 1_000

        private fun etherscanV2(explorerUrl: String, apiKeys: List<String>) = SourceType.Etherscan(
            apiBaseUrl = "https://api.etherscan.io/v2/",
            txBaseUrl = explorerUrl,
            apiKeys = apiKeys
        )

        private fun etherscan(name: String, explorerUrl: String, apiKeys: List<String>): TransactionSource {
            return TransactionSource(
                name = name,
                type = etherscanV2(explorerUrl, apiKeys)
            )
        }

        fun ethereum(apiKeys: List<String>): TransactionSource {
            return etherscan("etherscan.io", "https://etherscan.io", apiKeys)
        }

        fun binance(apiKeys: List<String>): TransactionSource {
            return etherscan("bscscan.com", "https://bscscan.com", apiKeys)
        }

        fun polygon(apiKeys: List<String>): TransactionSource {
            return etherscan("polygonscan.com", "https://polygonscan.com", apiKeys)
        }

        fun optimism(apiKeys: List<String>): TransactionSource {
            return etherscan("optimistic.etherscan.io", "https://optimistic.etherscan.io", apiKeys)
        }

        fun arbitrumOne(apiKeys: List<String>): TransactionSource {
            return etherscan("arbiscan.io", "https://arbiscan.io", apiKeys)
        }

        fun avalanche(apiKeys: List<String>): TransactionSource {
            return etherscan("snowtrace.io", "https://snowtrace.io", apiKeys)
        }

        fun gnosis(apiKeys: List<String>): TransactionSource {
            return etherscan("gnosisscan.io", "https://gnosisscan.io", apiKeys)
        }

        fun base(apiKeys: List<String>): TransactionSource {
            return etherscan("basescan.org", "https://basescan.org", apiKeys)
        }

        fun fantom(apiKeys: List<String>): TransactionSource {
            return etherscan("ftmscan.com", "https://ftmscan.com", apiKeys)
        }

        // Blockscout PRO API: Etherscan V2-compatible multichain endpoint for chains Etherscan does not index.
        // Public per-instance APIs are deprecated and challenge non-browser clients, so they are not usable for syncing.
        private fun blockscoutPro(explorerHost: String, apiKeys: List<String>) = SourceType.Etherscan(
            apiBaseUrl = "https://api.blockscout.com/v2/",
            txBaseUrl = "https://$explorerHost",
            apiKeys = apiKeys
        )

        // The official zkSync API is keyless and quota-free, so Blockscout credits are only spent when it fails.
        fun zkSync(blockscoutApiKeys: List<String>) = TransactionSource(
            name = "explorer.zksync.io",
            type = SourceType.Etherscan(
                apiBaseUrl = "https://block-explorer-api.mainnet.zksync.io/",
                txBaseUrl = "https://explorer.zksync.io",
                apiKeys = emptyList(),
                listPageSize = ZKSYNC_LIST_PAGE_SIZE
            ),
            fallbacks = listOf(blockscoutPro("zksync.blockscout.com", blockscoutApiKeys))
        )

        fun robinhood(blockscoutApiKeys: List<String>, etherscanApiKeys: List<String>) = TransactionSource(
            name = "robinhoodchain.blockscout.com",
            type = blockscoutPro("robinhoodchain.blockscout.com", blockscoutApiKeys),
            fallbacks = listOf(etherscanV2("https://robin.etherscan.io", etherscanApiKeys))
        )
    }

}
