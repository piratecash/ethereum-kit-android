package io.horizontalsystems.erc20kit

import io.horizontalsystems.erc20kit.core.Erc20Kit
import io.horizontalsystems.erc20kit.fixture.Erc20KitFixture
import io.horizontalsystems.ethereumkit.PlatformContext
import io.horizontalsystems.ethereumkit.fixture.BACKUP_SUFFIX
import io.horizontalsystems.ethereumkit.fixture.ManifestPhase
import io.horizontalsystems.ethereumkit.fixture.STAGING_SUFFIX
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
import io.horizontalsystems.ethereumkit.fixture.writeManifest
import io.horizontalsystems.ethereumkit.models.Address
import io.horizontalsystems.ethereumkit.models.Chain
import io.horizontalsystems.sqlcipher.room.DatabaseMigrationResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout
import java.io.File

class Erc20KitDatabaseMigrationDesktopTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private val directory: File get() = tmp.root
    private val context: PlatformContext get() = PlatformContext(directory)

    @Test
    fun migrateDatabases_tokenFixtures_encryptsThemAndKitReadsBalances() = runBlocking {
        val tokens = Erc20KitFixture.balances.keys.map { copyFixture(it, File(directory, it)) }
        val plaintext = tokens.map(::plaintextTables)

        val result = Erc20Kit.migrateDatabases(context, Chain.Ethereum, FIXTURE_WALLET, databaseKey)

        assertEquals(DatabaseMigrationResult(2, 0), result)
        tokens.forEach { assertFalse(it.name, hasPlaintextSqliteHeader(it)) }
        assertEquals(plaintext, tokens.map { encryptedTables(it, databaseKey) })
        val ethereumKit = watchKit(context, FIXTURE_WALLET, databaseKey)
        Erc20KitFixture.balances.forEach { (name, balance) ->
            val erc20Kit = Erc20Kit.getInstance(context, ethereumKit, Address(name.substringAfterLast('-')), databaseKey)
            assertEquals(name, balance, erc20Kit.balance)
        }
    }

    @Test
    fun migrateDatabasesAndClear_otherWalletsChainsAndCompanions_touchOnlyOwnTokens() = runBlocking {
        val own = listOf(token("Erc20-1-a-0xaaaa"), token("Erc20-1-a-0xbbbb"))
        // An empty -wal/-shm/-journal is a valid SQLite companion, not a database of its own.
        val companions = listOf("-wal", "-shm", "-journal").map { File("${own[0].path}$it").apply { writeText("") } }
        val others = listOf(token("Erc20-1-a-b-0xcccc"), token("Erc20-1-a-0xdead-0xbeef"), token("Erc20-10-a-0xaaaa"))
        val foreign = foreignFiles(directory, others)

        val result = Erc20Kit.migrateDatabases(context, Chain.Ethereum, "a", databaseKey)

        assertEquals(DatabaseMigrationResult(2, 0), result)
        own.forEach { assertFalse(it.name, hasPlaintextSqliteHeader(it)) }
        companions.forEach { assertFalse(it.name, it.exists()) }
        assertUnchanged(foreign)

        Erc20Kit.clear(context, Chain.Ethereum, "a")

        own.forEach { assertEquals(it.name, emptyList<String>(), databaseFamily(directory, it.name)) }
        assertUnchanged(foreign)
    }

    @Test
    fun clear_migrationLeftoversWithoutDatabase_removesThem() = runBlocking {
        val token = File(directory, "Erc20-1-a-0xaaaa")
        val leftovers = listOf(
            File("${token.path}$STAGING_SUFFIX"),
            File("${token.path}$BACKUP_SUFFIX"),
            File("${token.path}-wal$BACKUP_SUFFIX"),
        ).onEach { it.writeText("x") }

        Erc20Kit.clear(context, Chain.Ethereum, "a")

        leftovers.forEach { assertFalse(it.name, it.exists()) }
    }

    @Test
    fun clear_interruptedClearOrMigrationWithoutTokenFiles_removesItsManifest() = runBlocking {
        val token = File(directory, "Erc20-1-a-0xaaaa")
        ManifestPhase.entries.filter { it != ManifestPhase.COMMITTED }.forEach { phase ->
            val manifest = writeManifest(directory, MIGRATION_ID, phase, listOf(token))

            Erc20Kit.clear(context, Chain.Ethereum, "a")

            assertFalse(phase.name, manifest.exists())
        }
        assertEquals(emptyList<String>(), migrationArtifacts(directory))
    }

    @Test
    fun clear_otherWalletsInterruptedMigration_keepsItsManifest() = runBlocking {
        val manifest = writeManifest(directory, "erc20:1:a-b", ManifestPhase.STAGED, listOf(File(directory, "Erc20-1-a-b-0xcccc")))

        Erc20Kit.clear(context, Chain.Ethereum, "a")

        assertEquals(listOf(manifest.name), migrationArtifacts(directory))
    }

    private fun token(name: String): File = copyFixture(Erc20KitFixture.USDT_DB, File(directory, name))

    private companion object {
        const val FIXTURE_WALLET = "fixturewallet"
        const val MIGRATION_ID = "erc20:1:a"
    }
}
