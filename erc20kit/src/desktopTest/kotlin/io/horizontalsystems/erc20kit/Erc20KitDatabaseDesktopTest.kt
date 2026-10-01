package io.horizontalsystems.erc20kit

import io.horizontalsystems.erc20kit.core.Erc20Storage
import io.horizontalsystems.erc20kit.core.room.Erc20KitDatabase
import io.horizontalsystems.erc20kit.fixture.Erc20KitFixture
import io.horizontalsystems.ethereumkit.PlatformContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Erc20KitDatabaseDesktopTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun erc20KitDatabase_desktop_writesAndReadsThroughDao() = runTest {
        val context = PlatformContext(tempFolder.newFolder("databases"))

        Erc20KitFixture.balances.forEach { (name, balance) ->
            val storage = Erc20Storage(Erc20KitDatabase.getInstance(context, name))
            storage.save(balance)

            assertEquals(name, balance, storage.getBalance())
        }
    }
}
