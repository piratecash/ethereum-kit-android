package io.horizontalsystems.merkleiokit.fixture

import io.horizontalsystems.merkleiokit.MerkleTransactionHash

/** Expected content of the plaintext fixture database in `src/test/resources/databases`; no platform dependencies. */
object MerkleIoFixture {
    const val DB = "MerkleIo-1-fixturewallet"

    val hashes = listOf(
        MerkleTransactionHash(ByteArray(32) { 0x01 }),
        MerkleTransactionHash(ByteArray(32) { 0x02 })
    )

    fun MerkleTransactionHash.snapshot() = hash.toList()
}
