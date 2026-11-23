package io.horizontalsystems.ethereumkit.models

import io.horizontalsystems.ethereumkit.api.jsonrpc.models.RpcAuthorization
import io.horizontalsystems.ethereumkit.api.jsonrpc.models.RpcTransaction
import io.horizontalsystems.ethereumkit.crypto.CryptoUtils
import java.math.BigInteger

/** Hardhat/anvil account #0; [signAuthorization] signs as this account. */
internal val testAccount = Address("0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266")
private val testPrivateKey = BigInteger("ac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80", 16)

internal fun signAuthorization(chainId: Long, delegate: Address, nonce: BigInteger): RpcAuthorization {
    val unsigned = RpcAuthorization(BigInteger.valueOf(chainId), delegate, nonce, 0, BigInteger.ONE, BigInteger.ONE)
    val signature = CryptoUtils.ellipticSign(unsigned.signingHash(), testPrivateKey)
    return RpcAuthorization(
        chainId = unsigned.chainId,
        address = delegate,
        nonce = nonce,
        yParity = signature[64].toLong(),
        r = BigInteger(1, signature.copyOfRange(0, 32)),
        s = BigInteger(1, signature.copyOfRange(32, 64)),
    )
}

internal fun rpcTransaction(hash: ByteArray, type: Long?, authorizations: List<RpcAuthorization>?) = RpcTransaction(
    hash = hash,
    nonce = 0,
    blockHash = null,
    blockNumber = null,
    transactionIndex = null,
    from = Address("0xfcfee4726b59b02b5aaed7bc996ee468157886c6"),
    to = testAccount,
    value = BigInteger.ZERO,
    gasPrice = 0,
    maxFeePerGas = null,
    maxPriorityFeePerGas = null,
    gasLimit = 0,
    input = ByteArray(0),
    type = type,
    authorizationList = authorizations,
)
