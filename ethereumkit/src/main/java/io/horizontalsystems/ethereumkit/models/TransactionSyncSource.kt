package io.horizontalsystems.ethereumkit.models

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class SyncSource {
    ETHERSCAN,
    ERC20_SYNCER,
    MERKLE,
    RPC
}

@Entity(tableName = "TransactionSyncSource")
data class TransactionSyncSource(
    @PrimaryKey
    val transactionHash: ByteArray,
    val source: SyncSource
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as TransactionSyncSource
        return transactionHash.contentEquals(other.transactionHash)
    }

    override fun hashCode(): Int = transactionHash.contentHashCode()
}
