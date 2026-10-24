package io.horizontalsystems.nftkit

import io.horizontalsystems.ethereumkit.PlatformContext
import io.horizontalsystems.ethereumkit.fixture.databaseKey
import io.horizontalsystems.nftkit.core.db.NftKitDatabase
import io.horizontalsystems.nftkit.fixture.NftKitFixture
import io.horizontalsystems.nftkit.fixture.NftKitFixture.snapshot
import io.horizontalsystems.nftkit.models.NftBalanceRecord
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NftKitDatabaseDesktopTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun nftKitDatabase_desktop_writesAndReadsThroughDao() = runTest {
        val database = NftKitDatabase.getInstance(PlatformContext(tempFolder.newFolder("databases")), NftKitFixture.DB, databaseKey)

        database.nftBalanceDao().insertAll(NftKitFixture.balances)
        database.eip721EventDao().insertAll(NftKitFixture.eip721Events)

        assertEquals(
            NftKitFixture.balances.map { it.snapshot() },
            database.nftBalanceDao().existingNftBalances().map { NftBalanceRecord(it).snapshot() }
        )
        assertEquals(NftKitFixture.eip721Events.map { it.snapshot() }, database.eip721EventDao().events().map { it.snapshot() })
    }
}
