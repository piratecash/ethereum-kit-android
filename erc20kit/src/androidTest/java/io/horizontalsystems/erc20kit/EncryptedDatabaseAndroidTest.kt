package io.horizontalsystems.erc20kit

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.horizontalsystems.erc20kit.core.Erc20Kit
import io.horizontalsystems.erc20kit.fixture.Erc20KitFixture
import io.horizontalsystems.ethereumkit.core.EthereumKit
import io.horizontalsystems.ethereumkit.core.storage.TransactionDatabase
import io.horizontalsystems.ethereumkit.core.storage.TransactionStorage
import io.horizontalsystems.ethereumkit.fixture.EthereumKitFixture
import io.horizontalsystems.ethereumkit.fixture.EthereumKitFixture.snapshot
import io.horizontalsystems.ethereumkit.fixture.copyFixture
import io.horizontalsystems.ethereumkit.fixture.databaseKey
import io.horizontalsystems.ethereumkit.fixture.hasPlaintextSqliteHeader
import io.horizontalsystems.ethereumkit.fixture.otherDatabaseKey
import io.horizontalsystems.ethereumkit.fixture.watchKit
import io.horizontalsystems.ethereumkit.models.Address
import io.horizontalsystems.ethereumkit.models.Chain
import io.horizontalsystems.sqlcipher.room.DatabaseKeyMismatchException
import io.horizontalsystems.sqlcipher.room.DatabaseMigrationRequiredException
import io.horizontalsystems.sqlcipher.room.DatabaseMigrationResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** The SQLCipher path on a real Android runtime: native library, SupportOpenHelperFactory and getDatabasePath. */
@RunWith(AndroidJUnit4::class)
class EncryptedDatabaseAndroidTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val walletId = "encrypted-${UUID.randomUUID()}"
    private val usdt = Address(Erc20KitFixture.USDT_DB.substringAfterLast('-'))

    // The fixtures belong to "fixturewallet"; each test copies them under its own wallet id.
    private val ethereumFiles = listOf(EthereumKitFixture.API_DB, EthereumKitFixture.TRANSACTIONS_DB, EthereumKitFixture.EIP20_EVENTS_DB)
        .associateWith { context.getDatabasePath(it.replace(FIXTURE_WALLET, walletId)) }
    private val tokenFile = context.getDatabasePath(Erc20KitFixture.USDT_DB.replace(FIXTURE_WALLET, walletId))
    private val files: List<File> = ethereumFiles.values + tokenFile

    @After
    fun tearDown() = runBlocking {
        EthereumKit.clear(context, Chain.Ethereum, walletId)
        Erc20Kit.clear(context, Chain.Ethereum, walletId)
    }

    @Test
    fun migrate_fixtures_kitsReadStoredRows() = runBlocking {
        copyFixtures()

        assertEquals(DatabaseMigrationResult(3, 0), migrateEthereum(databaseKey))
        assertEquals(DatabaseMigrationResult(1, 0), migrateTokens(databaseKey))

        files.forEach { assertFalse(it.name, hasPlaintextSqliteHeader(it)) }
        val kit = watchKit(context, walletId, databaseKey)
        assertEquals(EthereumKitFixture.LAST_BLOCK_HEIGHT, kit.lastBlockHeight)
        assertEquals(
            EthereumKitFixture.transactions.map { it.snapshot() }.sortedBy { it.toString() },
            kit.getFullTransactions(EthereumKitFixture.transactions.map { it.hash })
                .map { it.transaction.snapshot() }.sortedBy { it.toString() }
        )
        assertEquals(
            listOf(EthereumKitFixture.pendingTransaction.snapshot()),
            kit.getPendingFullTransactions(emptyList()).map { it.transaction.snapshot() }
        )
        val transactionsName = checkNotNull(ethereumFiles[EthereumKitFixture.TRANSACTIONS_DB]).name
        val database = TransactionDatabase.getInstance(context, transactionsName, databaseKey)
        try {
            assertEquals(
                EthereumKitFixture.rawBroadcasts.map { it.snapshot() }.sortedBy { it.toString() },
                TransactionStorage(database).getRawTransactionBroadcasts().map { it.snapshot() }.sortedBy { it.toString() }
            )
        } finally {
            database.close()
        }
        assertEquals(Erc20KitFixture.balances[Erc20KitFixture.USDT_DB], Erc20Kit.getInstance(context, kit, usdt, databaseKey).balance)
    }

    @Test
    fun migrate_alreadyEncrypted_reportsAlreadyEncrypted() = runBlocking {
        copyFixtures()
        migrateEthereum(databaseKey)
        migrateTokens(databaseKey)

        assertEquals(DatabaseMigrationResult(0, 3), migrateEthereum(databaseKey))
        assertEquals(DatabaseMigrationResult(0, 1), migrateTokens(databaseKey))
    }

    @Test
    fun getInstance_otherKey_throwsKeyMismatchWithoutChangingFiles() = runBlocking {
        copyFixtures()
        migrateEthereum(databaseKey)
        migrateTokens(databaseKey)
        val ethereumKit = watchKit(context, walletId, databaseKey)
        val encrypted = files.map(File::readBytes)

        assertThrows(DatabaseKeyMismatchException::class.java) { runBlocking { watchKit(context, walletId, otherDatabaseKey) } }
        assertThrows(DatabaseKeyMismatchException::class.java) {
            runBlocking { Erc20Kit.getInstance(context, ethereumKit, usdt, otherDatabaseKey) }
        }

        files.zip(encrypted).forEach { (file, bytes) -> assertArrayEquals(file.name, bytes, file.readBytes()) }
    }

    @Test
    fun getInstance_plaintextWithoutMigration_throwsMigrationRequired() {
        copyFixtures()
        val plaintext = files.map(File::readBytes)

        assertThrows(DatabaseMigrationRequiredException::class.java) { runBlocking { watchKit(context, walletId, databaseKey) } }

        files.zip(plaintext).forEach { (file, bytes) -> assertArrayEquals(file.name, bytes, file.readBytes()) }
    }

    @Test
    fun clear_migratedDatabases_removesEveryFile() = runBlocking {
        copyFixtures()
        migrateEthereum(databaseKey)
        migrateTokens(databaseKey)
        Erc20Kit.getInstance(context, watchKit(context, walletId, databaseKey), usdt, databaseKey)

        EthereumKit.clear(context, Chain.Ethereum, walletId)
        Erc20Kit.clear(context, Chain.Ethereum, walletId)

        val leftovers = tokenFile.parentFile?.list()?.filter { name ->
            name.contains(walletId) || (name.startsWith(".ethereum-kit-sqlcipher-") && name.endsWith(".json"))
        }
        assertTrue("leftovers: $leftovers", leftovers.isNullOrEmpty())
    }

    private fun copyFixtures() {
        ethereumFiles.forEach { (fixture, file) -> copyFixture(fixture, file) }
        copyFixture(Erc20KitFixture.USDT_DB, tokenFile)
    }

    private suspend fun migrateEthereum(key: ByteArray) = EthereumKit.migrateDatabase(context, Chain.Ethereum, walletId, key)

    private suspend fun migrateTokens(key: ByteArray) = Erc20Kit.migrateDatabases(context, Chain.Ethereum, walletId, key)

    private companion object {
        const val FIXTURE_WALLET = "fixturewallet"
    }
}
