package io.horizontalsystems.ethereumkit.api.storage

import io.horizontalsystems.ethereumkit.api.models.AccountState
import io.horizontalsystems.ethereumkit.api.models.LastBlockHeight
import io.horizontalsystems.ethereumkit.core.IApiStorage

class ApiStorage(
        private val database: ApiDatabase
) : IApiStorage {

    override suspend fun getLastBlockHeight(): Long? {
        return database.lastBlockHeightDao().getLastBlockHeight()?.height
    }

    override suspend fun saveLastBlockHeight(lastBlockHeight: Long) {
        database.lastBlockHeightDao().insert(LastBlockHeight(lastBlockHeight))
    }

    override suspend fun saveAccountState(state: AccountState) {
        database.balanceDao().insert(state)
    }

    override suspend fun getAccountState(): AccountState? {
        return database.balanceDao().getAccountState()
    }

}
