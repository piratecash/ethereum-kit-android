package io.horizontalsystems.ethereumkit.models

import io.horizontalsystems.ethereumkit.api.jsonrpc.models.RpcAuthorization
import io.horizontalsystems.ethereumkit.api.jsonrpc.models.RpcTransaction
import io.horizontalsystems.ethereumkit.crypto.CryptoUtils
import io.horizontalsystems.ethereumkit.spv.rlp.RLP
import java.math.BigInteger

private const val SET_CODE_AUTHORIZATION_MAGIC: Byte = 0x05
private val AUTHORIZATION_NONCE_LIMIT = BigInteger.ONE.shiftLeft(64) - BigInteger.ONE
private val DELEGATION_DESIGNATOR = byteArrayOf(0xef.toByte(), 0x01, 0x00)
private const val ADDRESS_SIZE = 20

data class AccountDelegation(val delegate: Address, val isActive: Boolean)

/** Signer of the tuple, or null when EIP-7702 rules make it invalid. */
fun RpcAuthorization.authority(): Address? {
    val recId = yParity
    if (recId == null || recId !in 0L..1L) return null
    if (s > CryptoUtils.HALF_CURVE_ORDER) return null
    if (nonce.signum() < 0 || nonce >= AUTHORIZATION_NONCE_LIMIT) return null
    return CryptoUtils.recoverAddress(signingHash(), r, s, recId.toInt())
}

internal fun RpcAuthorization.signingHash(): ByteArray {
    val payload = RLP.encodeList(RLP.encodeBigInteger(chainId), RLP.encodeElement(address.raw), RLP.encodeBigInteger(nonce))
    return CryptoUtils.sha3(byteArrayOf(SET_CODE_AUTHORIZATION_MAGIC) + payload)
}

/** Delegates of every tuple the account signed for this chain, in list order; says nothing about which one the chain applied. */
fun RpcTransaction.ownDelegates(account: Address, chainId: Long): List<Address> {
    val chain = BigInteger.valueOf(chainId)
    return authorizationList.orEmpty()
        .filter { (it.chainId.signum() == 0 || it.chainId == chain) && it.authority() == account }
        .map { it.address }
}

/** Stored form of [Transaction.authorizationDelegates]: concatenated 20-byte addresses, null when empty. */
internal fun List<Address>.toAuthorizationDelegates(): ByteArray? =
    takeIf { it.isNotEmpty() }?.fold(ByteArray(0)) { blob, address -> blob + address.raw }

internal fun ByteArray.toDelegates(): List<Address> =
    (0 until size / ADDRESS_SIZE).map { Address(copyOfRange(it * ADDRESS_SIZE, (it + 1) * ADDRESS_SIZE)) }

internal val Address.isZero: Boolean
    get() = raw.all { it == 0.toByte() }

fun currentDelegate(code: ByteArray): Address? {
    if (code.size != DELEGATION_DESIGNATOR.size + ADDRESS_SIZE) return null
    if (!code.copyOfRange(0, DELEGATION_DESIGNATOR.size).contentEquals(DELEGATION_DESIGNATOR)) return null
    return Address(code.copyOfRange(DELEGATION_DESIGNATOR.size, code.size))
}
