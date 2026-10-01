package io.horizontalsystems.merkleiokit.fixture

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.horizontalsystems.ethereumkit.fixture.DatabaseFixtureFiles
import io.horizontalsystems.merkleiokit.MerkleDatabase
import io.horizontalsystems.merkleiokit.fixture.MerkleIoFixture.snapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FixtureDatabasesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun merkleDatabase_fixture_containsTransactionHashes() = runTest {
        DatabaseFixtureFiles.install(context, MerkleIoFixture.DB)

        val dao = MerkleDatabase.build(DatabaseFixtureFiles.plaintextBuilder(context, MerkleIoFixture.DB)).merkleTransactionDao()

        assertEquals(
            MerkleIoFixture.hashes.map { it.snapshot() }.sortedBy { it.toString() },
            dao.hashes().map { it.snapshot() }.sortedBy { it.toString() }
        )
    }
}
