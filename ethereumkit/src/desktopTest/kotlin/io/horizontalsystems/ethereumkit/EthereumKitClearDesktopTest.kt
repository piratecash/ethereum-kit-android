package io.horizontalsystems.ethereumkit

import io.horizontalsystems.ethereumkit.core.EthereumKit
import io.horizontalsystems.ethereumkit.fixture.EthereumKitFixture
import io.horizontalsystems.ethereumkit.fixture.copyFixture
import io.horizontalsystems.ethereumkit.fixture.databaseFamily
import io.horizontalsystems.ethereumkit.fixture.databaseKey
import io.horizontalsystems.ethereumkit.fixture.watchKit
import io.horizontalsystems.ethereumkit.models.Chain
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout
import java.io.File

class EthereumKitClearDesktopTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private val directory: File get() = tmp.root
    private val context: PlatformContext get() = PlatformContext(directory)

    @Test
    fun clear_kitOpen_closesItsDatabasesAndDeletesFiles() = runBlocking {
        val kit = watchKit(context, WALLET_ID, databaseKey)
        val otherWalletKit = watchKit(context, OTHER_WALLET_ID, databaseKey)

        EthereumKit.clear(context, Chain.Ethereum, WALLET_ID)

        assertDeleted(WALLET_ID)
        assertClosed(kit)
        assertEquals(emptyList<String>(), otherWalletKit.getTagTokenContractAddresses())
    }

    @Test
    fun clear_kitOpenedTwice_closesBothAndNextKitWorks() = runBlocking {
        // An RPC change recreates the kit while the previous one still holds its databases.
        val first = watchKit(context, WALLET_ID, databaseKey)
        val second = watchKit(context, WALLET_ID, databaseKey)

        EthereumKit.clear(context, Chain.Ethereum, WALLET_ID)

        assertDeleted(WALLET_ID)
        assertClosed(first)
        assertClosed(second)
        val next = watchKit(context, WALLET_ID, databaseKey)
        assertEquals(emptyList<String>(), next.getTagTokenContractAddresses())
        databaseNames(WALLET_ID).forEach { assertTrue(it, File(directory, it).isFile) }
    }

    @Test
    fun clear_noOpenDatabases_deletesFiles() = runBlocking {
        listOf(EthereumKitFixture.API_DB, EthereumKitFixture.TRANSACTIONS_DB, EthereumKitFixture.EIP20_EVENTS_DB)
            .forEach { copyFixture(it, File(directory, it)) }
        EthereumKit.migrateDatabase(context, Chain.Ethereum, WALLET_ID, databaseKey)

        EthereumKit.clear(context, Chain.Ethereum, WALLET_ID)

        assertDeleted(WALLET_ID)
    }

    private fun assertDeleted(walletId: String) {
        databaseNames(walletId).forEach { assertEquals(it, emptyList<String>(), databaseFamily(directory, it)) }
    }

    // Room fails any query on a closed database; an open one would still read the deleted file.
    private fun assertClosed(kit: EthereumKit) {
        assertThrows(IllegalStateException::class.java) { runBlocking { kit.getTagTokenContractAddresses() } }
    }

    private fun databaseNames(walletId: String) = listOf("api", "txs", "erc20_events").map { "Ethereum-1-$walletId-$it" }

    private companion object {
        // Matches the fixture file names.
        const val WALLET_ID = "fixturewallet"
        const val OTHER_WALLET_ID = "otherwallet"
    }
}
