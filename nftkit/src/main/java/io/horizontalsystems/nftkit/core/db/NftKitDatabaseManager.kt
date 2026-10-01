package io.horizontalsystems.nftkit.core.db

import io.horizontalsystems.ethereumkit.PlatformContext
import io.horizontalsystems.ethereumkit.database.EthereumKitDatabases
import io.horizontalsystems.ethereumkit.models.Chain
import io.horizontalsystems.sqlcipher.room.DatabaseMigrationResult

internal object NftKitDatabaseManager {

    suspend fun open(context: PlatformContext, chain: Chain, walletId: String, databaseKey: ByteArray): NftKitDatabase =
        EthereumKitDatabases.open { NftKitDatabase.getInstance(context, getDbName(chain, walletId), databaseKey) }

    suspend fun migrate(context: PlatformContext, chain: Chain, walletId: String, databaseKey: ByteArray): DatabaseMigrationResult =
        EthereumKitDatabases.migrate(context, migrationId(chain, walletId), listOf(getDbName(chain, walletId)), databaseKey)

    suspend fun clear(context: PlatformContext, chain: Chain, walletId: String) {
        EthereumKitDatabases.clear(context, migrationId(chain, walletId), listOf(getDbName(chain, walletId)))
    }

    private fun migrationId(chain: Chain, walletId: String) = EthereumKitDatabases.migrationId("nft", chain, walletId)

    private fun getDbName(chain: Chain, walletId: String): String {
        return "NftKit-${chain.id}-$walletId"
    }

}
