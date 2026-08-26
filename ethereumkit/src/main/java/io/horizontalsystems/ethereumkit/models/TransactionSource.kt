package io.horizontalsystems.ethereumkit.models

class TransactionSource(val name: String, val type: SourceType) {

    fun transactionUrl(hash: String) = "${type.txBaseUrl.trimEnd('/')}/tx/$hash"

    sealed class SourceType(open val txBaseUrl: String) {
        class Etherscan(
            val apiBaseUrl: String,
            override val txBaseUrl: String,
            val apiKeys: List<String>
        ) : SourceType(txBaseUrl)

        class Blockscout(
            val apiBaseUrl: String,
            override val txBaseUrl: String,
            val apiKeys: List<String>
        ) : SourceType(txBaseUrl)
    }

    companion object {
        private fun etherscan(name: String, explorerUrl: String, apiKeys: List<String>): TransactionSource {
            return TransactionSource(
                name = name,
                type = SourceType.Etherscan(
                    apiBaseUrl = "https://api.etherscan.io/v2/",
                    txBaseUrl = explorerUrl,
                    apiKeys = apiKeys
                )
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

        fun zkSync(apiKeys: List<String>): TransactionSource {
            return etherscan("era.zksync.network", "https://era.zksync.network", apiKeys)
        }

        fun robinhood(apiKeys: List<String>): TransactionSource {
            val explorerUrl = "https://robinhoodchain.blockscout.com"
            return TransactionSource(
                name = "robinhoodchain.blockscout.com",
                type = SourceType.Blockscout(
                    apiBaseUrl = "$explorerUrl/",
                    txBaseUrl = explorerUrl,
                    apiKeys = apiKeys
                )
            )
        }
    }

}
