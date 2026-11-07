package io.horizontalsystems.ethereumkit.core.storage

import io.horizontalsystems.ethereumkit.core.IExtraDecorator
import io.horizontalsystems.ethereumkit.models.FullTransaction
import io.horizontalsystems.ethereumkit.models.SyncSource
import io.horizontalsystems.ethereumkit.models.TransactionSyncSource

class TransactionSyncSourceStorage(private val dao: TransactionSyncSourceDao) : IExtraDecorator {

    suspend fun getSource(hash: ByteArray): SyncSource? = dao.getSource(hash)?.source

    suspend fun save(hash: ByteArray, source: SyncSource) {
        dao.insert(TransactionSyncSource(hash, source))
    }

    suspend fun saveAll(hashes: List<ByteArray>, source: SyncSource) {
        if (hashes.isEmpty()) return
        dao.insertAll(hashes.map { TransactionSyncSource(it, source) })
    }

    override suspend fun extra(hash: ByteArray): Map<String, Any> =
        getSource(hash)?.let { mapOf(syncSourceKey to it) } ?: emptyMap()

    companion object {
        private const val syncSourceKey = "syncSource"

        fun syncSource(fullTransaction: FullTransaction): SyncSource? =
            fullTransaction.extra[syncSourceKey] as? SyncSource
    }
}
