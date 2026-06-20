package io.horizontalsystems.ethereumkit.models

data class RawTransactionBroadcastResult(
    val transactionHash: ByteArray,
    val status: RawTransactionBroadcastStatus,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RawTransactionBroadcastResult) return false

        return transactionHash.contentEquals(other.transactionHash) &&
                status == other.status
    }

    override fun hashCode(): Int {
        var result = transactionHash.contentHashCode()
        result = 31 * result + status.hashCode()
        return result
    }
}

enum class RawTransactionBroadcastStatus {
    Submitted,
    Queued,
}
