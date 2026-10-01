package io.horizontalsystems.ethereumkit.core

import androidx.room.RoomDatabase
import io.horizontalsystems.ethereumkit.PlatformContext
import io.horizontalsystems.ethereumkit.api.storage.ApiDatabase
import io.horizontalsystems.ethereumkit.core.storage.Eip20Database
import io.horizontalsystems.ethereumkit.core.storage.TransactionDatabase
import io.horizontalsystems.ethereumkit.database.EthereumKitDatabases
import io.horizontalsystems.ethereumkit.models.Chain
import io.horizontalsystems.sqlcipher.room.DatabaseMigrationResult

internal object EthereumDatabaseManager {

    class Databases(val api: ApiDatabase, val transactions: TransactionDatabase, val erc20: Eip20Database)

    /** Opens all three databases or, if one fails, closes those already open. */
    suspend fun open(context: PlatformContext, chain: Chain, walletId: String, databaseKey: ByteArray): Databases {
        val opened = mutableListOf<RoomDatabase>()
        suspend fun <T : RoomDatabase> open(build: () -> T): T = EthereumKitDatabases.open(build).also(opened::add)
        try {
            return Databases(
                api = open { ApiDatabase.getInstance(context, getDbNameApi(walletId, chain), databaseKey) },
                transactions = open { TransactionDatabase.getInstance(context, getDbNameTransactions(walletId, chain), databaseKey) },
                erc20 = open { Eip20Database.getInstance(context, getDbNameErc20Events(walletId, chain), databaseKey) },
            )
        } catch (error: Throwable) {
            opened.forEach(RoomDatabase::close)
            throw error
        }
    }

    suspend fun migrate(context: PlatformContext, chain: Chain, walletId: String, databaseKey: ByteArray): DatabaseMigrationResult =
        EthereumKitDatabases.migrate(context, migrationId(chain, walletId), databaseNames(chain, walletId), databaseKey)

    suspend fun clear(context: PlatformContext, chain: Chain, walletId: String) {
        val names = databaseNames(chain, walletId) + getDbNameSpv(walletId, chain)
        EthereumKitDatabases.clear(context, migrationId(chain, walletId), names)
    }

    private fun migrationId(chain: Chain, walletId: String) = EthereumKitDatabases.migrationId("ethereum", chain, walletId)

    private fun databaseNames(chain: Chain, walletId: String) = listOf(
        getDbNameApi(walletId, chain),
        getDbNameTransactions(walletId, chain),
        getDbNameErc20Events(walletId, chain),
    )

    private fun getDbNameApi(walletId: String, chain: Chain): String {
        return getDbName(chain, walletId, "api")
    }

    // No database class any more; clear still removes the file an older kit version left.
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
