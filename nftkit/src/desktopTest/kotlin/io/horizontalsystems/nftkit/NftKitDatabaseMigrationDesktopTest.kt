package io.horizontalsystems.nftkit

import io.horizontalsystems.ethereumkit.PlatformContext
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
import io.horizontalsystems.nftkit.core.NftKit
import io.horizontalsystems.nftkit.fixture.NftKitFixture
import io.horizontalsystems.nftkit.fixture.NftKitFixture.snapshot
import io.horizontalsystems.nftkit.models.NftBalanceRecord
import io.horizontalsystems.sqlcipher.room.DatabaseMigrationResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout
import java.io.File

class NftKitDatabaseMigrationDesktopTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private val directory: File get() = tmp.root
    private val context: PlatformContext get() = PlatformContext(directory)
    private val database: File get() = File(directory, NftKitFixture.DB)

    @Test
    fun migrateDatabase_fixture_encryptsItAndKitReadsBalances() = runBlocking {
        copyFixture(NftKitFixture.DB, database)
        val plaintext = plaintextTables(database)

        val result = NftKit.migrateDatabase(context, Chain.Ethereum, WALLET_ID, databaseKey)

        assertEquals(DatabaseMigrationResult(1, 0), result)
        assertFalse(hasPlaintextSqliteHeader(database))
        assertEquals(plaintext, encryptedTables(database, databaseKey))
        val nftKit = NftKit.getInstance(context, watchKit(context, WALLET_ID, databaseKey), databaseKey)
        assertEquals(
            NftKitFixture.balances.filter { it.balance > 0 }.map { it.snapshot() },
            nftKit.nftBalances.map { NftBalanceRecord(it).snapshot() }
        )
    }

    @Test
    fun clear_migratedDatabaseWithLeftovers_removesItsFamilyAndLeavesForeignFiles() = runBlocking {
        copyFixture(NftKitFixture.DB, database)
        val otherWallet = copyFixture(NftKitFixture.DB, File(directory, "NftKit-1-$WALLET_ID-b"))
        val foreign = foreignFiles(directory, listOf(otherWallet))
        NftKit.migrateDatabase(context, Chain.Ethereum, WALLET_ID, databaseKey)
        File("${database.path}-wal$BACKUP_SUFFIX").writeText("x")

        NftKit.clear(context, Chain.Ethereum, WALLET_ID)

        assertEquals(emptyList<String>(), databaseFamily(directory, database.name).filterNot { it == otherWallet.name })
        assertEquals(emptyList<String>(), migrationArtifacts(directory) - foreign.keys.map(File::getName).toSet())
        assertUnchanged(foreign)
    }

    private companion object {
        const val WALLET_ID = "fixturewallet"
    }
}
