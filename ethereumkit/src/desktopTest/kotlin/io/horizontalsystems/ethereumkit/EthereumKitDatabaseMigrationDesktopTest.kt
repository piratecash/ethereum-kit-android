package io.horizontalsystems.ethereumkit

import io.horizontalsystems.ethereumkit.core.EthereumKit
import io.horizontalsystems.ethereumkit.core.storage.TransactionDatabase
import io.horizontalsystems.ethereumkit.core.storage.TransactionStorage
import io.horizontalsystems.ethereumkit.fixture.EthereumKitFixture
import io.horizontalsystems.ethereumkit.fixture.EthereumKitFixture.snapshot
import io.horizontalsystems.ethereumkit.fixture.BACKUP_SUFFIX
import io.horizontalsystems.ethereumkit.fixture.LOCK_FILE_NAME
import io.horizontalsystems.ethereumkit.fixture.ManifestPhase
import io.horizontalsystems.ethereumkit.fixture.STAGING_SUFFIX
import io.horizontalsystems.ethereumkit.fixture.assertUnchanged
import io.horizontalsystems.ethereumkit.fixture.copyFixture
import io.horizontalsystems.ethereumkit.fixture.databaseFamily
import io.horizontalsystems.ethereumkit.fixture.databaseKey
import io.horizontalsystems.ethereumkit.fixture.encryptedTables
import io.horizontalsystems.ethereumkit.fixture.foreignFiles
import io.horizontalsystems.ethereumkit.fixture.hasPlaintextSqliteHeader
import io.horizontalsystems.ethereumkit.fixture.interruptStagedMigration
import io.horizontalsystems.ethereumkit.fixture.migrationArtifacts
import io.horizontalsystems.ethereumkit.fixture.otherDatabaseKey
import io.horizontalsystems.ethereumkit.fixture.plaintextTables
import io.horizontalsystems.ethereumkit.fixture.watchKit
import io.horizontalsystems.ethereumkit.fixture.writeManifest
import io.horizontalsystems.ethereumkit.models.Chain
import io.horizontalsystems.sqlcipher.room.DatabaseKeyMismatchException
import io.horizontalsystems.sqlcipher.room.DatabaseMigrationInProgressException
import io.horizontalsystems.sqlcipher.room.DatabaseMigrationRequiredException
import io.horizontalsystems.sqlcipher.room.DatabaseMigrationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout
import java.io.File

/** How EthereumKit wires sqlcipher-room for its databases; engine internals are covered by that module. */
class EthereumKitDatabaseMigrationDesktopTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private val directory: File get() = tmp.root
    private val context: PlatformContext get() = PlatformContext(directory)
    private val fixtureNames = listOf(EthereumKitFixture.API_DB, EthereumKitFixture.TRANSACTIONS_DB, EthereumKitFixture.EIP20_EVENTS_DB)
    private val fixtureFiles: List<File> get() = fixtureNames.map { File(directory, it) }

    @Test
    fun migrateDatabase_fixtures_encryptsAndKitReadsStoredRows() = runBlocking {
        copyFixtures()
        val plaintext = fixtureFiles.map(::plaintextTables)

        val result = migrate(databaseKey)

        assertEquals(DatabaseMigrationResult(3, 0), result)
        fixtureFiles.forEach { assertFalse(it.name, hasPlaintextSqliteHeader(it)) }
        assertEquals(plaintext, fixtureFiles.map { encryptedTables(it, databaseKey) })
        assertEquals(emptyList<String>(), migrationArtifacts(directory))

        val kit = kit(databaseKey)
        assertEquals(EthereumKitFixture.LAST_BLOCK_HEIGHT, kit.lastBlockHeight)
        assertEquals(EthereumKitFixture.accountState, kit.accountState)
        assertEquals(
            EthereumKitFixture.transactions.map { it.snapshot() }.sortedBy { it.toString() },
            kit.getFullTransactions(EthereumKitFixture.transactions.map { it.hash })
                .map { it.transaction.snapshot() }.sortedBy { it.toString() }
        )
        assertEquals(
            listOf(EthereumKitFixture.pendingTransaction.snapshot()),
            kit.getPendingFullTransactions(emptyList()).map { it.transaction.snapshot() }
        )
        assertEquals(
            EthereumKitFixture.eip20Events.map { it.snapshot() }.sortedBy { it.toString() },
            kit.eip20Storage.getEvents().map { it.snapshot() }.sortedBy { it.toString() }
        )
        val offlineQueue = TransactionStorage(
            TransactionDatabase.getInstance(context, EthereumKitFixture.TRANSACTIONS_DB, databaseKey)
        ).getRawTransactionBroadcasts()
        assertEquals(
            EthereumKitFixture.rawBroadcasts.map { it.snapshot() }.sortedBy { it.toString() },
            offlineQueue.map { it.snapshot() }.sortedBy { it.toString() }
        )
    }

    @Test
    fun migrateDatabase_alreadyEncrypted_reportsItAndKeepsFileBytes() = runBlocking {
        copyFixtures()
        migrate(databaseKey)
        val encrypted = fixtureFiles.map(File::readBytes)

        val result = migrate(databaseKey)

        assertEquals(DatabaseMigrationResult(0, 3), result)
        assertBytes(encrypted)
    }

    @Test
    fun migrateDatabase_otherKey_throwsKeyMismatchWithoutChangingFiles() = runBlocking {
        copyFixtures()
        migrate(databaseKey)
        val encrypted = fixtureFiles.map(File::readBytes)

        assertThrows(DatabaseKeyMismatchException::class.java) { runBlocking { migrate(otherDatabaseKey) } }

        assertBytes(encrypted)
    }

    @Test
    fun getInstance_otherKey_throwsKeyMismatchWithoutChangingFiles() = runBlocking {
        copyFixtures()
        migrate(databaseKey)
        val encrypted = fixtureFiles.map(File::readBytes)

        assertThrows(DatabaseKeyMismatchException::class.java) { runBlocking { kit(otherDatabaseKey) } }

        assertBytes(encrypted)
        assertEquals(EthereumKitFixture.LAST_BLOCK_HEIGHT, kit(databaseKey).lastBlockHeight)
    }

    @Test
    fun getInstance_plaintextWithoutMigration_throwsMigrationRequiredWithoutChangingFiles() {
        copyFixtures()
        val plaintext = fixtureFiles.map(File::readBytes)

        assertThrows(DatabaseMigrationRequiredException::class.java) { runBlocking { kit(databaseKey) } }

        assertBytes(plaintext)
    }

    @Test
    fun getInstance_manifestInNamespace_throwsMigrationInProgress() = runBlocking {
        copyFixtures()
        migrate(databaseKey)
        writeManifest(directory, "erc20:1:$WALLET_ID", ManifestPhase.STAGED, emptyList())

        assertThrows(DatabaseMigrationInProgressException::class.java) { runBlocking { kit(databaseKey) } }

        migrate(databaseKey)
        assertEquals(EthereumKitFixture.LAST_BLOCK_HEIGHT, kit(databaseKey).lastBlockHeight)
    }

    @Test
    fun migrateDatabase_stagedMigrationWasInterrupted_recoversAndMigrates() = runBlocking {
        copyFixtures()
        val transactions = File(directory, EthereumKitFixture.TRANSACTIONS_DB)
        val plaintext = plaintextTables(transactions)
        interruptStagedMigration(transactions, "ethereum:1:$WALLET_ID", otherDatabaseKey)

        val result = migrate(databaseKey)

        assertEquals(DatabaseMigrationResult(3, 0), result)
        assertEquals(plaintext, encryptedTables(transactions, databaseKey))
        assertEquals(emptyList<String>(), migrationArtifacts(directory))
    }

    @Test
    fun migrateDatabase_otherModuleMigrationWasInterrupted_recoversItsManifest() = runBlocking {
        copyFixtures()
        val token = copyFixture(EthereumKitFixture.API_DB, File(directory, "Erc20-1-$WALLET_ID-0xdac17f958d2ee523a2206206994597c13d831ec7"))
        val tokenBytes = token.readBytes()
        interruptStagedMigration(token, "erc20:1:$WALLET_ID", databaseKey)

        migrate(databaseKey)

        assertEquals(emptyList<String>(), migrationArtifacts(directory))
        assertTrue(token.readBytes().contentEquals(tokenBytes))
    }

    @Test
    fun clear_migratedAndInterruptedFiles_removesFamiliesAndLeavesForeignFiles() = runBlocking {
        copyFixtures()
        val otherWallet = copyFixture(EthereumKitFixture.API_DB, File(directory, "Ethereum-1-other-$WALLET_ID-api"))
        val otherChain = copyFixture(EthereumKitFixture.API_DB, File(directory, "Ethereum-10-$WALLET_ID-api"))
        val foreign = foreignFiles(directory, listOf(otherWallet, otherChain))
        migrate(databaseKey)
        kit(databaseKey)
        val spv = File(directory, "Ethereum-1-$WALLET_ID-spv").apply { writeText("spv") }
        listOf("-wal", "-shm", "-journal").forEach { File("${spv.path}$it").writeText("x") }
        val transactions = File(directory, EthereumKitFixture.TRANSACTIONS_DB)
        File("${transactions.path}$STAGING_SUFFIX").writeText("staging")
        File("${transactions.path}$BACKUP_SUFFIX").writeText("backup")
        writeManifest(directory, "ethereum:1:$WALLET_ID", ManifestPhase.STAGED, listOf(transactions))

        EthereumKit.clear(context, Chain.Ethereum, WALLET_ID)

        (fixtureNames + spv.name).forEach { assertEquals(it, emptyList<String>(), databaseFamily(directory, it)) }
        val foreignNames = foreign.keys.map(File::getName)
        assertEquals(emptyList<String>(), migrationArtifacts(directory) - foreignNames.toSet())
        assertUnchanged(foreign)
        assertTrue(File(directory, LOCK_FILE_NAME).exists())
    }

    @Test
    fun invalidArguments_throwBeforeAnyFileIsCreated() {
        val dataContext = PlatformContext(File(directory, "data"))
        invalidArguments().forEach { (walletId, key) ->
            assertThrows(walletId, IllegalArgumentException::class.java) {
                runBlocking { EthereumKit.migrateDatabase(dataContext, Chain.Ethereum, walletId, key) }
            }
            assertThrows(walletId, IllegalArgumentException::class.java) {
                runBlocking { watchKit(dataContext, walletId, key) }
            }
            if (key.size == databaseKey.size) {
                assertThrows(walletId, IllegalArgumentException::class.java) {
                    runBlocking { EthereumKit.clear(dataContext, Chain.Ethereum, walletId) }
                }
            }
            assertEquals("files after '$walletId'", emptyList<String>(), directory.list()?.toList())
        }
    }

    @Test
    fun migrateAndGetInstance_differentChainsInParallel_allSucceed() = runBlocking {
        copyFixtures()
        fixtureNames.forEach { copyFixture(it, File(directory, it.replace("Ethereum-1-", "Ethereum-56-"))) }

        val ethereum = async(Dispatchers.IO) { EthereumKit.migrateDatabase(context, Chain.Ethereum, WALLET_ID, databaseKey) }
        val binance = async(Dispatchers.IO) { EthereumKit.migrateDatabase(context, Chain.BinanceSmartChain, WALLET_ID, databaseKey) }
        val polygon = async(Dispatchers.IO) { watchKit(context, WALLET_ID, databaseKey, Chain.Polygon) }

        assertEquals(DatabaseMigrationResult(3, 0), ethereum.await())
        assertEquals(DatabaseMigrationResult(3, 0), binance.await())
        assertEquals(Chain.Polygon, polygon.await().chain)
        assertEquals(emptyList<String>(), migrationArtifacts(directory))
    }

    private fun copyFixtures() {
        fixtureNames.forEach { copyFixture(it, File(directory, it)) }
    }

    private fun assertBytes(expected: List<ByteArray>) {
        fixtureFiles.zip(expected).forEach { (file, bytes) -> assertTrue(file.name, file.readBytes().contentEquals(bytes)) }
    }

    private suspend fun migrate(key: ByteArray): DatabaseMigrationResult =
        EthereumKit.migrateDatabase(context, Chain.Ethereum, WALLET_ID, key)

    private suspend fun kit(key: ByteArray): EthereumKit = watchKit(context, WALLET_ID, key)

    private fun invalidArguments(): List<Pair<String, ByteArray>> = listOf(
        WALLET_ID to ByteArray(31),
        WALLET_ID to ByteArray(33),
        "" to databaseKey,
        " " to databaseKey,
        "a/b" to databaseKey,
        "a\\b" to databaseKey,
        "wallet.plaintext-backup" to databaseKey,
        "wallet.sqlcipher-migrating" to databaseKey,
    )

    private companion object {
        const val WALLET_ID = "fixturewallet"
    }
}
