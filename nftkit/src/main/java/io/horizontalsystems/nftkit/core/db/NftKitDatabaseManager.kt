package io.horizontalsystems.nftkit.core.db

import io.horizontalsystems.ethereumkit.PlatformContext
import io.horizontalsystems.ethereumkit.database.databaseNames
import io.horizontalsystems.ethereumkit.database.deleteDatabase
import io.horizontalsystems.ethereumkit.models.Chain

internal object NftKitDatabaseManager {

    fun getNftKitDatabase(context: PlatformContext, chain: Chain, walletId: String): NftKitDatabase {
        return NftKitDatabase.getInstance(context, getDbNameBase(chain, walletId))
    }

    fun clear(context: PlatformContext, chain: Chain, walletId: String) {
        synchronized(this) {
            val dbNameBase = getDbNameBase(chain, walletId)

            databaseNames(context).forEach {
                if (it.contains(dbNameBase)) {
                    deleteDatabase(context, it)
                }
            }
        }
    }

    private fun getDbNameBase(chain: Chain, walletId: String): String {
        return "NftKit-${chain.id}-$walletId"
    }

}