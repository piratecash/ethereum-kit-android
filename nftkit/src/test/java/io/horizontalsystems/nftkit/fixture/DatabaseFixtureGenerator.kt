package io.horizontalsystems.nftkit.fixture

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.horizontalsystems.ethereumkit.fixture.DatabaseFixtureFiles
import io.horizontalsystems.nftkit.core.db.NftKitDatabase
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
    fun generateNftDatabase() {
        val database = NftKitDatabase.getInstance(context, NftKitFixture.DB)
        database.nftBalanceDao().insertAll(NftKitFixture.balances)
        database.eip721EventDao().insertAll(NftKitFixture.eip721Events)
        database.eip1155EventDao().insertAll(NftKitFixture.eip1155Events)
        DatabaseFixtureFiles.export(context, database, NftKitFixture.DB)
    }
}
