package io.horizontalsystems.erc20kit.core

import io.horizontalsystems.erc20kit.core.room.Erc20KitDatabase
import io.horizontalsystems.ethereumkit.PlatformContext
import io.horizontalsystems.ethereumkit.database.EthereumKitDatabases
import io.horizontalsystems.ethereumkit.models.Address
import io.horizontalsystems.ethereumkit.models.Chain
import io.horizontalsystems.sqlcipher.room.DatabaseMigrationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal object Erc20DatabaseManager {

    suspend fun open(
        context: PlatformContext,
        chain: Chain,
        walletId: String,
        contractAddress: Address,
        databaseKey: ByteArray,
    ): Erc20KitDatabase = EthereumKitDatabases.open {
        Erc20KitDatabase.getInstance(context, "${getDbNameBase(chain, walletId)}-${contractAddress.hex}", databaseKey)
    }

    suspend fun migrate(context: PlatformContext, chain: Chain, walletId: String, databaseKey: ByteArray): DatabaseMigrationResult {
        val names = erc20DatabaseNames(context, chain, walletId)
        return EthereumKitDatabases.migrate(context, migrationId(chain, walletId), names, databaseKey)
    }

    // Always issued, even without token files: the engine still finds an interrupted migration or clear by its id.
    suspend fun clear(context: PlatformContext, chain: Chain, walletId: String) {
        val names = erc20DatabaseNames(context, chain, walletId)
        EthereumKitDatabases.clear(context, migrationId(chain, walletId), names)
    }

    /**
     * Token databases of [walletId] on [chain], including those only migration leftovers or SQLite
     * companions remain of. Exact match: wallet `a` must not take `a-b` or `a-0xdead`, chain 1 not 10.
     */
    private suspend fun erc20DatabaseNames(context: PlatformContext, chain: Chain, walletId: String): List<String> =
        withContext(Dispatchers.IO) { erc20DatabaseNames(EthereumKitDatabases.directory(context), chain, walletId) }

    private fun erc20DatabaseNames(dataDir: File, chain: Chain, walletId: String): List<String> {
        val pattern = Regex("^${Regex.escape(getDbNameBase(chain, walletId))}-0x[0-9a-f]+$")
        return dataDir.list().orEmpty().map(::baseDatabaseName).filter(pattern::matches).distinct()
    }

    private fun baseDatabaseName(fileName: String): String {
        val withoutArtifact = MIGRATION_ARTIFACTS.fold(fileName) { name, marker -> name.substringBefore(marker) }
        return SQLITE_SUFFIXES.firstOrNull(withoutArtifact::endsWith)?.let(withoutArtifact::removeSuffix) ?: withoutArtifact
    }

    private fun migrationId(chain: Chain, walletId: String) = EthereumKitDatabases.migrationId("erc20", chain, walletId)

    private fun getDbNameBase(chain: Chain, walletId: String): String {
        return "Erc20-${chain.id}-$walletId"
    }

    private val MIGRATION_ARTIFACTS = listOf(".sqlcipher-migrating", ".plaintext-backup")
    private val SQLITE_SUFFIXES = listOf("-wal", "-shm", "-journal")
}
