package io.horizontalsystems.ethereumkit.core.storage

import io.horizontalsystems.ethereumkit.models.TransactionSyncerState

class TransactionSyncerStateStorage(database: TransactionDatabase) {
    private val dao = database.transactionSyncerStateDao()

    suspend fun get(syncerId: String): TransactionSyncerState? =
        dao.get(syncerId)

    suspend fun save(transactionSyncerState: TransactionSyncerState) {
        dao.save(transactionSyncerState)
    }

}
