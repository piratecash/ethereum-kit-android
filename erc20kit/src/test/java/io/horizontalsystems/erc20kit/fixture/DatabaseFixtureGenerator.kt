package io.horizontalsystems.erc20kit.fixture

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.horizontalsystems.erc20kit.core.Erc20Storage
import io.horizontalsystems.erc20kit.core.room.Erc20KitDatabase
import io.horizontalsystems.ethereumkit.fixture.DatabaseFixtureFiles
import kotlinx.coroutines.test.runTest
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
    fun generateTokenDatabases() = runTest {
        Erc20KitFixture.balances.forEach { (name, balance) ->
            val database = Erc20KitDatabase.build(DatabaseFixtureFiles.plaintextBuilder(context, name))
            Erc20Storage(database).save(balance)
            DatabaseFixtureFiles.export(context, database, name)
        }
    }
}
