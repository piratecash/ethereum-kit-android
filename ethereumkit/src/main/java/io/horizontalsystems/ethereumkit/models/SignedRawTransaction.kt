package io.horizontalsystems.ethereumkit.models

data class SignedRawTransaction(
    val raw: ByteArray,
    val hash: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SignedRawTransaction) return false

        return raw.contentEquals(other.raw) &&
                hash.contentEquals(other.hash)
    }

    override fun hashCode(): Int {
        var result = raw.contentHashCode()
        result = 31 * result + hash.contentHashCode()
        return result
    }
}
