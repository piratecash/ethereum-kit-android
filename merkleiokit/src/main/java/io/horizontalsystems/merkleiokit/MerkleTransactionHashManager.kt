package io.horizontalsystems.merkleiokit

class MerkleTransactionHashManager(private val dao: MerkleTransactionDao) {

    suspend fun hashes() = dao.hashes()

    suspend fun hash(hash: ByteArray) = dao.hash(hash)

    suspend fun save(hash: MerkleTransactionHash) = dao.save(hash)

    suspend fun handle(txHashes: List<ByteArray>) = dao.delete(txHashes)
}

