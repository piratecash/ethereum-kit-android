package io.horizontalsystems.nftkit.fixture

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.horizontalsystems.ethereumkit.fixture.DatabaseFixtureFiles
import io.horizontalsystems.nftkit.core.db.NftKitDatabase
import io.horizontalsystems.nftkit.fixture.NftKitFixture.snapshot
import io.horizontalsystems.nftkit.models.NftBalanceRecord
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FixtureDatabasesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun nftDatabase_fixture_containsBalancesAndEvents() {
        DatabaseFixtureFiles.install(context, NftKitFixture.DB)

        val database = NftKitDatabase.getInstance(context, NftKitFixture.DB)

        assertEquals(
            NftKitFixture.balances.filter { it.balance > 0 }.map { it.snapshot() },
            database.nftBalanceDao().existingNftBalances().map { NftBalanceRecord(it).snapshot() }
        )
        assertEquals(
            NftKitFixture.balances.filter { !it.synced }.map { it.snapshot() },
            database.nftBalanceDao().nonSyncedNftBalances().map { NftBalanceRecord(it).snapshot() }
        )
        assertEquals(NftKitFixture.eip721Events.map { it.snapshot() }, database.eip721EventDao().events().map { it.snapshot() })
        assertEquals(NftKitFixture.eip1155Events.map { it.snapshot() }, database.eip1155EventDao().events().map { it.snapshot() })
    }
}
