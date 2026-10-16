package io.horizontalsystems.ethereumkit.models

import androidx.room.Entity
import androidx.room.Ignore
import io.horizontalsystems.ethereumkit.core.toHexString
import java.math.BigInteger
import java.util.*

@Entity(
    primaryKeys = ["hash", "traceId"]
)
data class InternalTransaction(
    val hash: ByteArray,
    val traceId: String,
    val blockNumber: Long,
    val from: Address,
    val to: Address,
    val value: BigInteger
) {

    @delegate:Ignore
    val hashString: String by lazy {
        hash.toHexString()
    }

    override fun equals(other: Any?): Boolean {
        if (other !is InternalTransaction)
            return false

        return hash.contentEquals(other.hash) && traceId == other.traceId
    }

    override fun hashCode(): Int {
        return Objects.hash(hash.contentHashCode(), traceId)
    }
}