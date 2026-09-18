package io.horizontalsystems.ethereumkit.models

import androidx.room.Entity
import androidx.room.Ignore
import io.horizontalsystems.ethereumkit.core.toHexString
import java.math.BigInteger

@Entity(
    primaryKeys = ["hash", "contractAddress", "from", "to", "value"]
)
class Eip20Event(
    val hash: ByteArray,
    val blockNumber: Long,
    val contractAddress: Address,
    val from: Address,
    val to: Address,
    val value: BigInteger,

    val tokenName: String,
    val tokenSymbol: String,
    val tokenDecimal: Int,
) {

    @delegate:Ignore
    val hashString: String by lazy {
        hash.toHexString()
    }

}
