package io.horizontalsystems.ethereumkit.api.jsonrpc

import io.horizontalsystems.ethereumkit.models.Address
import io.horizontalsystems.ethereumkit.models.DefaultBlockParameter

class GetCodeJsonRpc(
        @Transient val address: Address,
        @Transient val defaultBlockParameter: DefaultBlockParameter
) : DataJsonRpc(
        method = "eth_getCode",
        params = listOf(address, defaultBlockParameter)
)
