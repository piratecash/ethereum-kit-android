package io.horizontalsystems.erc20kit.fixture

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.horizontalsystems.erc20kit.core.Erc20Storage
import io.horizontalsystems.erc20kit.core.room.Erc20KitDatabase
import io.horizontalsystems.ethereumkit.fixture.DatabaseFixtureFiles
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FixtureDatabasesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun tokenDatabases_fixtures_containTokenBalances() {
        Erc20KitFixture.balances.forEach { (name, balance) ->
            DatabaseFixtureFiles.install(context, name)

            val storage = Erc20Storage(Erc20KitDatabase.getInstance(context, name))

            assertEquals(name, balance, storage.getBalance())
        }
    }
}
