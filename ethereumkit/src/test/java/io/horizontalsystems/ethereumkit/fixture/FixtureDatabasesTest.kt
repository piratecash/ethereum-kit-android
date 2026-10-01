package io.horizontalsystems.ethereumkit.fixture

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.horizontalsystems.ethereumkit.api.storage.ApiDatabase
import io.horizontalsystems.ethereumkit.api.storage.ApiStorage
import io.horizontalsystems.ethereumkit.core.storage.Eip20Database
import io.horizontalsystems.ethereumkit.core.storage.Eip20Storage
import io.horizontalsystems.ethereumkit.core.storage.TransactionDatabase
import io.horizontalsystems.ethereumkit.core.storage.TransactionStorage
import io.horizontalsystems.ethereumkit.core.storage.TransactionSyncSourceStorage
import io.horizontalsystems.ethereumkit.core.storage.TransactionSyncerStateStorage
import io.horizontalsystems.ethereumkit.fixture.EthereumKitFixture.snapshot
import io.horizontalsystems.ethereumkit.models.TransactionTag
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FixtureDatabasesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun apiDatabase_fixture_containsAccountStateAndLastBlockHeight() = runTest {
        DatabaseFixtureFiles.install(context, EthereumKitFixture.API_DB)

        val storage = ApiStorage(ApiDatabase.getInstance(context, EthereumKitFixture.API_DB))

        assertEquals(EthereumKitFixture.accountState, storage.getAccountState())
        assertEquals(EthereumKitFixture.LAST_BLOCK_HEIGHT, storage.getLastBlockHeight())
    }

    @Test
    fun transactionDatabase_fixture_containsAllTables() = runTest {
        DatabaseFixtureFiles.install(context, EthereumKitFixture.TRANSACTIONS_DB)
        val database = TransactionDatabase.getInstance(context, EthereumKitFixture.TRANSACTIONS_DB)
        val storage = TransactionStorage(database)

        val hashes = EthereumKitFixture.transactions.map { it.hash }
        assertEquals(
            EthereumKitFixture.transactions.map { it.snapshot() }.sortedBy { it.toString() },
            storage.getTransactions(hashes).map { it.snapshot() }.sortedBy { it.toString() }
        )
        assertEquals(
            listOf(EthereumKitFixture.pendingTransaction.snapshot()),
            storage.getPendingTransactions().map { it.snapshot() }
        )
        assertEquals(
            listOf(EthereumKitFixture.internalTransaction.snapshot()),
            storage.getInternalTransactions().map { it.snapshot() }
        )
        assertEquals(
            setOf(TransactionTag.EVM_COIN_INCOMING, TransactionTag.EVM_COIN_OUTGOING, TransactionTag.tokenOutgoing(EthereumKitFixture.usdt.hex)),
            storage.getDistinctTokenContractAddresses().toSet()
        )
        assertEquals(
            listOf(EthereumKitFixture.pendingTransaction, EthereumKitFixture.tokenTransferTransaction).map { it.snapshot() },
            storage.getTransactionsBefore(listOf(listOf(TransactionTag.OUTGOING)), null, null).map { it.snapshot() }
        )
        assertEquals(
            EthereumKitFixture.rawBroadcasts.map { it.snapshot() }.sortedBy { it.toString() },
            storage.getRawTransactionBroadcasts().map { it.snapshot() }.sortedBy { it.toString() }
        )
        val syncerStateStorage = TransactionSyncerStateStorage(database)
        EthereumKitFixture.syncerStates.forEach {
            assertEquals(it.lastBlockNumber, syncerStateStorage.get(it.syncerId)?.lastBlockNumber)
        }
        val sourceStorage = TransactionSyncSourceStorage(database.transactionSyncSourceDao())
        EthereumKitFixture.syncSources.forEach { (hash, source) -> assertEquals(source, sourceStorage.getSource(hash)) }
    }

    @Test
    fun eip20EventsDatabase_fixture_containsEventsAndSyncCursor() = runTest {
        DatabaseFixtureFiles.install(context, EthereumKitFixture.EIP20_EVENTS_DB)

        val storage = Eip20Storage(Eip20Database.getInstance(context, EthereumKitFixture.EIP20_EVENTS_DB))

        assertEquals(
            EthereumKitFixture.eip20Events.map { it.snapshot() }.sortedBy { it.toString() },
            storage.getEvents().map { it.snapshot() }.sortedBy { it.toString() }
        )
        assertEquals(EthereumKitFixture.eip20SyncState.lastScannedBlock, storage.getLastScannedBlock())
        assertEquals(EthereumKitFixture.eip20SyncState.historicalMinScannedBlock, storage.getHistoricalMinScannedBlock())
    }
}
