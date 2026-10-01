package io.horizontalsystems.merkleiokit

import io.horizontalsystems.ethereumkit.PlatformContext
import io.horizontalsystems.ethereumkit.core.EthereumKit
import io.horizontalsystems.ethereumkit.fixture.BACKUP_SUFFIX
import io.horizontalsystems.ethereumkit.fixture.assertUnchanged
import io.horizontalsystems.ethereumkit.fixture.copyFixture
import io.horizontalsystems.ethereumkit.fixture.databaseFamily
import io.horizontalsystems.ethereumkit.fixture.databaseKey
import io.horizontalsystems.ethereumkit.fixture.encryptedTables
import io.horizontalsystems.ethereumkit.fixture.foreignFiles
import io.horizontalsystems.ethereumkit.fixture.hasPlaintextSqliteHeader
import io.horizontalsystems.ethereumkit.fixture.migrationArtifacts
import io.horizontalsystems.ethereumkit.fixture.plaintextTables
import io.horizontalsystems.ethereumkit.fixture.watchKit
import io.horizontalsystems.ethereumkit.models.Chain
import io.horizontalsystems.merkleiokit.fixture.MerkleIoFixture
import io.horizontalsystems.sqlcipher.room.DatabaseMigrationResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout
import java.io.File

class MerkleDatabaseMigrationDesktopTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private val directory: File get() = tmp.root
    private val context: PlatformContext get() = PlatformContext(directory)
    private val database: File get() = File(directory, MerkleIoFixture.DB)

    @Test
    fun migrateDatabase_fixture_encryptsItAndAdapterReadsProtectedHashes() = runBlocking {
        copyFixture(MerkleIoFixture.DB, database)
        val plaintext = plaintextTables(database)

        val result = MerkleTransactionAdapter.migrateDatabase(context, Chain.Ethereum, WALLET_ID, databaseKey)

        assertEquals(DatabaseMigrationResult(1, 0), result)
        assertFalse(hasPlaintextSqliteHeader(database))
        assertEquals(plaintext, encryptedTables(database, databaseKey))
        val adapter = adapter(watchKit(context, WALLET_ID, databaseKey))
        MerkleIoFixture.hashes.forEach {
            assertEquals(mapOf(MerkleTransactionAdapter.protectedKey to true), adapter.syncer.extra(it.hash))
        }
    }

    @Test
    fun clear_migratedDatabaseWithLeftovers_removesItsFamilyAndLeavesForeignFiles() = runBlocking {
        copyFixture(MerkleIoFixture.DB, database)
        val otherChain = copyFixture(MerkleIoFixture.DB, File(directory, "MerkleIo-56-$WALLET_ID"))
        val foreign = foreignFiles(directory, listOf(otherChain))
        MerkleTransactionAdapter.migrateDatabase(context, Chain.Ethereum, WALLET_ID, databaseKey)
        File("${database.path}-wal$BACKUP_SUFFIX").writeText("x")

        MerkleTransactionAdapter.clear(context, Chain.Ethereum, WALLET_ID)

        assertEquals(emptyList<String>(), databaseFamily(directory, database.name))
        assertEquals(emptyList<String>(), migrationArtifacts(directory) - foreign.keys.map(File::getName).toSet())
        assertUnchanged(foreign)
    }

    private suspend fun adapter(kit: EthereumKit): MerkleTransactionAdapter = checkNotNull(
        MerkleTransactionAdapter.getInstance(
            merkleIoPubKey = "key",
            address = kit.receiveAddress,
            chain = kit.chain,
            context = context,
            walletId = WALLET_ID,
            databaseKey = databaseKey,
            transactionManager = kit.transactionManager,
            sourceTag = "test",
            transactionSyncSourceStorage = kit.transactionSyncSourceStorage,
        )
    )

    private companion object {
        const val WALLET_ID = "fixturewallet"
    }
}
