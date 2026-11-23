package io.horizontalsystems.ethereumkit.core

import io.horizontalsystems.ethereumkit.api.jsonrpc.JsonRpc
import io.reactivex.Single
import java.util.Locale
import java.util.concurrent.TimeUnit

// Whole phrases only: the refusal "unknown transaction type" contains "known transaction".
private val knownTransactionMessage =
    Regex("(?<![a-z])(already known|alreadyknown|already imported|known transaction)(?![a-z])")

private val permanentErrorMessages = listOf(
    "invalid sender",
    "nonce too low",
    "transaction underpriced",
    "replacement transaction underpriced",
    "insufficient funds",
    "intrinsic gas too low",
    "exceeds block gas limit",
    "chain id",
    "unknown transaction type",
    "transaction type not supported",
)

internal fun Throwable.isKnownTransactionError(): Boolean =
    knownTransactionMessage.containsMatchIn(rpcMessage())

internal fun Throwable.isPermanentBroadcastError(): Boolean {
    val message = rpcMessage()
    return permanentErrorMessages.any { message.contains(it) }
}

internal fun IBlockchain.transactionExists(hash: ByteArray, timeoutMs: Long): Single<Boolean> {
    return getTransaction(hash)
        .timeout(timeoutMs, TimeUnit.MILLISECONDS)
        .map { true }
        .onErrorResumeNext {
            getTransactionReceipt(hash)
                .timeout(timeoutMs, TimeUnit.MILLISECONDS)
                .map { true }
                .onErrorReturnItem(false)
        }
}

private fun Throwable.rpcMessage(): String {
    return when (this) {
        is JsonRpc.ResponseError.RpcError -> error.message
        else -> message.orEmpty()
    }.lowercase(Locale.US)
}
