package io.horizontalsystems.ethereumkit.core.storage

import io.horizontalsystems.ethereumkit.models.SyncSource
import io.horizontalsystems.ethereumkit.models.TransactionSyncSource

class TransactionSyncSourceStorage(private val dao: TransactionSyncSourceDao) {

    fun getSource(hash: ByteArray): SyncSource? = dao.getSource(hash)?.source

    fun save(hash: ByteArray, source: SyncSource) {
        dao.insert(TransactionSyncSource(hash, source))
    }

    fun saveAll(hashes: List<ByteArray>, source: SyncSource) {
        if (hashes.isEmpty()) return
        dao.insertAll(hashes.map { TransactionSyncSource(it, source) })
    }
}
