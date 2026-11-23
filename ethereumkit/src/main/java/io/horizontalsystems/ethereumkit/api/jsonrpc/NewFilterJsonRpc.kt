package io.horizontalsystems.ethereumkit.api.jsonrpc

class NewFilterJsonRpc(
    @Transient val fromBlock: Long,
    @Transient val toBlock: Long,
    @Transient val topics: List<Any?>
) : JsonRpc<String>(
    method = "eth_newFilter",
    params = listOf(
        mapOf(
            "fromBlock" to "0x${fromBlock.toString(16)}",
            "toBlock" to "0x${toBlock.toString(16)}",
            "topics" to topics
        )
    )
) {
    @Transient
    override val typeOfResult = String::class.java
}
