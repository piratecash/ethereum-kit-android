package io.horizontalsystems.erc20kit.core

import io.horizontalsystems.erc20kit.core.room.Erc20KitDatabase
import io.horizontalsystems.ethereumkit.PlatformContext
import io.horizontalsystems.ethereumkit.database.databaseNames
import io.horizontalsystems.ethereumkit.database.deleteDatabase
import io.horizontalsystems.ethereumkit.models.Address
import io.horizontalsystems.ethereumkit.models.Chain

internal object Erc20DatabaseManager {

    fun getErc20Database(context: PlatformContext, chain: Chain, walletId: String, contractAddress: Address): Erc20KitDatabase {
        return Erc20KitDatabase.getInstance(context, "${getDbNameBase(chain, walletId)}-${contractAddress.hex}")
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
        return "Erc20-${chain.id}-$walletId"
    }

}
