package io.horizontalsystems.ethereumkit.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class RawTransactionBroadcastRecord(
    @PrimaryKey
    val hash: ByteArray,
    val rawTransaction: ByteArray,
    val firstSendTime: Long,
    val lastSendTime: Long,
    val retriesCount: Int,
    val expiresAt: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RawTransactionBroadcastRecord) return false

        return hash.contentEquals(other.hash) &&
                rawTransaction.contentEquals(other.rawTransaction) &&
                firstSendTime == other.firstSendTime &&
                lastSendTime == other.lastSendTime &&
                retriesCount == other.retriesCount &&
                expiresAt == other.expiresAt
    }

    override fun hashCode(): Int {
        var result = hash.contentHashCode()
        result = 31 * result + rawTransaction.contentHashCode()
        result = 31 * result + firstSendTime.hashCode()
        result = 31 * result + lastSendTime.hashCode()
        result = 31 * result + retriesCount
        result = 31 * result + expiresAt.hashCode()
        return result
    }
}
