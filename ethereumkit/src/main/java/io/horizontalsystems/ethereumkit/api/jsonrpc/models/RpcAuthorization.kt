package io.horizontalsystems.ethereumkit.api.jsonrpc.models

import io.horizontalsystems.ethereumkit.models.Address
import java.math.BigInteger

class RpcAuthorization(
    val chainId: BigInteger,
    val address: Address,
    val nonce: BigInteger,
    val yParity: Long?,
    val r: BigInteger,
    val s: BigInteger,
)
