package io.horizontalsystems.ethereumkit.models

class TransactionSource(val name: String, val type: SourceType) {

    fun transactionUrl(hash: String) =
        when (type) {
            is SourceType.Etherscan -> "${type.txBaseUrl}/tx/$hash"
        }

    sealed class SourceType {
        class Etherscan(val apiBaseUrl: String, val txBaseUrl: String, val apiKeys: List<String>) :
            SourceType()
    }

    companion object {
        private fun etherscan(
            apiSubdomain: String,
            txSubdomain: String?,
            apiKeys: List<String>,
            name: String?,
            txBaseUrl: String?
        ): TransactionSource {
            return TransactionSource(
                name = name ?: "etherscan.io",
                type = SourceType.Etherscan(
                    apiBaseUrl = "https://$apiSubdomain.etherscan.io/v2/",
                    txBaseUrl = txBaseUrl
                        ?: "https://${txSubdomain?.let { "$it." } ?: ""}etherscan.io",
                    apiKeys = apiKeys)
            )
        }

        fun etherscanApi(apiKeys: List<String>, name: String? = null, txBaseUrl: String? = null): TransactionSource {
            return etherscan(
                apiSubdomain = "api",
                txSubdomain = null,
                apiKeys = apiKeys,
                name = name,
                txBaseUrl = txBaseUrl
            )
        }

    }

}
