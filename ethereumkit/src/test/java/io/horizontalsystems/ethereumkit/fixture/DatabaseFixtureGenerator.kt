package io.horizontalsystems.ethereumkit.fixture

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.horizontalsystems.ethereumkit.api.models.LastBlockHeight
import io.horizontalsystems.ethereumkit.api.storage.ApiDatabase
import io.horizontalsystems.ethereumkit.core.storage.Eip20Database
import io.horizontalsystems.ethereumkit.core.storage.TransactionDatabase
import io.horizontalsystems.ethereumkit.fixture.EthereumKitFixture.API_DB
import io.horizontalsystems.ethereumkit.fixture.EthereumKitFixture.EIP20_EVENTS_DB
import io.horizontalsystems.ethereumkit.fixture.EthereumKitFixture.TRANSACTIONS_DB
import io.horizontalsystems.ethereumkit.models.TransactionSyncSource
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Skipped by `test`; run only to regenerate the fixtures (see [DatabaseFixtureFiles.assumeRegenerating]). */
@RunWith(RobolectricTestRunner::class)
class DatabaseFixtureGenerator {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() = DatabaseFixtureFiles.assumeRegenerating()

    @Test
    fun generateApiDatabase() {
        val database = ApiDatabase.getInstance(context, API_DB)
        database.balanceDao().insert(EthereumKitFixture.accountState)
        database.lastBlockHeightDao().insert(LastBlockHeight(EthereumKitFixture.LAST_BLOCK_HEIGHT))
        DatabaseFixtureFiles.export(context, database, API_DB)
    }

    @Test
    fun generateTransactionDatabase() {
        val database = TransactionDatabase.getInstance(context, TRANSACTIONS_DB)
        database.transactionDao().insert(EthereumKitFixture.transactions)
        database.transactionDao().insertInternalTransactions(listOf(EthereumKitFixture.internalTransaction))
        database.transactionTagDao().insert(EthereumKitFixture.tags)
        EthereumKitFixture.syncerStates.forEach(database.transactionSyncerStateDao()::save)
        database.transactionSyncSourceDao().insertAll(
            EthereumKitFixture.syncSources.map { (hash, source) -> TransactionSyncSource(hash, source) }
        )
        EthereumKitFixture.rawBroadcasts.forEach(database.rawTransactionBroadcastDao()::insert)
        DatabaseFixtureFiles.export(context, database, TRANSACTIONS_DB)
    }

    @Test
    fun generateEip20EventsDatabase() {
        val database = Eip20Database.getInstance(context, EIP20_EVENTS_DB)
        database.eip20EventDao().insertEip20Events(EthereumKitFixture.eip20Events)
        database.eip20SyncStateDao().insert(EthereumKitFixture.eip20SyncState)
        DatabaseFixtureFiles.export(context, database, EIP20_EVENTS_DB)
    }
}
