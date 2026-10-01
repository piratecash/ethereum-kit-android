package io.horizontalsystems.ethereumkit.core

import io.horizontalsystems.ethereumkit.PlatformContext
import io.horizontalsystems.ethereumkit.api.storage.ApiDatabase
import io.horizontalsystems.ethereumkit.core.storage.Eip20Database
import io.horizontalsystems.ethereumkit.core.storage.TransactionDatabase
import io.horizontalsystems.ethereumkit.database.deleteDatabase
import io.horizontalsystems.ethereumkit.models.Chain

internal object EthereumDatabaseManager {

    fun getEthereumApiDatabase(context: PlatformContext, walletId: String, chain: Chain): ApiDatabase {
        return ApiDatabase.getInstance(context, getDbNameApi(walletId, chain))
    }

    fun getTransactionDatabase(context: PlatformContext, walletId: String, chain: Chain): TransactionDatabase {
        return TransactionDatabase.getInstance(context, getDbNameTransactions(walletId, chain))
    }

    fun getErc20Database(context: PlatformContext, walletId: String, chain: Chain): Eip20Database {
        return Eip20Database.getInstance(context, getDbNameErc20Events(walletId, chain))
    }

    fun clear(context: PlatformContext, chain: Chain, walletId: String) {
        synchronized(this) {
            deleteDatabase(context, getDbNameApi(walletId, chain))
            deleteDatabase(context, getDbNameSpv(walletId, chain))
            deleteDatabase(context, getDbNameTransactions(walletId, chain))
            deleteDatabase(context, getDbNameErc20Events(walletId, chain))
        }
    }

    private fun getDbNameApi(walletId: String, chain: Chain): String {
        return getDbName(chain, walletId, "api")
    }

    private fun getDbNameSpv(walletId: String, chain: Chain): String {
        return getDbName(chain, walletId, "spv")
    }

    private fun getDbNameTransactions(walletId: String, chain: Chain): String {
        return getDbName(chain, walletId, "txs")
    }

    private fun getDbNameErc20Events(walletId: String, chain: Chain): String {
        return getDbName(chain, walletId, "erc20_events")
    }

    private fun getDbName(chain: Chain, walletId: String, suffix: String): String {
        return "Ethereum-${chain.id}-$walletId-$suffix"
    }
}
